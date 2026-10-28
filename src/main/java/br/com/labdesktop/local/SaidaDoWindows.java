package br.com.labdesktop.local;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodifica a saída de programas do Windows (ARQUITETURA 7.7 e 11.4). O {@code wsl.exe} escreve em <b>UTF-16LE</b> quando a saída vai para um pipe (cada letra tem um byte zero ao
 * lado: lido como UTF-8 vira "W\0S\0L\0"), outros programas escrevem na página de código do console (CP850 no Brasil). A ordem de tentativa:
 *
 * <ol>
 *   <li>marca BOM ({@code FF FE} UTF-16LE, {@code FE FF} UTF-16BE, {@code EF BB BF} UTF-8);</li>
 *   <li>sem marca, mas com muitos bytes zero nas posições ímpares (texto latino em UTF-16LE): UTF-16LE;</li>
 *   <li>UTF-8 estrito (rejeita bytes inválidos);</li>
 *   <li>a página de código nativa do sistema ({@code native.encoding}, ou windows-1252 se não houver), que nunca falha.</li>
 * </ol>
 * Puro e testado com bytes de verdade de cada caso.
 */
public final class SaidaDoWindows {

    private static final Pattern VERSAO_PADRAO = Pattern.compile("(?im)^[ \\t]*[^:\\r\\n]*vers\\p{L}*[^:\\r\\n]*:[ \\t]*([12])[ \\t]*$");

    private SaidaDoWindows() { }

    /** O texto da saída, sem BOM nem NULs. */
    public static String texto(byte[] bytes) {
        return texto(bytes, nativa());
    }

    static String texto(byte[] bytes, Charset nativa) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return limpar(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE));
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return limpar(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE));
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return limpar(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
        }
        if (pareceUtf16Le(bytes)) {
            return limpar(new String(bytes, StandardCharsets.UTF_16LE));
        }
        try {
            return limpar(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return limpar(new String(bytes, nativa));
        }
    }

    /** Pelo menos 3 de cada 4 bytes de posição ímpar são zero (texto em ASCII/Latin-1 escrito em UTF-16LE). */
    static boolean pareceUtf16Le(byte[] b) {
        if (b.length < 4) {
            return b.length >= 2 && b[1] == 0 && b[0] != 0;
        }
        int pares = b.length / 2;
        int zeros = 0;
        for (int i = 1; i < b.length; i += 2) {
            if (b[i] == 0) {
                zeros++;
            }
        }
        return zeros * 4 >= pares * 3;
    }

    private static Charset nativa() {
        String n = System.getProperty("native.encoding");
        try {
            return n == null ? Charset.forName("windows-1252") : Charset.forName(n);
        } catch (RuntimeException e) {
            return Charset.forName("windows-1252");
        }
    }

    private static String limpar(String s) {
        return s.replace("\u0000", "").replace("\uFEFF", "");
    }

    /** A primeira linha com texto (sem espaços nas pontas), para uma frase curta; vazia se não há. */
    public static String primeiraLinha(String texto) {
        for (String l : texto.split("\\R")) {
            if (!l.isBlank()) {
                return l.strip();
            }
        }
        return "";
    }

    /**
     * A versão padrão do WSL que a saída de {@code wsl --status} diz ({@code 1} ou {@code 2}) em qualquer idioma ({@code Default Version: 2}, {@code Versão Padrão: 2}); {@code 0} se
     * não houver uma linha assim (a saída muda de idioma e de versão: só se afirma o que se leu inteiro, o resto o código de saída decide).
     */
    public static int versaoPadraoDoWsl(String saida) {
        Matcher m = VERSAO_PADRAO.matcher(saida == null ? "" : saida);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
