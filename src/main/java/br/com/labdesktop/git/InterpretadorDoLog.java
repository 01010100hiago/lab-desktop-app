package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a saída do {@code git log} no formato FIXO do app: cada commit começa com o separador de registro (U+001E) e traz os campos separados por U+001F
 * ({@code hash, hash curto, autor, e-mail, data em segundos, pais, assunto}); depois da linha vem, com {@code --shortstat}, a linha de totais
 * ({@code 3 files changed, ...}, que o app lê com {@code LC_ALL=C}). Separadores de controle, e não espaço ou barra, para um assunto com qualquer texto nunca confundir os campos.
 */
public final class InterpretadorDoLog {

    /** O formato que o app pede ao git (uma só definição: o comando e o parser a usam). */
    public static final String FORMATO = "%x1e%H%x1f%h%x1f%an%x1f%ae%x1f%at%x1f%P%x1f%s";

    private static final Pattern ARQUIVOS = Pattern.compile("^\\s*(\\d+) files? changed", Pattern.MULTILINE);
    private static final int TETO_DO_ASSUNTO = 300;

    private InterpretadorDoLog() { }

    /** O cabeçalho de um commit com a mensagem INTEIRA (o formato de {@code show -s} com {@code %B} no lugar do assunto). */
    public record Cabecalho(Commit commit, String mensagem) { }

    /** Lê o primeiro commit de uma saída de {@code git show -s --format=...%B}; {@code null} se não há um registro legível. */
    public static Cabecalho interpretarCabecalho(String saida) {
        if (saida == null) {
            return null;
        }
        for (String registro : saida.split("\u001e")) {
            if (registro.isBlank()) {
                continue;
            }
            String[] c = registro.split("\u001f", 7);
            if (c.length < 7 || !ValidadorDeGit.hashValido(c[0]) || c[0].length() != 40 || !ValidadorDeGit.hashValido(c[1])) {
                continue;
            }
            try {
                Instant quando = Instant.ofEpochSecond(Long.parseLong(c[4].strip()));
                List<String> pais = c[5].isBlank() ? List.of() : Arrays.stream(c[5].strip().split(" ")).filter(ValidadorDeGit::hashValido).toList();
                String mensagem = TextoSeguro.limpar(c[6]).strip();
                int quebra = mensagem.indexOf('\n');
                String assunto = quebra < 0 ? mensagem : mensagem.substring(0, quebra);
                return new Cabecalho(new Commit(c[0], c[1], TextoSeguro.limparLinha(c[2]), TextoSeguro.limparLinha(c[3]), quando, pais,
                    TextoSeguro.truncar(TextoSeguro.limparLinha(assunto), TETO_DO_ASSUNTO), -1), mensagem);
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return null;
    }

    public static List<Commit> interpretar(String saida) {
        List<Commit> r = new ArrayList<>();
        if (saida == null || saida.isEmpty()) {
            return r;
        }
        for (String registro : saida.split("\u001e")) {
            if (registro.isBlank()) {
                continue;
            }
            int quebra = registro.indexOf('\n');
            String linha = quebra < 0 ? registro : registro.substring(0, quebra);
            String resto = quebra < 0 ? "" : registro.substring(quebra + 1);
            String[] c = linha.split("\u001f", 7);
            if (c.length < 7 || !ValidadorDeGit.hashValido(c[0]) || c[0].length() != 40 || !ValidadorDeGit.hashValido(c[1])) {
                continue; // registro quebrado: some da lista, não quebra a tela
            }
            Instant quando;
            try {
                quando = Instant.ofEpochSecond(Long.parseLong(c[4].strip()));
            } catch (NumberFormatException e) {
                continue;
            }
            List<String> pais = c[5].isBlank() ? List.of() : Arrays.stream(c[5].strip().split(" ")).filter(ValidadorDeGit::hashValido).toList();
            Matcher m = ARQUIVOS.matcher(resto);
            int arquivos = m.find() ? Integer.parseInt(m.group(1)) : -1;
            r.add(new Commit(c[0], c[1], TextoSeguro.limparLinha(c[2]), TextoSeguro.limparLinha(c[3]), quando, pais,
                TextoSeguro.truncar(TextoSeguro.limparLinha(c[6]), TETO_DO_ASSUNTO), arquivos));
        }
        return r;
    }
}
