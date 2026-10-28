package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.AreaDaPagina;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;

/**
 * A tela Ajustes (a engrenagem da barra lateral, Arquivo, Ajustes e a tecla de comando do sistema mais a vírgula): quatro seções, Aparência (o tema e o tamanho do
 * texto), Navegação (a ordem e as abas escondidas), Terminal (as preferências do terminal) e Sobre (versão, sistema, licença MIT e licenças de terceiros). Cada mudança vale
 * na hora e é lembrada; nada aqui pede para reiniciar. "Voltar" (ou Esc) volta para onde o usuário estava.
 */
public final class TelaAjustes extends Painel {

    private static final long serialVersionUID = 1L;

    /** As seções, na ordem da tela. */
    public enum Secao {
        APARENCIA("aparencia", "Aparência"),
        NAVEGACAO("navegacao", "Navegação"),
        TERMINAL("terminal", "Terminal"),
        AJUDA("ajuda", "Ajuda"),
        SOBRE("sobre", "Sobre");

        private final String id;
        private final String rotulo;

        Secao(String id, String rotulo) {
            this.id = id;
            this.rotulo = rotulo;
        }

        public String id() {
            return id;
        }

        public String rotulo() {
            return rotulo;
        }

        static Secao de(String id) {
            for (Secao s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return APARENCIA;
        }
    }

    private final transient ControleDeAjustes controle;
    private final Abas abas = new Abas();
    private final CardLayout cartoes = new CartoesAjustaveis();
    private final Painel corpo = new Painel(cartoes);
    private final SecaoAparencia aparencia;
    private final SecaoNavegacao navegacao;
    private final SecaoTerminal terminal;
    private final PainelSobre sobre;
    private final SecaoAjuda ajuda;
    private final Botao voltar = Botoes.fantasma("Voltar", "voltar");
    private Secao atual = Secao.APARENCIA;
    private transient Runnable aoVoltar = () -> { };

    public TelaAjustes(ControleDeAjustes controle) {
        super(new BorderLayout());
        this.controle = controle;
        fundo(Tokens.BG);
        aparencia = new SecaoAparencia(controle);
        navegacao = new SecaoNavegacao(controle);
        terminal = new SecaoTerminal(controle);
        sobre = new PainelSobre(controle.informacoes(), controle.copiador(), controle.avisos());
        ajuda = new SecaoAjuda(controle);

        voltar.setToolTipText("Volta para onde você estava (Esc)");
        voltar.addActionListener(e -> aoVoltar.run());
        Rotulo titulo = Rotulo.titulo("Ajustes");
        JComponent cabecalho = Caixa.linha(12, voltar, titulo, Caixa.mola());
        cabecalho.setBorder(new EmptyBorder(14, 14, 6, 14));

        for (Secao s : Secao.values()) {
            abas.adicionar(s.id, s.rotulo);
        }
        abas.setBorder(new EmptyBorder(0, 12, 0, 12));
        abas.aoSelecionar(id -> mostrarSecao(Secao.de(id)));
        corpo.add(new AreaDaPagina(aparencia, false), Secao.APARENCIA.id);
        corpo.add(new AreaDaPagina(navegacao, false), Secao.NAVEGACAO.id);
        corpo.add(new AreaDaPagina(terminal, false), Secao.TERMINAL.id);
        corpo.add(ajuda, Secao.AJUDA.id); // a Ajuda enche o espaço e rola cada painel por dentro dele: não vai dentro de outra rolagem
        corpo.add(new AreaDaPagina(sobre, false), Secao.SOBRE.id);

        JComponent topo = Caixa.coluna(0, cabecalho, abas);
        add(topo, BorderLayout.NORTH);
        add(corpo, BorderLayout.CENTER);
        cartoes.show(corpo, Secao.APARENCIA.id);

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "voltar-dos-ajustes");
        getActionMap().put("voltar-dos-ajustes", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                aoVoltar.run();
            }
        });
        getAccessibleContext().setAccessibleName("Ajustes");
    }

    /** O que "Voltar" e Esc fazem (o app leva o usuário de volta para o que ele via). */
    public void aoVoltar(Runnable acao) {
        this.aoVoltar = acao == null ? () -> { } : acao;
    }

    /** Mostra uma seção (e marca a aba). As seções que mostram o estado de agora são refeitas. */
    public void mostrarSecao(Secao secao) {
        atual = secao;
        abas.selecionar(secao.id);
        switch (secao) {
            case APARENCIA -> aparencia.atualizar();
            case NAVEGACAO -> navegacao.atualizar();
            case TERMINAL -> terminal.atualizar();
            case AJUDA -> ajuda.aoMostrar();
            case SOBRE -> { }
        }
        cartoes.show(corpo, secao.id);
        corpo.revalidate();
        corpo.repaint();
    }

    /** Abre a seção Ajuda já num tópico (um link de outra tela e os testes). */
    public void abrirTopicoDeAjuda(String id) {
        mostrarSecao(Secao.AJUDA);
        ajuda.abrir(id);
    }

    public Secao secaoAtual() {
        return atual;
    }

    // ---- para os testes ----

    public Abas abas() {
        return abas;
    }

    public Botao botaoVoltar() {
        return voltar;
    }

    SecaoAparencia aparencia() {
        return aparencia;
    }

    SecaoNavegacao navegacao() {
        return navegacao;
    }

    SecaoTerminal terminal() {
        return terminal;
    }

    SecaoAjuda ajuda() {
        return ajuda;
    }

    public PainelSobre sobre() {
        return sobre;
    }

    ControleDeAjustes controle() {
        return controle;
    }
}
