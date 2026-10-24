package br.com.labdesktop.cofre;

/**
 * Como o app chega ao SSH do servidor (ARQUITETURA 5.3). Na fase 1 só existe {@link Tipo#DIRETO} (LAN); o
 * transporte {@code cloudflared} é da fase 5 e já cabe no formato, sem mudar a versão do cofre.
 *
 * @param tipo     direto ou cloudflared
 * @param hostname só para cloudflared: o hostname do túnel (nulo no direto)
 */
public record Transporte(Tipo tipo, String hostname) {

    /** Os transportes conhecidos. */
    public enum Tipo { DIRETO, CLOUDFLARED }

    public static final Transporte DIRETO = new Transporte(Tipo.DIRETO, null);

    public Transporte {
        if (tipo == null) {
            throw new IllegalArgumentException("Tipo de transporte ausente.");
        }
        if (tipo == Tipo.CLOUDFLARED && (hostname == null || hostname.isBlank())) {
            throw new IllegalArgumentException("O transporte cloudflared pede um hostname.");
        }
        if (tipo == Tipo.DIRETO) {
            hostname = null;
        }
    }

    public static Transporte cloudflared(String hostname) {
        return new Transporte(Tipo.CLOUDFLARED, hostname);
    }
}
