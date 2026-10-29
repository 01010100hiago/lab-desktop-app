package br.com.labdesktop.git;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O que NUNCA entra num repositório criado pelo app: senhas, chaves e credenciais. Uma só fonte: as linhas do {@code .gitignore} que o app garante, o teste em Java que confere a lista de
 * arquivos antes do {@code git add} e o que o app consulta no {@code git check-ignore}. {@code .env.example} é a exceção (é o modelo SEM valores, feito para ir ao repositório).
 *
 * <p>Também guarda o "lixo comum" ({@code node_modules/}, {@code .DS_Store}, {@code Thumbs.db}), que não é segredo mas encheria o primeiro commit.
 */
public final class SegredosDoProjeto {

    /** As linhas de segredo do {@code .gitignore}, NESTA ordem (a exceção {@code !.env.example} vem depois de {@code .env.*}). */
    public static final List<String> PADROES = List.of(".env", ".env.*", "!.env.example", "*.pem", "*.key", "*.p12", "*.pfx", "*.keystore", "*.jks",
        "id_rsa*", "id_dsa*", "id_ecdsa*", "id_ed25519*", "credentials*", ".npmrc", ".pypirc", ".netrc");

    /** Arquivos e pastas que não são segredo mas não devem ir para o primeiro commit. */
    public static final List<String> LIXO_COMUM = List.of("node_modules/", ".DS_Store", "Thumbs.db");

    private static final Set<String> NOMES_EXATOS = Set.of(".env", ".npmrc", ".pypirc", ".netrc");
    private static final List<String> PREFIXOS = List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials");
    private static final List<String> EXTENSOES = List.of(".pem", ".key", ".p12", ".pfx", ".keystore", ".jks");

    private SegredosDoProjeto() { }

    /** O nome do arquivo (sem as pastas). */
    static String nome(String caminho) {
        int i = caminho.lastIndexOf('/');
        return i < 0 ? caminho : caminho.substring(i + 1);
    }

    /**
     * O arquivo tem cara de segredo? Pelo NOME (as pastas não contam). {@code .env.example} é a única exceção. Esta é a mesma regra das linhas de {@link #PADROES}: um teste confere que as duas
     * concordam.
     */
    public static boolean pareceSegredo(String caminho) {
        if (caminho == null || caminho.isEmpty()) {
            return false;
        }
        String n = nome(caminho);
        String m = n.toLowerCase(Locale.ROOT);
        if (n.equals(".env.example")) {
            return false;
        }
        if (NOMES_EXATOS.contains(n) || n.startsWith(".env.")) {
            return true;
        }
        for (String p : PREFIXOS) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        for (String e : EXTENSOES) {
            if (n.endsWith(e) || m.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /** Caminhos (que podem nem existir) que o {@code .gitignore} TEM de ignorar: o app os passa ao {@code git check-ignore} depois de ajustar o arquivo. */
    public static List<String> amostrasIgnoradas() {
        return List.of(".env", ".env.local", ".env.production", "app/.env", "chave.pem", "certs/servidor.key", "id_rsa", "id_rsa.pub", ".ssh/id_ed25519", "credentials.json",
            "aws/credentials", ".npmrc", "web/.npmrc", "loja.p12", "assinatura.keystore", "release.jks", ".pypirc", ".netrc");
    }

    /** Caminhos que NÃO podem ser ignorados pela regra de segredos (o modelo sem valores vai ao repositório). */
    public static List<String> amostrasLiberadas() {
        return List.of(".env.example", "app/.env.example");
    }

    /** Os padrões para o {@code git ls-files} achar, no que já está no índice, o que tem cara de segredo (sintaxe {@code :(glob)} do próprio git). */
    public static List<String> pathspecsParaOGit() {
        return List.of(":(glob)**/.env", ":(glob)**/.env.*", ":(glob)**/*.pem", ":(glob)**/*.key", ":(glob)**/*.p12", ":(glob)**/*.pfx", ":(glob)**/*.keystore", ":(glob)**/*.jks",
            ":(glob)**/id_rsa*", ":(glob)**/id_dsa*", ":(glob)**/id_ecdsa*", ":(glob)**/id_ed25519*", ":(glob)**/credentials*", ":(glob)**/.npmrc", ":(glob)**/.pypirc", ":(glob)**/.netrc");
    }

    /** As linhas de segredo e de lixo juntas, na ordem em que o app as acrescenta ao {@code .gitignore}. */
    public static List<String> todasAsLinhas() {
        java.util.ArrayList<String> l = new java.util.ArrayList<>(PADROES);
        l.addAll(LIXO_COMUM);
        return List.copyOf(l);
    }
}
