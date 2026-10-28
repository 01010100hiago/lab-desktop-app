package br.com.labdesktop.instalador;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Instalar o Git num SERVIDOR por SSH, só quando o usuário pede (a regra "nada se instala sozinho"). Conferir é só leitura ({@code git --version}, {@code /etc/os-release} lido como texto, quais gerenciadores
 * existem, se o usuário é root ou tem {@code sudo -n}); instalar roda os comandos que a confirmação MOSTROU, na ordem, e para no primeiro que falha. Regras:
 * <ul>
 *   <li>só executa se o usuário é root ou tem {@code sudo -n} (sudo que nunca pergunta senha); senão a prévia traz o comando para o USUÁRIO rodar no Terminal, e o app nunca digita senha;</li>
 *   <li>cada comando é uma lista de palavras seguras montada aqui (nenhum texto do servidor entra: o {@code os-release} só ESCOLHE entre seis gerenciadores fixos);</li>
 *   <li>não mexe na configuração do git ({@code git config} nunca roda: nem {@code --global}, nem {@code --system}).</li>
 * </ul>
 */
public final class InstaladorDeGit {

    /** Onde a prévia chega. */
    public enum Situacao {
        JA_INSTALADO,
        /** O usuário é root ou tem {@code sudo -n}: o app executa depois do clique. */
        PODE_INSTALAR,
        /** Sem root nem {@code sudo -n}: o usuário roda o comando no Terminal. */
        PRECISA_DO_DONO,
        /** Nenhum dos seis gerenciadores existe: o app só orienta. */
        GERENCIADOR_NAO_ACHADO
    }

    /** O git: instalado (e a versão) ou não. */
    public record Estado(boolean instalado, String versao) {
        public Estado {
            versao = versao == null ? "" : versao;
        }
    }

    /**
     * O que a confirmação mostra.
     *
     * @param comandos          o que o app EXECUTA se o usuário confirmar (com o prefixo {@code sudo -n} se não é root)
     * @param comandosParaODono o que o usuário roda no Terminal quando o app não pode (com {@code sudo}, que ele mesmo responde); vazio se o app pode
     * @param sistema           o nome do sistema, para a tela dizer
     */
    public record Previa(Estado estado, Situacao situacao, GerenciadorDePacotes gerenciador, String sistema, boolean root, boolean sudoSemSenha, List<String> comandos,
                         List<String> comandosParaODono) {
        public Previa {
            comandos = List.copyOf(comandos);
            comandosParaODono = List.copyOf(comandosParaODono);
            sistema = sistema == null ? "" : sistema;
        }

        public boolean podeExecutar() {
            return situacao == Situacao.PODE_INSTALAR;
        }
    }

    /** Como terminou. */
    public record Resultado(boolean instalou, Estado estado, String mensagem, String passoQueFalhou) {
        public Resultado {
            mensagem = mensagem == null ? "" : mensagem;
            passoQueFalhou = passoQueFalhou == null ? "" : passoQueFalhou;
        }
    }

    /** Quem acompanha ao vivo (a tela): um passo em palavras. Chamado de thread de fundo. */
    @FunctionalInterface
    public interface Observador {
        void passo(String frase);

        Observador NENHUM = frase -> { };
    }

    private static final Duration PRAZO_CURTO = Duration.ofSeconds(30);
    private static final Duration PRAZO_DA_ATUALIZACAO = Duration.ofMinutes(3);
    private static final Duration PRAZO_DA_INSTALACAO = Duration.ofMinutes(10);

    private final ProvisionadorRemoto.Executor executor;

    public InstaladorDeGit(ProvisionadorRemoto.Executor executor) {
        this.executor = executor;
    }

    // =====================================================================================================
    // As linhas de comando (FIXAS)
    // =====================================================================================================

    /** {@code git --version}: o app confere o git SÓ por aqui (nunca roda {@code git config}). */
    public static String lerVersao() {
        return "git --version";
    }

    /** O sistema, lido como TEXTO. */
    public static String lerOsRelease() {
        return "cat /etc/os-release";
    }

