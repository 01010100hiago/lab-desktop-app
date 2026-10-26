package br.com.labdesktop.ui.terminal;

import br.com.labdesktop.terminal.CanalDeTerminal;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.EstadoDoTerminal;
import br.com.labdesktop.terminal.PortaoDeEntrada;
import br.com.labdesktop.terminal.SessaoTerminal;
import br.com.labdesktop.terminal.TamanhoDeTerminal;
import br.com.labdesktop.terminal.TituloDeAba;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Teclas;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Um terminal de verdade dentro do app: um {@link JediTermWidget} ligado a uma {@link SessaoTerminal} (ARQUITETURA 7). Mostra o terminal
 * e, por cima, uma faixa só quando há algo a dizer (abrindo, reconectando, sem conexão, sessão fechada, terminou, cofre bloqueado).
 *
 * <ul>
 *   <li>Cada anexação cria um {@link JediTermWidget} NOVO e o põe no lugar do antigo; o antigo é fechado. O tmux repinta a tela inteira
 *       no attach; reaproveitar o widget depois de {@code stop()} é frágil (no 3.76, {@code allSessionsClosed} dispara antes de a
 *       sessão ser limpa).</li>
 *   <li>O tamanho inicial vem do componente DEPOIS do primeiro layout (abrir num padrão faz o tmux redesenhar duas vezes); sem
 *       medida, 120 por 32.</li>
 *   <li>Enquanto o terminal não está ATIVO, o widget antigo fica à vista (dá para ler e copiar), mas toda tecla é descartada e a
 *       faixa diz "sem conexão: teclas descartadas". Nada digitado na queda aparece depois.</li>
 *   <li>Bloquear o cofre descarta o widget e fecha o canal ({@link #descartar()}).</li>
 *   <li>O título que o programa remoto manda (OSC) é limpo antes de virar nome de aba; links só {@code http} e {@code https}, com
 *       Cmd+clique.</li>
 * </ul>
 *
 * <p>Tudo que toca componente acontece na EDT; a sessão e o conector avisam de outras threads e este painel despacha.
 */
public final class PainelTerminal extends Painel {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(PainelTerminal.class);
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"'`\\x00-\\x1f\\x7f]+");

    /** O que a tela de cima quer saber deste terminal. */
    public interface Saida {
        /** O programa remoto trocou o título (já limpo e curto). */
        void aoMudarTitulo(String titulo);

        /** O estado da sessão mudou (já na EDT). */
        void aoMudarEstado(EstadoDoTerminal estado);
    }

    private final transient ContextoDeTerminal ctx;
    private final transient ComandoDeTerminal comando;
    private final transient Saida saida;
    private final transient SessaoTerminal sessao;
    private final transient PortaoDeEntrada portao;
    private final Painel centro = new Painel(new BorderLayout());
    private final Painel faixa = new Painel(new BorderLayout(12, 0)).fundo(Tokens.ATENCAO_SUAVE).borda(Tokens.ATENCAO).raio(10);
    private final Rotulo textoDaFaixa = new Rotulo().tamanho(13f).truncavel();
    private final Rotulo avisoDeTeclas = new Rotulo("Sem conexão: teclas descartadas.").negrito().tamanho(12.5f)
        .corDoToken(Tokens.PERIGO);
    private final Botao reconectar = Botoes.mini("Reconectar", "atualizar");

    private transient JediTermWidget widget;
    private transient JediTermWidget widgetEmEspera;
    private volatile TamanhoDeTerminal tamanhoAtual = TamanhoDeTerminal.PADRAO;
    private boolean iniciado;
    private boolean descartado;
    private boolean tecladoDescartado;
    private EstadoDoTerminal estadoMostrado = EstadoDoTerminal.ABRINDO;
    private String tituloAtual;

    @SuppressWarnings("this-escape") // ligar a sessão e os ouvintes no construtor é o jeito do Swing
    public PainelTerminal(ContextoDeTerminal ctx, ComandoDeTerminal comando, Saida saida) {
        super(new BorderLayout(0, 6));
        this.ctx = ctx;
        this.comando = comando;
        this.saida = saida == null ? new Saida() {
            @Override
            public void aoMudarTitulo(String titulo) { }

            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) { }
        } : saida;
        this.sessao = new SessaoTerminal(ctx.transporte(), comando, () -> tamanhoAtual, ctx.agendador());
        this.portao = new PortaoDeEntrada(ctx.agendador(), sessao::estado);
        fundo(Tokens.BG);

        centro.setBackground(new Color(PaletaTerminal.FUNDO));
        centro.setOpaque(true);
        textoDaFaixa.corDoToken(Tokens.TEXTO);
        faixa.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        reconectar.addActionListener(e -> sessao.reconectar());
        faixa.add(textoDaFaixa, BorderLayout.CENTER);
        faixa.add(br.com.labdesktop.ui.componentes.Caixa.fluxo(java.awt.FlowLayout.RIGHT, 10, avisoDeTeclas, reconectar), BorderLayout.EAST);
        faixa.setVisible(false);
        add(faixa, BorderLayout.NORTH);
        add(centro, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName(comando.ehShell() ? "Terminal do servidor" : "Terminal da sessão do Claude");

        sessao.adicionarOuvinte(new SessaoTerminal.Ouvinte() {
            @Override
            public void aoMudarEstado(EstadoDoTerminal estado) {
                Fundo.naEdt(() -> aplicarEstado(estado));
            }

            @Override
            public void aoAnexar(CanalDeTerminal canal, long geracao) {
                Fundo.naEdt(() -> anexar(canal, geracao));
            }
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(this::iniciar); // depois do primeiro layout, para medir
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida (EDT)
    // ---------------------------------------------------------------------------------------------

    /** Mede o terminal e abre o canal. Chamado quando o painel aparece pela primeira vez; idempotente. */
    public void iniciar() {
        if (iniciado || descartado) {
            return;
        }
        iniciado = true;
        widgetEmEspera = criarWidget();
        centro.add(widgetEmEspera, BorderLayout.CENTER);
        layoutarJa(this);
        tamanhoAtual = medir(widgetEmEspera);
        aplicarEstado(sessao.estado());
        sessao.iniciar();
    }

    /**
     * Fecha tudo: a sessão (e o canal), o widget e a espera. É o que acontece ao fechar a aba, ao sair da página e ao bloquear o cofre.
     * Idempotente.
     */
    public void descartar() {
        if (descartado) {
            return;
        }
        descartado = true;
        sessao.fechar();
        fecharWidget(widgetEmEspera);
        widgetEmEspera = null;
        fecharWidget(widget);
        widget = null;
        centro.removeAll();
        centro.revalidate();
        centro.repaint();
    }

    public SessaoTerminal sessao() {
        return sessao;
    }

    /** O portão que ordena o teclado do usuário e os envios da barra lateral. */
    public PortaoDeEntrada portao() {
        return portao;
    }

    public ComandoDeTerminal comando() {
        return comando;
    }

    /** Dá o foco ao terminal (se há um widget). */
    public void focar() {
        JediTermWidget w = widget != null ? widget : widgetEmEspera;
        if (w != null) {
            w.getTerminalPanel().requestFocusInWindow();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Anexação
    // ---------------------------------------------------------------------------------------------

    private void anexar(CanalDeTerminal canal, long geracao) {
        if (descartado) {
            canal.fechar();
            return;
        }
        JediTermWidget novo = widgetEmEspera != null ? widgetEmEspera : criarWidget();
        widgetEmEspera = null;
        JediTermWidget antigo = widget;
        boolean tinhaFoco = antigo != null && antigo.getTerminalPanel().isFocusOwner();
        SshTtyConnector conector = new SshTtyConnector(canal, () -> sessao.ativa(geracao),
            comando.ehShell() ? "shell" : "claude", new SshTtyConnector.Ouvinte() {
                @Override
                public void aoDescartarTecla() {
                    Fundo.naEdt(() -> descartouTecla());
                }

                @Override
                public void aoRedimensionar(TamanhoDeTerminal tamanho) {
                    tamanhoAtual = tamanho;
                }

                @Override
                public void aoEscritaTravada() {
                    LOG.info("Uma escrita no terminal travou; o canal foi fechado e o terminal vai reanexar.");
                }
            });
        centro.removeAll();
        centro.add(novo, BorderLayout.CENTER);
        layoutarJa(centro); // o widget novo precisa de tamanho ANTES de iniciar: sem isso o terminal nasce 1 por 1
        novo.setTtyConnector(conector);
        novo.start();
        widget = novo;
        fecharWidget(antigo);
        tecladoDescartado = false;
        atualizarFaixa();
        if (tinhaFoco || isShowing()) {
            novo.getTerminalPanel().requestFocusInWindow();
        }
        revalidate();
        repaint();
    }

    private JediTermWidget criarWidget() {
        ConfigTerminal config = new ConfigTerminal(ctx.preferencias().get());
        JediTermWidget w = new JediTermWidget(TamanhoDeTerminal.PADRAO.colunas(), TamanhoDeTerminal.PADRAO.linhas(), config) {
            private static final long serialVersionUID = 1L;

            @Override
            protected TerminalPanel createTerminalPanel(SettingsProvider settings, StyleState estilo, TerminalTextBuffer buffer) {
                return new TerminalPanel(settings, buffer, estilo) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void setWindowTitle(String titulo) {
                        super.setWindowTitle(titulo);
                        Fundo.naEdt(() -> mudouOTitulo(titulo));
                    }
                };
            }
        };
        w.setBackground(new Color(PaletaTerminal.FUNDO));
        w.addHyperlinkFilter(filtroDeLinks());
        w.getTerminalPanel().addCustomKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                portao.aoDigitar();
            }

            @Override
            public void keyTyped(KeyEvent e) {
                portao.aoDigitar();
            }
        });
        w.getAccessibleContext().setAccessibleName(getAccessibleContext().getAccessibleName());
        return w;
    }

    private static void fecharWidget(JediTermWidget w) {
        if (w == null) {
            return;
        }
        try {
            w.close(); // para o emulador, descarta o painel e fecha o conector (o canal)
        } catch (RuntimeException e) {
            LOG.debug("Falha ao fechar o widget do terminal: {}", e.toString());
        }
    }

    /**
     * Refaz o layout AGORA, de cima para baixo. {@code validate()} não faz nada num componente que ainda não tem janela (peer), e o
     * terminal precisa do tamanho já na primeira medida; com janela, dá o mesmo resultado que {@code validate()}.
     */
    private static void layoutarJa(Container c) {
        c.doLayout();
        for (Component f : c.getComponents()) {
            if (f instanceof Container k) {
                layoutarJa(k);
            }
        }
    }

    /** Mede o terminal no componente (depois do layout); sem medida confiável, o padrão de 120 por 32. */
    private static TamanhoDeTerminal medir(JediTermWidget w) {
        try {
            TermSize t = w.getTerminalPanel().getTerminalSizeFromComponent();
            if (t != null && t.getColumns() >= 20 && t.getRows() >= 5) {
                return new TamanhoDeTerminal(t.getColumns(), t.getRows());
            }
        } catch (RuntimeException e) {
            LOG.debug("Não consegui medir o terminal: {}", e.toString());
        }
        return TamanhoDeTerminal.PADRAO;
    }

    // ---------------------------------------------------------------------------------------------
    // Estado e faixa
    // ---------------------------------------------------------------------------------------------

    private void aplicarEstado(EstadoDoTerminal estado) {
        if (descartado) {
            return;
        }
        estadoMostrado = estado;
        if (estado.ativa()) {
            tecladoDescartado = false;
        }
        if (estado.tipo() == EstadoDoTerminal.Tipo.BLOQUEADA) {
            // o cofre foi bloqueado: o widget (com o que estava na tela) é descartado, não só escondido
            fecharWidget(widget);
            fecharWidget(widgetEmEspera);
            widget = null;
            widgetEmEspera = null;
            centro.removeAll();
        }
        portao.estadoMudou();
        atualizarFaixa();
        saida.aoMudarEstado(estado);
        centro.revalidate();
        centro.repaint();
    }

    private void descartouTecla() {
        if (descartado || tecladoDescartado) {
            return;
        }
        tecladoDescartado = true;
        atualizarFaixa();
    }

    private void atualizarFaixa() {
        String texto = textoDaFaixa(estadoMostrado, comando.ehShell());
        boolean mostra = texto != null;
        faixa.setVisible(mostra);
        if (mostra) {
            textoDaFaixa.setText(texto);
            textoDaFaixa.setToolTipText(texto);
        }
        avisoDeTeclas.setVisible(mostra && !estadoMostrado.ativa() && estadoMostrado.tipo() != EstadoDoTerminal.Tipo.ABRINDO
            && tecladoDescartado);
        reconectar.setVisible(mostra && podeReconectar(estadoMostrado));
        reconectar.setText(estadoMostrado.tipo() == EstadoDoTerminal.Tipo.ENCERRADA ? "Abrir de novo" : "Reconectar");
        faixa.revalidate();
        faixa.repaint();
    }

    private static boolean podeReconectar(EstadoDoTerminal e) {
        return switch (e.tipo()) {
            case SEM_CONEXAO, SESSAO_FECHADA, ENCERRADA -> true;
            default -> false;
        };
    }

    /** O texto da faixa para o estado, ou {@code null} quando não há nada a dizer (terminal ativo). Público para os testes. */
    public static String textoDaFaixa(EstadoDoTerminal e, boolean shell) {
        return switch (e.tipo()) {
            case ATIVA -> null;
            case ABRINDO -> "Abrindo o terminal…";
            case REANEXANDO -> "Conexão perdida. Reconectando (tentativa " + e.tentativa() + " de 5)…";
            case SEM_CONEXAO -> (e.motivo() == null || e.motivo().isBlank() ? "Sem conexão com o terminal." : e.motivo());
            case SESSAO_FECHADA -> "A sessão do Claude não está aberta neste projeto. Abra a sessão e o terminal volta sozinho.";
            case ENCERRADA -> shell
                ? "O terminal terminou" + (e.saida() == null ? "." : " (código " + e.saida() + ").")
                : "A sessão do Claude terminou (o tmux saiu).";
            case BLOQUEADA -> "O cofre foi bloqueado: o terminal foi fechado.";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Título e links
    // ---------------------------------------------------------------------------------------------

    private void mudouOTitulo(String bruto) {
        if (descartado) {
            return;
        }
        String limpo = TituloDeAba.limpar(bruto, "");
        if (limpo.isEmpty() || limpo.equals(tituloAtual)) {
            return;
        }
        tituloAtual = limpo;
        saida.aoMudarTitulo(limpo);
    }

    /** Acha os links {@code http} e {@code https} de uma linha do terminal (com a ponta aparada); só eles viram link clicável. */
    HyperlinkFilter filtroDeLinks() {
        return texto -> {
            List<LinkResultItem> itens = new ArrayList<>();
            Matcher m = LINK.matcher(texto);
            while (m.find()) {
                String url = aparar(m.group());
                if (url.length() > 8) {
                    itens.add(new LinkResultItem(m.start(), m.start() + url.length(), new LinkInfo(() -> abrirLink(url, EventQueue.getCurrentEvent()))));
                }
            }
            return itens.isEmpty() ? null : new LinkResult(itens);
        };
    }

    /** Tira da ponta o que não faz parte do endereço (ponto, vírgula, parêntese que fecha a frase). */
    static String aparar(String url) {
        int fim = url.length();
        while (fim > 0 && ".,;:!?)]}>".indexOf(url.charAt(fim - 1)) >= 0) {
            fim--;
        }
        return url.substring(0, fim);
    }

    /**
     * Só abre com Cmd+clique (Ctrl fora do Mac): um clique comum só seleciona e nunca leva o usuário a uma página. O JediTerm chama o
     * link de dentro do tratamento do clique, então o evento em curso diz quais teclas estavam apertadas.
     */
    void abrirLink(String url, AWTEvent evento) {
        if (ehCliqueComComando(evento)) {
            ctx.abrirLink().accept(url);
        }
    }

    static boolean ehCliqueComComando(AWTEvent evento) {
        return evento instanceof MouseEvent m && (m.getModifiersEx() & Teclas.comando()) != 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    /** O widget do terminal que está na tela agora, ou {@code null}. */
    public JediTermWidget widget() {
        return widget;
    }

    public boolean faixaVisivel() {
        return faixa.isVisible();
    }

    public String textoDaFaixaAgora() {
        return textoDaFaixa.getText();
    }

    public boolean avisoDeTeclasVisivel() {
        return avisoDeTeclas.isVisible();
    }

    public Botao botaoReconectar() {
        return reconectar;
    }

    public JComponent areaDoTerminal() {
        return centro;
    }

    public TamanhoDeTerminal tamanhoAtual() {
        return tamanhoAtual;
    }

    public EstadoDoTerminal estadoMostrado() {
        return estadoMostrado;
    }

    public boolean descartado() {
        return descartado;
    }
}
