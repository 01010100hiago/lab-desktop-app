package br.com.labdesktop.local;

import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Roda um {@link PlanoDeInstalacao} (instalar o Docker, o git) e ajuda a ABRIR o Docker, sempre e só quando a tela de confirmação chama: este
 * é o ÚNICO lugar do pacote que instala algo, e só é alcançado pelo clique do usuário (um teste varre o código para garantir). Nunca digita senha
 * de administrador (se o sistema a pedir, é a janela do próprio sistema), nunca eleva privilégio, tem prazo e obedece ao Cancelar (interromper a
 * thread mata o processo).
 */
public final class InstaladorDeRequisitos {

    /** Quanto cada comando de instalação pode levar (o Docker Desktop pesa 2 GB). */
    static final Duration PRAZO_DA_INSTALACAO = Duration.ofMinutes(25);
    private static final int TETO_DA_SAIDA_NO_LOG = 3000;

    /** O que aconteceu. */
    public record Resultado(boolean concluido, boolean cancelado, String mensagem) { }

    private final ExecutorDeProcesso executor;
    private final Duration prazo;

    public InstaladorDeRequisitos(ExecutorDeProcesso executor) {
        this(executor, PRAZO_DA_INSTALACAO);
    }

    InstaladorDeRequisitos(ExecutorDeProcesso executor, Duration prazo) {
        this.executor = executor;
        this.prazo = prazo;
    }

    /**
     * Executa os comandos do plano, um depois do outro, parando no primeiro que falha. Um plano sem comandos (só página ou passos) não roda nada.
     */
    public Resultado instalar(PlanoDeInstalacao plano, ObservadorDaInstalacao observador, Cancelamento cancelamento) {
        ObservadorDaInstalacao obs = observador == null ? ObservadorDaInstalacao.NENHUM : observador;
        Cancelamento cancela = cancelamento == null ? Cancelamento.NUNCA : cancelamento;
        if (!plano.executavelPeloApp()) {
            return new Resultado(false, false, "Este plano não tem comandos para o app rodar: siga os passos mostrados.");
        }
        for (List<String> comando : plano.comandos()) {
            if (cancela.cancelado()) {
                return new Resultado(false, true, "Cancelado por você.");
            }
            obs.aoRegistrar("$ " + PlanoDeInstalacao.comoTexto(comando));
            try {
                ResultadoExec x = executor.executar(comando, new byte[0], prazo);
                String saida = (x.stdout() + (x.stderr().isBlank() ? "" : "\n" + x.stderr())).strip();
                if (!saida.isEmpty()) {
                    obs.aoRegistrar(TextoSeguro.truncar(saida, TETO_DA_SAIDA_NO_LOG));
                }
                if (x.status() != 0) {
                    obs.aoRegistrar("[status " + x.status() + "]");
                    return new Resultado(false, false, "O comando falhou (status " + x.status() + "): " + fim(saida));
                }
            } catch (ProgramaNaoEncontradoException e) {
                return new Resultado(false, false, "Não encontrei o programa " + e.programa() + " neste computador. Use a página oficial de download.");
            } catch (ComandoDemorouException e) {
                return new Resultado(false, false, "O comando demorou demais e foi interrompido. Tente de novo ou use a página oficial de download.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Resultado(false, true, "Cancelado por você. O que o instalador já tinha feito pode ter ficado pela metade: clique em "
                    + "Verificar de novo para ver o que há.");
            } catch (IOException e) {
                return new Resultado(false, false, "Não consegui rodar o comando: " + TextoSeguro.limparLinha(Redator.redigir(e)));
            }
        }
        obs.aoRegistrar("[pronto]");
        return new Resultado(true, false, plano.passosManuais().isEmpty() ? "Pronto." : String.join(" ", plano.passosManuais()));
    }

    private static String fim(String saida) {
        String[] linhas = saida.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, linhas.length - 6); i < linhas.length; i++) {
            sb.append(TextoSeguro.limparLinha(linhas[i])).append(' ');
        }
        String t = TextoSeguro.truncar(sb.toString().strip(), 600);
        return t.isEmpty() ? "sem mensagem" : t;
    }

    /**
     * ABRE o Docker (o Docker Desktop, ou liga o Colima) e espera o motor responder. Não instala nada. Sem comando para abrir (Linux, ou nada
     * instalado), diz isso e só espera.
     *
     * @param comandoParaAbrir {@link PlataformaLocal#comandoParaAbrirODocker}; vazio = o usuário abre
     * @param espera           quanto esperar o motor subir (a primeira vez do Docker Desktop demora)
     * @return {@code true} se o motor respondeu
     */
    public boolean abrirEEsperar(List<String> comandoParaAbrir, Duration espera, Duration intervalo, ObservadorDaInstalacao observador,
                                 Cancelamento cancelamento) throws InterruptedException {
        ObservadorDaInstalacao obs = observador == null ? ObservadorDaInstalacao.NENHUM : observador;
        Cancelamento cancela = cancelamento == null ? Cancelamento.NUNCA : cancelamento;
        if (!comandoParaAbrir.isEmpty()) {
            obs.aoRegistrar("$ " + PlanoDeInstalacao.comoTexto(comandoParaAbrir));
            try {
                ResultadoExec x = executor.executar(comandoParaAbrir, new byte[0], Duration.ofMinutes(2));
                if (x.status() != 0) {
                    obs.aoRegistrar("[status " + x.status() + "] " + TextoSeguro.truncar(TextoSeguro.limparLinha(x.stderr().strip()), 300));
                }
            } catch (IOException e) {
                obs.aoRegistrar("Não consegui abrir: " + TextoSeguro.limparLinha(Redator.redigir(e)));
            }
        }
        long limite = System.nanoTime() + espera.toNanos();
        while (System.nanoTime() < limite) {
            if (cancela.cancelado()) {
                throw new InterruptedException("Cancelado.");
            }
            try {
                if (executor.executar(ComandosLocais.versaoDoDocker(), Duration.ofSeconds(20)).status() == 0) {
                    obs.aoRegistrar("[o Docker respondeu]");
                    return true;
                }
            } catch (IOException e) {
                // ainda subindo
            }
            Thread.sleep(intervalo.toMillis());
        }
        return false;
    }
}
