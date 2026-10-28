package br.com.labdesktop.arquivos;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * O nome com que um arquivo baixado do servidor é gravado NESTE computador. O nome vem do servidor (não é confiável) e vira um caminho na pasta que o usuário escolheu, então:
 * <ul>
 *   <li>nome que é caminho ({@code ../x}, {@code /etc/x}, {@code C:\x}), {@code .} e {@code ..}, vazio, com controle ou NUL: RECUSADO (nada é baixado com ele);</li>
 *   <li>caractere que o Windows não aceita em nome de arquivo ({@code < > : " | ? *}) e barra invertida: trocado por {@code _};</li>
 *   <li>caractere que engana quem lê ou some na tela (inversão de texto como U+202E, espaço de largura zero, controles C1): trocado por {@code _}, e o nome conta como mudado;</li>
 *   <li>espaço ou ponto no fim (o Windows os apaga), nome de aparelho do Windows ({@code CON}, {@code NUL}, {@code COM1}, {@code CONIN$}, {@code COM¹}...) e nome comprido demais: ajustados.</li>
 * </ul>
 * O mesmo nome vale no Mac e no Linux: o app não grava de um jeito num sistema e de outro, e o backup baixado num Mac abre igual num Windows.
 */
public final class NomeLocalSeguro {

    /** O que um nome não pode ter no Windows (e no resto fica igual, de propósito). */
    private static final String RESERVADOS = "<>:\"|?*\\";
    private static final Set<String> APARELHOS = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        // o Windows também reserva estes, e os expoentes (COM¹, COM², COM³, LPT¹...) como se fossem COM1... LPT3
        "CONIN$", "CONOUT$", "CLOCK$", "COM\u00B9", "COM\u00B2", "COM\u00B3", "LPT\u00B9", "LPT\u00B2", "LPT\u00B3");
    /** O maior caminho que o Windows aceita sem o prefixo especial: 260 contando o fim da cadeia. */
    public static final int MAXIMO_DO_CAMINHO_NO_WINDOWS = 259;
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
        n.codePoints().forEach(cp -> sb.append(cp < 128 && RESERVADOS.indexOf(cp) >= 0 || enganoso(cp) ? "_" : new String(Character.toChars(cp))));
        String s = sb.toString();
        while (!s.isEmpty() && (s.endsWith(" ") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.stripLeading();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        base = base.stripTrailing(); // "CON .txt" também é o aparelho CON no Windows
        if (APARELHOS.contains(base.toUpperCase(Locale.ROOT))) {
            s = "_" + s;
        }
        s = cortar(s);
        return Optional.of(new Nome(s, !s.equals(nomeDoServidor)));
    }

    /**
     * Caractere que o usuário não veria como é: controles C1 (0x80 a 0x9F), marcas de direção do texto (U+200E e U+200F, U+202A a U+202E, U+2066 a U+2069: o clássico
     * "backup‮gpj.sql" que parece terminar em .jpg), espaços de largura zero (U+200B a U+200D, U+2060, U+FEFF), hífen invisível (U+00AD) e separadores de linha (U+2028, U+2029).
     */
    static boolean enganoso(int cp) {
        return cp >= 0x80 && cp <= 0x9F || cp == 0x200E || cp == 0x200F || cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069
            || cp >= 0x200B && cp <= 0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0xAD || cp == 0x2028 || cp == 0x2029 || cp == 0x061C;
    }

    /**
     * O caminho (pasta de destino + nome + a folga do arquivo parcial) passa dos 259 caracteres do Windows? Só o Windows tem esse limite; a frase diz o que fazer.
     *
     * @return vazio se cabe; senão a frase
     */
    public static Optional<String> longoDemaisNoWindows(String pastaDeDestino, String nome) {
        // o parcial (".labdesktop-" + 16 hex + ".parcial" = 36) mora na mesma pasta: o maior dos dois nomes é o que vale
        int maiorNome = Math.max(nome.length(), 36);
        int total = pastaDeDestino.length() + 1 + maiorNome;
        if (total <= MAXIMO_DO_CAMINHO_NO_WINDOWS) {
            return Optional.empty();
        }
        return Optional.of("O caminho de \"" + (nome.length() <= 40 ? nome : nome.substring(0, 39) + "…") + "\" ficaria com " + total + " caracteres, e o Windows não aceita mais de "
            + (MAXIMO_DO_CAMINHO_NO_WINDOWS + 1) + ". Escolha uma pasta de destino mais curta (por exemplo, uma pasta direto em Documentos).");
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