    /** Quais gerenciadores existem: uma linha {@code gerenciador=<programa>} para cada. */
    public static String lerGerenciadores() {
        return "sh -c 'for g in apt-get dnf yum apk pacman zypper; do if command -v $g >/dev/null 2>&1; then echo gerenciador=$g; fi; done'";
    }

    /** O número do usuário e se ele tem {@code sudo -n} (sem senha). */
    public static String lerPermissoes() {
        return "sh -c 'echo \"uid=$(id -u)\"; if sudo -n true >/dev/null 2>&1; then echo sudo=sim; else echo sudo=nao; fi'";
    }

    private static final java.util.regex.Pattern PALAVRA = java.util.regex.Pattern.compile("^[A-Za-z0-9_@%+=:.,/~-]{1,100}$");

    /** Uma lista de palavras vira uma linha de comando (cada palavra só com caracteres seguros), com o prefixo de permissão. */
    static String linha(List<String> palavras, String prefixo) {
        for (String p : palavras) {
            if (!PALAVRA.matcher(p).matches()) {
                throw new IllegalArgumentException("Palavra fora da regra do comando remoto.");
            }
        }
        return prefixo + String.join(" ", palavras);
    }

    // =====================================================================================================
    // Conferir (só leitura)
    // =====================================================================================================

    /** Confere só o git (a tela "Já instalei, conferir de novo" e o fim da instalação). Só lê. */
    public Estado conferirOGit() throws IOException, InterruptedException {
        ResultadoExec r = executor.executar(lerVersao(), new byte[0], PRAZO_CURTO);
        if (r.status() == 0 && r.stdout().strip().toLowerCase(Locale.ROOT).startsWith("git version")) {
            return new Estado(true, TextoSeguro.truncar(TextoSeguro.limparLinha(r.stdout().strip()), 60));
        }
        return new Estado(false, "");
    }

    /**
     * A prévia: o estado do git e, se falta, o que o app faria (ou o que o usuário faria). Só lê.
     *
     * @throws IOException          a conexão caiu
     * @throws InterruptedException cancelado
     */
    public Previa conferir() throws IOException, InterruptedException {
        Estado estado = conferirOGit();
        if (estado.instalado()) {
            return new Previa(estado, Situacao.JA_INSTALADO, null, "", false, false, List.of(), List.of());
        }
        String os = saida(lerOsRelease());
        Set<String> disponiveis = new HashSet<>();
        for (String l : saida(lerGerenciadores()).split("\n")) {
            String t = l.strip();
            if (t.startsWith("gerenciador=")) {
                disponiveis.add(t.substring("gerenciador=".length()));
            }
        }
        boolean root = false;
        boolean sudo = false;
        for (String l : saida(lerPermissoes()).split("\n")) {
            String t = l.strip();
            root = root || t.equals("uid=0");
            sudo = sudo || t.equals("sudo=sim");
        }
        String sistema = GerenciadorDePacotes.nomeDoSistema(os);
        java.util.Optional<GerenciadorDePacotes> g = GerenciadorDePacotes.escolher(os, disponiveis);
        if (g.isEmpty()) {
            return new Previa(estado, Situacao.GERENCIADOR_NAO_ACHADO, null, sistema, root, sudo, List.of(), List.of());
        }
        List<String> doApp = new ArrayList<>();
        List<String> doDono = new ArrayList<>();
        for (List<String> c : g.get().comandosDeInstalar()) {
            doApp.add(linha(c, root ? "" : "sudo -n "));
            doDono.add(linha(c, root ? "" : "sudo "));
        }
        boolean pode = root || sudo;
        return new Previa(estado, pode ? Situacao.PODE_INSTALAR : Situacao.PRECISA_DO_DONO, g.get(), sistema, root, sudo, doApp, pode ? List.of() : doDono);
    }

    private String saida(String comando) throws IOException, InterruptedException {
        ResultadoExec r = executor.executar(comando, new byte[0], PRAZO_CURTO);
        return r.status() == 0 ? new String(r.stdoutBytes(), StandardCharsets.UTF_8) : "";
    }

    // =====================================================================================================
    // Instalar (só depois do clique de confirmar)
    // =====================================================================================================

