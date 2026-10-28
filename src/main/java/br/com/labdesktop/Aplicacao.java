package br.com.labdesktop;

import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.AtrasoDeSenha;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.MonitorDeSono;
import br.com.labdesktop.ssh.VigiaDeRelogio;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.JanelaPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.DialogoSenhaMestra;
import br.com.labdesktop.ui.dialogos.DialogoSobre;
import br.com.labdesktop.ui.dialogos.PedidoDeCodigoPorDialogo;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Fundo;
import java.awt.Desktop;
import java.awt.Window;
import java.awt.desktop.AppReopenedListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A composição manual do app (ARQUITETURA 4.4, passo 6): sem framework de DI. Na ordem: o tema, a integração com o
 * macOS (Cmd+Q, "Sobre", clique no Dock), o diálogo da senha-mestra (cria o cofre na primeira vez, ou abre) e, com o
 * cofre aberto, o {@link GerenciadorServidores} (um {@code ServidorLab} por servidor do cofre), a
 * {@link JanelaPrincipal} e o aviso de sono do Mac.
 *
 * <p>Bloquear o cofre ({@code Cmd+Shift+L}) é o ciclo inteiro ao contrário (ARQUITETURA 5.6): solta as telas, zera os
 * segredos, fecha as conexões e volta ao diálogo da senha. Cmd+Q faz o mesmo e encerra. Tudo aqui roda na EDT; o que
 * bloqueia (Argon2id, SSH, rede) roda fora dela, em quem for chamado.
 */
public final class Aplicacao {

    private static final Logger LOG = LoggerFactory.getLogger(Aplicacao.class);

    private final CaminhosApp caminhos;
    private final PreferenciasAtuais preferencias;
    private final GerenciadorTema tema;
    private final AtrasoDeSenha atraso = new AtrasoDeSenha();
    private final DialogoSenhaMestra.Abridor abridor;
    private final Runnable aoEncerrar;
    private boolean cofreExiste;

    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private JanelaPrincipal janela;
    private MonitorDeSono monitorDeSono;
    private Runnable desfazerSono = () -> { };
    private VigiaDeRelogio vigiaDeRelogio;
    private boolean encerrando;
    /** Roda UMA vez, quando a primeira janela de verdade aparece (fecha a janelinha de abertura e guarda a medida da partida). */
    private Runnable aoAparecerAPrimeiraJanela = () -> { };

    /**
     * @param cofreExiste {@code true} se já há um cofre na pasta de dados (conferido fora da EDT, na partida)
     * @param aoEncerrar  o que acontece depois da limpeza (em produção, soltar a trava de instância e {@code System.exit})
     */
    public Aplicacao(CaminhosApp caminhos, PreferenciasAtuais preferencias, GerenciadorTema tema, boolean cofreExiste,
                     Runnable aoEncerrar) {
        this(caminhos, preferencias, tema, cofreExiste, DialogoSenhaMestra.Abridor.doDisco(caminhos), aoEncerrar);
    }

    /** Com o abridor do cofre trocável (testes: Argon2id mínimo). */
    public Aplicacao(CaminhosApp caminhos, PreferenciasAtuais preferencias, GerenciadorTema tema, boolean cofreExiste,
                     DialogoSenhaMestra.Abridor abridor, Runnable aoEncerrar) {
        this.caminhos = caminhos;
        this.preferencias = preferencias;
        this.tema = tema;
        this.cofreExiste = cofreExiste;
        this.abridor = abridor;
        this.aoEncerrar = aoEncerrar;
    }

    /**
     * O que fazer quando a primeira janela (a da senha-mestra, ou a principal no modo de teste) está à vista: o {@code Principal} fecha a janelinha de
     * abertura e grava quanto a partida levou. Roda uma só vez, na EDT.
     */
    public Aplicacao aoAparecerAPrimeiraJanela(Runnable acao) {
        this.aoAparecerAPrimeiraJanela = acao == null ? () -> { } : acao;
        return this;
    }

    private void primeiraJanelaApareceu() {
        Runnable acao = aoAparecerAPrimeiraJanela;
        aoAparecerAPrimeiraJanela = () -> { };
        acao.run();
    }

    /** Liga tudo e mostra o diálogo da senha-mestra. Na EDT. */
    public void iniciar() {
        br.com.labdesktop.ui.IconesDoApp.aplicarAoApp();
        tema.instalar(); // o claro/escuro do sistema já foi lido na partida; ao ganhar foco a janela confere de novo
        integrarComOSistema();
        desbloquear();
    }

