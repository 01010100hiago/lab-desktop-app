package br.com.labdesktop.projetos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O endereço de um repositório Git que o app aceita clonar. Só duas formas: {@code https://host/caminho} e {@code git@host:caminho} (a forma do
 * SSH que o GitHub e o GitLab mostram). Nada de {@code file:}, {@code ext::}, {@code ssh://}, {@code http://} (sem cifra), espaço, aspas,
 * {@code ; | & $ ` ( ) < >}, quebra de linha, consulta ({@code ?}) ou fragmento ({@code #}), nem usuário e senha dentro do endereço (um
 * token na URL iria parar no histórico do servidor e nos logs). O endereço entra num comando, por isso a regra é por LISTA BRANCA de
 * caracteres, e nunca pode começar com hífen (viraria opção do {@code git}).
 */
public final class UrlDeGit {

    private static final int MAXIMO = 300;
    private static final Pattern HTTPS = Pattern.compile("^https://([A-Za-z0-9][A-Za-z0-9.-]{0,252})(:[0-9]{1,5})?/([A-Za-z0-9._~%+/-]{1,200})$");
    private static final Pattern SCP = Pattern.compile("^git@([A-Za-z0-9][A-Za-z0-9.-]{0,252}):([A-Za-z0-9._~/-]{1,200})$");

    private final String texto;
    private final String caminho;

    private UrlDeGit(String texto, String caminho) {
        this.texto = texto;
        this.caminho = caminho;
    }

    /** O resultado de conferir um endereço: o endereço pronto, ou a frase do que está errado. */
    public record Resultado(UrlDeGit url, String mensagem) {
        public boolean valida() {
            return url != null;
        }
    }

    /** Confere o texto como foi colado (sem cortar nada: espaço no meio ou nas pontas é recusado). */
    public static Resultado conferir(String texto) {
        if (texto == null || texto.isEmpty()) {
            return new Resultado(null, "");
        }
        if (texto.length() > MAXIMO) {
            return recusa("O endereço é longo demais.");
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c <= ' ' || c == 127 || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return recusa("O endereço não pode ter espaço nem quebra de linha.");
            }
        }
        String minusculas = texto.toLowerCase(java.util.Locale.ROOT);
        if (texto.charAt(0) == '-') {
            return recusa("Cole o endereço do repositório: começa com https:// ou com git@.");
        }
        if (minusculas.contains("::") || minusculas.startsWith("file:") || minusculas.startsWith("ssh://")
            || minusculas.startsWith("git://") || minusculas.startsWith("ftp://")) {
            return recusa("Esse tipo de endereço não é aceito. Use https://… ou git@servidor:usuario/repositorio.");
        }
        if (minusculas.startsWith("http://")) {
            return recusa("O endereço http:// não é cifrado. Use https://…");
        }
        if (minusculas.startsWith("https://")) {
            String depois = texto.substring("https://".length());
            int barra = depois.indexOf('/');
            String autoridade = barra < 0 ? depois : depois.substring(0, barra);
            if (autoridade.contains("@")) {
                return recusa("O endereço não pode levar usuário nem senha. Se o repositório é privado, deixe o servidor logado "
                    + "(gh auth login) ou use uma chave de deploy.");
            }
            Matcher m = HTTPS.matcher(texto);
            if (!m.matches()) {
                return recusa("Esse endereço tem caracteres que o app não aceita (sem ?, # nem símbolos). Use o endereço simples do "
                    + "repositório, como https://github.com/usuario/repositorio.git");
            }
            return conferirCaminho(texto, m.group(3));
        }
        if (texto.startsWith("git@")) {
            Matcher m = SCP.matcher(texto);
            if (!m.matches()) {
                return recusa("O endereço git@… deve ser como git@github.com:usuario/repositorio.git");
            }
            return conferirCaminho(texto, m.group(2));
        }
        return recusa("Cole o endereço do repositório: https://… ou git@servidor:usuario/repositorio.git");
    }

    private static Resultado conferirCaminho(String texto, String caminho) {
        for (String parte : caminho.split("/", -1)) {
            if (parte.equals("..") || parte.equals(".")) {
                return recusa("O endereço não pode ter '.' nem '..' no caminho.");
            }
        }
        if (caminho.startsWith("-")) {
            return recusa("O caminho do repositório não pode começar com hífen.");
        }
        return new Resultado(new UrlDeGit(texto, caminho), "");
    }

    private static Resultado recusa(String mensagem) {
        return new Resultado(null, mensagem);
    }

    /** O endereço, já conferido: é o único texto que vai ao comando. */
    public String texto() {
        return texto;
    }

    /** O nome do repositório (o último trecho do caminho, sem {@code .git}), para sugerir o nome do projeto; pode vir vazio. */
    public String nomeDoRepositorio() {
        String c = caminho;
        while (c.endsWith("/")) {
            c = c.substring(0, c.length() - 1);
        }
        String ultimo = c.substring(c.lastIndexOf('/') + 1);
        if (ultimo.endsWith(".git")) {
            ultimo = ultimo.substring(0, ultimo.length() - 4);
        }
        return ultimo;
    }

    /** O nome sugerido para o projeto, já na regra do {@link NomeDoProjeto} (vazio se não der). */
    public String nomeSugerido() {
        return NomeDoProjeto.sugerirDe(nomeDoRepositorio());
    }

    @Override
    public String toString() {
        return texto;
    }
}
