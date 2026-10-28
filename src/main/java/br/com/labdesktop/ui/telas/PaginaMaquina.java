package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import javax.swing.JComponent;

/**
 * A aba "Máquina": versões, hardware, discos por montagem, sensores, energia, núcleos, carga, rede e memória, em duas
 * colunas e sem recolher. Usa {@code /api/servidor} e o detalhe do servidor (sensores, energia e hardware). O bloco do Git vem PRIMEIRO: ele é pequeno e é o que tem
 * botão, e embaixo dos detalhes ficava fora da janela de 1100 por 700.
 */
public final class PaginaMaquina extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    private final DetalhesDaMaquina detalhes = new DetalhesDaMaquina();
    private final BlocoDoGit git;

    public PaginaMaquina(ServidorLab servidor, Runnable aoAtualizar) {
        this(servidor, aoAtualizar, null);
    }

    /** @param acoes as ações da janela (o "Instalar o Git…" do bloco do git abre a confirmação); {@code null}: o bloco só mostra o estado */
    PaginaMaquina(ServidorLab servidor, Runnable aoAtualizar, br.com.labdesktop.ui.AcoesDaInterface acoes) {
        super(servidor, aoAtualizar);
        this.git = new BlocoDoGit(servidor, acoes);
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
        return br.com.labdesktop.ui.componentes.Caixa.coluna(12, git, detalhes);
    }

    /** A aba apareceu: confere o git neste servidor (só leitura). */
    @Override
    void aoAssinarMais() {
        git.conferir();
    }

    @Override
    void aoSoltarMais() {
        git.soltar();
    }

    BlocoDoGit git() {
        return git;
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
