package br.com.labdesktop.projetos;

import java.util.List;
import java.util.Map;

/**
 * TODOS os comandos que criar, importar e completar um projeto rodam num servidor ou neste computador, num lugar só. Cada um é uma LISTA de
 * argumentos (nunca texto para um shell); o que varia (nome, endereço, caminho) já passou por {@link NomeDoProjeto}, {@link UrlDeGit} ou pela regra
 * de caminho antes de chegar aqui, e por SSH ainda passa por {@link ExecutorDeArgumentos#SEGURO}. Todos são ESCRITA (ou rede) e só nascem de um
 * botão do usuário: {@code NovoProjetoSemEfeitoSozinhoTest} varre o código e confere.
 */
public final class ComandosDeProjeto {

    /** Os rótulos que o compose exige para adotar uma rede criada antes (o mesmo que o agente usa). */
    static final String ROTULO_REDE = "com.docker.compose.network=default";
    static final String ROTULO_PROJETO = "com.docker.compose.project=";

    private ComandosDeProjeto() { }

    /** Cria a rede {@code <nome>_default} com os rótulos do compose, para o primeiro {@code up} já a achar pronta. */
    public static List<String> criarRede(String nome) {
        exigir(nome);
        return List.of("docker", "network", "create", "--label", ROTULO_REDE, "--label", ROTULO_PROJETO + nome, GabaritoDoProjeto.redeDoProjeto(nome));
    }

    /** {@code git clone -- <endereço> <destino>}: o {@code --} impede que um endereço vire opção. */
    public static List<String> clonar(UrlDeGit url, String destino) {
        return List.of("git", "clone", "--", url.texto(), destino);
    }

    /**
     * O ambiente do clone: nunca pedir senha (ninguém vai digitá-la: a falha vem na hora e o app a explica) e só os protocolos {@code https} e
     * {@code ssh} (nada de {@code file}, {@code ext}, {@code git}), por cima da regra de {@link UrlDeGit}.
     */
    public static Map<String, String> ambienteDoGit() {
        return Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_ALLOW_PROTOCOL", "https:ssh");
    }

    /** Confere o compose que chega pela ENTRADA ({@code -f -}) como se estivesse na pasta do projeto (o {@code .env} dela vale). */
    public static List<String> validarCompose(String pastaDoProjeto) {
        return List.of("docker", "compose", "--project-directory", pastaDoProjeto, "-f", "-", "config", "-q");
    }

    /** Move uma pasta (nunca sobrescreve: {@code -n}). */
    public static List<String> mover(String de, String para) {
        return List.of("mv", "-n", "--", de, para);
    }

    /** Copia uma pasta inteira, preservando tudo ({@code -a}). */
    public static List<String> copiar(String de, String para) {
        return List.of("cp", "-a", "--", de, para);
    }

    private static void exigir(String nome) {
        if (!NomeDoProjeto.valido(nome)) {
            throw new IllegalArgumentException("Nome de projeto inválido.");
        }
    }
}
