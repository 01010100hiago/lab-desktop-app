package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import java.util.function.Consumer;

/**
 * Pergunta de sim ou não antes de uma ação que interrompe algo (reabrir a sessão do Claude). O texto é do app (nunca
 * do servidor); o botão de confirmar leva o verbo da ação, não "OK".
 */
public final class DialogoConfirmar extends Dialogo {

    private final Consumer<Boolean> aoDecidir;
    private final Botao confirmar;
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private boolean decidiu;

    /**
     * @param icone     nome do ícone do cabeçalho ({@code "atualizar"}, {@code "alerta"}...)
     * @param verbo     rótulo do botão de confirmar ("Reabrir")
     * @param perigoso  o botão de confirmar sai em terracota
     * @param aoDecidir {@code true} = confirmou; {@code false} = cancelou; chamado uma vez
     */
    public DialogoConfirmar(String titulo, String icone, String texto, String verbo, boolean perigoso,
                            Consumer<Boolean> aoDecidir) {
        super(titulo);
        this.aoDecidir = aoDecidir;
        this.confirmar = perigoso ? Botoes.perigo(verbo, null) : Botoes.primario(verbo, null);
        conteudo(Caixa.coluna(14, cabecalho(icone, titulo), paragrafo(texto), rodape(null, cancelar, confirmar)));
        botaoPadrao(confirmar);
        confirmar.addActionListener(e -> decidir(true));
        cancelar.addActionListener(e -> aoCancelar());
    }

    public Botao botaoConfirmar() {
        return confirmar;
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
