package br.com.labdesktop.cofre;

/**
 * Como o app chega ao servidor (ARQUITETURA 5.3). {@link Tipo#DIRETO} é o SSH na rede (LAN); {@code cloudflared} é da fase 5; e
 * {@link Tipo#LOCAL} é "Este computador": nenhum SSH, nenhuma chave, nenhum túnel, o servidor é o próprio computador
 * do usuário. Cabem no formato sem mudar a versão do cofre (cofre antigo abre igual).
 *
 * @param tipo     direto, cloudflared ou local
 * @param hostname só para cloudflared: o hostname do túnel (nulo no direto)
 */
public record Transporte(Tipo tipo, String hostname) {

    /** Os transportes conhecidos. */
    public enum Tipo { DIRETO, CLOUDFLARED, LOCAL }

    public static final Transporte DIRETO = new Transporte(Tipo.DIRETO, null);
    /** "Este computador": sem SSH. */
    public static final Transporte LOCAL = new Transporte(Tipo.LOCAL, null);

    public Transporte {
        if (tipo == null) {
            throw new IllegalArgumentException("Tipo de transporte ausente.");
        }
        if (tipo == Tipo.CLOUDFLARED && (hostname == null || hostname.isBlank())) {
            throw new IllegalArgumentException("O transporte cloudflared pede um hostname.");
        }
        if (tipo == Tipo.DIRETO || tipo == Tipo.LOCAL) {
            hostname = null;
        }
    }

    public static Transporte cloudflared(String hostname) {
        return new Transporte(Tipo.CLOUDFLARED, hostname);
    }
}
