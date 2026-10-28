package br.com.labdesktop.servidor;

import br.com.labdesktop.local.ComandosLocais;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;

/**
 * Descobre, por LEITURAS (nunca escrita), por que o agente de "Este computador" não responde: sem Docker, Docker parado, laboratório ainda não
 * criado, ou o agente criado e sem resposta. É o que faz a faixa dizer "Preparar este computador" em vez de "agente fora".
 */
final class LeitorDaSituacaoLocal {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private final ExecutorDeProcesso executor;
    private final String container;

    LeitorDaSituacaoLocal(ExecutorDeProcesso executor, String container) {
        this.executor = executor;
        this.container = container;
    }

    SituacaoDoAgente ler() throws InterruptedException {
        try {
            ResultadoExec docker = executor.executar(ComandosLocais.versaoDoDocker(), PRAZO);
            if (docker.status() != 0) {
                return SituacaoDoAgente.LOCAL_DOCKER_PARADO;
            }
            ResultadoExec estado = executor.executar(ComandosLocais.lerEstadoDoContainer(container), PRAZO);
            return estado.status() == 0 ? SituacaoDoAgente.INSTALADO_SEM_RESPOSTA : SituacaoDoAgente.LOCAL_NAO_CRIADO;
        } catch (ProgramaNaoEncontradoException e) {
            return SituacaoDoAgente.LOCAL_SEM_DOCKER;
        } catch (IOException e) {
            return SituacaoDoAgente.DESCONHECIDA;
        }
    }
}
