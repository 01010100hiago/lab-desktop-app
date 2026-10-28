package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.GanchosDaInstalacao;
import br.com.labdesktop.instalador.GanchosDaInstalacao.Sonda;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.Passo;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao.Desfecho;
import br.com.labdesktop.instalador.ResultadoDaRemocao;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.ComputadorSimulado.Cont;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Criar meu laboratório local" e "Remover laboratório local" com o computador simulado: passos, idempotência, desfazer e o que nunca se apaga. */
class LaboratorioLocalTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {5, 4, 3, 2, 1});

    @TempDir
    Path tmp;

    private final ComputadorSimulado pc = new ComputadorSimulado();
    private final AtomicInteger sondagens = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();
    private Sonda sonda = Sonda.RESPONDE;
    private PlataformaLocal plataforma;
    private ParametrosDoLaboratorio p;
    private Path projetos;

    private final Map<Passo, EstadoDoPasso> ultimo = new java.util.HashMap<>();
    private final List<String> log = new ArrayList<>();

    private final br.com.labdesktop.instalador.ObservadorDaInstalacao obs = new br.com.labdesktop.instalador.ObservadorDaInstalacao() {
        @Override
        public void aoMudarPasso(Passo passo, EstadoDoPasso estado, String detalhe) {
            ultimo.put(passo, estado);
        }

        @Override
        public void aoRegistrar(String linha) {
            log.add(linha);
        }
    };

    private final GanchosDaInstalacao ganchos = new GanchosDaInstalacao() {
        @Override
        public Sonda sondar() {
            sondagens.incrementAndGet();
            return sonda;
        }

        @Override
        public boolean registrarToken(br.com.labdesktop.instalador.ParametrosDaInstalacao parametros, String docker) {
            tokens.incrementAndGet();
            assertEquals("docker", docker);
            assertEquals("painel-lab", parametros.container());
            return true;
        }
    };

    @BeforeEach
    void montar() {
        plataforma = new PlataformaLocal(SistemaOperacional.MAC, tmp.toString(), Map.of());
        projetos = tmp.resolve("LabLocal").resolve("projects");
        p = ParametrosDoLaboratorio.padrao(7070, projetos.toString());
    }

    private LaboratorioLocal lab() {
        return new LaboratorioLocal(pc, p, plataforma, PACOTE, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10));
    }

    private ResultadoDaInstalacao criar() {
        return lab().criar(obs, Cancelamento.NUNCA);
    }

    private static java.util.function.Predicate<List<String>> contem(String t) {
        return ComputadorSimulado.contem(t);
    }

    @Test
    void caminhoFelizCriaPastaRedeImagemContainerEConferePorOrdem() {
        ResultadoDaInstalacao r = criar();

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertTrue(Files.isDirectory(projetos), "a pasta dos projetos nasceu (com os pais)");
        assertTrue(pc.redes.containsKey("edge"));
        assertTrue(pc.imagens.contains("labdesktop-agente:0.1.0-abc1234"));
        Cont c = pc.containers.get("painel-lab");
        assertNotNull(c);
        assertEquals("0.1.0-abc1234", c.rotulo);
        assertEquals(7070, c.portaPublicada);
        assertTrue(pc.volumes.contains("painel-lab-dados"));
        assertEquals(1, tokens.get());
        assertTrue(sondagens.get() >= 1);
        List<String> w = pc.escritas().stream().map(x -> x.get(1)).toList();
        assertEquals(List.of("network", "build", "run"), w, "só estas três escritas de Docker, nessa ordem");
        assertEquals(EstadoDoPasso.FEITO, ultimo.get(PassoDoLaboratorio.PASTA));
        assertEquals(EstadoDoPasso.FEITO, ultimo.get(PassoDoLaboratorio.CONTAINER));
        // o pacote foi pela entrada do docker build, intacto
        int i = pc.comandos.indexOf(ComandosLocais.construirImagem(p, PACOTE));
        assertEquals(List.of(5, 4, 3, 2, 1), bytesComoLista(pc.entradas.get(i)));
    }

    @Test
    void oAgenteSobeSoNoLoopbackSemRedeDoHostEComAPastaNoMesmoCaminhoDoAnfitriao() {
        criar();
        List<String> run = pc.containers.get("painel-lab").argumentos;
        assertTrue(run.contains("127.0.0.1:7070:7070"));
        assertFalse(run.contains("host") && run.indexOf("--network") >= 0);
        assertTrue(run.contains(projetos + ":" + projetos), "no Mac a pasta entra no container no MESMO caminho: " + run);
        assertTrue(run.contains("PAINEL_EXIGE_TOKEN=1"));
        assertTrue(run.contains("PAINEL_ESCUTA=todas"));
    }

    @Test
    void rodarDeNovoNaoEstragaNadaESoLe() {
        assertEquals(Desfecho.CONCLUIDA, criar().desfecho());
        pc.limparRegistro();
        ultimo.clear();

        ResultadoDaInstalacao r = criar();

        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertTrue(pc.escritas().isEmpty(), "a segunda vez só lê: " + pc.escritas());
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, ultimo.get(PassoDoLaboratorio.PASTA));
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, ultimo.get(PassoDoLaboratorio.REDE));
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, ultimo.get(PassoDoLaboratorio.IMAGEM));
        assertEquals(EstadoDoPasso.JA_ESTAVA_FEITO, ultimo.get(PassoDoLaboratorio.CONTAINER));
    }

    @Test
    void dockerParadoRecusaSemTocarEmNada() {
        pc.dockerParado = true;
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.RECUSADA, r.desfecho());
        assertTrue(r.erro().contains("Docker"));
        assertTrue(pc.escritas().isEmpty());
        assertFalse(Files.exists(projetos));
    }

    @Test
    void redeEPastaQueJaExistiamNaoSaoTocadasNemDesfeitas() throws Exception {
        Files.createDirectories(projetos);
        Files.writeString(projetos.resolve("meu-projeto.txt"), "do usuário");
        pc.redes.put("edge", ""); // uma rede edge que não é do app
        pc.falharEm(contem(" run -d "), 125, "docker: port is already allocated");

        ResultadoDaInstalacao r = criar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDoLaboratorio.CONTAINER, r.passo());
        assertTrue(r.erro().contains("port is already allocated"), r.erro());
        assertTrue(pc.redes.containsKey("edge"), "a rede que já existia fica");
        assertTrue(Files.exists(projetos.resolve("meu-projeto.txt")), "os arquivos do usuário nunca são tocados");
        assertFalse(pc.imagens.contains("labdesktop-agente:0.1.0-abc1234"), "a imagem que o app construiu sai");
        assertTrue(r.desfeito().contains("Removi a imagem que o app construiu."), r.desfeito().toString());
    }

    @Test
    void falhaNoMeioDesfazSoOQueEstaExecucaoCriou() {
        pc.falharEm(contem(" run -d "), 125, "docker: Error response from daemon: port is already allocated");
        ResultadoDaInstalacao r = criar();

        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertFalse(pc.redes.containsKey("edge"), "a rede criada por esta execução sai");
        assertFalse(pc.imagens.contains("labdesktop-agente:0.1.0-abc1234"));
        assertFalse(Files.exists(projetos), "a pasta que o app criou, vazia, sai");
        assertFalse(Files.exists(projetos.getParent()), "e a pasta pai que ele também criou");
        assertEquals(EstadoDoPasso.DESFEITO, ultimo.get(PassoDoLaboratorio.PASTA));
        assertEquals(EstadoDoPasso.DESFEITO, ultimo.get(PassoDoLaboratorio.REDE));
        assertEquals(EstadoDoPasso.DESFEITO, ultimo.get(PassoDoLaboratorio.IMAGEM));
        assertTrue(tmp.toFile().isDirectory(), "a pasta pessoal obviamente fica");
    }

    @Test
    void agenteQueNaoRespondeMostraOsLogsEDesfazOContainer() {
        sonda = Sonda.NAO_RESPONDE;
        pc.logsDoContainer = "Error: listen EADDRINUSE: address already in use 0.0.0.0:7070";
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDoLaboratorio.SONDAR, r.passo());
        assertTrue(r.erro().contains("EADDRINUSE"), r.erro());
        assertTrue(r.erro().contains("127.0.0.1:7070"));
        assertTrue(pc.containers.isEmpty(), "o container que esta execução criou saiu");
        assertFalse(pc.volumes.contains("painel-lab-dados"), "e os dados novos também");
    }

    @Test
    void falhaAoRegistrarOTokenDesfazTudo() {
        GanchosDaInstalacao ruim = new GanchosDaInstalacao() {
            @Override
            public Sonda sondar() {
                return Sonda.RESPONDE;
            }

            @Override
            public boolean registrarToken(br.com.labdesktop.instalador.ParametrosDaInstalacao parametros, String docker) throws Exception {
                throw new Exception("token_conflito: Já existe um token com esse nome.");
            }
        };
        ResultadoDaInstalacao r = new LaboratorioLocal(pc, p, plataforma, PACOTE, ruim).criar(obs, Cancelamento.NUNCA);
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals(PassoDoLaboratorio.TOKEN, r.passo());
        assertTrue(r.erro().contains("token_conflito"));
        assertTrue(pc.containers.isEmpty());
    }

    @Test
    void containerDeOutroDonoNuncaETocado() {
        Cont alheio = new Cont();
        alheio.rotulo = ""; // sem o rótulo do app
        pc.containers.put("painel-lab", alheio);
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(r.erro().contains("não foi criado por este app"), r.erro());
        assertTrue(pc.containers.containsKey("painel-lab"));
        assertTrue(pc.escritas().stream().noneMatch(c -> c.contains("rename") || c.equals(ComandosLocais.removerContainer("painel-lab"))), pc.escritas().toString());
    }

    @Test
    void cancelarNoMeioDesfazENaoSegueParaOsProximosPassos() {
        int[] chamadas = {0};
        ResultadoDaInstalacao r = lab().criar(obs, () -> ++chamadas[0] > 4);
        assertEquals(Desfecho.CANCELADA, r.desfecho());
        assertTrue(pc.containers.isEmpty());
        assertEquals(0, tokens.get());
    }

    @Test
    void interromperAThreadNoBuildCancelaEDesfaz() throws Exception {
        pc.antesDe(contem(" build "), () -> Thread.currentThread().interrupt());
        ResultadoDaInstalacao[] r = new ResultadoDaInstalacao[1];
        Thread t = new Thread(() -> r[0] = criar());
        t.start();
        t.join(10_000);
        assertEquals(Desfecho.CANCELADA, r[0].desfecho(), r[0].erro());
        assertFalse(pc.redes.containsKey("edge"), "a rede criada antes do build foi desfeita");
    }

    @Test
    void interromperADuranteODesfazerNaoAbortaALimpeza() throws Exception {
        pc.falharEm(contem(" run -d "), 125, "docker: port is already allocated");
        pc.antesDe(contem(" rmi "), () -> Thread.currentThread().interrupt()); // outro Cancelar no meio da limpeza
        ResultadoDaInstalacao[] r = new ResultadoDaInstalacao[1];
        boolean[] flag = new boolean[1];
        Thread t = new Thread(() -> {
            r[0] = criar();
            flag[0] = Thread.currentThread().isInterrupted();
        });
        t.start();
        t.join(10_000);
        assertEquals(Desfecho.FALHOU, r[0].desfecho());
        assertFalse(pc.redes.containsKey("edge"), "a rede (depois da imagem) saiu mesmo com a interrupção: " + r[0].desfeito());
        assertFalse(Files.exists(projetos));
        assertTrue(flag[0]);
    }

    @Test
    void atualizarGuardaOAgenteAntigoEDevolveSeONovoFalha() {
        Cont velho = new Cont();
        velho.rotulo = "0.0.9-velha";
        velho.rotuloLocal = "1";
        pc.containers.put("painel-lab", velho);
        pc.volumes.add("painel-lab-dados");
        pc.redes.put("edge", "1");
        pc.imagens.add("labdesktop-agente:0.1.0-abc1234");
        try {
            Files.createDirectories(projetos);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        sonda = Sonda.NAO_RESPONDE;
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertEquals("0.0.9-velha", pc.containers.get("painel-lab").rotulo, "o agente antigo voltou ao nome");
        assertEquals("running", pc.containers.get("painel-lab").estado, "e ao ar");
        assertNull(pc.containers.get("painel-lab-anterior"));
        assertTrue(pc.volumes.contains("painel-lab-dados"), "os dados já existiam e ficam");
        assertTrue(r.desfeito().stream().anyMatch(l -> l.contains("Devolvi o agente anterior")), r.desfeito().toString());
    }

    @Test
    void atualizarQueDaCertoRemoveOAntigoSoNoFim() {
        Cont velho = new Cont();
        velho.rotulo = "0.0.9-velha";
        velho.rotuloLocal = "1";
        pc.containers.put("painel-lab", velho);
        pc.volumes.add("painel-lab-dados");
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.CONCLUIDA, r.desfecho(), r.erro());
        assertEquals("0.1.0-abc1234", pc.containers.get("painel-lab").rotulo);
        assertNull(pc.containers.get("painel-lab-anterior"), "o antigo saiu no fim");
        int run = -1;
        for (int i = 0; i < pc.comandos.size(); i++) {
            if (pc.comandos.get(i).contains("run")) {
                run = i;
            }
        }
        assertTrue(pc.comandos.indexOf(ComandosLocais.removerContainer("painel-lab-anterior")) > run, "só depois de o novo estar de pé");
    }

    // ---------------------------------------------------------------------------------------------
    // Remover
    // ---------------------------------------------------------------------------------------------

    @Test
    void removerTiraOAgenteOsDadosAImagemEARedeMasNuncaOsProjetosDoDono() throws Exception {
        assertEquals(Desfecho.CONCLUIDA, criar().desfecho());
        Files.writeString(projetos.resolve("meu-projeto.txt"), "do usuário");
        pc.limparRegistro();

        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);

        assertTrue(r.removido());
        assertFalse(r.recusada());
        assertTrue(pc.containers.isEmpty());
        assertFalse(pc.volumes.contains("painel-lab-dados"));
        assertFalse(pc.imagens.contains("labdesktop-agente:0.1.0-abc1234"));
        assertFalse(pc.redes.containsKey("edge"), "a rede é do app e estava vazia");
        assertTrue(Files.exists(projetos.resolve("meu-projeto.txt")), "os projetos do usuário NUNCA são apagados");
        assertTrue(r.feito().stream().anyMatch(f -> f.contains("continuam onde estavam")), r.feito().toString());
        assertTrue(r.avisos().isEmpty());
    }

    @Test
    void removerNaoMexeNaRedeQueNaoEDoAppNemNaQueTemProjetosLigados() throws Exception {
        assertEquals(Desfecho.CONCLUIDA, criar().desfecho());
        pc.containersNaRede.put("edge", 2); // projetos ligados à rede
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertTrue(pc.redes.containsKey("edge"));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("ainda tem containers")), r.avisos().toString());

        pc.redes.put("edge", ""); // agora a rede não é do app
        pc.containersNaRede.put("edge", 0);
        ResultadoDaRemocao r2 = lab().remover(obs, Cancelamento.NUNCA);
        assertTrue(pc.redes.containsKey("edge"));
        assertTrue(r2.avisos().stream().anyMatch(a -> a.contains("não foi criada por este app")), r2.avisos().toString());
    }

    @Test
    void removerRecusaOContainerQueNaoEDoApp() throws Exception {
        Cont alheio = new Cont();
        pc.containers.put("painel-lab", alheio);
        pc.volumes.add("painel-lab-dados");
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertTrue(r.recusada());
        assertFalse(r.removido());
        assertTrue(pc.escritas().isEmpty(), "nada foi tocado: " + pc.escritas());
        assertTrue(pc.containers.containsKey("painel-lab"));
    }

    @Test
    void removerSemContainerDoAppNaoApagaVolumeNemImagem() throws Exception {
        pc.volumes.add("painel-lab-dados"); // de outra instalação
        pc.imagens.add("labdesktop-agente:0.1.0-abc1234");
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertTrue(pc.volumes.contains("painel-lab-dados"));
        assertTrue(pc.imagens.contains("labdesktop-agente:0.1.0-abc1234"));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("deixei os dados e a imagem")), r.avisos().toString());
    }

    @Test
    void removerComODockerParadoNaoFazNada() throws Exception {
        pc.dockerParado = true;
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertFalse(r.removido());
        assertFalse(r.avisos().isEmpty());
        assertTrue(pc.escritas().isEmpty());
    }

    @Test
    void removerApagaAImagemDaVersaoQueEstavaInstalada() throws Exception {
        Cont velho = new Cont();
        velho.rotulo = "0.0.9-velha";
        velho.rotuloLocal = "1";
        pc.containers.put("painel-lab", velho);
        pc.volumes.add("painel-lab-dados");
        pc.imagens.add("labdesktop-agente:0.0.9-velha");
        lab().remover(obs, Cancelamento.NUNCA);
        assertFalse(pc.imagens.contains("labdesktop-agente:0.0.9-velha"));
    }

    private static List<Integer> bytesComoLista(byte[] b) {
        List<Integer> l = new ArrayList<>();
        for (byte x : b) {
            l.add((int) x);
        }
        return l;
    }

    // ----- as leituras do Docker que falham são ERRO, nunca "não existe" -----

    @Test
    void leituraQueFalhaAoCriarEErroENaoViraNaoExisteNemCriaNada() {
        for (String leitura : List.of("network ls", "ps -a", "volume ls")) {
            pc.consertar();
            pc.falharEm(contem(leitura), 1, "permission denied while trying to connect to the Docker daemon socket");
            ResultadoDaInstalacao r = criar();
            assertEquals(Desfecho.FALHOU, r.desfecho(), leitura + ": " + r.erro());
            assertTrue(r.erro().contains("Não consegui ler o estado do Docker"), r.erro());
            assertFalse(pc.containers.containsKey("painel-lab"), "sem ler o estado não se cria o container (" + leitura + ")");
            pc.containers.clear();
            pc.redes.clear();
            pc.imagens.clear();
            pc.volumes.clear();
        }
    }

    @Test
    void leituraDaImagemQueFalhaPorOutroMotivoNaoViraImagemAusente() {
        pc.falharEm(contem("image inspect"), 1, "Cannot connect to the Docker daemon");
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(pc.escritas().stream().noneMatch(c -> c.get(1).equals("build")), "não construiu por achar que a imagem não existia: " + pc.escritas());
    }

    @Test
    void removerComALeituraDoContainerFalhandoNaoDizRemovidoENaoApagaNada() throws Exception {
        assertEquals(Desfecho.CONCLUIDA, criar().desfecho());
        pc.limparRegistro();
        pc.falharEm(contem("ps -a"), 1, "Error response from daemon: boom");
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertFalse(r.removido());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Não consegui ler o estado do Docker")), r.avisos().toString());
        assertTrue(pc.escritas().isEmpty(), "nada foi apagado: " + pc.escritas());
        assertTrue(pc.containers.containsKey("painel-lab"));
    }

    @Test
    void removerComALeituraDaRedeFalhandoRemoveOAgenteMasNaoMentePelaRede() throws Exception {
        assertEquals(Desfecho.CONCLUIDA, criar().desfecho());
        pc.falharEm(contem("network ls"), 1, "boom");
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertTrue(r.removido(), "o agente saiu");
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Não consegui ler o estado do Docker")), r.avisos().toString());
        assertTrue(pc.redes.containsKey("edge"), "a rede ficou: a leitura falhou");
    }

    // ----- M1: o rótulo do instalador por SSH não basta; é preciso o rótulo local -----

    private Cont painelInstaladoPorSsh() {
        Cont c = new Cont();
        c.rotulo = "0.1.0-abc1234"; // o instalador por SSH grava só o rótulo do agente
        c.rotuloLocal = "";
        c.imagem = "labdesktop-agente:0.1.0-abc1234";
        pc.containers.put("painel-lab", c);
        pc.volumes.add("painel-lab-dados");
        pc.imagens.add("labdesktop-agente:0.1.0-abc1234");
        return c;
    }

    @Test
    void umPainelInstaladoPorSshNumDebianUsadoComoEsteComputadorNuncaETrocado() {
        painelInstaladoPorSsh();
        ResultadoDaInstalacao r = criar();
        assertEquals(Desfecho.FALHOU, r.desfecho());
        assertTrue(r.erro().contains("não foi criado por este app"), r.erro());
        assertTrue(pc.containers.containsKey("painel-lab"), "o painel do SSH continua lá");
        assertTrue(pc.escritas().stream().noneMatch(c -> c.get(1).equals("rm") || c.get(1).equals("rename") || c.get(1).equals("stop")),
            "nada foi removido, renomeado nem parado: " + pc.escritas());
    }

    @Test
    void removerNaoTocaNoPainelInstaladoPorSshNemNosDadosNemNaImagemDele() throws Exception {
        painelInstaladoPorSsh();
        ResultadoDaRemocao r = lab().remover(obs, Cancelamento.NUNCA);
        assertTrue(r.recusada());
        assertTrue(pc.containers.containsKey("painel-lab"));
        assertTrue(pc.volumes.contains("painel-lab-dados"));
        assertTrue(pc.imagens.contains("labdesktop-agente:0.1.0-abc1234"));
        assertTrue(pc.escritas().isEmpty(), pc.escritas().toString());
    }

    @Test
    void oContainerQueOAppCriaLevaOsDoisRotulos() {
        criar();
        Cont c = pc.containers.get("painel-lab");
        assertEquals("0.1.0-abc1234", c.rotulo);
        assertEquals("1", c.rotuloLocal);
    }
}
