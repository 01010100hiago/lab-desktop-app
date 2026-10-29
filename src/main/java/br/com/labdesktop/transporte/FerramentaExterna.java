package br.com.labdesktop.transporte;

/**
 * Os programas de OUTRAS pessoas que o transporte sem porta aberta usa. Ficam no COMPUTADOR de quem roda o app, em cada sistema; o app NÃO os
 * embute, NÃO os baixa e NÃO os instala: só procura, confere a versão ({@code --version}) e diz como instalar.
 */
public enum FerramentaExterna {

    /** A linha de comando da AWS ({@code aws}): abre a sessão do Session Manager. */
    AWS("aws", "AWS CLI"),
    /** O plugin do Session Manager: o {@code aws} o chama por conta própria e precisa achá-lo no PATH. */
    PLUGIN_SSM("session-manager-plugin", "plugin do Session Manager"),
    /** O {@code cloudflared}: abre o túnel até o hostname da Cloudflare. */
    CLOUDFLARED("cloudflared", "cloudflared");

    private final String nomeBase;
    private final String rotulo;

    FerramentaExterna(String nomeBase, String rotulo) {
        this.nomeBase = nomeBase;
        this.rotulo = rotulo;
    }

    /** O nome do programa, sem {@code .exe}. */
    public String nomeBase() {
        return nomeBase;
    }

    /** Como o usuário lê o nome. */
    public String rotulo() {
        return rotulo;
    }
}
