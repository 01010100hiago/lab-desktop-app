package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheProjeto.Arquivo;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Arquivos": a lista da RAIZ do projeto que o detalhe já traz (pastas primeiro, sem os arquivos ocultos), em tabela
 * paginada e SOMENTE LEITURA. Navegar dentro das pastas e enviar arquivos chega com o gerenciador de arquivos (SFTP), de outra
 * etapa; a aba avisa isso numa frase. Não consulta nada além do detalhe do projeto.
 */
final class PaginaArquivos extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    static final int LINHAS_POR_PAGINA = 12;

    private final CaixaDoServidor caixa = new CaixaDoServidor("Arquivos da raiz do projeto", "pasta");
    private final Rotulo apoio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Rotulo aviso = new Rotulo("Aqui só dá para ver a lista. Abrir pastas e enviar arquivos chega com o gerenciador de "
        + "arquivos (SFTP), numa próxima etapa.").corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
    private static final String SEM_TAMANHO = "sem-tamanho";
    private static final String COM_TAMANHO = "com-tamanho";
    private static final Coluna<Arquivo> NOME = new Coluna<>("Nome", 2.2, false, true, Arquivo::nome,
        Comparator.comparing(a -> a.nome() == null ? "" : a.nome().toLowerCase()));
    private static final Coluna<Arquivo> TIPO = new Coluna<>("Tipo", 0.7, false, false,
        a -> FormatosDoProjeto.tipoDeItem(a.tipo()), null);
    private static final Coluna<Arquivo> TAMANHO = new Coluna<>("Tamanho", 0.7, true, false,
        a -> FormatosDoProjeto.tamanho(a.tamanho()), Comparator.comparingLong(a -> a.tamanho() == null ? -1 : a.tamanho()));

    /**
     * Duas tabelas: o agente de hoje não manda o tamanho dos itens da raiz, e uma coluna cheia de traços é só ruído. A com
     * tamanho aparece sozinha no dia em que o agente passar a mandar.
     */
    private final TabelaPaginada<Arquivo> tabelaSemTamanho = new TabelaPaginada<>(LINHAS_POR_PAGINA, List.of(NOME, TIPO));
    private final TabelaPaginada<Arquivo> tabelaComTamanho = new TabelaPaginada<>(LINHAS_POR_PAGINA, List.of(NOME, TIPO, TAMANHO));
    private final CardLayout cartoes = new CardLayout();
    private final JPanel tabelas = new Painel(cartoes);
    private boolean comTamanho;

    PaginaArquivos(ContextoDoProjeto ctx) {
        super(ctx);
        iniciar();
    }

    @Override
    JComponent montarConteudo() {
        caixa.apoio(apoio);
        tabelas.add(tabelaSemTamanho, SEM_TAMANHO);
        tabelas.add(tabelaComTamanho, COM_TAMANHO);
        cartoes.show(tabelas, SEM_TAMANHO);
        caixa.linha(tabelas, 0);
        caixa.linha(aviso, 10);
        JPanel conteudo = new Painel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        conteudo.add(caixa, BorderLayout.NORTH);
        return conteudo;
    }

    @Override
    void atualizarConteudo() {
        List<Arquivo> itens = ctx.detalhe.arquivos();
        long pastas = itens.stream().filter(a -> "pasta".equals(a.tipo())).count();
        apoio.setText(itens.isEmpty() ? "" : pastas + (pastas == 1 ? " pasta" : " pastas") + " · " + (itens.size() - pastas)
            + ((itens.size() - pastas) == 1 ? " arquivo" : " arquivos"));
        comTamanho = itens.stream().anyMatch(a -> a.tamanho() != null);
        String vazio = "A raiz do projeto está vazia (ou o agente não listou nada).";
        tabelaSemTamanho.definir(itens, vazio);
        tabelaComTamanho.definir(itens, vazio);
        cartoes.show(tabelas, comTamanho ? COM_TAMANHO : SEM_TAMANHO);
        caixa.terminar();
    }

    /** A tabela que está à vista. */
    TabelaPaginada<Arquivo> tabela() {
        return comTamanho ? tabelaComTamanho : tabelaSemTamanho;
    }

    boolean mostraTamanho() {
        return comTamanho;
    }

    String textoDoAviso() {
        return aviso.getText();
    }

    String textoDoApoio() {
        return apoio.getText();
    }
}
