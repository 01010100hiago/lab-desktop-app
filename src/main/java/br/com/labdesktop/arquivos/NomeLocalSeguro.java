package br.com.labdesktop.arquivos;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * O nome com que um arquivo baixado do servidor é gravado NESTE computador. O nome vem do servidor (não é confiável) e vira um caminho na pasta que o usuário escolheu, então:
 * <ul>
 *   <li>nome que é caminho ({@code ../x}, {@code /etc/x}, {@code C:\x}), {@code .} e {@code ..}, vazio, com controle ou NUL: RECUSADO (nada é baixado com ele);</li>
 *   <li>caractere que o Windows não aceita em nome de arquivo ({@code < > : " | ? *}) e barra invertida: trocado por {@code _};</li>
 *   <li>espaço ou ponto no fim (o Windows os apaga), nome de aparelho do Windows ({@code CON}, {@code NUL}, {@code COM1}...) e nome comprido demais: ajustados.</li>
 * </ul>
 * O mesmo nome vale no Mac e no Linux: o app não grava de um jeito num sistema e de outro, e o backup baixado num Mac abre igual num Windows.
 */
public final class NomeLocalSeguro {

    /** O que um nome não pode ter no Windows (e no resto fica igual, de propósito). */
    private static final String RESERVADOS = "<>:\"|?*\\";
    private static final Set<String> APARELHOS = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    /** Bytes de um nome (255 no disco menos a folga do arquivo parcial e do "-2" de "manter os dois"). */
    private static final int MAXIMO = 200;

    private NomeLocalSeguro() { }

    /** O resultado: o nome seguro e se ele mudou em relação ao do servidor. */
    public record Nome(String seguro, boolean mudou) { }

    /**
     * @return o nome seguro, ou vazio se o nome do servidor é um caminho, vazio ou perigoso (o item não é baixado)
     */
    public static Optional<Nome> de(String nomeDoServidor) {
        if (nomeDoServidor == null) {
            return Optional.empty();
        }
        String n = nomeDoServidor;
        if (n.isBlank() || n.equals(".") || n.equals("..") || n.indexOf('/') >= 0 || n.indexOf('\0') >= 0 || n.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            return Optional.empty();
        }
        if (n.startsWith("\\") || n.matches("^[A-Za-z]:.*") || n.contains("..\\") || n.contains("\\..")) {
            return Optional.empty(); // caminho do Windows (absoluto, com letra de disco, ou que sobe de pasta)
        }
        StringBuilder sb = new StringBuilder();
        n.codePoints().forEach(cp -> sb.append(cp < 128 && RESERVADOS.indexOf(cp) >= 0 ? '_' : new String(Character.toChars(cp))));
        String s = sb.toString();
        while (!s.isEmpty() && (s.endsWith(" ") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.stripLeading();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (APARELHOS.contains(base.toUpperCase(Locale.ROOT))) {
            s = "_" + s;
        }
        s = cortar(s);
        return Optional.of(new Nome(s, !s.equals(nomeDoServidor)));
    }

    /** Corta o nome por BYTES (UTF-8), guardando a extensão ({@code .sql.gz}). */
    private static String cortar(String s) {
        if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAXIMO) {
            return s;
        }
        int ponto = s.lastIndexOf('.');
        String ext = ponto > 0 && s.length() - ponto <= 12 ? s.substring(ponto) : "";
        String corpo = ext.isEmpty() ? s : s.substring(0, ponto);
        int limite = MAXIMO - ext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < corpo.length(); ) {
            int cp = corpo.codePointAt(i);
            int b = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + b > limite) {
                break;
            }
            sb.appendCodePoint(cp);
            bytes += b;
            i += Character.charCount(cp);
        }
        return sb + ext;
    }
}
