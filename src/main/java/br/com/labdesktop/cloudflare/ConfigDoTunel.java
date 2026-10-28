package br.com.labdesktop.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A configuração remota de um túnel gerido pela API. {@code config} é o objeto INTEIRO que a Cloudflare devolveu (regras de entrada, roteamento
 * WARP, opções de origem...): ao gravar, o app devolve tudo o que leu e só mexe na lista {@code ingress}, para não apagar o que não é dele.
 *
 * @param config pode ser objeto vazio (túnel novo, sem regras)
 * @param versao a versão que a Cloudflare numera a cada gravação
 */
public record ConfigDoTunel(JsonNode config, int versao) { }
