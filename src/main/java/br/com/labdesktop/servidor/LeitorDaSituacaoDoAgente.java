package br.com.labdesktop.servidor;

import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import java.io.IOException;
import java.time.Duration;

/**
 * Descobre, por UMA leitura por SSH (nunca escrita), se o agente está instalado num servidor que está de pé por SSH e não responde
 * pelo túnel. É o que separa "ainda não instalado" de "instalado e não responde". O comando é o fixo de
 * {@link ComandosRemotos#lerSituacaoDoAgente(String)}.
 */
final class LeitorDaSituacaoDoAgente {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private final ProvisionadorRemoto.Executor executor;
    private final String container;

    LeitorDaSituacaoDoAgente(ProvisionadorRemoto.Executor executor, String container) {
        this.executor = executor;
        this.container = container;
    }

    SituacaoDoAgente ler() throws InterruptedException {
        try {
            String saida = executor.executar(ComandosRemotos.lerSituacaoDoAgente(container), new byte[0], PRAZO).stdout().strip();
            return interpretar(saida);
        } catch (IOException e) {
            return SituacaoDoAgente.DESCONHECIDA; // sem sessão agora: o próximo SSH_OK_SEM_AGENTE pergunta de novo
        }
    }

    static SituacaoDoAgente interpretar(String saida) {
        if (saida.startsWith("existe:")) {
            return SituacaoDoAgente.INSTALADO_SEM_RESPOSTA;
        }
        return switch (saida) {
            case "ausente", "sem-docker" -> SituacaoDoAgente.NAO_INSTALADO;
            case "sem-permissao" -> SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER;
            default -> SituacaoDoAgente.DESCONHECIDA;
        };
    }
}
