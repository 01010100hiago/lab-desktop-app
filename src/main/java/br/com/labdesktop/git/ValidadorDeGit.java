package br.com.labdesktop.git;

import java.util.regex.Pattern;

/**
 * As regras de TUDO que o app põe numa linha de comando do git: o hash de um commit, o nome de um branch, o caminho de um arquivo dentro do repositório e a pasta do projeto.
 * Nada disso é digitado pelo usuário (vem do próprio git, da lista de projetos ou de um clique), mas o texto chega de um servidor e por isso passa por uma regra curta e fixa antes de
 * entrar numa linha de comando: o que não passa nem é enviado.
 */
public final class ValidadorDeGit {

    /** Um hash de commit, abreviado ou inteiro (de 7 a 64 em minúsculas: o inteiro tem 40 num repositório comum e 64 num repositório SHA-256). */
    public static final Pattern HASH = Pattern.compile("^[0-9a-f]{7,64}$");

    /** O tamanho de um hash INTEIRO: 40 (SHA-1) ou 64 (SHA-256). */
    public static boolean hashInteiro(String hash) {
        return hashValido(hash) && (hash.length() == 40 || hash.length() == 64);
    }

    private static final int MAXIMO_DO_BRANCH = 200;

    private static final int MAXIMO_DO_CAMINHO = 1024;

    private ValidadorDeGit() { }

    public static boolean hashValido(String hash) {
        return hash != null && HASH.matcher(hash).matches();
    }

    /** @throws IllegalArgumentException se não é um hash */
    public static String exigirHash(String hash) {
        if (!hashValido(hash)) {
            throw new IllegalArgumentException("Hash de commit inválido.");
        }
        return hash;
    }

    /**
     * Um nome de branch que o app aceita pôr num comando: o que o PRÓPRIO git aceita ({@code git check-ref-format}: acento, {@code #}, {@code +}, {@code @} no meio, {@code ;} e o que mais ele deixa), menos o perigoso:
     * vazio, mais de 200 caracteres, começar com {@code -} (viraria opção), espaço ou caractere de controle, {@code ~ ^ : ? * [ \\}, {@code ..}, {@code //}, {@code @{}, o nome {@code @} sozinho, terminar em {@code /},
     * {@code .} ou {@code .lock} e segmento que começa com ponto. O nome vai SEMPRE como texto livre (entre aspas simples por SSH), então os símbolos de shell que o git deixa não viram comando.
     */
    public static boolean branchValido(String nome) {
        if (nome == null || nome.isEmpty() || nome.length() > MAXIMO_DO_BRANCH || nome.charAt(0) == '-' || nome.equals("@")) {
            return false;
        }
        for (int i = 0; i < nome.length(); i++) {
            char c = nome.charAt(i);
            if (c <= 0x20 || c == 0x7F || (c >= 0x80 && c < 0xA0) || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        if (nome.contains("..") || nome.contains("//") || nome.contains("@{") || nome.endsWith("/") || nome.endsWith(".") || nome.endsWith(".lock")) {
            return false;
        }
        for (String parte : nome.split("/")) {
            if (parte.startsWith(".") || parte.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    public static String exigirBranch(String nome) {
        if (!branchValido(nome)) {
            throw new IllegalArgumentException("Nome de branch inválido.");
        }
        return nome;
    }

    /**
     * Um caminho DENTRO do repositório, com barras comuns: relativo, sem {@code ..} nem {@code .}, sem barra dupla, sem caractere de controle (nova linha, tabulação, NUL),
     * sem letra de disco e com no máximo 1024 caracteres. Acento, espaço e aspas são nomes de arquivo legítimos: passam (e vão como texto livre, entre aspas simples).
     */
    public static boolean caminhoRelativoValido(String caminho) {
        if (caminho == null || caminho.isEmpty() || caminho.length() > MAXIMO_DO_CAMINHO) {
            return false;
        }
        if (caminho.startsWith("/") || caminho.contains("\\") || caminho.contains("//") || caminho.endsWith("/")
            || (caminho.length() >= 2 && caminho.charAt(1) == ':' && Character.isLetter(caminho.charAt(0)))) {
            return false;
        }
        for (int i = 0; i < caminho.length(); i++) {
            char c = caminho.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c < 0xA0)) {
                return false;
            }
        }
        for (String parte : caminho.split("/", -1)) {
            if (parte.isEmpty() || parte.equals("..") || parte.equals(".")) {
                return false;
            }
        }
        return true;
    }

    public static String exigirCaminhoRelativo(String caminho) {
        if (!caminhoRelativoValido(caminho)) {
            throw new IllegalArgumentException("Caminho de arquivo inválido.");
        }
        return caminho;
    }

    /**
     * A pasta do projeto: absoluta ({@code /srv/lab/projects/loja-web}, {@code /Users/ana/LabLocal/projects/app-exemplo} ou, no Windows, {@code C:\Users\ana\...}), sem {@code ..},
     * sem caractere de controle, até 1024 caracteres, e que não começa por {@code -} (não vira opção).
     */
    public static boolean pastaValida(String pasta) {
        if (pasta == null || pasta.isEmpty() || pasta.length() > MAXIMO_DO_CAMINHO || pasta.startsWith("-")) {
            return false;
        }
        boolean absoluta = pasta.startsWith("/") || (pasta.length() >= 3 && Character.isLetter(pasta.charAt(0)) && pasta.charAt(1) == ':'
            && (pasta.charAt(2) == '\\' || pasta.charAt(2) == '/'));
        if (!absoluta) {
            return false;
        }
        for (int i = 0; i < pasta.length(); i++) {
            char c = pasta.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        for (String parte : pasta.split("[/\\\\]")) {
            if (parte.equals("..")) {
                return false;
            }
        }
        return true;
    }

    public static String exigirPasta(String pasta) {
        if (!pastaValida(pasta)) {
            throw new IllegalArgumentException("Pasta do projeto inválida.");
        }
        return pasta;
    }

    /** O nome de quem assina os commits: texto simples de uma linha, de 1 a 100 caracteres, sem controle nem {@code < >} (que o git recusa). */
    public static boolean nomeDeAutorValido(String nome) {
        if (nome == null) {
            return false;
        }
        String n = nome.strip();
        if (n.isEmpty() || n.length() > 100 || !n.equals(nome)) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c < 0xA0) || c == '<' || c == '>' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");

    /** O e-mail de quem assina: a forma comum {@code usuario@example.com}, só com caracteres seguros (vai numa linha de comando). */
    public static boolean emailValido(String email) {
        return email != null && email.length() <= 120 && EMAIL.matcher(email).matches();
    }
}
