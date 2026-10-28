package br.com.labdesktop.acesso;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Lê a configuração do sshd e responde à pergunta do desligar a senha: "se eu gravar {@code PasswordAuthentication no} e
 * {@code KbdInteractiveAuthentication no} num arquivo meu em {@code sshd_config.d}, isso vale?". Modelo puro (texto entra, resposta sai).
 *
 * <p>A regra do sshd, que manda aqui: para cada opção vale o PRIMEIRO valor lido, e o arquivo principal lê os {@code Include} no lugar em
 * que eles aparecem (os arquivos da pasta, em ordem alfabética). Por isso um arquivo que vem antes do nosso, ou um valor no principal antes do
 * {@code Include}, vence o nosso. Um bloco {@code Match} vale só para quem ele escolhe e termina no fim do arquivo: o que está dentro dele não
 * é a regra geral. Os arquivos da pasta são lidos em ordem ASCII (a ordem do {@code glob} em locale C; num servidor com outro locale o {@code sshd} pode ordenar
 * diferente para nomes com letras maiúsculas ou acentos, e o app só garante o caso de nomes comuns como {@code 10-}, {@code 50-cloud-init}).
 */
public final class AnaliseDoSshd {

    private static final List<String> SENHA = List.of("passwordauthentication");
    private static final List<String> TECLADO = List.of("kbdinteractiveauthentication", "challengeresponseauthentication");

    private AnaliseDoSshd() { }

    /** O que o {@code sshd -T} diz (o valor efetivo, depois de juntar tudo). Campo {@code null} = a opção não apareceu. */
    public record Efetiva(Boolean senha, Boolean teclado, Boolean usaPam, String metodos, Boolean chave) { }

    /** Quem decide uma opção: o valor (minúsculo) e o arquivo de onde veio ({@code null}: ninguém definiu, vale o padrão do sshd). */
    public record Decisor(String valor, String arquivo) { }

    /** A previsão de como a regra do app ficaria no meio do que já existe. */
    public record Previsao(Decisor senha, Decisor teclado, boolean incluiAPasta, boolean temMatch) {

        /** Esta opção, com a regra do app gravada, vale o que o app quer ({@code no}), decidida pelo arquivo do app? */
        public boolean nossaVale(Decisor d) {
            return d.arquivo() != null && d.arquivo().equals(ARQUIVO_NOSSO) && "no".equals(d.valor());
        }
    }

    /** O nome do arquivo do app (o último trecho do caminho). */
    public static final String ARQUIVO_NOSSO = ComandosDoAcesso.NOME_DO_ARQUIVO_PROPRIO;

