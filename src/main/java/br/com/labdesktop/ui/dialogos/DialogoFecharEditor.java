package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import java.util.function.Consumer;
import javax.swing.JComponent;

/** Fechar o editor com alterações que não foram salvas: Salvar, Descartar ou Cancelar. Salvar é o botão em destaque. */
public final class DialogoFecharEditor extends Dialogo {

    /** O que o usuário escolheu. */
    public enum Resposta { SALVAR, DESCARTAR, CANCELAR }

    private final Consumer<Resposta> aoDecidir;
    private final Botao salvar = Botoes.primario("Salvar", "salvar");
    private final Botao descartar = Botoes.perigo("Descartar as alterações", null);
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private boolean decidiu;

    public DialogoFecharEditor(String nome, boolean podeSalvar, Consumer<Resposta> aoDecidir) {
        super("Alterações não salvas");
        this.aoDecidir = aoDecidir;
        String n = TextoSeguro.limparLinha(nome);
        JComponent corpo = Caixa.coluna(14,
            cabecalho("alerta", "Salvar as alterações em " + (n.length() > 40 ? n.substring(0, 18) + "…" + n.substring(n.length() - 18) : n) + "?"),
            paragrafo("Você mexeu neste arquivo e ainda não salvou. Se fechar sem salvar, o que você digitou se perde."),
            rodape(null, cancelar, descartar, podeSalvar ? salvar : null));
        conteudo(corpo);
        botaoPadrao(podeSalvar ? salvar : cancelar);
        salvar.addActionListener(e -> decidir(Resposta.SALVAR));
        descartar.addActionListener(e -> decidir(Resposta.DESCARTAR));
        cancelar.addActionListener(e -> aoCancelar());
    }

    public Botao botaoSalvar() {
        return salvar;
    }

    public Botao botaoDescartar() {
        return descartar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    @Override
    protected void aoCancelar() {
        decidir(Resposta.CANCELAR);
    }

    private void decidir(Resposta r) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        fechar();
        aoDecidir.accept(r);
    }

    @Override
    protected void aoFechar() {
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(Resposta.CANCELAR);
        }
    }
}
