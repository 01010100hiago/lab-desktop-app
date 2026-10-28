package br.com.labdesktop.local;

import java.util.Locale;

/** O sistema deste computador. Só o que muda o jeito de instalar e abrir o Docker; o resto do código não olha para isto. */
public enum SistemaOperacional {
    MAC("macOS"),
    WINDOWS("Windows"),
    LINUX("Linux");

    private final String rotulo;

    SistemaOperacional(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Pelo {@code os.name} da JVM. Desconhecido vale Linux (o mais neutro: só mostra orientação em texto). */
    public static SistemaOperacional de(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.contains("mac") || n.contains("darwin")) {
            return MAC;
        }
        if (n.contains("win")) {
            return WINDOWS;
        }
        return LINUX;
    }

    public static SistemaOperacional detectar() {
        return de(System.getProperty("os.name"));
    }
}
