package br.com.labdesktop.arquivos;

import java.time.Instant;
import java.util.List;

/**
 * O que uma leitura de pasta devolveu: até o limite de itens, mais quantos há de verdade.
 *
 * @param caminho  a pasta lida
 * @param entradas os itens guardados (sem {@code .} nem {@code ..}), na ordem em que o servidor mandou
 * @param total    quantos itens a pasta tem ({@code >= entradas.size()}); com {@code totalExato == false} é "pelo menos isto"
 * @param truncada {@code true} se a pasta tem mais itens do que os guardados
 * @param totalExato {@code false} quando a contagem parou num teto (pasta com centenas de milhares de itens)
 * @param lidaEm   quando a leitura terminou
 */
public record ListagemDePasta(String caminho, List<EntradaRemota> entradas, int total, boolean truncada, boolean totalExato,
                              Instant lidaEm) {

    public ListagemDePasta {
        entradas = List.copyOf(entradas);
    }

    /** O limite padrão da tela: mais que isto numa pasta só entra pelo filtro. */
    public static final int LIMITE_PADRAO = 2000;
}
