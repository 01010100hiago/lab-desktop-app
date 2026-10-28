package br.com.labdesktop;

import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.CofreEmUsoException;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TravaDeInstancia;
import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.ui.EstiloDeLaf;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.util.AparenciaDoSistema;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Json;
import br.com.labdesktop.util.NativosDoApp;
import br.com.labdesktop.util.Preferencias;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * {@code Lab Desktop --autoteste}: confere, SEM janela e sem tocar na pasta de dados do usuário, que o app que está rodando (principalmente o EMPACOTADO, com o runtime enxuto do
 * {@code jlink}) tem tudo o que precisa: os módulos do Java, as bibliotecas e os nativos de cada sistema. É o que o CI roda dentro do instalador recém-gerado (ARQUITETURA 11.4).
 *
 * <p>Cada verificação imprime {@code OK nome}, {@code FALHA nome: motivo} (CRÍTICA: o programa sai com 1) ou {@code AVISO nome: motivo} (informativa: o PTY do terminal local, o
 * claro/escuro do sistema). Tudo numa pasta temporária que é apagada no fim.
 */
public final class AutoTeste {

    @FunctionalInterface
    private interface Verificacao {
        String rodar(Path tmp) throws Exception;
    }

    private final PrintStream saida;
    private int criticas;
    private int avisos;

    private AutoTeste(PrintStream saida) {
        this.saida = saida;
    }

    /** Roda tudo e devolve o código de saída: 0 se nenhuma verificação crítica falhou. */
    public static int executar(PrintStream saida) {
        System.setProperty("java.awt.headless", "true");
        AutoTeste t = new AutoTeste(saida);
        Path tmp;
        try {
            tmp = Files.createTempDirectory("labdesktop-autoteste");
        } catch (IOException e) {
            saida.println("FALHA pasta-temporaria: " + e);
            return 1;
        }
        try {
            t.rodarTudo(tmp);
        } finally {
            apagar(tmp);
        }
        saida.println(t.criticas == 0 ? "AUTOTESTE OK" + (t.avisos > 0 ? " (com " + t.avisos + " aviso(s))" : "") : "AUTOTESTE FALHOU: " + t.criticas + " verificação(ões) crítica(s)");
        return t.criticas == 0 ? 0 : 1;
    }

