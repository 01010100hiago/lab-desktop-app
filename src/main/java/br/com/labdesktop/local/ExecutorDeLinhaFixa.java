package br.com.labdesktop.local;

import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Faz as linhas FIXAS que o app já tem para falar com o agente (as do
 * {@link ProvisionadorRemoto}) rodarem como um PROCESSO local, sem shell: a linha vira uma lista de argumentos. Como ela vem de constantes do
 * app com um nome de container validado, só se aceita a forma exata fixa do app; qualquer outra
 * coisa (aspas, {@code sh -c}, {@code sudo}, redirecionamento) é recusada, então nada que o usuário digita ou que o servidor devolve vira comando.
 */
public final class ExecutorDeLinhaFixa implements ProvisionadorRemoto.Executor {

    private static final Pattern FORMA = Pattern.compile(
        "^docker exec -i [a-z0-9][a-z0-9_.-]{0,40} node acesso\\.js (token-registrar|token-revogar|totp-iniciar|totp-confirmar|totp-desativar)$");

    private final ExecutorDeProcesso executor;

    public ExecutorDeLinhaFixa(ExecutorDeProcesso executor) {
        this.executor = executor;
    }

    @Override
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        return executor.executar(paraArgumentos(comando), stdin, prazo);
    }

    /** @throws IOException a linha não é uma das do agente (neste computador não há shell para rodar outra coisa) */
    static List<String> paraArgumentos(String comando) throws IOException {
        if (comando == null || !FORMA.matcher(comando).matches()) {
            throw new IOException("Este computador só roda os comandos do agente (docker exec ... acesso.js), nada além disso.");
        }
        return List.copyOf(Arrays.asList(comando.split(" ")));
    }
}
