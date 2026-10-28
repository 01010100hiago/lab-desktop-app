package br.com.labdesktop.cloudflare;

import br.com.labdesktop.cofre.ConfiguracaoCloudflare;

/**
 * Um túnel da conta.
 *
 * @param estado o que a Cloudflare diz ({@code healthy}, {@code degraded}, {@code down}, {@code inactive}) ou vazio
 * @param origem como as regras dele são mantidas: pela API ({@code config_src = cloudflare}) ou por arquivo no servidor ({@code local}; também o
 *               que vale para um túnel sem o campo, que é o de antes da configuração remota)
 */
public record TunelCf(String id, String nome, String estado, ConfiguracaoCloudflare.Tipo origem, boolean removido) {

    /** Uma frase simples sobre o estado, para a tela. */
    public String estadoEmTexto() {
        return switch (estado == null ? "" : estado) {
            case "healthy" -> "no ar";
            case "degraded" -> "com problema";
            case "down" -> "fora do ar";
            case "inactive" -> "sem conexão (nunca conectou)";
            default -> "estado desconhecido";
        };
    }
}
