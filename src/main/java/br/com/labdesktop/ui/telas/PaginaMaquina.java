package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import javax.swing.JComponent;

/**
 * A aba "Máquina": versões, hardware, discos por montagem, sensores, energia, núcleos, carga, rede e memória, em duas
 * colunas e sem recolher. Usa {@code /api/servidor} e o detalhe do servidor (sensores, energia e hardware).
 */
public final class PaginaMaquina extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    private final DetalhesDaMaquina detalhes = new DetalhesDaMaquina();

    public PaginaMaquina(ServidorLab servidor, Runnable aoAtualizar) {
        super(servidor, aoAtualizar);
        iniciar();
    }

    @Override
    boolean usaServidor() {
        return true;
    }

    @Override
    boolean usaDetalhe() {
        return true;
    }

    @Override
    boolean usaSeguranca() {
        return false;
    }

    @Override
    JComponent montarConteudo() {
        return detalhes;
    }

    @Override
    void atualizarConteudo() {
        detalhes.definirLocal(estado != null && estado.local());
        detalhes.atualizar(info, detalhe, detalheFalhou);
    }

    DetalhesDaMaquina detalhes() {
        return detalhes;
    }
}
