package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import javax.swing.JComponent;

/**
 * A aba "Processos": os 20 programas que mais usam o processador, ordenáveis por CPU e por memória (clique no cabeçalho).
 * Usa {@code /api/servidor} e, para dizer de que projeto é cada processo, o detalhe do servidor.
 */
public final class PaginaProcessos extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    private final BlocoDeProcessos bloco = new BlocoDeProcessos();

    public PaginaProcessos(ServidorLab servidor, Runnable aoAtualizar) {
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
        return bloco;
    }

    @Override
    void atualizarConteudo() {
        bloco.atualizar(info, detalhe);
    }

    BlocoDeProcessos bloco() {
        return bloco;
    }
}
