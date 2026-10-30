package br.com.labdesktop.transporte;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.SistemaOperacional;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Um túnel externo aberto para UMA conexão: o programa do modo ({@code aws ssm start-session} ou {@code cloudflared access tcp}) lançado por LISTA de
 * argumentos (nunca por shell), escutando numa porta ALEATÓRIA só de 127.0.0.1. O {@link #abrir} espera a porta ter alguém escutando (prazo claro) e só então devolve; o SSH do app
 * conecta em {@code 127.0.0.1:<porta>}.
 *
 * <h2>Ciclo de vida</h2>
 * {@link #close()} encerra o programa e os filhos dele; é chamado quando a conexão fecha, quando o servidor é editado ou removido, ao fechar o app, e (melhor esforço) pelo gancho de
 * desligamento da JVM ({@link RegistroDeTuneis}). Se o programa terminar SOZINHO depois de pronto, {@code aoCair} recebe a causa (uma vez) e o usuário dela fecha a conexão.
 *
 * <h2>A porta e a corrida</h2>
 * A porta é reservada e liberada ({@link PortasLocais#reservar}) e só depois o programa a ocupa; nessa janela outro programa pode pegá-la. Se o programa terminar dizendo que a porta está
 * em uso, o túnel tenta outra porta (até {@link ConfigDoTunel#tentativasDePorta()}). Se a porta foi tomada por um programa que continua escutando, o SSH que conectar nela
 * encontra a chave de OUTRO servidor, e o pin recusa antes de qualquer credencial sair.
 */
public final class TunelDeTransporte implements DestinoSsh {

    private static final Logger LOG = LoggerFactory.getLogger(TunelDeTransporte.class);

    /** Tudo de que o túnel depende (o que os testes trocam). */
    public record Dependencias(LancadorDeProcesso lancador, LocalizadorDeFerramenta localizador, PortasLocais portas, RegistroDeTuneis registro, ConfigDoTunel config,
                               SistemaOperacional sistema, String pathDoProcesso, String separadorDoPath) { }

    private final Transporte transporte;
    private final ProcessoExterno processo;
    private final int porta;
    private final Dependencias dependencias;
    private final AtomicBoolean fechado = new AtomicBoolean();
    private volatile FalhaDoTunel falhaFinal;

    private TunelDeTransporte(Transporte transporte, ProcessoExterno processo, int porta, Dependencias dependencias) {
        this.transporte = transporte;
        this.processo = processo;
        this.porta = porta;
        this.dependencias = dependencias;
    }

    /**
     * Sobe o túnel e espera ele ficar pronto. Bloqueia (até o prazo): fora da EDT.
     *
     * @param aoCair chamado UMA vez se o programa terminar sozinho depois de pronto
     * @throws FalhaDoTunelException o programa não está instalado, não iniciou, saiu (login da AWS expirado, instância sem SSM, Cloudflare pedindo login...) ou não ficou pronto no prazo
     * @throws InterruptedException  a thread foi interrompida; o programa já lançado é encerrado
     */
    public static TunelDeTransporte abrir(Transporte transporte, Dependencias d, Consumer<FalhaDoTunel> aoCair) throws FalhaDoTunelException, InterruptedException {
        if (!transporte.ehTunel()) {
            throw new IllegalArgumentException("Este transporte não abre túnel.");
        }
        FerramentaExterna principal = transporte.tipo() == Transporte.Tipo.SSM ? FerramentaExterna.AWS : FerramentaExterna.CLOUDFLARED;
        String executavel = d.localizador().localizar(principal)
            .orElseThrow(() -> new FalhaDoTunelException(GuiaDeInstalacao.ausente(principal, d.sistema())));
        Map<String, String> ambiente = Map.of();
        if (transporte.tipo() == Transporte.Tipo.SSM) {
            // o aws chama o session-manager-plugin por conta própria e o procura no PATH DELE: um app aberto pelo Finder não herda o PATH do terminal,
            // então a pasta do plugin vai no PATH do processo filho (e só dele).
            String plugin = d.localizador().localizar(FerramentaExterna.PLUGIN_SSM)
                .orElseThrow(() -> new FalhaDoTunelException(GuiaDeInstalacao.ausente(FerramentaExterna.PLUGIN_SSM, d.sistema())));
            ambiente = Map.of("PATH", pathComAPasta(DescobertaDeFerramentas.pastaDe(plugin), d.pathDoProcesso(), d.separadorDoPath()));
        }
        long prazoEmSegundos = Math.max(1, d.config().prazoParaSubir().toSeconds());
        for (int tentativa = 1; ; tentativa++) {
            int porta;
            try {
                porta = d.portas().reservar();
            } catch (IOException e) {
                throw new FalhaDoTunelException(new FalhaDoTunel(FalhaDoTunel.Tipo.NAO_INICIOU, "Não consegui reservar uma porta local para o túnel: " + InterpretadorDaSaida.trecho(e.getMessage())));
            }
            ProcessoExterno processo;
            try {
                processo = d.lancador().lancar(ArgumentosDoTunel.para(executavel, transporte, porta), ambiente);
            } catch (IOException e) {
                throw new FalhaDoTunelException(new FalhaDoTunel(FalhaDoTunel.Tipo.NAO_INICIOU,
                    "Não consegui iniciar o programa " + principal.nomeBase() + ": " + InterpretadorDaSaida.trecho(e.getMessage())));
            }
            TunelDeTransporte tunel = new TunelDeTransporte(transporte, processo, porta, d);
            d.registro().registrar(tunel);
            FalhaDoTunel falha;
            try {
                falha = tunel.esperarPronto(prazoEmSegundos);
            } catch (InterruptedException e) {
                tunel.close();
                throw e;
            }
            if (falha == null) {
                tunel.vigiar(aoCair);
                return tunel;
            }
            tunel.close();
            if (falha.tipo() == FalhaDoTunel.Tipo.PORTA_OCUPADA && tentativa < d.config().tentativasDePorta()) {
                LOG.info("A porta local {} foi tomada por outro programa; tentando outra ({} de {}).", porta, tentativa + 1, d.config().tentativasDePorta());
                continue;
            }
            throw new FalhaDoTunelException(falha);
        }
    }

    /** O PATH do filho: a pasta do plugin na frente, depois o que o processo já tinha. */
    static String pathComAPasta(String pasta, String pathAtual, String separador) {
        boolean semPath = pathAtual == null || pathAtual.isBlank();
        if (pasta == null || pasta.isEmpty()) {
            return semPath ? "" : pathAtual;
        }
        return semPath ? pasta : pasta + separador + pathAtual;
    }

    /** {@code null} se a porta passou a ter alguém escutando com o programa de pé; senão a causa. */
    private FalhaDoTunel esperarPronto(long prazoEmSegundos) throws InterruptedException {
        long limite = System.nanoTime() + dependencias.config().prazoParaSubir().toNanos();
        long passo = Math.max(1, dependencias.config().passoDaEspera().toMillis());
        while (true) {
            if (!processo.vivo()) {
                return interpretarQueda();
            }
            if (dependencias.portas().alguemEscuta(porta)) {
                // confere de novo: a porta pode ter sido tomada por outro programa enquanto o nosso morria
                return processo.vivo() ? null : interpretarQueda();
            }
            if (System.nanoTime() >= limite) {
                return InterpretadorDaSaida.interpretar(transporte, processo.saida(), null, dependencias.sistema(), prazoEmSegundos);
            }
            Thread.sleep(passo);
        }
    }

    /** Quanto esperar o código de saída de um programa que já não está de pé (o código chega um instante depois de o processo morrer). */
    private static final long ESPERA_DO_CODIGO_EM_MILISSEGUNDOS = 1_000;

    private FalhaDoTunel interpretarQueda() {
        Integer codigo = -1;
        try {
            codigo = processo.aoSair().get(ESPERA_DO_CODIGO_EM_MILISSEGUNDOS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOG.debug("O código de saída do túnel não chegou: {}", e.toString());
        }
        return InterpretadorDaSaida.interpretar(transporte, processo.saida(), codigo, dependencias.sistema(), 0);
    }

    private void vigiar(Consumer<FalhaDoTunel> aoCair) {
        processo.aoSair().thenAccept(codigo -> {
            if (fechado.get()) {
                return; // fomos nós que encerramos
            }
            FalhaDoTunel falha = InterpretadorDaSaida.interpretar(transporte, processo.saida(), codigo, dependencias.sistema(), 0);
            falhaFinal = falha;
            LOG.info("O túnel de {} terminou sozinho: {}", transporte.tipo(), falha.mensagem());
            try {
                aoCair.accept(falha);
            } catch (RuntimeException e) {
                LOG.warn("Quem ouvia a queda do túnel falhou: {}", e.toString());
            }
        });
    }

    @Override
    public String host() {
        return ArgumentosDoTunel.ENDERECO_LOCAL;
    }

    @Override
    public int porta() {
        return porta;
    }

    @Override
    public String saidaRecente() {
        return processo.saida();
    }

    @Override
    public Optional<FalhaDoTunel> causaPelaSaida() {
        return InterpretadorDaSaida.causaEspecifica(transporte, processo.saida(), dependencias.sistema());
    }

    @Override
    public Optional<FalhaDoTunel> falhaDoTunel() {
        if (fechado.get()) {
            return Optional.empty();
        }
        FalhaDoTunel f = falhaFinal;
        if (f != null) {
            return Optional.of(f);
        }
        if (!processo.vivo()) {
            return Optional.of(interpretarQueda());
        }
        return Optional.empty();
    }

    @Override
    public Optional<FalhaDoTunel> falhaDoTunelAposPerderASessao() {
        Optional<FalhaDoTunel> agora = falhaDoTunel();
        if (agora.isPresent() || fechado.get()) {
            return agora; // já se sabe a causa, ou fomos nós que fechamos
        }
        // O sistema fecha os sockets de um processo que morre (e a sessão SSH cai) um instante ANTES de o Java dá-lo como morto e de a saída ser lida: sem esperar um pouco, a causa se
        // perdia. Se o programa continua de pé depois da espera (a sessão caiu por outro motivo, com o túnel bom), não há causa de túnel. Quem chama NÃO está na EDT nem no ator da conexão.
        try {
            processo.aoSair().get(dependencias.config().esperaPelaCausa().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOG.debug("A sessão caiu e o programa do túnel seguiu de pé depois da espera: {}", e.toString());
        }
        return falhaDoTunel();
    }

    /** O programa do túnel ainda está de pé? */
    public boolean vivo() {
        return !fechado.get() && processo.vivo();
    }

    @Override
    public void close() {
        if (!fechado.compareAndSet(false, true)) {
            return;
        }
        try {
            processo.encerrar();
        } finally {
            dependencias.registro().remover(this);
        }
    }

    @Override
    public String toString() {
        return "túnel " + transporte.tipo() + " em 127.0.0.1:" + porta;
    }
}
