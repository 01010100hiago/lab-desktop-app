package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Login do Claude pendente num projeto (só a lista traz). A URL é do servidor e portanto não confiável: só se
 * abre pelo navegador depois de passar pela {@code ListaPermitidaDeUrls} (https://claude.ai ou claude.com).
 */
public record LoginPendente(@JsonProperty("url") String url) {

    public LoginPendente {
        url = Textos.linha(url);
    }
}
