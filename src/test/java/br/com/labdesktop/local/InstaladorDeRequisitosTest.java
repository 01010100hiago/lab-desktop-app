package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Cada "Instalar" mostra exatamente o comando e só executa depois do clique: a prova é que NADA roda antes de {@code instalar} ser chamado
 * (chamá-lo é o clique), e que, chamado, roda exatamente os comandos do plano, na ordem, e para no primeiro que falha.
 */
class InstaladorDeRequisitosTest {

    private final ComputadorSimulado pc = new ComputadorSimulado();
    private final SondaFalsa sonda = new SondaFalsa();
    private final PlataformaLocal mac = new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of());

    private static final class Log implements br.com.labdesktop.instalador.ObservadorDaInstalacao {
        final List<String> linhas = new ArrayList<>();

        @Override
        public void aoMudarPasso(br.com.labdesktop.instalador.Passo passo, br.com.labdesktop.instalador.EstadoDoPasso estado, String detalhe) { }

        @Override
        public void aoRegistrar(String linha) {
            linhas.add(linha);
        }
    }

    @Test
    void nadaRodaAntesDoCliqueNemAoCriarOsPlanosNemAoConferir() throws Exception {
        pc.dockerInstalado = false;
        RelatorioDoComputador r = new VerificacoesDoComputador(pc, sonda, mac).executar(mac.pastaDoLaboratorio(), 7070);
        List<PlanoDeInstalacao> planos = r.de(ItemDoComputador.DOCKER).planos();
        assertFalse(planos.isEmpty());
        new InstaladorDeRequisitos(pc); // montar o instalador também não faz nada
        assertTrue(pc.escritas().isEmpty(), "só leituras até o clique: " + pc.escritas());
        assertFalse(pc.dockerInstalado);
    }

    @Test
    void oCliqueRodaExatamenteOsComandosDoPlanoNaOrdemEOLogMostraCadaUm() throws Exception {
        pc.dockerInstalado = false;
        PlanoDeInstalacao plano = PlanosDeInstalacao.docker(SistemaOperacional.MAC, true, false).get(0);
        pc.limparRegistro();
        Log log = new Log();

        InstaladorDeRequisitos.Resultado r = new InstaladorDeRequisitos(pc).instalar(plano, log, Cancelamento.NUNCA);

        assertTrue(r.concluido(), r.mensagem());
        assertEquals(plano.comandos(), pc.comandos);
        assertEquals("$ brew install --cask docker-desktop", log.linhas.get(0));
        assertTrue(pc.dockerInstalado);
        assertTrue(r.mensagem().contains("aceite os termos"), r.mensagem());
    }

    @Test
    void falhaNoMeioParaNoPrimeiroQueFalhaEDizOErro() throws Exception {
        PlanoDeInstalacao dois = new PlanoDeInstalacao("x", "x", "x", List.of(List.of("brew", "install", "a"), List.of("brew", "install", "b")), null, List.of(),
            null, false, false, true);
        pc.falharEm(ComputadorSimulado.contem("install a"), 1, "Error: No available formula");
        InstaladorDeRequisitos.Resultado r = new InstaladorDeRequisitos(pc).instalar(dois, new Log(), Cancelamento.NUNCA);
        assertFalse(r.concluido());
        assertTrue(r.mensagem().contains("No available formula"), r.mensagem());
        assertEquals(1, pc.comandos.size(), "o segundo nem rodou");
    }

    @Test
    void planoSemComandosNaoRodaNada() {
        PlanoDeInstalacao pagina = PlanosDeInstalacao.docker(SistemaOperacional.MAC, false, false).get(0);
        InstaladorDeRequisitos.Resultado r = new InstaladorDeRequisitos(pc).instalar(pagina, null, null);
        assertFalse(r.concluido());
        assertTrue(pc.comandos.isEmpty());
    }

    @Test
    void programaAusenteDiziaParaUsarAPaginaOficial() {
        pc.semPrograma("brew");
        PlanoDeInstalacao plano = PlanosDeInstalacao.docker(SistemaOperacional.MAC, true, false).get(0);
        InstaladorDeRequisitos.Resultado r = new InstaladorDeRequisitos(pc).instalar(plano, null, null);
        assertFalse(r.concluido());
        assertTrue(r.mensagem().contains("brew"), r.mensagem());
        assertTrue(r.mensagem().contains("página oficial"), r.mensagem());
    }

    @Test
    void comandoQueDemoraDemaisEInterrompidoComFraseDePrazo() {
        ExecutorDeProcesso lento = (a, stdin, prazo) -> {
            throw ComputadorSimulado.demorou();
        };
        InstaladorDeRequisitos.Resultado r = new InstaladorDeRequisitos(lento).instalar(PlanosDeInstalacao.docker(SistemaOperacional.MAC, true, false).get(0), null, null);
        assertFalse(r.concluido());
        assertTrue(r.mensagem().contains("demorou demais"), r.mensagem());
    }

    @Test
    void cancelarAntesDoComandoNaoRodaNada() {
        AtomicBoolean cancelado = new AtomicBoolean(true);
        InstaladorDeRequisitos.Resultado r = new InstaladorDeRequisitos(pc).instalar(PlanosDeInstalacao.docker(SistemaOperacional.MAC, true, false).get(0), null,
            cancelado::get);
        assertTrue(r.cancelado());
        assertTrue(pc.comandos.isEmpty());
    }

    @Test
    void interromperAThreadDuranteOComandoCancelaEDevolveAFlag() throws Exception {
        ExecutorDeProcesso preso = (a, stdin, prazo) -> {
            throw new InterruptedException("cancelado pelo teste");
        };
        boolean[] flag = new boolean[1];
        InstaladorDeRequisitos.Resultado[] r = new InstaladorDeRequisitos.Resultado[1];
        Thread t = new Thread(() -> {
            r[0] = new InstaladorDeRequisitos(preso).instalar(PlanosDeInstalacao.docker(SistemaOperacional.MAC, true, false).get(0), null, null);
            flag[0] = Thread.currentThread().isInterrupted();
        });
        t.start();
        t.join(5000);
        assertTrue(r[0].cancelado());
        assertTrue(flag[0]);
    }

    @Test
    void abrirOEsperarOMotorSubirDepoisDeAlgumasConsultas() throws Exception {
        pc.dockerParado = true;
        pc.consultasAteSubir = 2;
        Log log = new Log();
        boolean subiu = new InstaladorDeRequisitos(pc).abrirEEsperar(mac.comandoParaAbrirODocker(true, false), Duration.ofSeconds(10), Duration.ofMillis(10), log,
            Cancelamento.NUNCA);
        assertTrue(subiu);
        assertEquals(List.of("open", "-a", "Docker"), pc.comandos.get(0));
        assertTrue(log.linhas.contains("[o Docker respondeu]"));
    }

    @Test
    void abrirESoEsperarSeNaoHaComandoParaAbrirEDesistePorTempo() throws Exception {
        pc.dockerParado = true; // ninguém o liga
        boolean subiu = new InstaladorDeRequisitos(pc).abrirEEsperar(List.of(), Duration.ofMillis(150), Duration.ofMillis(20), null, Cancelamento.NUNCA);
        assertFalse(subiu);
        assertTrue(pc.comandos.stream().allMatch(c -> c.equals(ComandosLocais.versaoDoDocker())), "só consultou");
    }
}
