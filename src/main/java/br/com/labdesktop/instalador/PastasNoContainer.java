package br.com.labdesktop.instalador;

/** Caminhos que o app combina com o container do agente. */
public final class PastasNoContainer {

    private static final String RAIZ = "/srv/painel";
    /** Onde o agente espera a pasta de dados (o volume nomeado do Docker é montado aqui). */
    public static final String DADOS = RAIZ + "/dados";

    private PastasNoContainer() {
    }
}
