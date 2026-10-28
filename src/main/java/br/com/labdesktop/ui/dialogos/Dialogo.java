package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.MioloRolavel;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;

/**
 * A base dos diálogos do app (modais, no tema, fechados com Esc). O CONTEÚDO fica num painel ({@link #raiz()}) e a
 * janela {@code JDialog} só nasce em {@link #mostrar}: assim os testes montam, preenchem e clicam no painel sem
 * precisar de uma janela de verdade (o Swing sem cabeça não cria {@code JDialog}).
 *
 * <p>Regras de todos os diálogos (ARQUITETURA 1.3): nada de rede, disco ou KDF na EDT (o diálogo dispara a tarefa
 * em {@code Fundo}, desabilita o que não pode ser clicado e desenha o resultado); texto do servidor entra por
 * componentes que desligam o HTML; segredo digitado é zerado ao fechar.
 */
public abstract class Dialogo {

    /** Largura do texto corrido nos diálogos comuns. */
    public static final int LARGURA_DO_TEXTO = 440;

    /** Quanto da altura útil da tela um diálogo com miolo rolável pode ocupar. */
    private static final double FRACAO_DA_TELA = 0.8;
    /** Altura útil da tela quando não há tela (testes sem cabeça): a de um notebook comum, 1280 por 800. */
    private static final int ALTURA_DA_TELA_SEM_CABECA = 800;
    /** A barra de título e a borda da janela, que o {@code pack} soma ao conteúdo. */
    private static final int ALTURA_DA_BARRA_DE_TITULO = 32;
    private static final int FOLGA_DO_RODAPE = 12;
    /** Só os testes mexem: a altura da tela "de mentira" (0 ou menos volta a perguntar à tela de verdade). */
    private static volatile int alturaDaTelaParaTeste;

    /** Os diálogos que já foram mostrados e ainda não fecharam (só a EDT mexe; o cofre que bloqueia os fecha, para nenhum segredo digitado ficar esperando). */
    private static final java.util.Set<Dialogo> ABERTOS = new java.util.LinkedHashSet<>();

    private final String titulo;
    private final Painel raiz = new Painel(new BorderLayout());
    private JDialog janela;
    private JButton botaoPadrao;
    private boolean fechado;
    private MioloRolavel miolo;
    private JComponent rodapeFixo;

    protected Dialogo(String titulo) {
        this.titulo = titulo;
        raiz.fundo(Tokens.BG);
        raiz.setBorder(new EmptyBorder(22, 26, 18, 26));
    }

    /** O painel com todo o conteúdo (para testes e para quem quiser embutir). */
    public final JComponent raiz() {
        return raiz;
    }

    public final String titulo() {
        return titulo;
    }

    /** Define o conteúdo. */
    protected final void conteudo(JComponent c) {
        raiz.add(c, BorderLayout.CENTER);
    }

    /**
     * Define o conteúdo em duas partes: o {@code corpo} ROLA quando passa de 80 % da altura da tela, e o {@code rodape}
     * (os botões) fica fixo embaixo, sempre à vista. Para diálogos cujo conteúdo cresce (seções que abrem), de modo que a
     * janela, que não é redimensionável, nunca fique maior que a tela e esconda o botão que o usuário precisa clicar.
     */
    protected final void conteudoComRodape(JComponent corpo, JComponent rodape) {
        miolo = new MioloRolavel(corpo, this::alturaMaximaDoMiolo);
        rodapeFixo = rodape;
        JPanel embaixo = new Painel(new BorderLayout());
        embaixo.setBorder(new EmptyBorder(FOLGA_DO_RODAPE, 0, 0, 0));
        embaixo.add(rodape, BorderLayout.CENTER);
        raiz.add(miolo, BorderLayout.CENTER);
        raiz.add(embaixo, BorderLayout.SOUTH);
    }

    /** O miolo rolável, se o diálogo usa {@link #conteudoComRodape}; {@code null} nos outros. */
    public final MioloRolavel mioloRolavel() {
        return miolo;
    }

    /** Volta o miolo ao começo (troca de passo). Não faz nada se o diálogo não tem miolo rolável. */
    protected final void rolarParaOTopo() {
        if (miolo != null) {
            miolo.rolarParaOTopo();
        }
    }

