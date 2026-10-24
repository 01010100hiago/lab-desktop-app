package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
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

    private final String titulo;
    private final Painel raiz = new Painel(new BorderLayout());
    private JDialog janela;
    private JButton botaoPadrao;
    private boolean fechado;

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

    /** O botão que responde ao Enter. */
    protected final void botaoPadrao(JButton b) {
        this.botaoPadrao = b;
        if (janela != null) {
            janela.getRootPane().setDefaultButton(b);
        }
    }

    /** Mostra o diálogo modal sobre {@code pai} e só volta quando ele fechar. Na EDT. */
    public final void mostrar(Window pai) {
        janela = new JDialog(pai, titulo, Dialog.ModalityType.APPLICATION_MODAL);
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
        janela.setResizable(false);
        janela.pack();
        janela.setLocationRelativeTo(pai);
        antesDeMostrar();
        janela.setVisible(true);
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
        Rotulo marca = new Rotulo().icone(Icones.colorido(icone, 22, Tokens.ACENTO));
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
