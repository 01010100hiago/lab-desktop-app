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
        return new CaminhosLocais(br.com.labdesktop.plataforma.SistemaOperacional.detectar() == br.com.labdesktop.plataforma.SistemaOperacional.WINDOWS);
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

    /**
     * O texto do sistema como texto do app ({@code C:\Users\ana} vira {@code /C:/Users/ana}). No Windows aceita barra invertida ou comum, o disco em letra minúscula, a barra do fim
     * e o prefixo de caminho longo ({@code \\?\C:\x}). O que NÃO tem disco (pasta de rede {@code \\servidor\pasta}, raiz sem disco) sai como um caminho que {@link #paraTextoDoSistema}
     * recusa depois: o app nunca chega a tocar nele (falha fechada, nunca um caminho mal traduzido).
     *
     * @throws IllegalArgumentException se está vazio, ou se tem disco sem a barra depois ({@code C:pasta}: "a pasta atual do disco C", que o app não sabe qual é)
     */
    public String doTextoDoSistema(String caminhoDoSistema) {
        if (caminhoDoSistema == null || caminhoDoSistema.isEmpty()) {
            throw new IllegalArgumentException("O caminho está vazio.");
        }
        if (!windows) {
            return caminhoDoSistema;
        }
        String s = caminhoDoSistema.replace('\\', '/');
        if (s.startsWith("//?/") || s.startsWith("//./")) {
            // o prefixo de caminho longo do Windows: \\?\C:\x é o C:\x; \\?\UNC\servidor\x é a pasta de rede \\servidor\x
            String r = s.substring(4);
            s = r.regionMatches(true, 0, "UNC/", 0, 4) ? "//" + r.substring(4) : r;
        }
        if (s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0))) {
            if (s.length() > 2 && s.charAt(2) != '/') {
                throw new IllegalArgumentException("O caminho precisa de uma barra depois do disco (C:\\pasta), e não só C:pasta.");
            }
            String disco = Character.toUpperCase(s.charAt(0)) + ":";
            String resto = s.substring(2);
            while (resto.endsWith("/") && resto.length() > 0) {
                resto = resto.substring(0, resto.length() - 1);
            }
            return "/" + disco + resto;
        }
        return s.startsWith("/") ? s : "/" + s;
    }

    /**
     * Para onde um link aponta, no texto do app, para o {@link CaminhoRemoto#resolverAlvo} e a cerca do gerenciador entenderem. Fora do Windows é o texto como veio. No Windows o alvo vem
     * com barra invertida ({@code ..\x}, {@code D:\pasta}): sem esta tradução um {@code ..\..\fora} seria UM nome só para a cerca (que só conhece a barra comum) e o link levaria
     * para fora da pasta sem ninguém ver. O alvo absoluto vira {@code /D:/pasta}; o relativo troca as barras; o que começa pela raiz sem disco ({@code \x}) usa o disco da pasta do link.
     *
     * @param pastaDoLink a pasta onde o link está, no texto do app ({@code /D:/a/b})
     * @return o alvo no texto do app, ou {@code null} se não há como dizer (pasta de rede, {@code C:pasta}): quem recebe {@code null} recusa o link
     */
    public String alvoDoLink(String pastaDoLink, String destinoDoSistema) {
        if (destinoDoSistema == null || destinoDoSistema.isEmpty()) {
            return null;
        }
        if (!windows) {
            return destinoDoSistema;
        }
        String s = destinoDoSistema.replace('\\', '/');
        if (s.startsWith("//?/") || s.startsWith("//./") || s.startsWith("/??/")) {
            String r = s.substring(4);
            if (r.regionMatches(true, 0, "UNC/", 0, 4)) {
                return null;
            }
            s = r;
        }
        if (s.startsWith("//")) {
            return null; // pasta de rede
        }
        if (s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0))) {
            return s.length() > 2 && s.charAt(2) != '/' ? null : doTextoDoSistema(s);
        }
        if (s.startsWith("/")) {
            // a raiz do disco do link: \x no D:\pasta\link é o D:\x
            String p = pastaDoLink == null ? "" : pastaDoLink;
            int barra = p.indexOf('/', 1);
            String disco = barra < 0 ? p : p.substring(0, barra);
            return disco.length() == 3 && disco.charAt(2) == ':' ? disco + s : null;
        }
        return s;
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
