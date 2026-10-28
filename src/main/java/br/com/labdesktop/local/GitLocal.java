package br.com.labdesktop.local;

import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * O git DESTE computador, só leitura: a mesma regra da conferência de "Preparar este computador" (no Mac, {@code /usr/bin/git} sem as ferramentas da Apple é só um atalho que abre o aviso de
 * instalação do sistema, então sem um git de verdade a conferência nem tenta rodar o git). Serve à aba Máquina e ao aviso do "Novo projeto" em Este computador, que reaproveitam a verificação
 * local em vez de ter uma segunda.
 */
public final class GitLocal {

    private static final Duration PRAZO = Duration.ofSeconds(15);

    private GitLocal() { }

    /**
     * @return a versão ({@code git version 2.43.0}) se o git responde; vazio se não está instalado
     */
    public static Optional<String> versao(SondaDoComputador sonda, ExecutorDeProcesso executor, SistemaOperacional so) throws InterruptedException {
        boolean podeRodar = so != SistemaOperacional.MAC || sonda.localizarPrograma("git").isPresent();
        if (!podeRodar) {
            return Optional.empty();
        }
        try {
            ResultadoExec x = new ExecutorSoDeLeitura(executor).executar(ComandosLocais.versaoDoGit(), PRAZO);
            if (x.status() == 0) {
                return Optional.of(TextoSeguro.truncar(TextoSeguro.limparLinha(x.stdout().strip()), 60));
            }
        } catch (IOException e) {
            // não achou ou não rodou: não está instalado
        }
        return Optional.empty();
    }
}
