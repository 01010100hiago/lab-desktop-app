package br.com.labdesktop.ui.telas;

import br.com.labdesktop.tendencias.Metrica;

/** A ponte pública (só de teste) para o estilo das linhas do bloco de Tendências, que são detalhes do pacote. */
public final class GraficosParaTeste {

    private GraficosParaTeste() { }

    public static String token(Metrica m) {
        return BlocoDeTendencias.tokenDaLinha(m);
    }

    public static boolean tracejada(Metrica m) {
        return BlocoDeTendencias.tracejada(m);
    }
}
