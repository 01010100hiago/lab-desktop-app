package br.com.labdesktop;

import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.AtrasoDeSenha;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.MonitorDeSono;
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
import java.time.Instant;
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
    private boolean encerrando;

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

    /** Liga tudo e mostra o diálogo da senha-mestra. Na EDT. */
    public void iniciar() {
        tema.instalar(); // o claro/escuro do sistema já foi lido na partida; ao ganhar foco a janela confere de novo
        integrarComOMac();
        desbloquear();
    }

    // ---------------------------------------------------------------------------------------------
    // Cofre
    // ---------------------------------------------------------------------------------------------

    private void desbloquear() {
        DialogoSenhaMestra dialogo = new DialogoSenhaMestra(!cofreExiste, abridor, atraso, Instant::now, resultado -> {
            if (resultado.isPresent()) {
                // Depois que o diálogo fechar: montar a janela de dentro do callback de um modal ainda aberto confunde o foco.
                Fundo.naEdt(() -> montar(resultado.get()));
            } else {
                Fundo.naEdt(this::encerrar);
            }
        });
        dialogo.mostrar(null);
    }

    private void montar(CofreDeChaves aberto) {
        this.cofre = aberto;
        this.cofreExiste = true;
        Apresentador apresentadorTardio = dialogo -> dialogo.mostrar(janelaDeDialogos());
        gerenciador = new GerenciadorServidores(aberto, new PedidoDeCodigoPorDialogo(apresentadorTardio));
        AssistenteDeServidor assistente = new AssistenteDeServidor(aberto, gerenciador, ConfigSsh.padrao());
        janela = new JanelaPrincipal(gerenciador, assistente, tema, preferencias, this::bloquearCofre);

        monitorDeSono = new MonitorDeSono();
        desfazerSono = gerenciador.acompanhar(monitorDeSono);
        registrarSonoNoMac();

        gerenciador.iniciarTodos();
        janela.setVisible(true);
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

    /** Cmd+Q: limpa tudo e encerra o app. */
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

    private void integrarComOMac() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler((e, resposta) -> {
                    encerrar();
                    resposta.performQuit();
                });
            }
            if (d.isSupported(Desktop.Action.APP_ABOUT)) {
                d.setAboutHandler(e -> new DialogoSobre().mostrar(janelaDeDialogos()));
            }
            if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                d.addAppEventListener((AppReopenedListener) e -> reabrirJanela());
            }
        } catch (RuntimeException e) {
            LOG.warn("Não consegui ligar a integração com o macOS: {}", e.toString());
        }
    }

    private void registrarSonoNoMac() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) {
                Desktop.getDesktop().addAppEventListener(monitorDeSono);
            }
        } catch (RuntimeException e) {
            LOG.warn("Não consegui ligar o aviso de sono do Mac: {}", e.toString());
        }
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