    /** A saída do {@code sshd -T}. */
    public static Efetiva lerEfetiva(String saida) {
        Boolean senha = null;
        Boolean teclado = null;
        Boolean pam = null;
        Boolean chave = null;
        String metodos = null;
        for (String linha : saida == null ? new String[0] : saida.split("\\R")) {
            String[] kv = linha.strip().split("\\s+", 2);
            if (kv.length < 2) {
                continue;
            }
            String valor = kv[1].strip().toLowerCase(Locale.ROOT);
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "passwordauthentication" -> senha = simOuNao(valor);
                case "kbdinteractiveauthentication", "challengeresponseauthentication" -> {
                    Boolean v = simOuNao(valor);
                    teclado = teclado == null ? v : (Boolean.TRUE.equals(v) || Boolean.TRUE.equals(teclado));
                }
                case "usepam" -> pam = simOuNao(valor);
                case "pubkeyauthentication" -> chave = simOuNao(valor);
                case "authenticationmethods" -> metodos = valor;
                default -> { }
            }
        }
        return new Efetiva(senha, teclado, pam, metodos, chave);
    }

    private static Boolean simOuNao(String v) {
        return "yes".equals(v) ? Boolean.TRUE : "no".equals(v) ? Boolean.FALSE : null;
    }

    /**
     * Os arquivos da pasta, a partir do texto de {@code lerPastaDaConfig}: {@code #### <caminho>} abre cada arquivo. Ordem alfabética (a do sshd).
     * Devolve vazio se o texto não tem nenhum arquivo.
     */
    public static SortedMap<String, String> arquivosDaPasta(String saida) {
        SortedMap<String, String> arquivos = new TreeMap<>();
        String nome = null;
        StringBuilder corpo = new StringBuilder();
        for (String linha : saida == null ? new String[0] : saida.split("\\R")) {
            if (linha.startsWith(ComandosDoAcesso.MARCADOR_DE_ARQUIVO + " ")) {
                if (nome != null) {
                    arquivos.put(nome, corpo.toString());
                }
                String caminho = linha.substring(ComandosDoAcesso.MARCADOR_DE_ARQUIVO.length() + 1).strip();
                nome = caminho.substring(caminho.lastIndexOf('/') + 1);
                corpo = new StringBuilder();
            } else if (nome != null) {
                corpo.append(linha).append('\n');
            }
        }
        if (nome != null) {
            arquivos.put(nome, corpo.toString());
        }
        return arquivos;
    }

    /** O texto de {@code lerPastaDaConfig} diz que a pasta {@code sshd_config.d} existe? */
    public static boolean pastaExiste(String saida) {
        return saida != null && saida.lines().anyMatch(l -> l.strip().equals(ComandosDoAcesso.MARCADOR_DA_PASTA + " sim"));
    }

    /**
     * Simula a leitura da configuração COM o arquivo do app no lugar certo da pasta e diz quem decide cada opção.
     *
     * @param principal o {@code sshd_config}
     * @param pasta     os arquivos {@code *.conf} da pasta (nome para conteúdo), o do app incluído ou não
     * @param conteudoDoNosso o que o app gravaria no arquivo dele
     */
    public static Previsao prever(String principal, SortedMap<String, String> pasta, String conteudoDoNosso) {
        SortedMap<String, String> comNosso = new TreeMap<>(pasta);
        comNosso.put(ARQUIVO_NOSSO, conteudoDoNosso);
        Leitura leitura = new Leitura();
        lerArquivo("sshd_config", principal, comNosso, leitura);
        return new Previsao(leitura.senha, leitura.teclado, leitura.incluiAPasta, leitura.temMatch);
    }

    /** Há algum bloco {@code Match} em qualquer dos textos (ele pode mudar a regra para certos usuários ou endereços)? */
    public static boolean temMatch(String principal, SortedMap<String, String> pasta) {
        if (temLinhaMatch(principal)) {
            return true;
        }
        return pasta.entrySet().stream().anyMatch(e -> !e.getKey().equals(ARQUIVO_NOSSO) && temLinhaMatch(e.getValue()));
    }

    private static boolean temLinhaMatch(String texto) {
        return texto != null && texto.lines().anyMatch(l -> "match".equals(palavra(l)));
    }

    /** Os arquivos que definem uma das opções de senha sem ser o do app (para o relato "o que encontrei"). */
    public static List<String> quemMexeEmSenha(String principal, SortedMap<String, String> pasta) {
        List<String> achados = new ArrayList<>();
        if (definePalavra(principal)) {
            achados.add("sshd_config");
        }
        for (var e : pasta.entrySet()) {
            if (!e.getKey().equals(ARQUIVO_NOSSO) && definePalavra(e.getValue())) {
                achados.add(e.getKey());
            }
        }
        return achados;
    }

    private static boolean definePalavra(String texto) {
        if (texto == null) {
            return false;
        }
        for (String linha : texto.split("\\R")) {
            String p = palavra(linha);
            if (SENHA.contains(p) || TECLADO.contains(p)) {
                return true;
            }
            if ("match".equals(p)) {
                return false; // o que vem depois é de Match, não da regra geral
            }
        }
        return false;
    }

    private static final class Leitura {
        Decisor senha = new Decisor(null, null);
        Decisor teclado = new Decisor(null, null);
        boolean incluiAPasta;
        boolean temMatch;
    }

    private static void lerArquivo(String nome, String texto, SortedMap<String, String> pasta, Leitura leitura) {
        if (texto == null) {
            return;
        }
        for (String linha : texto.split("\\R")) {
            String p = palavra(linha);
            if (p.isEmpty()) {
                continue;
            }
            if ("match".equals(p)) {
                leitura.temMatch = true;
                return; // até o fim DESTE arquivo vale só para quem o Match escolhe
            }
            if ("include".equals(p)) {
                if (incluiNossaPasta(valores(linha))) {
                    leitura.incluiAPasta = true;
                    for (var arquivo : pasta.entrySet()) {
                        lerArquivo(arquivo.getKey(), arquivo.getValue(), pasta, leitura);
                    }
                }
                continue;
            }
            String valor = valores(linha).isEmpty() ? "" : valores(linha).get(0).toLowerCase(Locale.ROOT);
            if (SENHA.contains(p) && leitura.senha.arquivo() == null) {
                leitura.senha = new Decisor(valor, nome);
            } else if (TECLADO.contains(p) && leitura.teclado.arquivo() == null) {
                leitura.teclado = new Decisor(valor, nome);
            }
        }
    }

    private static boolean incluiNossaPasta(List<String> globs) {
        for (String g : globs) {
            if (g.matches("^(/etc/ssh/)?sshd_config\\.d/(\\*\\.conf|\\*)$")) {
                return true;
            }
        }
        return false;
    }

    /** A palavra-chave da linha, minúscula ({@code ""} para linha vazia ou comentário). Aceita {@code Chave valor} e {@code Chave=valor}. */
    private static String palavra(String linha) {
        String l = linha.strip();
        if (l.isEmpty() || l.startsWith("#")) {
            return "";
        }
        int fim = 0;
        while (fim < l.length() && !Character.isWhitespace(l.charAt(fim)) && l.charAt(fim) != '=') {
            fim++;
        }
        return l.substring(0, fim).toLowerCase(Locale.ROOT);
    }

    /** Os valores depois da palavra-chave (separados por espaço ou {@code =}), sem comentário ao final. */
    private static List<String> valores(String linha) {
        String l = linha.strip();
        int fim = 0;
        while (fim < l.length() && !Character.isWhitespace(l.charAt(fim)) && l.charAt(fim) != '=') {
            fim++;
        }
        String resto = l.substring(fim).replaceFirst("^[\\s=]+", "");
        int comentario = resto.indexOf('#');
        if (comentario >= 0) {
            resto = resto.substring(0, comentario);
        }
        resto = resto.strip();
        if (resto.isEmpty()) {
            return List.of();
        }
        List<String> valores = new ArrayList<>();
        for (String v : resto.split("\\s+")) {
            valores.add(v.replaceAll("^\"+|\"+$", "")); // Include "/etc/ssh/sshd_config.d/*.conf" "outro/*.conf": aspas fora, vários globs
        }
        return valores;
    }
}
