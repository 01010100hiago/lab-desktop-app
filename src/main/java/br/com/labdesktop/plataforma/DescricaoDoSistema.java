package br.com.labdesktop.plataforma;

/** O sistema em palavras, para a tela Sobre e para o texto que o usuário copia num chamado: nome, versão e arquitetura, como a JVM informa. Sem Swing. */
public final class DescricaoDoSistema {

    private DescricaoDoSistema() { }

    /** Por exemplo {@code Mac OS X 15.6}. */
    public static String nomeEVersao() {
        return (System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "")).strip();
    }

    /** Por exemplo {@code aarch64}. */
    public static String arquitetura() {
        return System.getProperty("os.arch", "?");
    }
}
