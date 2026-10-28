package br.com.labdesktop.local;

import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Um {@link ExecutorDeProcesso} que só deixa passar o que {@link ComandosLocais#ehLeitura} reconhece. É o que a conferência de "Preparar este
 * computador" e o diagnóstico de fundo do servidor local usam: rodam sozinhos (sem clique), então a fronteira "só leitura" é imposta AQUI, em
 * código de produção, e não só conferida num teste. Um comando de escrita que chegue a este executor é um erro de programação.
 */
public final class ExecutorSoDeLeitura implements ExecutorDeProcesso {

    private final ExecutorDeProcesso por;

    public ExecutorSoDeLeitura(ExecutorDeProcesso por) {
        this.por = por;
    }

    @Override
    public ResultadoExec executar(List<String> argumentos, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        if (!ComandosLocais.ehLeitura(argumentos)) {
            throw new IllegalStateException("Este executor só roda leituras, e isto não é uma: " + argumentos);
        }
        return por.executar(argumentos, stdin, prazo);
    }
}