    /**
     * A altura que o miolo pode pedir: 80 % da altura útil da tela menos o que o resto do diálogo já ocupa (a borda interna,
     * o rodapé e a barra de título).
     */
    private int alturaMaximaDoMiolo() {
        Insets margem = raiz.getInsets();
        int rodape = rodapeFixo == null ? 0 : rodapeFixo.getPreferredSize().height + FOLGA_DO_RODAPE;
        int disponivel = (int) (alturaUtilDaTela() * FRACAO_DA_TELA) - margem.top - margem.bottom - rodape - ALTURA_DA_BARRA_DE_TITULO;
        return Math.max(160, disponivel);
    }

    /** A altura da tela onde o diálogo aparece, sem as barras do sistema (menu do macOS, Dock). */
    private int alturaUtilDaTela() {
        if (alturaDaTelaParaTeste > 0) {
            return alturaDaTelaParaTeste;
        }
        if (GraphicsEnvironment.isHeadless()) {
            return ALTURA_DA_TELA_SEM_CABECA;
        }
        Rectangle area = areaUtilDaTela();
        return area.height;
    }

    private Rectangle areaUtilDaTela() {
        GraphicsConfiguration tela = janela != null ? janela.getGraphicsConfiguration()
            : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle limites = tela.getBounds();
        Insets barras = Toolkit.getDefaultToolkit().getScreenInsets(tela);
        return new Rectangle(limites.x + barras.left, limites.y + barras.top,
            limites.width - barras.left - barras.right, limites.height - barras.top - barras.bottom);
    }

    /** Só para os testes: finge uma tela com esta altura útil. Passe 0 para voltar à tela de verdade. */
    public static void definirAlturaDaTelaParaTeste(int altura) {
        alturaDaTelaParaTeste = altura;
    }

    /** O botão que responde ao Enter. */
    protected final void botaoPadrao(JButton b) {
        this.botaoPadrao = b;
        if (janela != null) {
            janela.getRootPane().setDefaultButton(b);
        }
    }

    /** O botão que responde ao Enter (o botão padrão da janela), ou {@code null}: os testes de cor o ligam a um {@code JRootPane} para ver como o FlatLaf o pinta. */
    public final JButton botaoDoEnter() {
        return botaoPadrao;
    }

    /** Registra o diálogo como aberto (o {@link #mostrar} chama; os testes chamam direto, porque o Swing sem cabeça não cria {@code JDialog}). */
    final void marcarAberto() {
        ABERTOS.add(this);
    }

    /**
     * Fecha TODOS os diálogos abertos, como se o usuário os tivesse fechado: cada um passa por {@link #aoFechar()} (a senha digitada é zerada) e só depois a janela some. O app chama
     * isto ao bloquear o cofre (e ao sair): com o cofre travado a janela principal desaparece, e um diálogo modal que ficasse para trás seria derrubado junto com ela, sem
     * passar por aqui, deixando o campo da senha cheio. Na EDT.
     */
    public static void fecharTodosOsAbertos() {
        for (Dialogo d : new java.util.ArrayList<>(ABERTOS)) {
            d.fechar();
        }
    }

    /** Quantos diálogos estão abertos agora (para os testes). */
    public static int quantosAbertos() {
        return ABERTOS.size();
    }

