package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Resposta 200 da confirmação do código: o token deste Mac fica confirmado até
 * {@code ativadoAte} (30 dias, contados pelo agente; o app não guarda isto).
 */
public record Ativacao2fa(
    @JsonProperty("ok") Boolean ok,
    @JsonProperty("ativado_ate") Instant ativadoAte) {
}
