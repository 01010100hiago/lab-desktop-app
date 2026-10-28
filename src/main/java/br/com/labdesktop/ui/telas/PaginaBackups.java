package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheProjeto.Backup;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Backups": a tabela dos backups que o detalhe traz (arquivo, tamanho, quando), do mais recente ao mais antigo, paginada.
 * O contador vai no rótulo da aba ({@code Backups (5)}), como na web. Sem backups, uma frase explica de onde eles vêm. Não
 * consulta nada além do detalhe do projeto.
 */
final class PaginaBackups extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    static final int LINHAS_POR_PAGINA = 10;

    private final CaixaDoServidor caixa = new CaixaDoServidor("Backups do projeto", "disco");
    private final Rotulo apoio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final TabelaPaginada<Backup> tabela = new TabelaPaginada<>(LINHAS_POR_PAGINA, List.of(
        new Coluna<>("Arquivo", 2.4, false, true, Backup::arquivo, null),
        new Coluna<>("Tamanho", 0.7, true, false, b -> b.tamanho() == null ? null : Formato.fmtBytes(b.tamanho()),
            Comparator.comparingLong(b -> b.tamanho() == null ? -1 : b.tamanho())),
        new Coluna<>("Quando", 1.2, false, false, b -> quando(b), null)));

    PaginaBackups(ContextoDoProjeto ctx) {
        super(ctx);
        iniciar();
    }

    @Override
    JComponent montarConteudo() {
        caixa.apoio(apoio);
        caixa.linha(tabela, 0);
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
        List<Backup> itens = ctx.detalhe.backups();
        apoio.setText(itens.isEmpty() ? "" : itens.size() + (itens.size() == 1 ? " backup" : " backups"));
        tabela.definir(itens, ctx.temClaude()
            ? "Nenhum backup ainda. O app do servidor faz o backup do banco de dados (pg_dump) antes de abrir a "
                + "sessão do Claude em projetos que têm Postgres."
            : "Nenhum backup ainda.");
        caixa.terminar();
    }

    /** O texto da coluna "Quando": a data e a hora e, entre parênteses, há quanto tempo. */
    String quando(Backup b) {
        return FormatosDoServidor.desde(b.quando(), ctx.agora(), ctx.fuso);
    }

    TabelaPaginada<Backup> tabela() {
        return tabela;
    }

    String textoDoApoio() {
        return apoio.getText();
    }
}
