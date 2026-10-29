package br.com.labdesktop.transporte;

import br.com.labdesktop.cofre.Transporte;
import java.util.ArrayList;
import java.util.List;

/**
 * Monta a LISTA de argumentos do programa externo de cada modo. Nunca uma linha de comando: cada argumento é um item da lista, entregue direto ao
 * {@code ProcessBuilder}, sem {@code sh -c} nem {@code cmd /c}. Nada que o usuário digitou começa com {@code -} (a regra de {@code ValidadorDeTransporte} já impede, e este
 * montador confere de novo: se um valor suspeito chegasse aqui, falha em vez de virar opção do programa).
 *
 * <p>SSM: {@code aws ssm start-session --target <id> --document-name AWS-StartPortForwardingSession --parameters portNumber=<portaSsh>,localPortNumber=<local> [--region R] [--profile P]}.
 * Cloudflare: {@code cloudflared access tcp --hostname <h> --url 127.0.0.1:<local>}. A porta local é sempre de 127.0.0.1.
 */
public final class ArgumentosDoTunel {

    /** O destino do túnel local: sempre só o laço de volta. */
    public static final String ENDERECO_LOCAL = "127.0.0.1";
    /** O documento do Session Manager que encaminha uma porta. */
    public static final String DOCUMENTO_DO_SSM = "AWS-StartPortForwardingSession";

    private ArgumentosDoTunel() { }

    /** A lista do modo do {@code transporte}, com o programa já localizado em {@code executavel}. */
    public static List<String> para(String executavel, Transporte transporte, int portaLocal) {
        return switch (transporte.tipo()) {
            case SSM -> ssm(executavel, transporte, portaLocal);
            case CLOUDFLARED -> cloudflared(executavel, transporte, portaLocal);
            case DIRETO, LOCAL -> throw new IllegalArgumentException("Este transporte não abre túnel.");
        };
    }

    public static List<String> ssm(String aws, Transporte t, int portaLocal) {
        conferirPorta(portaLocal);
        List<String> a = new ArrayList<>();
        a.add(aws);
        a.add("ssm");
        a.add("start-session");
        a.add("--target");
        a.add(doUsuario(t.instanciaId()));
        a.add("--document-name");
        a.add(DOCUMENTO_DO_SSM);
        a.add("--parameters");
        a.add("portNumber=" + t.portaSshNaInstancia() + ",localPortNumber=" + portaLocal);
        if (t.regiao() != null) {
            a.add("--region");
            a.add(doUsuario(t.regiao()));
        }
        if (t.perfilAws() != null) {
            a.add("--profile");
            a.add(doUsuario(t.perfilAws()));
        }
        return List.copyOf(a);
    }

    public static List<String> cloudflared(String cloudflared, Transporte t, int portaLocal) {
        conferirPorta(portaLocal);
        return List.of(cloudflared, "access", "tcp", "--hostname", doUsuario(t.hostname()), "--url", ENDERECO_LOCAL + ":" + portaLocal);
    }

    /** O argumento da versão: {@code <programa> --version}. */
    public static List<String> versao(String executavel) {
        return List.of(executavel, "--version");
    }

    private static void conferirPorta(int porta) {
        if (porta < 1 || porta > 65_535) {
            throw new IllegalArgumentException("Porta local fora de 1 a 65535.");
        }
    }

    /** Defesa em profundidade: um valor que veio do usuário nunca começa com hífen, nem tem espaço ou controle. */
    private static String doUsuario(String valor) {
        if (valor == null || valor.isEmpty() || valor.startsWith("-") || valor.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Valor recusado para argumento de programa externo.");
        }
        return valor;
    }
}
