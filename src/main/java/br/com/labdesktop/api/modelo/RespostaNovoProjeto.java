package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A resposta de {@code POST /api/projetos/novo} (CONTRATO-API 4.x): {@code {"ok":true,"guia_ia":true}}. {@code guia_ia} só vem de um agente
 * que entende a opção; um agente de antes devolve só {@code {"ok":true}} e o campo fica {@code null} (o app avisa se o usuário pediu "sem guia").
 */
public record RespostaNovoProjeto(
    @JsonProperty("ok") Boolean ok,
    @JsonProperty("guia_ia") Boolean guiaIa) {
}