    /**
     * Roda os comandos da prévia, na ordem, e confere {@code git --version} no fim. Recusa se a prévia não é {@link Situacao#PODE_INSTALAR}. Para no primeiro comando que falha, com o motivo em
     * frase simples (nunca o texto cru do servidor).
     */
    public Resultado instalar(Previa previa, Observador obs, Cancelamento cancelamento) throws InterruptedException {
        if (!previa.podeExecutar()) {
            return new Resultado(false, previa.estado(), "O app não pode instalar sozinho neste servidor (sem root nem sudo sem senha). Rode o comando que a tela mostra no Terminal.", "");
        }
        int n = 0;
        for (String comando : previa.comandos()) {
            n++;
            if (cancelamento.cancelado()) {
                return new Resultado(false, previa.estado(), "Você parou antes do fim. O que já tinha rodado não foi desfeito (um pacote instalado pela metade o gerenciador termina ou limpa na próxima vez).", "");
            }
            String passo = "Passo " + n + " de " + previa.comandos().size() + ": " + comando;
            obs.passo(passo);
            ResultadoExec r;
            try {
                r = executor.executar(comando, new byte[0], comando.contains(" update") ? PRAZO_DA_ATUALIZACAO : PRAZO_DA_INSTALACAO);
            } catch (ComandoDemorouException e) {
                return new Resultado(false, previa.estado(), "O servidor demorou demais neste passo e o app parou de esperar. Confira com Já instalei, conferir de novo; se ainda falta, rode o comando no Terminal.", passo);
            } catch (IOException e) {
                return new Resultado(false, previa.estado(), "A conexão caiu no meio. Confira com Já instalei, conferir de novo antes de tentar outra vez.", passo);
            }
            if (r.status() != 0) {
                return new Resultado(false, previa.estado(), explicar(r.stderr() + "\n" + r.stdout()), passo);
            }
        }
        obs.passo("Conferindo o git (git --version)…");
        try {
            Estado agora = conferirOGit();
            if (agora.instalado()) {
                return new Resultado(true, agora, "O git foi instalado: " + agora.versao() + ".", "");
            }
            return new Resultado(false, agora, "Os comandos terminaram bem, mas o git ainda não responde (git --version). Talvez o programa esteja fora do caminho do seu usuário: abra o Terminal e confira.", "");
        } catch (IOException e) {
            return new Resultado(false, previa.estado(), "Instalei, mas a conexão caiu antes de conferir. Use Já instalei, conferir de novo.", "");
        }
    }

    /** Traduz o que o gerenciador disse para uma frase simples; o que não se conhece sai limpo, redigido e curto. */
    static String explicar(String erro) {
        String m = erro == null ? "" : erro.toLowerCase(Locale.ROOT);
        if (m.contains("could not get lock") || m.contains("unable to acquire the dpkg") || m.contains("another app is currently holding the yum lock") || m.contains("waiting for process")) {
            return "Outro instalador está rodando neste servidor (o gerenciador de pacotes está ocupado). Espere ele terminar e tente de novo.";
        }
        if (m.contains("unable to locate package") || m.contains("no match for argument") || m.contains("no package git") || m.contains("target not found") || m.contains("no provider of")) {
            return "O gerenciador não achou o pacote git. Se for Debian ou Ubuntu, a lista de pacotes pode estar velha: rode apt-get update no Terminal e tente de novo.";
        }
        if (m.contains("permission denied") || m.contains("a password is required") || m.contains("sudo: a terminal is required")) {
            return "O servidor recusou por falta de permissão (o sudo pediu senha). Rode o comando no Terminal, onde você pode responder.";
        }
        if (m.contains("temporary failure resolving") || m.contains("could not resolve") || m.contains("network is unreachable") || m.contains("failed to fetch") || m.contains("cannot find a valid baseurl")) {
            return "O servidor não alcançou o repositório de pacotes (sem internet ou endereço errado).";
        }
        if (m.contains("no space left on device")) {
            return "Acabou o espaço em disco no servidor.";
        }
        String limpo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(erro == null ? "" : erro.strip())), 240);
        return limpo.isEmpty() ? "O gerenciador de pacotes falhou sem dizer o motivo." : "O gerenciador de pacotes falhou: " + limpo;
    }
}
