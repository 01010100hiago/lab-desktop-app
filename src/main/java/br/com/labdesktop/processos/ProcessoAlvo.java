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
            return t; // "kworker/0:1" e "sshd: ana@pts/0" são nomes, não caminhos
        }
        int i = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        return i >= 0 ? t.substring(i + 1) : t;
    }

    /** O usuário diz respeito a container: quem manda é o container (docker stop e restart), não o PID. */
    public boolean ehDeContainer() {
        return container != null;
    }
}
