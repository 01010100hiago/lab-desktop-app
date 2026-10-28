package br.com.labdesktop.servidor;

import br.com.labdesktop.local.ComandosLocais;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.local.ExecutorSoDeLeitura;
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
        this.executor = new ExecutorSoDeLeitura(executor); // roda sozinho, em segundo plano: só leituras passam
        this.container = container;
    }

    private static String texto(ResultadoExec x) {
        return (x.stderr() + " " + x.stdout()).toLowerCase(java.util.Locale.ROOT);
    }

    SituacaoDoAgente ler() throws InterruptedException {
        try {
            ResultadoExec docker = executor.executar(ComandosLocais.versaoDoDocker(), PRAZO);
            if (docker.status() != 0) {
                // "permission denied" (Linux fora do grupo docker) NÃO é Docker parado: o remédio é outro (sudo usermod -aG docker)
                return texto(docker).contains("permission denied") ? SituacaoDoAgente.LOCAL_SEM_PERMISSAO : SituacaoDoAgente.LOCAL_DOCKER_PARADO;
            }
            ResultadoExec estado = executor.executar(ComandosLocais.lerEstadoDoContainer(container), PRAZO);
            if (estado.status() == 0) {
                return "running".equals(estado.stdout().strip()) ? SituacaoDoAgente.INSTALADO_SEM_RESPOSTA : SituacaoDoAgente.LOCAL_CONTAINER_PARADO;
            }
            // só "não existe" quando o Docker diz que não existe; qualquer outro erro é estado desconhecido, nunca "não criado"
            return texto(estado).contains("no such") ? SituacaoDoAgente.LOCAL_NAO_CRIADO : SituacaoDoAgente.DESCONHECIDA;
        } catch (ProgramaNaoEncontradoException e) {
            return SituacaoDoAgente.LOCAL_SEM_DOCKER;
        } catch (IOException e) {
            return SituacaoDoAgente.DESCONHECIDA;
        }
    }
}
