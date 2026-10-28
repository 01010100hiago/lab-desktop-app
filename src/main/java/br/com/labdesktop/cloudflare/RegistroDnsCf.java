package br.com.labdesktop.cloudflare;

/** Um registro de DNS de uma zona (só o que o app usa). {@code comentario} é o campo {@code comment} da Cloudflare. */
public record RegistroDnsCf(String id, String nome, String tipo, String conteudo, boolean proxied, String comentario) { }