    private void rodarTudo(Path tmp) {
        AmbienteDoSistema ambiente = AmbienteDoSistema.detectar();
        saida.println(Versao.NOME + " " + Versao.atual() + " | Java " + System.getProperty("java.version") + " | " + System.getProperty("os.name") + " "
            + System.getProperty("os.arch") + " | LaF " + EstiloDeLaf.padrao());
        critica("versao", t -> {
            if (Versao.atual().equals("dev") && !Boolean.getBoolean("labdesktop.autoteste.aceitaDev")) {
                throw new IllegalStateException("sem Implementation-Version no jar (o runtime não está lendo o manifesto)");
            }
            return Versao.atual();
        }, tmp);
        critica("locale-pt-BR", t -> {
            String v = NumberFormat.getInstance(Locale.forLanguageTag("pt-BR")).format(1234.5);
            if (!"1.234,5".equals(v)) {
                throw new IllegalStateException("formatou " + v + " (falta jdk.localedata no runtime)");
            }
            return v;
        }, tmp);
        critica("modulos-do-java", t -> {
            Class.forName("java.sql.Timestamp");
            Class.forName("javax.naming.InitialContext");
            Class.forName("java.net.http.HttpClient");
            Class.forName("java.lang.management.ManagementFactory");
            Class.forName("com.sun.management.OperatingSystemMXBean");
            Class.forName("java.util.prefs.Preferences");
            java.security.KeyPairGenerator.getInstance("EC");
            return "sql, naming, http, management, prefs, jdk.crypto.ec";
        }, tmp);
        critica("json", t -> Json.mapper().readTree("{\"a\":[1,2]}").get("a").size() == 2 ? "jackson" : "?", tmp);
        critica("chave-ed25519", t -> {
            var c = GeradorDeChaves.gerar("autoteste", "pc");
            if (!c.publica().startsWith("ssh-ed25519 ")) {
                throw new IllegalStateException("a pública não é ssh-ed25519");
            }
            return c.impressao();
        }, tmp);
        critica("cofre-argon2-aes", t -> {
            CaminhosApp caminhos = CaminhosApp.de(t.resolve("dados"), t.resolve("logs"));
            char[] senha = "senha-de-autoteste-1234".toCharArray();
            try (CofreDeChaves c = CofreDeChaves.criar(caminhos, senha, ParametrosKdf.MINIMO)) {
                c.bloquear();
            }
            try (CofreDeChaves c = CofreDeChaves.abrir(caminhos, senha.clone(), ParametrosKdf.MINIMO)) {
                c.bloquear();
            }
            return "criou, abriu, bloqueou (Argon2id, AES-GCM, gravação atômica, permissões)";
        }, tmp);
        critica("trava-de-instancia", t -> {
            CaminhosApp caminhos = CaminhosApp.de(t.resolve("dados-trava"), t.resolve("logs"));
            try (TravaDeInstancia primeira = TravaDeInstancia.adquirir(caminhos)) {
                java.util.Objects.requireNonNull(primeira);
                try (TravaDeInstancia segunda = TravaDeInstancia.adquirir(caminhos)) {
                    java.util.Objects.requireNonNull(segunda);
                    throw new IllegalStateException("a segunda trava foi aceita: duas instâncias poderiam mexer no cofre");
                } catch (CofreEmUsoException esperado) {
                    return "a segunda instância é recusada";
                }
            }
        }, tmp);
        critica("ssh-mina", t -> {
            var cliente = br.com.labdesktop.ssh.ClienteSshFactory.criar((a, b, c) -> false, br.com.labdesktop.ssh.ConfigSsh.padrao(), java.util.List.of("ssh-ed25519"));
            cliente.close();
            return "MINA SSH com BouncyCastle";
        }, tmp);
        critica("tema-flatlaf", t -> {
            GerenciadorTema.preparar();
            SwingUtilities.invokeAndWait(() -> new GerenciadorTema(Preferencias.Tema.ESCURO, false, false).instalar());
            if (UIManager.getColor("Lab.bg") == null || UIManager.getFont("Label.font") == null) {
                throw new IllegalStateException("os tokens do tema não chegaram ao UIManager");
            }
            return UIManager.getLookAndFeel().getName() + ", fonte " + UIManager.getFont("Label.font").getFamily();
        }, tmp);
        aviso("claro-escuro-do-sistema", t -> "sistema " + (AparenciaDoSistema.escuro() ? "escuro" : "claro"), tmp);
        aviso("pty-do-terminal-local", t -> {
            NativosDoApp.apontar(CaminhosApp.de(t.resolve("dados-pty"), t.resolve("logs")));
            String[] comando = ambiente.sistema() == SistemaOperacional.WINDOWS
                ? new String[] {ambiente.juntar(ambiente.variavel("SystemRoot") == null ? "C:\\Windows" : ambiente.variavel("SystemRoot"), "System32", "cmd.exe"), "/c", "echo", "labdesktop-pty"}
                : new String[] {"/bin/echo", "labdesktop-pty"};
            PtyProcess p = new PtyProcessBuilder(comando).setDirectory(t.toString()).setEnvironment(Map.copyOf(System.getenv())).start();
            try {
                String lido = new String(p.getInputStream().readNBytes(256), StandardCharsets.UTF_8);
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("o programa do PTY não terminou em 10 s");
                }
                if (!lido.contains("labdesktop-pty")) {
                    throw new IllegalStateException("o PTY abriu mas não devolveu o texto (leu: " + lido.strip() + ")");
                }
                return "abriu, leu e fechou (" + (ambiente.sistema() == SistemaOperacional.WINDOWS ? "ConPTY" : "forkpty") + ")";
            } finally {
                p.destroyForcibly();
            }
        }, tmp);
    }

    private void critica(String nome, Verificacao v, Path tmp) {
        try {
            saida.println("OK " + nome + ": " + v.rodar(tmp));
        } catch (Throwable e) {
            criticas++;
            saida.println("FALHA " + nome + ": " + e);
        }
    }

    private void aviso(String nome, Verificacao v, Path tmp) {
        try {
            saida.println("OK " + nome + ": " + v.rodar(tmp));
        } catch (Throwable e) {
            avisos++;
            saida.println("AVISO " + nome + ": " + e);
        }
    }

    private static void apagar(Path pasta) {
        try (Stream<Path> s = Files.walk(pasta)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            // pasta temporária: o sistema a limpa depois
        }
    }
}