    // ---------------------------------------------------------------------------------------------
    // Cofre
    // ---------------------------------------------------------------------------------------------

    /** Só desenvolvimento: arquivo da senha de um cofre de TESTE (ver {@code SenhaDeTeste}); {@code null} no uso normal. */
    private Path arquivoDaSenhaDeTeste;

    /** Liga a abertura automática do cofre de teste. O chamador já conferiu as condições de segurança. */
    public Aplicacao comSenhaDeTeste(Path arquivo) {
        this.arquivoDaSenhaDeTeste = arquivo;
        return this;
    }

    private void desbloquear() {
        if (arquivoDaSenhaDeTeste != null && cofreExiste && abrirComSenhaDeTeste()) {
            return;
        }
        pedirSenha();
    }

    /** Abre o cofre de teste sem diálogo. {@code false} se nem deu para ler a senha (aí o diálogo aparece). */
    private boolean abrirComSenhaDeTeste() {
        char[] senha;
        try {
            senha = Files.readString(arquivoDaSenhaDeTeste).strip().toCharArray();
        } catch (IOException e) {
            LOG.warn("--senha-de-teste: não consegui ler a senha ({}); vou pedir.", e.getMessage());
            return false;
        }
        LOG.warn("MODO DE DESENVOLVIMENTO: abrindo o cofre de TESTE com a senha do arquivo, sem diálogo.");
        abridor.abrir(senha).whenComplete((aberto, erro) -> {
            Arrays.fill(senha, '\0');
            if (erro == null) {
                Fundo.naEdt(() -> montar(aberto));
            } else {
                LOG.warn("--senha-de-teste: o cofre não abriu ({}); vou pedir a senha.", erro.toString());
                Fundo.naEdt(this::pedirSenha);
            }
        });
        return true;
    }

    private void pedirSenha() {
        DialogoSenhaMestra dialogo = new DialogoSenhaMestra(!cofreExiste, abridor, atraso, Instant::now, resultado -> {
            if (resultado.isPresent()) {
                // Depois que o diálogo fechar: montar a janela de dentro do callback de um modal ainda aberto confunde o foco.
                Fundo.naEdt(() -> montar(resultado.get()));
            } else {
                Fundo.naEdt(this::encerrar);
            }
        });
        // postado ANTES de mostrar: o diálogo é modal e só volta ao fechar; o que foi postado roda com ele já à vista (a janelinha de abertura some sem vão)
        javax.swing.SwingUtilities.invokeLater(this::primeiraJanelaApareceu);
        dialogo.mostrar(null);
    }

    private void montar(CofreDeChaves aberto) {
        this.cofre = aberto;
        this.cofreExiste = true;
        Apresentador apresentadorTardio = dialogo -> dialogo.mostrar(janelaDeDialogos());
        gerenciador = new GerenciadorServidores(aberto, new PedidoDeCodigoPorDialogo(apresentadorTardio));
        AssistenteDeServidor assistente = new AssistenteDeServidor(aberto, gerenciador, ConfigSsh.padrao());
        janela = new JanelaPrincipal(gerenciador, assistente, tema, preferencias, this::bloquearCofre,
            br.com.labdesktop.arquivos.ServicosDeArquivos.de(caminhos), br.com.labdesktop.ui.ComportamentoDaJanela.doSistema(this::encerrar));

        monitorDeSono = new MonitorDeSono();
        desfazerSono = gerenciador.acompanhar(monitorDeSono);
        registrarSono();

        gerenciador.iniciarTodos();
        janela.setVisible(true);
        primeiraJanelaApareceu(); // no modo de teste (senha do arquivo) a primeira janela é a principal; nos outros casos já rodou na da senha
        for (String aviso : aberto.avisos()) {
            janela.avisos().atencao(aviso);
        }
        LOG.info("Cofre aberto com {} servidor(es).", gerenciador.servidores().size());
    }

    /** A janela que serve de pai dos diálogos; {@code null} se ela está escondida (o diálogo aparece sozinho). */
    private Window janelaDeDialogos() {
        JanelaPrincipal j = janela;
        if (j != null && j.isVisible()) {
            return j;
        }
        trazerParaFrente();
        return null;
    }

