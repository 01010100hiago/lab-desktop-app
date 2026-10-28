package br.com.labdesktop.ui;

import br.com.labdesktop.cloudflare.ClienteCloudflare;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.cloudflare.ServicoCloudflare;
import br.com.labdesktop.cloudflare.TestadorDeUrl;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.dialogos.Dialogo;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

/**
 * O mundo dos testes de tela da Cloudflare: a Cloudflare FALSA, o servidor do usuário simulado (SSH e SFTP de verdade, Docker de mentira), um cofre de verdade e a fachada
 * {@link ServicoCloudflare} ligada a tudo isso. Nada fala com a conta ou com o lab de verdade.
 */
public final class CenarioDeCloudflare implements AutoCloseable {

    public static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    public final CloudflareFalsa cf;
    public final CofreDeChaves cofre;
    public final String servidorId;
    public final LabSimulado lab;
    public final ServicoCloudflare servico;
    /** O que o endereço público responde ao teste (programável). */
    public volatile TestadorDeUrl.Resultado respostaDoTeste = new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.NO_AR, 200, "No ar: o endereço respondeu (código 200).");

    public CenarioDeCloudflare(Path tmp) throws IOException, GeneralSecurityException, br.com.labdesktop.cofre.CofreException {
        cf = new CloudflareFalsa();
        Files.createDirectories(tmp.resolve("lab"));
        lab = new LabSimulado(tmp.resolve("lab"));
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), "uma-senha-mestra-de-teste".toCharArray(), ParametrosKdf.MINIMO);
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac-de-teste");
        cofre.adicionarChave(chave);
        ConfigServidor s = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", chave.id());
        cofre.adicionarServidor(s, null);
        servidorId = s.id();
        servico = novoServico(false);
    }

    public ServicoCloudflare novoServico(boolean local) {
        Supplier<TestadorDeUrl> t = () -> (h, c) -> respostaDoTeste;
        return new ServicoCloudflare(cofre, servidorId, local, raiz -> lab.ambiente(), cf::base, ClienteCloudflare.Opcoes.rapida(), t, RELOGIO, d -> { });
    }

    /** Conecta de verdade (token no cofre e escolha gravada), como o diálogo Conectar faria, mas sem tela. */
    public void conectar(br.com.labdesktop.cofre.ConfiguracaoCloudflare escolha) throws Exception {
        try (ServicoCloudflare.Descoberta d = servico.descobrir(CloudflareFalsa.TOKEN_CERTO.toCharArray())) {
            servico.salvar(d, escolha);
        }
    }

    public void conectarPorArquivo() throws Exception {
        conectar(lab.configPorArquivo());
    }

    public br.com.labdesktop.cofre.ConfiguracaoCloudflare configDaApi() {
        return new br.com.labdesktop.cofre.ConfiguracaoCloudflare(CloudflareFalsa.CONTA, "Conta de Teste", CloudflareFalsa.ZONA, "example.com", CloudflareFalsa.TUNEL_API, "site",
            br.com.labdesktop.cofre.ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null);
    }

    /** Um servidor de verdade (sem conectar ao SSH, nunca chama {@code iniciar}) cuja Cloudflare é a deste cenário. */
    public br.com.labdesktop.servidor.ServidorLab servidorLab() {
        br.com.labdesktop.servidor.ServidorLab s = new br.com.labdesktop.servidor.ServidorLab(servidorId, cofre, null, br.com.labdesktop.ssh.ConfigSsh.rapida(),
            br.com.labdesktop.util.Agendador.doFundo(), Runnable::run, br.com.labdesktop.util.Fundo.ioVirtual());
        s.definirCloudflare(servico);
        return s;
    }

    /** O estado de antes de alguém conectar: o compose do projeto demo e o config.yml do usuário no servidor. */
    public void prepararOServidorDoDono(String compose, String config) throws IOException {
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", compose);
        lab.escrever(LabSimulado.CONFIG, config);
    }

    @Override
    public void close() {
        cf.close();
        cofre.close();
        try {
            lab.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // apoio de tela
    // ---------------------------------------------------------------------------------------------

    /** Tira a foto de um diálogo nos dois temas, na largura que a janela de verdade teria (o {@code pack}). */
    public static void fotografar(Dialogo d, String nome) {
        Dialogo.definirAlturaDaTelaParaTeste(1700);
        try {
            for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
                Visual.tema(t);
                Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
                int largura = Math.max(560, Visual.naEdt(() -> d.raiz().getPreferredSize().width));
                Visual.salvar(Visual.renderizar(d.raiz(), largura, Visual.alturaPreferida(d.raiz(), largura)), nome + "-" + (t == Tema.CLARO ? "claro" : "escuro"));
            }
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
            Visual.tema(Tema.CLARO);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
        }
    }

    /** Todo o texto que o usuário VÊ numa árvore de componentes (o que está escondido não conta). */
    public static String textos(java.awt.Component raiz) {
        StringBuilder sb = new StringBuilder();
        Visual.naEdt(() -> coletar(raiz, sb));
        return sb.toString();
    }

    private static void coletar(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof javax.swing.text.JTextComponent t && !(c instanceof javax.swing.JPasswordField)) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b) {
            sb.append(b.getText()).append('\n');
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo s) {
            sb.append(s.texto()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                coletar(f, sb);
            }
        }
    }
}
