package br.com.labdesktop.cloudflare;

/** O resultado de {@code /user/tokens/verify}: o token existe e está ativo. {@code expiraEm} é o texto da Cloudflare ou vazio. */
public record TokenVerificado(String id, String status, String expiraEm) { }
