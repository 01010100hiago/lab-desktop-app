package br.com.labdesktop.ui;

import br.com.labdesktop.Versao;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.EstadoJanela;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Toasts;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoSobre;
import br.com.labdesktop.util.Preferencias;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * A janela principal (ARQUITETURA 4.2 e 9): {@link PainelPrincipal} no centro, o menu do app e os avisos flutuantes.
 * Fechar a janela NÃO encerra o app ({@code HIDE_ON_CLOSE}; Cmd+Q encerra): o app segue vivo e o polling de {@code LIMITES}
 * continua a cada 60 s. Em que pé a janela está (ativa, sem foco, ou escondida/minimizada) vai para o
 * {@link GerenciadorServidores}, que baixa a cadência do polling (×6 sem foco, só {@code LIMITES} escondida ou
 * minimizada) para não pesar no servidor: o lab já anda perto de 87 °C.
 *
 * <p>A posição e o tamanho são lembrados em {@code preferencias.json} (gravados fora da EDT, com atraso para não
 * escrever a cada pixel arrastado).
 */
public final class JanelaPrincipal extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final int LARGURA_PADRAO = 1440;
    private static final int ALTURA_PADRAO = 860;
    private static final int LARGURA_MINIMA = 980;
    private static final int ALTURA_MINIMA = 640;

    private final GerenciadorServidores gerenciador;
    private final PreferenciasAtuais preferencias;
    private final GerenciadorTema tema;
    private final Toasts avisos;
    private final PainelPrincipal painel;
    private final Apresentador apresentador;
    private final Timer gravarJanela;
    private final ComportamentoDaJanela comportamento;

    public JanelaPrincipal(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, GerenciadorTema tema,
                           PreferenciasAtuais preferencias, Runnable bloquearCofre) {
        this(gerenciador, assistente, tema, preferencias, bloquearCofre, br.com.labdesktop.arquivos.ServicosDeArquivos.descartaveis());
    }

    /** @param servicosDeArquivos o que o gerenciador de arquivos guarda no Mac (últimos caminhos, backups) */
    public JanelaPrincipal(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, GerenciadorTema tema,
                           PreferenciasAtuais preferencias, Runnable bloquearCofre,
                           br.com.labdesktop.arquivos.ServicosDeArquivos servicosDeArquivos) {
        this(gerenciador, assistente, tema, preferencias, bloquearCofre, servicosDeArquivos, ComportamentoDaJanela.padrao());
    }

    /** @param comportamento o que fechar a janela faz e se o menu leva "Sair" (o Mac esconde; o Windows e o Linux saem) */
    public JanelaPrincipal(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, GerenciadorTema tema,
                           PreferenciasAtuais preferencias, Runnable bloquearCofre,
                           br.com.labdesktop.arquivos.ServicosDeArquivos servicosDeArquivos, ComportamentoDaJanela comportamento) {
        super(Versao.NOME);
        this.gerenciador = gerenciador;
        this.preferencias = preferencias;
        this.tema = tema;
        this.comportamento = comportamento;
        IconesDoApp.aplicar(this);
        if (comportamento.fecharEscondeAJanela()) {
            setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        } else {
            // Windows e Linux: fechar a janela é sair do app (senão ela some e o app fica vivo, invisível, segurando a trava de instância)
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    pedirParaSair();
                }
            });
        }
        setMinimumSize(new Dimension(LARGURA_MINIMA, ALTURA_MINIMA));

        this.avisos = Toasts.instalar(getRootPane());
        this.apresentador = dialogo -> dialogo.mostrar(this);
        this.painel = new PainelPrincipal(gerenciador, assistente, avisos, apresentador, new Navegador(avisos), servicosDeArquivos,
            preferencias::get);
        setContentPane(painel);
        // "Sair" do menu passa pela mesma pergunta de fechar a janela
        ComportamentoDaJanela doMenu = comportamento.sair() == null ? comportamento : new ComportamentoDaJanela(comportamento.sistema(), this::pedirParaSair);
        setJMenuBar(new MenuApp(this, painel, tema, preferencias, bloquearCofre,
            () -> apresentador.mostrar(new DialogoSobre()), doMenu).barra());

        aplicarTamanhoGuardado();
        this.gravarJanela = new Timer(700, e -> guardarTamanho());
        gravarJanela.setRepeats(false);
        ligarEstadoDaJanela();
        getAccessibleContext().setAccessibleName(Versao.NOME);
    }

    /** O que fechar a janela faz (e se há "Sair"): para os testes. */
    public ComportamentoDaJanela comportamento() {
        return comportamento;
    }

    private void aplicarTamanhoGuardado() {
        Preferencias.Janela guardada = preferencias.get().janela();
        Rectangle tela = GraphicsEnvironment.isHeadless() ? new Rectangle(0, 0, 1920, 1080)
            : GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (guardada != null) {
            Rectangle r = new Rectangle(guardada.x(), guardada.y(),
                Math.min(Math.max(guardada.largura(), LARGURA_MINIMA), tela.width),
                Math.min(Math.max(guardada.altura(), ALTURA_MINIMA), tela.height));
            if (tela.intersects(r) && tela.contains(r.x + 40, r.y + 20)) {
                setBounds(r);
                if (guardada.maximizada()) {
                    setExtendedState(JFrame.MAXIMIZED_BOTH);
                }
                return;
            }
        }
        setSize(Math.min(LARGURA_PADRAO, tela.width), Math.min(ALTURA_PADRAO, tela.height));
        setLocationRelativeTo(null);
    }

    private void guardarTamanho() {
        boolean maximizada = (getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        if (maximizada || (getExtendedState() & JFrame.ICONIFIED) != 0) {
            return; // guarda só o tamanho "normal": a posição de uma janela maximizada não diz nada
        }
        Rectangle r = getBounds();
        if (r.width < Preferencias.Janela.LARGURA_MIN || r.height < Preferencias.Janela.ALTURA_MIN) {
            return;
        }
        preferencias.atualizar(p -> p.comJanela(new Preferencias.Janela(r.x, r.y, r.width, r.height, false)));
    }

    private void ligarEstadoDaJanela() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                avisarEstado();
                tema.conferirSistema();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                avisarEstado();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                // Minimizada = escondida para o polling (só LIMITES). Passa o estado direto: no instante do evento o
                // bit ICONIFIED do getExtendedState() pode ainda não ter sido atualizado.
                avisarEstado(EstadoJanela.de(isVisible(), true, false));
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                avisarEstado(EstadoJanela.de(isVisible(), false, isActive()));
            }

            @Override
            public void windowClosing(WindowEvent e) {
                // HIDE_ON_CLOSE: a janela some e o polling cai para só LIMITES
                avisarEstado(EstadoJanela.ESCONDIDA);
            }

            @Override
            public void windowClosed(WindowEvent e) {
                avisarEstado(EstadoJanela.ESCONDIDA);
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                avisarEstado();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                avisarEstado(EstadoJanela.ESCONDIDA);
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                gravarJanela.restart();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                gravarJanela.restart();
            }
        });
    }

    /** Ativa, sem foco, ou escondida (fechada para a barra de menus ou minimizada): o que o {@code Sondador} usa para a cadência. */
    EstadoJanela estadoAgora() {
        return EstadoJanela.de(isVisible(), (getExtendedState() & JFrame.ICONIFIED) != 0, isActive());
    }

    private void avisarEstado() {
        avisarEstado(estadoAgora());
    }

    private void avisarEstado(EstadoJanela e) {
        gerenciador.definirJanela(e);
    }

    public PainelPrincipal painel() {
        return painel;
    }

    public Notificacoes avisos() {
        return avisos;
    }

    /**
     * Sair do app (fechar a janela fora do Mac, "Sair" do menu, Cmd+Q): sem nada pendente sai direto; com transferência andando ou edição não salva pergunta antes, e o
     * botão em destaque é Cancelar. Sem saída configurada (testes e o uso antigo) só esconde a janela.
     */
    public void pedirParaSair() {
        Runnable sair = comportamento.sair();
        if (sair == null) {
            setVisible(false);
            return;
        }
        Pendencias pendencias = painel.pendencias();
        if (pendencias.vazio()) {
            sair.run();
            return;
        }
        apresentador.mostrar(new DialogoConfirmar("Sair do " + Versao.NOME + "?", "alerta", pendencias.texto(), "Sair mesmo assim", true, true, sim -> {
            if (sim) {
                sair.run();
            }
        }));
    }

    public Apresentador apresentador() {
        return apresentador;
    }

    /** Solta tudo e fecha a janela de vez (bloqueio do cofre ou encerramento). */
    public void encerrar() {
        gravarJanela.stop();
        painel.encerrar();
        setVisible(false);
        dispose();
    }
}
