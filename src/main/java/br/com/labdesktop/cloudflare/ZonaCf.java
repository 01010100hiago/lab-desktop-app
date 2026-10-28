package br.com.labdesktop.cloudflare;

/**
 * Um domínio (zona) na Cloudflare.
 *
 * @param status {@code active} quando o domínio já usa os servidores de nomes da Cloudflare ({@code pending} ainda não serve para nada)
 */
public record ZonaCf(String id, String nome, String contaId, String contaNome, String status) {

    public boolean ativa() {
        return "active".equals(status);
    }
}
