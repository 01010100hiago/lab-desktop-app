package br.com.labdesktop.seguranca;

import java.util.Locale;

/**
 * Tratamento de texto que veio do servidor (descrição de projeto, README, branch, nome de container, texto
 * da tela do Claude): tudo isso é NÃO confiável (ARQUITETURA 1.3, princípio 4).
 *
 * <p>O que o Swing faz de perigoso: um {@code JLabel}, {@code JButton}, {@code JMenuItem} ou tooltip cujo
 * texto começa com {@code <html>} é renderizado como HTML (imagens, links, estilos). A defesa é dupla:
 * quem cria o componente também liga {@code putClientProperty("html.disable", Boolean.TRUE)} (isso é da
 * camada de UI), e este utilitário garante que o texto em si nunca comece como HTML.
 *
 * <p>Também remove o que engana a leitura: controles C0/C1 (escape de terminal, retorno de carro que
 * sobrescreve a linha) e os caracteres de direção bidi (U+202E inverte o texto à direita, escondendo a
 * extensão de um arquivo, por exemplo).
 */
public final class TextoSeguro {

    /** Espaço de largura zero: invisível, e basta para o texto deixar de começar com '&lt;'. */
    private static final char NEUTRALIZADOR = '​';

    private TextoSeguro() { }

    /**
     * Limpa texto de várias linhas: mantém {@code \n} e {@code \t}, converte {@code \r\n} e {@code \r} em
     * {@code \n}, remove controles C0/C1 e caracteres bidi, e neutraliza o início {@code <html}.
     * {@code null} vira texto vazio.
     */
    public static String limpar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        return neutralizarHtml(removerPerigosos(texto, true));
    }

    /**
     * Limpa texto de UMA linha (nome de branch, de container, de projeto, aba): como {@link #limpar}, e ainda
     * troca quebras de linha e tabulações por espaço e apara as pontas.
     */
    public static String limparLinha(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        // Apara ANTES de neutralizar: o neutralizador é o primeiro caractere e o strip() o deixaria.
        return neutralizarHtml(removerPerigosos(texto, false).strip());
    }

    /** Limita o tamanho (em caracteres) acrescentando reticências; não corta no meio de um par substituto. */
    public static String truncar(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        if (maximo < 1) {
            throw new IllegalArgumentException("maximo < 1");
        }
        if (texto.length() <= maximo) {
            return texto;
        }
        int fim = maximo - 1;
        if (fim > 0 && Character.isHighSurrogate(texto.charAt(fim - 1))) {
            fim--;
        }
        return texto.substring(0, Math.max(fim, 0)) + "…";
    }

    /** Escapa {@code & < > " '} para quando o app MONTA HTML de propósito (tooltip com várias linhas, por exemplo). */
    public static String escaparHtml(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@code true} se o Swing trataria este texto como HTML (ou se parece o bastante para não arriscar). */
    public static boolean pareceHtml(String texto) {
        if (texto == null) {
            return false;
        }
        return texto.stripLeading().toLowerCase(Locale.ROOT).startsWith("<html");
    }

    private static String removerPerigosos(String texto, boolean manterQuebras) {
        StringBuilder sb = new StringBuilder(texto.length());
        int n = texto.length();
        for (int i = 0; i < n; i++) {
            char c = texto.charAt(i);
            if (c == '\r') {
                // \r\n vira um \n só; \r sozinho (sobrescreve a linha num terminal) também vira quebra.
                if (i + 1 < n && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                sb.append(manterQuebras ? '\n' : ' ');
            } else if (c == '\n' || c == '\t') {
                sb.append(manterQuebras ? c : ' ');
            } else if (c == ' ' || c == ' ') {
                sb.append(manterQuebras ? '\n' : ' ');
            } else if (!ehControleOuBidi(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String neutralizarHtml(String texto) {
        return pareceHtml(texto) ? NEUTRALIZADOR + texto : texto;
    }

    private static boolean ehControleOuBidi(char c) {
        if (c < 0x20 || c == 0x7F) {
            return true; // C0 e DEL (o \n e o \t já foram tratados antes)
        }
        if (c >= 0x80 && c <= 0x9F) {
            return true; // C1
        }
        return c == '؜'                      // marca de letra árabe
            || c == '‎' || c == '‏'     // LRM, RLM
            || (c >= '‪' && c <= '‮')   // LRE, RLE, PDF, LRO, RLO
            || (c >= '⁦' && c <= '⁩');  // LRI, RLI, FSI, PDI
    }
}
