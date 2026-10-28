package br.com.labdesktop.cloudflare;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.util.ArrayList;
import java.util.List;

/**
 * TODOS os comandos que o pacote da Cloudflare roda no servidor, num lugar só. Cada um é uma LISTA de argumentos FIXA (nunca texto para um shell): o que varia
 * (o nome do container do cloudflared e o caminho do {@code config.yml} dentro dele) já passou por {@link ValidadorDeNomes} aqui dentro, e por SSH ainda passa por
 * {@code ExecutorDeArgumentos#SEGURO}. Os comandos de ESCRITA ({@link #reiniciar}) só nascem do clique de confirmação; a varredura de código dos testes confere
 * (só {@code TunelPorArquivo} os monta, e só o publicador e o removedor chamam essa classe).
 */
public final class ComandosCloudflared {

    private ComandosCloudflared() { }

    private static List<String> docker(boolean comSudo) {
        return comSudo ? new ArrayList<>(List.of("sudo", "-n", "docker")) : new ArrayList<>(List.of("docker"));
    }

    private static String container(String nome) {
        if (nome == null || !nome.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,40}$") || !ValidadorDeNomes.containerValido(nome)) {
            throw new IllegalArgumentException("Nome de container inválido.");
        }
        return nome;
    }

    /** {@code docker inspect <container>}: LEITURA (o estado, o que ele monta). */
    public static List<String> inspecionar(boolean comSudo, String container) {
        List<String> c = docker(comSudo);
        c.addAll(List.of("inspect", container(container)));
        return List.copyOf(c);
    }

    /**
     * {@code docker exec <container> cloudflared tunnel --config <caminho> ingress validate}: o cloudflared confere as regras do arquivo (rodado dentro do
     * container que já o usa). Não muda nada no servidor.
     */
    public static List<String> validarRegras(boolean comSudo, String container, String caminhoNoContainer) {
        if (!ValidadorDeNomes.caminhoSeguroValido(caminhoNoContainer)) {
            throw new IllegalArgumentException("Caminho do config.yml dentro do container inválido.");
        }
        List<String> c = docker(comSudo);
        c.addAll(List.of("exec", container(container), "cloudflared", "tunnel", "--config", caminhoNoContainer, "ingress", "validate"));
        return List.copyOf(c);
    }

    /** {@code docker restart <container>}: ESCRITA. Reinicia SÓ o container do cloudflared (as regras do arquivo só valem depois disso). */
    public static List<String> reiniciar(boolean comSudo, String container) {
        List<String> c = docker(comSudo);
        c.addAll(List.of("restart", container(container)));
        return List.copyOf(c);
    }

    /** O comando é só de leitura? (a fronteira dos testes). */
    public static boolean ehLeitura(List<String> comando) {
        return comando.contains("inspect") || comando.contains("validate");
    }
}