    /** Mostra o diálogo modal sobre {@code pai} e só volta quando ele fechar. Na EDT. */
    public final void mostrar(Window pai) {
        marcarAberto();
        janela = new JDialog(pai, titulo, Dialog.ModalityType.APPLICATION_MODAL);
        br.com.labdesktop.ui.IconesDoApp.aplicar(janela); // sem janela pai (a senha-mestra) o diálogo aparece na barra de tarefas do Windows: com o ícone do app
        janela.setContentPane(raiz);
        janela.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        janela.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                aoCancelar();
            }
        });
        janela.getRootPane().registerKeyboardAction(e -> aoCancelar(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (botaoPadrao != null) {
            janela.getRootPane().setDefaultButton(botaoPadrao);
        }
        janela.setResizable(redimensionavel());
        janela.pack();
        janela.setLocationRelativeTo(pai);
        antesDeMostrar();
        janela.setVisible(true);
    }

    /** O usuário pode mudar o tamanho da janela? Só os diálogos que mostram uma lista longa (o registro, o relatório) dizem que sim; os outros têm o tamanho do conteúdo. */
    protected boolean redimensionavel() {
        return false;
    }

    /** Gancho: roda logo antes de a janela aparecer (foco inicial, primeira consulta). */
    protected void antesDeMostrar() { }

    /** Esc, o botão de fechar da janela e o botão Cancelar chamam isto. Por padrão só fecha. */
    protected void aoCancelar() {
        fechar();
    }

    /** Fecha a janela (se existe), uma vez, e roda {@link #aoFechar()}. Na EDT. */
    protected final void fechar() {
        if (fechado) {
            return;
        }
        fechado = true;
        ABERTOS.remove(this);
        try {
            aoFechar();
        } finally {
            if (janela != null) {
                janela.dispose();
            }
        }
    }

    /** Gancho: roda UMA vez ao fechar, por qualquer caminho. É onde segredos são zerados. */
    protected void aoFechar() { }

    /** Refaz o tamanho depois de o conteúdo mudar (mensagem de erro que apareceu, cartão trocado). */
    protected final void reajustar() {
        raiz.revalidate();
        if (janela != null) {
            janela.pack();
            manterNaTela();
        }
    }

    /** Crescer para baixo a partir do canto de cima pode empurrar a janela para fora da tela: sobe o quanto precisar. */
    private void manterNaTela() {
        if (GraphicsEnvironment.isHeadless() || !janela.isShowing()) {
            return;
        }
        Rectangle area = areaUtilDaTela();
        Rectangle atual = janela.getBounds();
        int x = Math.max(area.x, Math.min(atual.x, area.x + area.width - atual.width));
        int y = Math.max(area.y, Math.min(atual.y, area.y + area.height - atual.height));
        if (x != atual.x || y != atual.y) {
            janela.setLocation(x, y);
        }
    }

    protected final boolean jaFechou() {
        return fechado;
    }

    /** A janela, se já foi aberta (para posicionar diálogos filhos). */
    protected final JDialog janela() {
        return janela;
    }

    // ---------------------------------------------------------------------------------------------
    // Peças comuns
    // ---------------------------------------------------------------------------------------------

    /** Ícone grande no canto e o título ao lado. */
    protected static JComponent cabecalho(String icone, String titulo) {
        Rotulo marca = new Rotulo().icone(Icones.colorido(icone, 22, Tokens.DESTAQUE));
        return Caixa.linha(10, marca, Rotulo.titulo(titulo));
    }

    protected static TextoCorrido paragrafo(String texto) {
        return new TextoCorrido(texto, LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2);
    }

    /** Dica ou legenda longa: pequena, discreta e QUEBRANDO linha (um {@code Rotulo} cortaria com reticências). */
    protected static TextoCorrido dicaCorrida(String texto) {
        return new TextoCorrido(texto, LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO3).tamanho(12f);
    }

    protected static Rotulo erro() {
        return new Rotulo().corDoToken(Tokens.PERIGO).tamanho(12.5f);
    }

    /**
     * Mensagem de erro que QUEBRA linha (um {@link Rotulo} cortaria com reticências as frases longas, como as do importar
     * chave) e só ocupa lugar quando tem texto. Escreva nela com {@link #dizerErro}.
     */
    protected static TextoCorrido erroCorrido() {
        TextoCorrido t = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.PERIGO).tamanho(12.5f);
        t.setVisible(false);
        return t;
    }

    /** Põe (ou tira, com texto vazio) a mensagem de uma {@link #erroCorrido()}. */
    protected static void dizerErro(TextoCorrido destino, String texto) {
        destino.setText(texto == null ? "" : texto);
        destino.setVisible(!destino.getText().isEmpty());
    }

    /** Uma linha de botões à direita; o primeiro componente de {@code esquerda} (se houver) fica à esquerda. */
    protected static JComponent rodape(Component esquerda, Component... direita) {
        JPanel p = new Painel(new BorderLayout());
        if (esquerda != null) {
            p.add(esquerda, BorderLayout.WEST);
        }
        p.add(Caixa.linha(8, direita), BorderLayout.EAST);
        return p;
    }

    /** Uma ação de teclado nomeada, para atalhos locais. */
    protected static AbstractAction acao(Runnable r) {
        return new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }
}
