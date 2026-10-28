package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import javax.swing.JComponent;

/**
 * A aba "Containers": o resumo (rodando, parados, em pausa, espaço do Docker) e a tabela das sessões do Claude abertas.
 * Só usa {@code /api/servidor}.
 */
public final class PaginaContainers extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    private final BlocoDeContainers bloco = new BlocoDeContainers();

    public PaginaContainers(ServidorLab servidor, Runnable aoAtualizar) {
        super(servidor, aoAtualizar);
        iniciar();
    }

    @Override
    boolean usaServidor() {
        return true;
    }

    @Override
    boolean usaDetalhe() {
        return false;
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
        bloco.atualizar(info, agora(), fuso(), estado == null || !Boolean.FALSE.equals(estado.temClaude()));
    }

    BlocoDeContainers bloco() {
        return bloco;
    }
}
