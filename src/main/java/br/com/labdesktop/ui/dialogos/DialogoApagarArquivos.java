package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.ResumoDeTamanho;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.util.Formato;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * "Apagar" no gerenciador de arquivos. Como a remoção de servidor: o botão que apaga repete o NOME do alvo
 * ("Apagar relatorio.md"), nunca "OK", e CANCELAR é o botão em destaque (o que o Enter e o Esc acionam). Para pasta, diz quantos
 * itens e quanto espaço vão embora; para link, que só o link some; para vários, a lista. Não há lixeira no servidor, e o texto diz.
 */
public final class DialogoApagarArquivos extends Dialogo {

    /** Um item que vai ser apagado. */
    public record Item(String nome, boolean pasta, boolean link) { }

    private final Consumer<Boolean> aoDecidir;
    private final Botao cancelar = Botoes.primario("Cancelar", null);
    private final Botao apagar;
    private boolean decidiu;

    /**
     * @param itens   o que vai embora (um ou vários)
     * @param resumo  quanto há dentro (a soma, para pastas); {@code null} se não se conta (só arquivos e links)
     */
    public DialogoApagarArquivos(List<Item> itens, ResumoDeTamanho resumo, Consumer<Boolean> aoDecidir) {
        super("Apagar do servidor");
        this.aoDecidir = aoDecidir;
        String botao = itens.size() == 1 ? "Apagar " + encurtar(TextoSeguro.limparLinha(itens.get(0).nome()), 36) : "Apagar " + itens.size() + " itens";
        this.apagar = Botoes.perigo(botao, "lixeira");
        JComponent corpo = Caixa.coluna(14, cabecalho("lixeira", titulo(itens)), paragrafo(texto(itens, resumo)), rodape(null, cancelar, apagar));
        conteudo(corpo);
        botaoPadrao(cancelar);
        cancelar.addActionListener(e -> aoCancelar());
        apagar.addActionListener(e -> decidir(true));
    }

    static String titulo(List<Item> itens) {
        if (itens.size() > 1) {
            return "Apagar " + itens.size() + " itens?";
        }
        Item i = itens.get(0);
        return "Apagar " + (i.link() ? "o link" : i.pasta() ? "a pasta" : "o arquivo") + " " + encurtar(TextoSeguro.limparLinha(i.nome()), 40) + "?";
    }

    static String texto(List<Item> itens, ResumoDeTamanho r) {
        String semLixeira = " O servidor não tem lixeira: depois de apagar, não dá para desfazer por aqui.";
        if (itens.size() == 1) {
            Item i = itens.get(0);
            if (i.link()) {
                return "Só o link será apagado. O que ele aponta não é mexido." + semLixeira;
            }
            if (i.pasta()) {
                return "A pasta tem " + resumoEmPalavras(r) + ". Tudo o que está dentro dela será apagado." + semLixeira;
            }
            return "O arquivo será apagado" + (r == null ? "" : " (" + Formato.fmtBytes(r.bytes()) + ")") + "." + semLixeira;
        }
        StringBuilder sb = new StringBuilder("Serão apagados:\n");
        int mostrados = 0;
        for (Item i : itens) {
            if (mostrados++ >= 8) {
                sb.append("e mais ").append(itens.size() - 8).append(".\n");
                break;
            }
            sb.append("- ").append(encurtar(TextoSeguro.limparLinha(i.nome()), 50)).append(i.pasta() && !i.link() ? " (pasta)" : "").append('\n');
        }
        if (r != null && itens.stream().anyMatch(i -> i.pasta() && !i.link())) {
            sb.append("No total, ").append(resumoEmPalavras(r)).append(".\n");
        }
        return sb.toString().stripTrailing() + "\n" + semLixeira.strip();
    }

    /** "143 itens e 12 MB", "1 item e 5 KB", "mais de 100.000 itens". */
    static String resumoEmPalavras(ResumoDeTamanho r) {
        if (r == null) {
            return "itens";
        }
        long itens = r.itens();
        String quantos = !r.completo() ? "mais de " + Formato.fmtNum(itens) + " itens"
            : itens == 0 ? "nenhum item" : Formato.fmtNum(itens) + (itens == 1 ? " item" : " itens");
        return quantos + (r.bytes() > 0 ? " e " + Formato.fmtBytes(r.bytes()) : "");
    }

    private static String encurtar(String t, int max) {
        return t.length() <= max ? t : t.substring(0, max / 2 - 1) + "…" + t.substring(t.length() - (max / 2 - 1));
    }

    public Botao botaoApagar() {
        return apagar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    @Override
    protected void aoCancelar() {
        decidir(false);
    }

    private void decidir(boolean sim) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        fechar();
        aoDecidir.accept(sim);
    }

    @Override
    protected void aoFechar() {
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(false);
        }
    }
}
