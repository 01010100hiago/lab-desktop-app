package br.com.labdesktop.arquivos;

import java.nio.file.Path;

/**
 * A ponte entre o caminho DO APP (sempre com barra, sempre absoluto, como o {@link CaminhoRemoto}) e o caminho do sistema de arquivos deste
 * computador. No Mac e no Linux são o mesmo texto. No Windows o disco entra como o primeiro trecho: {@code C:\Users\ana} vira
 * {@code /C:/Users/ana}, e a raiz {@code /} é a lista dos discos. As conversões de texto são puras (testadas num Mac para os três casos); só
 * {@link #path} e {@link #texto(Path)} tocam o sistema de arquivos de verdade.
 */
public final class CaminhosLocais {

    private final boolean windows;

    public CaminhosLocais(boolean windows) {
        this.windows = windows;
    }

    public static CaminhosLocais doSistema() {
        return new CaminhosLocais(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
    }

    public boolean windows() {
        return windows;
    }

    /** O texto do app como texto do sistema ({@code /C:/Users/ana} vira {@code C:\Users\ana}); {@code null} para a raiz do Windows (a lista de discos). */
    public String paraTextoDoSistema(String caminhoDoApp) {
        if (!windows) {
            return caminhoDoApp;
        }
        if (caminhoDoApp.equals("/")) {
            return null;
        }
        String s = caminhoDoApp.substring(1); // tira a barra de frente
        int barra = s.indexOf('/');
        String disco = barra < 0 ? s : s.substring(0, barra);
        if (disco.length() != 2 || disco.charAt(1) != ':' || !Character.isLetter(disco.charAt(0))) {
            throw new IllegalArgumentException("No Windows o caminho começa pelo disco (por exemplo /C:/Users).");
        }
        String resto = barra < 0 ? "" : s.substring(barra).replace('/', '\\');
        return disco.toUpperCase(java.util.Locale.ROOT) + (resto.isEmpty() ? "\\" : resto);
    }

    /** O texto do sistema como texto do app ({@code C:\Users\ana} vira {@code /C:/Users/ana}). */
    public String doTextoDoSistema(String caminhoDoSistema) {
        if (!windows) {
            return caminhoDoSistema;
        }
        String s = caminhoDoSistema.replace('\\', '/');
        if (s.length() >= 2 && s.charAt(1) == ':') {
            String disco = Character.toUpperCase(s.charAt(0)) + ":";
            String resto = s.substring(2);
            while (resto.endsWith("/") && resto.length() > 0) {
                resto = resto.substring(0, resto.length() - 1);
            }
            return "/" + disco + resto;
        }
        return s.startsWith("/") ? s : "/" + s;
    }

    /** O {@link Path} do caminho do app; {@code null} para a raiz do Windows. */
    public Path path(String caminhoDoApp) {
        String t = paraTextoDoSistema(caminhoDoApp);
        return t == null ? null : Path.of(t);
    }

    /** O caminho do app de um {@link Path} do sistema. */
    public String texto(Path p) {
        return doTextoDoSistema(p.toString());
    }
}