    /** Bloqueia o cofre e volta ao diálogo da senha (Cmd+Shift+L). */
    public void bloquearCofre() {
        if (cofre == null) {
            return;
        }
        LOG.info("Bloqueando o cofre.");
        liberar();
        desbloquear();
    }

    /** Solta telas e conexões, zera os segredos do cofre. */
    private void liberar() {
        JanelaPrincipal j = janela;
        GerenciadorServidores g = gerenciador;
        CofreDeChaves c = cofre;
        janela = null;
        gerenciador = null;
        cofre = null;
        desfazerSono.run();
        desfazerSono = () -> { };
        if (vigiaDeRelogio != null) {
            vigiaDeRelogio.close();
            vigiaDeRelogio = null;
        }
        if (j != null) {
            j.encerrar(); // solta as telas (e as inscrições) antes de os segredos sumirem
        }
        if (c != null) {
            c.bloquear(); // zera chaves e tokens; o gancho do cofre manda cada conexão fechar
        }
        if (g != null) {
            g.fechar();
        }
    }

    /** Cmd+Q e "Sair": pergunta se houver transferência andando ou edição não salva; sem janela (cofre bloqueado) sai direto. */
    private void pedirParaSair() {
        JanelaPrincipal j = janela;
        if (j == null) {
            encerrar();
        } else {
            j.pedirParaSair();
        }
    }

    /** Limpa tudo e encerra o app (o que vem depois da pergunta de Cmd+Q, "Sair" e fechar a janela). */
    public void encerrar() {
        if (encerrando) {
            return;
        }
        encerrando = true;
        liberar();
        Fundo.encerrar();
        aoEncerrar.run();
    }

    // ---------------------------------------------------------------------------------------------
    // macOS
    // ---------------------------------------------------------------------------------------------

    /** Cmd+Q, "Sobre" e o clique no Dock do macOS (cada um só onde o {@code Desktop} diz que existe; no Windows e no Linux nada disto é ligado). */
    private void integrarComOSistema() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, resposta) -> {
                    // Sempre cancela o pedido do sistema e sai pelo caminho do app: com transferência andando ou edição não salva ele pergunta antes.
                    resposta.cancelQuit();
                    Fundo.naEdt(this::pedirParaSair);
                });
            }
            if (d.isSupported(Desktop.Action.APP_ABOUT)) {
                d.setAboutHandler(e -> Fundo.naEdt(this::mostrarSobre));
            }
            if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                d.addAppEventListener((AppReopenedListener) e -> reabrirJanela());
            }
        } catch (RuntimeException e) {
            LOG.warn("Não consegui ligar a integração com o macOS: {}", e.toString());
        }
    }

    /**
     * O aviso de que o computador dormiu: o evento do sistema onde o Java o entrega (macOS) e, onde não há evento (Windows, Linux), o {@link VigiaDeRelogio}, que vê o relógio saltar
     * (ARQUITETURA 6.3 e 11.4). Escolhido pela CAPACIDADE do {@code Desktop}, não pelo nome do sistema.
     */
    private void registrarSono() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) {
                Desktop.getDesktop().addAppEventListener(monitorDeSono);
                return;
            }
        } catch (RuntimeException e) {
            LOG.warn("Não consegui ligar o aviso de sono do sistema: {}", e.toString());
        }
        vigiaDeRelogio = VigiaDeRelogio.iniciar(monitorDeSono);
    }

    /** "Sobre": com a janela principal à vista leva aos Ajustes, na seção Sobre; sem ela (cofre bloqueado) abre a janelinha. */
    private void mostrarSobre() {
        JanelaPrincipal j = janela;
        if (j != null && j.isVisible()) {
            j.painel().mostrarAjustes(br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao.SOBRE);
            return;
        }
        new DialogoSobre(br.com.labdesktop.util.InformacoesDoApp.coletar(caminhos)).mostrar(janelaDeDialogos());
    }

    /** Clique no ícone do Dock com a janela escondida: mostra de novo. */
    private void reabrirJanela() {
        Fundo.naEdt(() -> {
            JanelaPrincipal j = janela;
            if (j != null) {
                j.setVisible(true);
                j.setExtendedState(j.getExtendedState() & ~java.awt.Frame.ICONIFIED);
                j.toFront();
            }
        });
    }

    private void trazerParaFrente() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                Desktop.getDesktop().requestForeground(true);
            }
        } catch (RuntimeException e) {
            LOG.debug("Não consegui trazer o app para a frente: {}", e.toString());
        }
    }
}
