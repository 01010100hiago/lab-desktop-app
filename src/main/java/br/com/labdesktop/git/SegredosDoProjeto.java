package br.com.labdesktop.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O que NUNCA entra num repositório criado pelo app: senhas, chaves e credenciais. Uma só fonte: as linhas do {@code .gitignore} que o app garante, o teste em Java que confere a lista de arquivos antes do
 * {@code git add} e o que o app consulta no {@code git check-ignore}. {@code .env.example} é a exceção (é o modelo SEM valores, feito para ir ao repositório).
 *
 * <p><b>Maiúscula e minúscula valem igual.</b> O {@code .gitignore} do Linux distingue {@code .env} de {@code .ENV}, então as linhas que o app escreve usam {@code [eE][nN][vV]} ({@link #semDistinguirCaixa}); e o teste
 * em Java compara em minúsculas, pelo nome do arquivo (e pelas pastas {@code .aws} e {@code .ssh}, inteiras), sem espaço no fim (o {@code .gitignore} também ignora o espaço no fim do padrão).
 *
 * <p>Também guarda o "lixo comum" ({@code node_modules/}, {@code .DS_Store}, {@code Thumbs.db}), que não é segredo mas encheria o primeiro commit.
 */
public final class SegredosDoProjeto {

    /**
     * Os padrões de segredo, LEGÍVEIS e em minúsculas (a ordem importa: a exceção {@code !.env.example} vem depois de {@code .env*}). Para o {@code .gitignore} cada um passa por {@link #semDistinguirCaixa}.
     * {@code .env*} cobre {@code .env}, {@code .env.local}, {@code .envrc} e também {@code ".env "} (com espaço no fim).
     */
    public static final List<String> PADROES = List.of(".env*", "!.env.example", "*.pem", "*.key", "*.p12", "*.pfx", "*.keystore", "*.jks", "*.kdbx", "*.tfstate", "*.tfstate.*",
        "id_rsa*", "id_dsa*", "id_ecdsa*", "id_ed25519*", "credentials*", "secrets*", "service-account*.json", ".npmrc", ".pypirc", ".netrc", ".htpasswd", ".git-credentials", ".aws/", ".ssh/");

    /** Arquivos e pastas que não são segredo mas não devem ir para o primeiro commit. */
    public static final List<String> LIXO_COMUM = List.of("node_modules/", ".DS_Store", "Thumbs.db");

    /** A linha de {@code .env*} como vai no {@code .gitignore}. */
    public static final String LINHA_DO_ENV = semDistinguirCaixa(".env*");
    /** A exceção do modelo sem valores, como vai no {@code .gitignore}: tem de vir DEPOIS de {@link #LINHA_DO_ENV}. */
    public static final String LINHA_DA_EXCECAO = semDistinguirCaixa("!.env.example");

    private static final Set<String> NOMES_EXATOS = Set.of(".npmrc", ".pypirc", ".netrc", ".htpasswd", ".git-credentials");
    private static final List<String> PREFIXOS = List.of(".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "secrets");
    private static final List<String> EXTENSOES = List.of(".pem", ".key", ".p12", ".pfx", ".keystore", ".jks", ".kdbx", ".tfstate");
    private static final Set<String> PASTAS = Set.of(".aws", ".ssh");

    private SegredosDoProjeto() { }

    /**
     * O padrão de {@code .gitignore} que vale para maiúscula e minúscula: cada letra vira {@code [xX]} ({@code .env*} vira {@code .[eE][nN][vV]*}). O {@code !} da frente, a barra, o {@code *} e o resto ficam.
     */
    public static String semDistinguirCaixa(String padrao) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padrao.length(); i++) {
            char c = padrao.charAt(i);
            if (Character.isLetter(c)) {
                sb.append('[').append(Character.toLowerCase(c)).append(Character.toUpperCase(c)).append(']');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** O nome do arquivo (sem as pastas). */
    static String nome(String caminho) {
        int i = caminho.lastIndexOf('/');
        return i < 0 ? caminho : caminho.substring(i + 1);
    }

    /**
     * O arquivo tem cara de segredo? Pelo NOME, em minúsculas e sem espaço no fim, ou por estar dentro de {@code .aws} ou {@code .ssh}. {@code .env.example} é a única exceção. Esta é a mesma regra das linhas de
     * {@link #PADROES}: um teste confere que as duas concordam, com o git de verdade.
     */
    public static boolean pareceSegredo(String caminho) {
        if (caminho == null || caminho.isEmpty()) {
            return false;
        }
        String[] partes = caminho.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < partes.length - 1; i++) {
            if (PASTAS.contains(partes[i].stripTrailing())) {
                return true;
            }
        }
        String n = partes[partes.length - 1].stripTrailing();
        if (n.equals(".env.example")) {
            return false;
        }
        if (NOMES_EXATOS.contains(n)) {
            return true;
        }
        for (String p : PREFIXOS) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        for (String e : EXTENSOES) {
            if (n.endsWith(e) || e.equals(".tfstate") && n.contains(".tfstate.")) {
                return true;
            }
        }
        return n.startsWith("service-account") && n.endsWith(".json");
    }

    /** Caminhos (que podem nem existir) que o {@code .gitignore} TEM de ignorar: o app os passa ao {@code git check-ignore} depois de ajustar o arquivo. */
    public static List<String> amostrasIgnoradas() {
        return List.of(".env", ".env.local", ".env.production", "pasta-de-amostra/.env", ".ENV", ".Env.Local", ".env ", "chave.pem", "CHAVE.PEM", "certs/servidor.key", "id_rsa", "ID_RSA", "id_rsa.pub", ".ssh/id_ed25519",
            "credentials.json", "Credentials.json", "aws/credentials", ".aws/config", "x/.aws/credentials", ".ssh/config", ".npmrc", "web/.npmrc", "loja.p12", "assinatura.keystore", "release.jks", ".pypirc", ".netrc",
            ".git-credentials", "secrets.json", "Secrets.YAML", "terraform.tfstate", "prod.TFSTATE", "terraform.tfstate.backup", "banco.kdbx", ".htpasswd", "service-account-prod.json", "Service-Account.json");
    }

    /**
     * Caminhos que NÃO podem ser ignorados pela regra de segredos (o modelo sem valores vai ao repositório). A subpasta de amostra tem um nome que nenhum projeto usa: se fosse {@code app}, um
     * repositório de subpasta chamado {@code app} (que o app põe no {@code .gitignore} quando o usuário confirma criar na pasta principal) faria a amostra parecer ignorada.
     */
    public static List<String> amostrasLiberadas() {
        return List.of(".env.example", "pasta-de-amostra/.env.example", ".ENV.EXAMPLE");
    }

    /** Os padrões para o {@code git ls-files} achar, no que já está no índice, o que tem cara de segredo (sintaxe {@code :(glob,icase)} do próprio git: sem distinguir maiúscula). */
    public static List<String> pathspecsParaOGit() {
        List<String> l = new ArrayList<>();
        for (String p : PADROES) {
            if (p.startsWith("!")) {
                continue;
            }
            l.add(p.endsWith("/") ? ":(glob,icase)**/" + p + "**" : ":(glob,icase)**/" + p);
        }
        return List.copyOf(l);
    }

    /** As linhas de segredo (sem distinguir maiúscula) e de lixo juntas, na ordem em que o app as acrescenta ao {@code .gitignore}. */
    public static List<String> todasAsLinhas() {
        List<String> l = new ArrayList<>();
        for (String p : PADROES) {
            l.add(semDistinguirCaixa(p));
        }
        l.addAll(LIXO_COMUM);
        return List.copyOf(l);
    }
}
