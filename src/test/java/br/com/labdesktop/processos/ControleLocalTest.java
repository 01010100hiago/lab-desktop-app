package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.teste.ShellDeTeste;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Este computador: Mac e Linux por ProcessHandle (de verdade, com um sleep aberto pelo teste) e Windows por taskkill (com um sistema de mentira). */
class ControleLocalTest {

    private final String eu = System.getProperty("user.name");
    private ProcessosDeVerdade processos;

    @BeforeEach
    void montar() {
        processos = new ProcessosDeVerdade();
    }

    @AfterEach
    void limpar() {
        processos.close();
    }

    private ControleLocal unix(int esperaPedido, int esperaForcar) {
        return new ControleLocal(ProcessosDoSistema.real(), (a, s, p) -> {
            throw new AssertionError("no Mac e no Linux o sinal vai pelo Java, sem programa: " + a);
        }, new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_MAC_OU_LINUX, eu, Set.of(), ProcessosDoSistema.real().pidsDoApp()),
            ms -> Thread.sleep(Math.min(ms, 100)), esperaPedido, esperaForcar);
    }

    private ProcessoAlvo alvoDe(int pid) {
        return new ProcessoAlvo(pid, "sleep", eu, null, null);
    }

    @Test
    void sigtermEncerraUmProcessoDeVerdadeDesteComputador() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int pid = processos.abrirEducado();
        ControleLocal c = unix(10, 3);
        Previa p = c.preparar(alvoDe(pid));
        assertTrue(p.iniciouEm().length() > 5, "a confirmação mostra a hora de início: " + p.iniciouEm());
        Resultado r = c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, r.desfecho(), r.mensagem());
        assertFalse(ProcessosDeVerdade.vivo(pid));
    }

    @Test
    void sigtermIgnoradoDeixaRodandoESigkillMataPorOutroPedido() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int pid = processos.abrirTeimoso();
        ControleLocal c = unix(2, 3);
        Previa p = c.preparar(alvoDe(pid));
        assertEquals(Desfecho.AINDA_RODANDO, c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertTrue(ProcessosDeVerdade.vivo(pid));
        assertEquals(Desfecho.ENCERRADO, c.forcar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertFalse(ProcessosDeVerdade.vivo(pid));
    }

    @Test
    void numeroReaproveitadoNaoEMorto() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int a = processos.abrirEducado();
        int b = processos.abrirEducado();
        ControleLocal c = unix(2, 1);
        Previa deA = c.preparar(alvoDe(a));
        Previa deB = c.preparar(alvoDe(b));
        // o número de B agora "é" outro: a identidade que o app leu de A é entregue com o número de B
        Previa trocada = new Previa(alvoDe(b), deB.oferta(), new IdentidadeDoProcesso(b, deA.identidade().uid(), deA.identidade().inicio() + "x", deA.identidade().nome()), null);
        assertEquals(Desfecho.MUDOU, c.encerrar(trocada, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertTrue(ProcessosDeVerdade.vivo(b), "o que ficou com o número não foi morto");
    }

    @Test
    void oProprioAppNuncaSeOferece() {
        ControleLocal c = unix(2, 1);
        long meu = ProcessHandle.current().pid();
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> c.preparar(new ProcessoAlvo((int) meu, "java", eu, null, null)));
        assertEquals(Desfecho.PROTEGIDO, f.desfecho());
        assertTrue(ProcessHandle.current().isAlive());
    }

    @Test
    void processoDeOutroUsuarioNaoEDoApp() {
        Oferta o = unix(2, 1).avaliar(new ProcessoAlvo(777, "postgres", "postgres", null, null));
        assertEquals(Oferta.Situacao.SEM_PERMISSAO, o.situacao());
        assertEquals("ps -o pid,user,lstart,comm -p 777\nps -o comm= -p 777 | grep -Eq '(^|/)postgres$' && sudo kill -TERM 777", o.comandoParaODono());
    }

    @Test
    void reiniciarProcessoComumNaoExiste() throws Exception {
        ControleLocal c = unix(2, 1);
        Resultado r = c.reiniciar(null, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(r.mensagem().contains("não sabe como religá-lo"));
    }

    // ------------------------------------------------------------------ Windows (com um sistema de mentira)

    /** Um Windows de mentira: um processo vivo que o taskkill (de mentira) mata, ou não, conforme o roteiro. */
    private static final class SistemaFalso implements ProcessosDoSistema {
        final Map<Long, Info> vivos = new LinkedHashMap<>();
        boolean ignoraOPedido;

        @Override
        public Optional<Info> ler(long pid) {
            return Optional.ofNullable(vivos.get(pid));
        }

        @Override
        public List<Info> todos() {
            return List.copyOf(vivos.values());
        }

        @Override
        public boolean pedirParaFechar(long pid) {
            throw new AssertionError("no Windows o app usa o taskkill");
        }

        @Override
        public boolean matar(long pid) {
            throw new AssertionError("no Windows o app usa o taskkill");
        }

        @Override
        public boolean estaVivo(long pid) {
            return vivos.containsKey(pid);
        }

        @Override
        public Set<Integer> pidsDoApp() {
            return Set.of(10);
        }
    }

    private ControleLocal windows(SistemaFalso sistema, List<List<String>> comandos, ExecutorDeProcesso executor) {
        return new ControleLocal(sistema, executor == null ? (a, s, p) -> {
            comandos.add(a);
            boolean forca = a.contains("/F");
            if (forca || !sistema.ignoraOPedido) {
                sistema.vivos.remove(Long.parseLong(a.get(2)));
            }
            return new ResultadoExec(0, "SUCCESS".getBytes(StandardCharsets.UTF_8), new byte[0], false);
        } : executor, new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS, "ana", Set.of(), Set.of(10)), ms -> { }, 3, 2);
    }

    private static ProcessosDoSistema.Info notepad(long pid) {
        return new ProcessosDoSistema.Info(pid, "C:\\Windows\\System32\\notepad.exe", "PC\\ana", Instant.parse("2026-10-28T12:00:00Z"), Duration.ofSeconds(1));
    }

    @Test
    void windowsEncerraComTaskkillDeListaFixaSoDeExe() throws Exception {
        SistemaFalso s = new SistemaFalso();
        s.vivos.put(4242L, notepad(4242));
        List<List<String>> comandos = new ArrayList<>();
        ControleLocal c = windows(s, comandos, null);
        Previa p = c.preparar(new ProcessoAlvo(4242, "notepad.exe", "PC\\ana", null, null));
        Resultado r = c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.ENCERRADO, r.desfecho(), r.mensagem());
        assertEquals(List.of(List.of("taskkill", "/PID", "4242")), comandos, "sem /T: os programas que ele abriu ficam como estão");
    }

    @Test
    void windowsSoForcaDepoisDoPedidoEPorOutroClique() throws Exception {
        SistemaFalso s = new SistemaFalso();
        s.vivos.put(4242L, notepad(4242));
        s.ignoraOPedido = true;
        List<List<String>> comandos = new ArrayList<>();
        ControleLocal c = windows(s, comandos, null);
        Previa p = c.preparar(new ProcessoAlvo(4242, "notepad.exe", "PC\\ana", null, null));
        assertEquals(Desfecho.AINDA_RODANDO, c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertEquals(Desfecho.ENCERRADO, c.forcar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertEquals(List.of(List.of("taskkill", "/PID", "4242"), List.of("taskkill", "/PID", "4242", "/F")), comandos);
    }

    @Test
    void windowsRecusaProgramaQueNaoEExe() {
        SistemaFalso s = new SistemaFalso();
        s.vivos.put(4242L, new ProcessosDoSistema.Info(4242, "C:\\x\\coisa", "PC\\ana", Instant.now(), null));
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> windows(s, new ArrayList<>(), null).preparar(new ProcessoAlvo(4242, "coisa", "PC\\ana", null, null)));
        assertTrue(f.getMessage().contains(".exe"));
    }

    @Test
    void windowsAcessoNegadoViraSemPermissaoComOComandoParaOAdministrador() throws Exception {
        SistemaFalso s = new SistemaFalso();
        s.vivos.put(4242L, notepad(4242));
        ControleLocal c = windows(s, new ArrayList<>(), (a, st, pr) -> new ResultadoExec(1, new byte[0], "ERROR: The process \"4242\" could not be terminated. Access is denied.".getBytes(), false));
        Resultado r = c.encerrar(c.preparar(new ProcessoAlvo(4242, "notepad.exe", "PC\\ana", null, null)), ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.SEM_PERMISSAO, r.desfecho());
        assertTrue(r.comandoParaODono().contains("taskkill /PID 4242 /FI \"PID eq 4242\" /FI \"IMAGENAME eq notepad.exe\""), r.comandoParaODono());
        assertFalse(r.comandoParaODono().contains("sudo"));
    }

    @Test
    void windowsNumeroReaproveitadoNaoEnviaNada() throws Exception {
        SistemaFalso s = new SistemaFalso();
        s.vivos.put(4242L, notepad(4242));
        List<List<String>> comandos = new ArrayList<>();
        ControleLocal c = windows(s, comandos, null);
        Previa p = c.preparar(new ProcessoAlvo(4242, "notepad.exe", "PC\\ana", null, null));
        s.vivos.put(4242L, new ProcessosDoSistema.Info(4242, "C:\\Windows\\System32\\notepad.exe", "PC\\ana", Instant.parse("2026-10-28T13:30:00Z"), null)); // outro, que ganhou o número
        Resultado r = c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.MUDOU, r.desfecho());
        assertTrue(comandos.isEmpty(), "nenhum taskkill foi rodado");
    }

    @Test
    void windowsProtegeOsDoSistema() {
        SistemaFalso s = new SistemaFalso();
        FalhaDeProcesso f = assertThrows(FalhaDeProcesso.class, () -> windows(s, new ArrayList<>(), null).preparar(new ProcessoAlvo(600, "svchost.exe", "PC\\ana", null, null)));
        assertEquals(Desfecho.PROTEGIDO, f.desfecho());
    }

    /** O sistema de verdade para ler e abrir, mas que NÃO deixa o app mandar sinal pelo número: só pelo handle da prévia. */
    private static ProcessosDoSistema soPeloHandle() {
        ProcessosDoSistema real = ProcessosDoSistema.real();
        return new ProcessosDoSistema() {
            @Override
            public Optional<Info> ler(long pid) {
                return real.ler(pid);
            }

            @Override
            public List<Info> todos() {
                return real.todos();
            }

            @Override
            public boolean pedirParaFechar(long pid) {
                throw new AssertionError("o sinal tem de ir pelo ProcessHandle da prévia, não pelo número");
            }

            @Override
            public boolean matar(long pid) {
                throw new AssertionError("o sinal tem de ir pelo ProcessHandle da prévia, não pelo número");
            }

            @Override
            public boolean estaVivo(long pid) {
                return real.estaVivo(pid);
            }

            @Override
            public Optional<ProcessHandle> abrir(long pid) {
                return real.abrir(pid);
            }

            @Override
            public Set<Integer> pidsDoApp() {
                return real.pidsDoApp();
            }
        };
    }

    @Test
    void aPreviaGuardaOHandleEOSinalVaiPorEleNuncaPeloNumero() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int pid = processos.abrirTeimoso();
        ControleLocal c = new ControleLocal(soPeloHandle(), (a, s, p) -> {
            throw new AssertionError("sem programa externo");
        }, new AmbienteDeProcessos(AmbienteDeProcessos.Tipo.COMPUTADOR_MAC_OU_LINUX, eu, Set.of(), ProcessosDoSistema.real().pidsDoApp()), ms -> Thread.sleep(Math.min(ms, 100)), 2, 3);
        Previa p = c.preparar(alvoDe(pid));
        assertEquals(pid, p.handle().pid(), "o handle foi aberto na hora de ler");
        assertEquals(Desfecho.AINDA_RODANDO, c.encerrar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertEquals(Desfecho.ENCERRADO, c.forcar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertFalse(ProcessosDeVerdade.vivo(pid));
    }

    @Test
    void forcarSemOPedidoEducadoSaidoPorEstaPreviaERecusado() throws Exception {
        assumeTrue(ShellDeTeste.temShell());
        int pid = processos.abrirTeimoso();
        ControleLocal c = unix(2, 3);
        Previa p = c.preparar(alvoDe(pid));
        Resultado r = c.forcar(p, ObservadorDeProcesso.NENHUM, new Cancelamento());
        assertEquals(Desfecho.FALHOU, r.desfecho(), r.mensagem());
        assertTrue(ProcessosDeVerdade.vivo(pid), "nada foi enviado");
        assertFalse(p.pedidoEducadoEnviado());
    }

    @Test
    void windowsTambemSoForcaDepoisDoPedidoEDizAsPalavrasDoWindows() throws Exception {
        SistemaFalso s = new SistemaFalso();
        s.vivos.put(4242L, notepad(4242));
        s.ignoraOPedido = true;
        List<List<String>> comandos = new ArrayList<>();
        ControleLocal c = windows(s, comandos, null);
        Previa p = c.preparar(new ProcessoAlvo(4242, "notepad.exe", "PC\\ana", null, null));
        assertEquals(Desfecho.FALHOU, c.forcar(p, ObservadorDeProcesso.NENHUM, new Cancelamento()).desfecho());
        assertTrue(comandos.isEmpty(), "nenhum taskkill foi rodado");
        List<String> etapas = new ArrayList<>();
        ObservadorDeProcesso obs = new ObservadorDeProcesso() {
            @Override
            public void etapa(String texto) {
                etapas.add(texto);
            }

            @Override
            public void aguardando(int passados, int total) { }
        };
        Resultado r = c.encerrar(p, obs, new Cancelamento());
        String todo = r.mensagem() + " " + String.join(" ", etapas) + " " + p.oferta().explicacao();
        assertFalse(todo.contains("SIGTERM") || todo.contains("SIGKILL") || todo.contains("Ctrl+C"), todo);
    }

    @Test
    void aHoraDeInicioVaiParaATelaEmPortugues() {
        Previa ps = new Previa(new ProcessoAlvo(4242, "sleep", "ana", null, null), new Oferta(Oferta.Situacao.LIBERADO, Via.PROCESSO, false, "", "", "", ""),
            new IdentidadeDoProcesso(4242, "1000", "Tue Oct 28 12:05:09 2026", "sleep"), null);
        assertEquals("28/10/2026 às 12:05:09", ps.iniciouEmParaATela());
        assertEquals("Tue Oct 28 12:05:09 2026", ps.iniciouEm(), "o valor que o servidor compara continua o do ps");
        Previa ruim = new Previa(ps.alvo(), ps.oferta(), new IdentidadeDoProcesso(4242, "1000", "Tue Oct 28 99:99:99 2026", "sleep"), null);
        assertEquals("Tue Oct 28 99:99:99 2026", ruim.iniciouEmParaATela(), "o que não dá para ler aparece como veio");
    }
}
