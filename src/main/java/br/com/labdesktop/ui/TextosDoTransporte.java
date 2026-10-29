package br.com.labdesktop.ui;

/** Os textos da tela sobre a conexão sem porta aberta, num lugar só para o cartão, a página e a Ajuda dizerem a mesma coisa. */
public final class TextosDoTransporte {

    private TextosDoTransporte() { }

    /** O que o selo "Sem porta aberta" quer dizer (tooltip do selo). */
    public static final String EXPLICACAO_DO_SELO = "Este servidor não tem porta de SSH aberta para a internet: o app entra por um túnel (AWS Session Manager ou Cloudflare) "
        + "que sobe neste computador. Para mudar, abra Editar servidor.";
}
