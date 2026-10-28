package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheProjeto.Backup;
import br.com.labdesktop.arquivos.PlanoDeBackups;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Backups": a tabela dos backups que o detalhe traz (arquivo, tamanho, quando), do mais recente ao mais antigo, paginada, e agora com <b>Baixar</b> em cada arquivo e "Baixar
 * selecionados". O contador vai no rótulo da aba ({@code Backups (5)}), como na web. Sem backups, uma frase explica de onde eles vêm.
 *
 * <p>Baixar só LÊ do servidor (SFTP), então não depende da trava das ações de escrita: não altera nada lá. Quem baixa é o {@code BaixadorDeBackups} (destino pelo seletor do sistema, fila
 * com progresso e Cancelar, tamanho conferido, "Mostrar na pasta"). Com o servidor sem conexão, os botões ficam desligados e dizem por quê.
 */
final class PaginaBackups extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    static final int LINHAS_POR_PAGINA = 10;

    private final CaixaDoServidor caixa = new CaixaDoServidor("Backups do projeto", "disco");
    private final Rotulo apoio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao baixarSelecionados = Botoes.mini("Baixar selecionados", "baixar");
    private final Botao selecionarTodos = Botoes.mini("Selecionar todos", null);
    private final TextoCorrido nota = new TextoCorrido("Baixar só lê do servidor: nada é mudado lá. Cada arquivo tem o tamanho conferido com o do servidor ao terminar, "
        + "e se a conexão cair o arquivo recomeça do zero.", 900).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Set<String> selecionados = new LinkedHashSet<>();
    private final List<JCheckBox> caixas = new ArrayList<>();
    private final List<Botao> botoesDeBaixar = new ArrayList<>();
    private List<Backup> itens = List.of();
    private final TabelaPaginada<Backup> tabela = new TabelaPaginada<>(LINHAS_POR_PAGINA, List.of(
        new Coluna<>("Arquivo", 2.4, false, true, Backup::arquivo, null),
        new Coluna<>("Tamanho", 0.7, true, false, b -> b.tamanho() == null ? null : Formato.fmtBytes(b.tamanho()),
            Comparator.comparingLong(b -> b.tamanho() == null ? -1 : b.tamanho())),
        new Coluna<>("Quando", 1.2, false, false, b -> quando(b), null)));

    PaginaBackups(ContextoDoProjeto ctx) {
        super(ctx);
        tabela.acaoPorLinha("Baixar", this::celulaDe);
        baixarSelecionados.setToolTipText("Baixa de uma vez os backups marcados");
        baixarSelecionados.setName("baixar-selecionados");
        selecionarTodos.setToolTipText("Marca todos os backups da lista");
        selecionarTodos.setName("selecionar-todos");
        baixarSelecionados.addActionListener(e -> baixar(itens.stream().filter(b -> selecionados.contains(b.arquivo())).toList()));
        selecionarTodos.addActionListener(e -> {
            if (selecionados.size() == itens.size()) {
                selecionados.clear();
            } else {
                itens.forEach(b -> selecionados.add(b.arquivo()));
            }
            tabela.redesenhar();
            atualizarBotoes();
        });
        iniciar();
    }

    @Override
    JComponent montarConteudo() {
        caixa.apoio(Caixa.linha(8, apoio, selecionarTodos, baixarSelecionados));
        caixa.linha(tabela, 0);
        caixa.linha(nota, 8);
        if (ctx.baixadorDeBackups != null) {
            caixa.linha(ctx.baixadorDeBackups, 8);
        }
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

    /** A célula de cada linha: a caixa de marcar e o botão Baixar. */
    private JComponent celulaDe(Backup b) {
        JCheckBox c = new JCheckBox();
        c.setOpaque(false);
        c.setSelected(selecionados.contains(b.arquivo()));
        c.setToolTipText("Marcar " + b.arquivo() + " para baixar junto com outros");
        c.getAccessibleContext().setAccessibleName("Marcar " + b.arquivo());
        c.setName("marcar");
        c.addActionListener(e -> {
            if (c.isSelected()) {
                selecionados.add(b.arquivo());
            } else {
                selecionados.remove(b.arquivo());
            }
            atualizarBotoes();
        });
        Botao baixar = Botoes.miniBaixo("Baixar", "baixar");
        baixar.setName("baixar");
        baixar.setToolTipText("Baixa " + b.arquivo() + " para este computador");
        baixar.getAccessibleContext().setAccessibleName("Baixar " + b.arquivo());
        baixar.addActionListener(e -> baixar(List.of(b)));
        caixas.add(c);
        botoesDeBaixar.add(baixar);
        return Caixa.linha(8, c, baixar);
    }

    private void baixar(List<Backup> escolhidos) {
        if (escolhidos.isEmpty() || ctx.baixarBackups == null) {
            return;
        }
        ctx.baixarBackups.accept(escolhidos.stream().map(b -> new PlanoDeBackups.Backup(b.arquivo(), b.tamanho(), b.quando())).toList());
    }

    private void atualizarBotoes() {
        int n = selecionados.size();
        baixarSelecionados.setText(n == 0 ? "Baixar selecionados" : "Baixar selecionados (" + n + ")");
        baixarSelecionados.setEnabled(n > 0);
        selecionarTodos.setText(!itens.isEmpty() && selecionados.size() == itens.size() ? "Limpar a seleção" : "Selecionar todos");
        selecionarTodos.setEnabled(!itens.isEmpty());
        boolean pode = ctx.baixarBackups != null && ctx.estado != null ? ctx.estado.conexao().permiteExec() : ctx.baixarBackups != null;
        for (Botao b : botoesDeBaixar) {
            b.setEnabled(pode);
            if (!pode) {
                b.setToolTipText("Sem conexão com o servidor agora: espere a conexão voltar.");
            }
        }
        baixarSelecionados.setVisible(!itens.isEmpty());
        selecionarTodos.setVisible(!itens.isEmpty());
    }

    @Override
    void atualizarConteudo() {
        List<Backup> novos = ctx.detalhe.backups();
        Set<String> nomes = new LinkedHashSet<>();
        novos.forEach(b -> nomes.add(b.arquivo()));
        selecionados.retainAll(nomes); // um backup que saiu da pasta sai da seleção
        this.itens = novos;
        caixas.clear();
        botoesDeBaixar.clear();
        apoio.setText(itens.isEmpty() ? "" : itens.size() + (itens.size() == 1 ? " backup" : " backups"));
        tabela.definir(itens, ctx.temClaude()
            ? "Nenhum backup ainda. O app do servidor faz o backup do banco de dados (pg_dump) antes de abrir a "
                + "sessão do Claude em projetos que têm Postgres."
            : "Nenhum backup ainda.");
        nota.setVisible(!itens.isEmpty());
        atualizarBotoes();
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

    // ---- para os testes ----

    Botao botaoBaixarSelecionados() {
        return baixarSelecionados;
    }

    Botao botaoSelecionarTodos() {
        return selecionarTodos;
    }

    List<Botao> botoesDeBaixar() {
        return List.copyOf(botoesDeBaixar);
    }

    List<JCheckBox> caixasDeMarcar() {
        return List.copyOf(caixas);
    }

    Set<String> selecionados() {
        return Set.copyOf(selecionados);
    }
}
