package br.com.labdesktop.instalador;

import java.util.List;

/**
 * Como terminou uma instalação ou atualização.
 *
 * @param desfecho  concluída, falhou, cancelada ou recusada (as verificações bloqueiam)
 * @param passo     o passo em que parou ({@code null} se concluída ou recusada)
 * @param erro      o erro REAL, em texto (nunca HTML), com o que o servidor disse; vazio se concluída
 * @param desfeito  o que o instalador tirou do servidor depois da falha ou do cancelamento, em frases
 */
public record ResultadoDaInstalacao(Desfecho desfecho, Passo passo, String erro, List<String> desfeito) {

    /** O desfecho. */
    public enum Desfecho {
        CONCLUIDA, FALHOU, CANCELADA, RECUSADA
    }

    public ResultadoDaInstalacao {
        erro = erro == null ? "" : erro;
        desfeito = List.copyOf(desfeito);
    }

    public boolean concluida() {
        return desfecho == Desfecho.CONCLUIDA;
    }
}
