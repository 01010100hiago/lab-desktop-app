package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a saída do {@code git log} no formato FIXO do app. Os campos são separados por NUL ({@code %x00}): um NUL NUNCA aparece num assunto, num autor nem num e-mail (o git recusa NUL na mensagem de um commit e um nome vem de
 * texto de linha de comando), então nenhum texto de commit, por mais estranho que seja (U+001E, U+001F, qualquer controle), confunde os campos. Cada commit começa com a marca {@code NUL nonce NUL}, onde o {@code nonce} é
 * sorteado a cada leitura: um commit com o nome do autor igual a uma marca fixa não conseguiria partir a lista. Depois dos sete campos ({@code hash, hash curto, autor, e-mail, data em segundos, pais, assunto}) vem, com
 * {@code --shortstat}, a linha de totais ({@code 3 files changed, ...}, lida com {@code LC_ALL=C}).
 *
 * <p>NENHUM commit some da lista: um registro que não dá para ler inteiro vira "(commit ilegível)" com o hash que o git deu.
 */
public final class InterpretadorDoLog {

    /** O assunto de um commit que o app não conseguiu ler. */
    public static final String ILEGIVEL = "(commit ilegível)";

    private static final Pattern ARQUIVOS = Pattern.compile("^\\s*(\\d+) files? changed", Pattern.MULTILINE);
    private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{16}$");
    private static final int TETO_DO_ASSUNTO = 300;
    private static final SecureRandom AZAR = new SecureRandom();

    private InterpretadorDoLog() { }

    /** Um nonce novo (16 letras hexadecimais) para UMA leitura do log. */
    public static String novoNonce() {
        byte[] b = new byte[8];
        AZAR.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static boolean nonceValido(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    /** O formato que o app pede ao git na lista de commits (uma só definição: o comando e o parser a usam). */
    public static String formato(String nonce) {
        if (!nonceValido(nonce)) {
            throw new IllegalArgumentException("Nonce inválido.");
        }
        return "%x00" + nonce + "%x00%H%x00%h%x00%an%x00%ae%x00%at%x00%P%x00%s";
    }

    /** O formato do cabeçalho de UM commit (a mensagem inteira vai por último). */
    public static final String FORMATO_DO_CABECALHO = "%H%x00%h%x00%an%x00%ae%x00%at%x00%P%x00%B";

    /** O cabeçalho de um commit com a mensagem INTEIRA. */
    public record Cabecalho(Commit commit, String mensagem) { }

    /** Lê o commit de uma saída de {@code git show -s --format=FORMATO_DO_CABECALHO}; {@code null} se não há um registro legível. */
    public static Cabecalho interpretarCabecalho(String saida) {
        if (saida == null) {
            return null;
        }
        String[] c = saida.split("\0", 7);
        if (c.length < 7 || !ValidadorDeGit.hashInteiro(c[0]) || !ValidadorDeGit.hashValido(c[1])) {
            return null;
        }
        try {
            Instant quando = Instant.ofEpochSecond(Long.parseLong(c[4].strip()));
            String mensagem = TextoSeguro.limpar(c[6]).strip();
            int quebra = mensagem.indexOf('\n');
            String assunto = quebra < 0 ? mensagem : mensagem.substring(0, quebra);
            return new Cabecalho(new Commit(c[0], c[1], TextoSeguro.limparLinha(c[2]), TextoSeguro.limparLinha(c[3]), quando, pais(c[5]),
                TextoSeguro.truncar(TextoSeguro.limparLinha(assunto), TETO_DO_ASSUNTO), -1), mensagem);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param saida a saída do {@code git log}
     * @param nonce o mesmo que foi posto no formato desta leitura
     * @return um {@link Commit} por registro que o git devolveu, na ordem (nenhum é descartado)
     */
    public static List<Commit> interpretar(String saida, String nonce) {
        List<Commit> r = new ArrayList<>();
        if (saida == null || saida.isEmpty() || !nonceValido(nonce)) {
            return r;
        }
        String marca = "\0" + nonce + "\0";
        for (String registro : saida.split(Pattern.quote(marca))) {
            if (registro.isBlank()) {
                continue;
            }
            r.add(registro(registro));
        }
        return r;
    }

    private static Commit registro(String registro) {
        String[] c = registro.split("\0", 7);
        String hash = c.length > 0 && ValidadorDeGit.hashInteiro(c[0].strip()) ? c[0].strip() : null;
        if (c.length < 7 || hash == null || !ValidadorDeGit.hashValido(c[1])) {
            String h = hash != null ? hash : "0".repeat(40);
            return new Commit(h, h.substring(0, 7), "", "", Instant.EPOCH, List.of(), ILEGIVEL, -1);
        }
        String ultimo = c[6];
        int quebra = ultimo.indexOf('\n');
        String assunto = quebra < 0 ? ultimo : ultimo.substring(0, quebra);
        String resto = quebra < 0 ? "" : ultimo.substring(quebra + 1);
        Instant quando;
        try {
            quando = Instant.ofEpochSecond(Long.parseLong(c[4].strip()));
        } catch (NumberFormatException e) {
            quando = Instant.EPOCH;
        }
        Matcher m = ARQUIVOS.matcher(resto);
        int arquivos = m.find() ? Integer.parseInt(m.group(1)) : -1;
        return new Commit(hash, c[1], TextoSeguro.limparLinha(c[2]), TextoSeguro.limparLinha(c[3]), quando, pais(c[5]),
            TextoSeguro.truncar(TextoSeguro.limparLinha(assunto), TETO_DO_ASSUNTO), arquivos);
    }

    private static List<String> pais(String campo) {
        return campo.isBlank() ? List.of() : Arrays.stream(campo.strip().split(" ")).filter(ValidadorDeGit::hashValido).toList();
    }
}
