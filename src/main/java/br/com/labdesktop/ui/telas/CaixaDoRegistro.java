package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import java.awt.Dimension;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * A caixa "Registro do servidor" do Resumo, abaixo das Tendências, na mesma coluna: ocupa a altura que sobra (a lista rola POR DENTRO, a página nunca rola). O miolo é o
 * {@link PainelDoRegistro}; "Ver tudo" abre a mesma coisa numa janela maior.
 */
final class CaixaDoRegistro extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;

    private final PainelDoRegistro painel;

    CaixaDoRegistro(PainelDoRegistro.Fonte fonte, CopiadorDeTexto copiador, Notificacoes avisos, Supplier<ZoneId> fuso) {
        super("Registro do servidor", "log");
        this.painel = new PainelDoRegistro(true, fonte, copiador, avisos, fuso);
        linha(painel, 0);
        apoio(painel.rotuloDoStatus());
        setName("caixa-do-registro");
    }

    PainelDoRegistro painel() {
        return painel;
    }

    /** Esta caixa estica para ocupar a altura que a coluna lhe dá (as outras caixas do Resumo ficam do tamanho que pedem). */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
}
