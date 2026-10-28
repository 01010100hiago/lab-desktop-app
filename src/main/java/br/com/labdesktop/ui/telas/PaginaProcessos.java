package br.com.labdesktop.ui.telas;

import br.com.labdesktop.processos.LeitorDeProcessosLocais;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.Tarefas;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * A aba "Processos": os 20 programas que mais usam o processador, ordenáveis por CPU e por memória (clique no cabeçalho), com Encerrar e Reiniciar. Usa {@code /api/servidor} e,
 * para dizer de que projeto é cada processo, o detalhe do servidor.
 *
 * <p>Em "Este computador" a lista NÃO vem do agente: ele roda no Docker e, no Mac e no Windows, os processos que ele enxerga são os da máquina virtual do Docker (um número dali é de OUTRO
 * processo aqui). A lista vem do próprio sistema, lida a cada 5 s só enquanto a aba está à vista, e o número é o deste computador.
 */
public final class PaginaProcessos extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;
    /** De quanto em quanto tempo a lista deste computador é lida, com a aba à vista. */
    static final int INTERVALO_LOCAL_MS = 5000;

    private final BlocoDeProcessos bloco;
    private final boolean local;
    private final AtomicInteger geracao = new AtomicInteger();
    private transient Timer relogioLocal;

    public PaginaProcessos(ServidorLab servidor, Runnable aoAtualizar) {
        this(servidor, aoAtualizar, null);
    }

    PaginaProcessos(ServidorLab servidor, Runnable aoAtualizar, AcoesDeProcessos acoes) {
        super(servidor, aoAtualizar);
        this.local = servidor != null && servidor.ehLocal();
        this.bloco = new BlocoDeProcessos(acoes, null);
        if (local) {
            bloco.modoLocal();
        }
        bloco.aoMudar(this::lerLocalAgora);
        iniciar();
    }

    @Override
    boolean usaServidor() {
        return !local;
    }

    @Override
    boolean usaDetalhe() {
        return !local;
    }

    @Override
    boolean usaSeguranca() {
        return false;
    }

    @Override
    boolean mostraCorpoSemLeitura(SituacaoDaVisao situacao) {
        return local; // a lista deste computador não depende do agente
    }

    @Override
    JComponent montarConteudo() {
        return bloco;
    }

    @Override
    void atualizarConteudo() {
        if (local) {
            faixaDeDados().setVisible(false); // o aviso do agente não diz nada sobre esta lista
            return;
        }
        bloco.atualizar(info, detalhe);
    }

    // ---- Este computador ----

    @Override
    void aoAssinarMais() {
        if (!local || servidor() == null) {
            return;
        }
        lerLocalAgora();
        relogioLocal = new Timer(INTERVALO_LOCAL_MS, e -> lerLocalAgora());
        relogioLocal.setRepeats(true);
        relogioLocal.start();
    }

    @Override
    void aoSoltarMais() {
        geracao.incrementAndGet(); // o que estava em voo é descartado
        if (relogioLocal != null) {
            relogioLocal.stop();
            relogioLocal = null;
        }
    }

    /** Lê a lista deste computador numa thread de fundo (a EDT só recebe o resultado). */
    void lerLocalAgora() {
        if (!local || servidor() == null) {
            return;
        }
        int minha = geracao.incrementAndGet();
        Tarefas.rodar(() -> servidor().leitorDeProcessosLocais().ler(), (List<LeitorDeProcessosLocais.Linha> linhas) -> {
            if (minha == geracao.get()) {
                bloco.atualizarLocal(linhas);
            }
        }, falha -> { });
    }

    /** Recebe uma lista deste computador (na EDT). Público para os testes alimentarem a aba com dados fixos. */
    void receberLocal(List<LeitorDeProcessosLocais.Linha> linhas) {
        bloco.atualizarLocal(linhas);
    }

    BlocoDeProcessos bloco() {
        return bloco;
    }

    boolean local() {
        return local;
    }
}
