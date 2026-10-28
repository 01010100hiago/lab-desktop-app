package br.com.labdesktop.processos;

/**
 * O processo que o usuário escolheu na lista, como a lista o mostrou: é só o que o agente (ou a leitura local) disse, e por isso NÃO é confiável
 * para decidir nada sozinho. O que vale na hora de mandar um sinal é a identidade lida de novo ({@link IdentidadeDoProcesso}).
 *
 * @param pid       o número do processo, ou {@code null} se a lista não o trouxe
 * @param nome      o nome do programa
 * @param usuario   o usuário, como a lista o escreveu
 * @param container o container a que pertence ({@code null} = processo do próprio computador ou servidor)
 * @param projeto   o projeto a que pertence, se a lista o disse ou se deu para deduzir
 */
public record ProcessoAlvo(Integer pid, String nome, String usuario, String container, String projeto) {

    public ProcessoAlvo {
        nome = nome == null ? "" : nome;
        usuario = usuario == null ? "" : usuario;
        container = container == null || container.isBlank() ? null : container;
        projeto = projeto == null || projeto.isBlank() ? null : projeto;
    }

    /** O nome sem a pasta, se ele é um caminho ({@code /usr/sbin/sshd} vira {@code sshd}; {@code C:\Windows\x.exe} vira {@code x.exe}); um nome como {@code kworker/0:1} fica como está. */
    public String nomeCurto() {
        return nomeCurto(nome);
    }

    static String nomeCurto(String n) {
        String t = n == null ? "" : n.strip();
        boolean caminho = t.startsWith("/") || t.startsWith("\\\\") || t.matches("^[A-Za-z]:[\\\\/].*");
        if (!caminho) {
            return semArgumentos(t); // "kworker/0:1" é um nome; "sshd: ana@pts/0" e "node server.js --porta 80" são nome com o que vem depois
        }
        // uma linha de comando ("/usr/sbin/sshd -D"): o programa vai até o primeiro argumento, e dele fica só o nome do arquivo
        String programa = t;
        for (String corte : new String[] {" -", " /"}) {
            int a = programa.indexOf(corte, 1);
            if (a > 0) {
                programa = programa.substring(0, a);
            }
        }
        int i = Math.max(programa.lastIndexOf('/'), programa.lastIndexOf('\\'));
        return i >= 0 ? programa.substring(i + 1) : programa;
    }

    /** {@code sshd: ana@pts/0} vira {@code sshd}; {@code node server.js} e {@code sshd -D} idem se o que vem depois é argumento; {@code Google Chrome} fica como está. */
    private static String semArgumentos(String t) {
        int e = t.indexOf(' ');
        if (e <= 0) {
            return t;
        }
        String primeiro = t.substring(0, e);
        String resto = t.substring(e + 1).stripLeading();
        if (primeiro.endsWith(":")) {
            return primeiro.substring(0, primeiro.length() - 1);
        }
        if (resto.startsWith("-") || resto.startsWith("/")) {
            return primeiro;
        }
        return t;
    }

    /** O usuário diz respeito a container: quem manda é o container (docker stop e restart), não o PID. */
    public boolean ehDeContainer() {
        return container != null;
    }
}
