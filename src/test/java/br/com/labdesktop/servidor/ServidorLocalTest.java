package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.ClienteSftpLocal;
import br.com.labdesktop.cofre.AgenteInstalado;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.local.ConfigLocal;
import br.com.labdesktop.local.ParametrosDoLaboratorio;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.local.SistemaOperacional;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import br.com.labdesktop.terminal.TransporteLocal;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O servidor "Este computador" ponta a ponta com o que o app tem de verdade (cofre, {@code ServidorLab}, {@code ClienteApi} em HTTP real contra o
 * agente falso), e o computador simulado no lugar do Docker: sem SSH, sem túnel, sem chave. Prova que as mesmas telas funcionam trocando só o
 * transporte.
 */
class ServidorLocalTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {5, 4, 3, 2, 1});

    @TempDir
    Path tmp;

    private AgenteDoLab agente;
    private ComputadorSimulado pc;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private ServidorLab lab;

    @BeforeEach
    void montar() throws Exception {
        agente = new AgenteDoLab();
        agente.fora = true;
        pc = new ComputadorSimulado();
        pc.execDoAgente = agente.comandosPorSsh();
        pc.antesDe(c -> c.contains("run") && c.contains("-d"), () -> {
            agente.perfil = "monitor";
            agente.fora = false;
        });
        PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.MAC, tmp.toString(), Map.of());
        ServicosLocais locais = new ServicosLocais(pc, plataforma, new SondaFalsa(), ConfigLocal.rapida());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), locais);
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        lab = assistente.cadastrarLocal("Este computador", agente.porta());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
    }

    private void aguardar(EstadoConexao e) {
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == e);
    }

    private ResultadoDaInstalacao criarLaboratorio() {
        ParametrosDoLaboratorio p = ParametrosDoLaboratorio.padrao(agente.porta(), tmp.resolve("LabLocal/projects").toString());
        return lab.laboratorioLocal(p, PACOTE).comSonda(Duration.ofSeconds(10), Duration.ofMillis(50)).criar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA);
    }

    @Test
    void cadastrarLocalNaoTemSshNemChaveENemTunelEEUmSo() throws Exception {
        assertTrue(lab.ehLocal());
        ConfigServidor c = lab.config();
        assertTrue(c.ehLocal());
        assertNull(c.chaveId());
        assertTrue(cofre.chaves().isEmpty());
        assertEquals(agente.porta(), c.portaAgente());
        assertSame(lab, assistente.cadastrarLocal("Outro nome", 7071), "não cria um segundo");
        assertEquals(1, cofre.servidores().size());
    }

    private static void assertSame(Object a, Object b, String msg) {
        org.junit.jupiter.api.Assertions.assertSame(a, b, msg);
    }

    @Test
    void semOLaboratorioACaixaDiagnosticaCadaCausaComALeituraCerta() {
        lab.iniciar();
        aguardar(EstadoConexao.SSH_OK_SEM_AGENTE);
        Espera.ate(PRAZO, () -> "situação=" + lab.estado().situacaoDoAgente(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.LOCAL_NAO_CRIADO);
        assertTrue(lab.estado().situacaoDoAgente().oferecerPreparar());
        assertTrue(lab.estado().situacaoDoAgente().oferecerInstalar(), "o botão do aviso existe (o rótulo é Preparar este computador)");
        assertTrue(pc.escritas().isEmpty(), "só LEITURAS enquanto o usuário não clica: " + pc.escritas());

        pc.dockerParado = true;
        lab.tentarAgora();
        Espera.ate(PRAZO, () -> "situação=" + lab.estado().situacaoDoAgente(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.LOCAL_DOCKER_PARADO);

        pc.dockerParado = false;
        pc.dockerInstalado = false;
        lab.tentarAgora();
        Espera.ate(PRAZO, () -> "situação=" + lab.estado().situacaoDoAgente(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.LOCAL_SEM_DOCKER);
        assertTrue(pc.escritas().isEmpty());
    }

    @Test
    void criarOLaboratorioSobeORegistraOTokenEOServidorPassaAConectadoComOAgenteDoMonitor() throws Exception {
        lab.iniciar();
        aguardar(EstadoConexao.SSH_OK_SEM_AGENTE);

        ResultadoDaInstalacao r = criarLaboratorio();

        assertTrue(r.concluida(), r.erro());
        assertEquals(1, agente.tokens.size(), "o token deste app foi registrado no agente (docker exec, sem shell)");
        assertTrue(cofre.tokenDoServidor(lab.id()).isPresent(), "e guardado no cofre");
        assertEquals(new AgenteInstalado("painel-lab", false), cofre.agenteInstalado(lab.id()).orElseThrow());
        for (String l : agente.comandosCli) {
            assertTrue(l.startsWith("docker exec -i painel-lab node acesso.js "), l);
        }
        lab.aoMudarOAgenteNoServidor();
        aguardar(EstadoConexao.CONECTADO);
        assertTrue(lab.estado().agenteDoMonitor());
        assertEquals(Boolean.FALSE, lab.estado().temClaude());
        assertEquals(SituacaoDoAgente.DESCONHECIDA, lab.estado().situacaoDoAgente());
        assertEquals(agente.porta(), lab.tunel().orElseThrow().portaLocal(), "sem túnel: a ponta é a própria porta do Docker em 127.0.0.1");
    }

    @Test
    void asTelasPedemOTerminalEOsArquivosAoServidorEAcamadaDeSudoNaoExiste() {
        assertInstanceOf(TransporteLocal.class, lab.transporteDeTerminal(() -> false));
        assertInstanceOf(ClienteSftpLocal.class, lab.fabricaDeArquivos().criar());
        assertNull(lab.executorDeSudo(), "\"Salvar com sudo\" não existe no computador local");
    }

    @Test
    void oInstaladorRemotoNaoSeAplicaAoLocal() {
        assertThrows(IllegalStateException.class, () -> lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7070), PACOTE));
    }

    @Test
    void agenteQueMorreDepoisDeConectadoVoltaAParaSemAgenteEOStatusDizOCaminho() throws Exception {
        lab.iniciar();
        aguardar(EstadoConexao.SSH_OK_SEM_AGENTE);
        assertTrue(criarLaboratorio().concluida());
        lab.aoMudarOAgenteNoServidor();
        aguardar(EstadoConexao.CONECTADO);

        agente.fora = true;
        pc.containers.clear(); // o container sumiu
        lab.conexao().verificarAgora();
        aguardar(EstadoConexao.SSH_OK_SEM_AGENTE);
        Espera.ate(PRAZO, () -> "situação=" + lab.estado().situacaoDoAgente(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.LOCAL_NAO_CRIADO);
        Espera.ate(PRAZO, () -> "registro=" + cofre.agenteInstalado(lab.id()), () -> cofre.agenteInstalado(lab.id()).isEmpty());

        agente.fora = false;
        aguardar(EstadoConexao.CONECTADO);
    }

    @Test
    void removerOServidorLocalRevogaOTokenSemChaveENaoMexeNoLaboratorio() throws Exception {
        lab.iniciar();
        aguardar(EstadoConexao.SSH_OK_SEM_AGENTE);
        assertTrue(criarLaboratorio().concluida());
        lab.aoMudarOAgenteNoServidor();
        aguardar(EstadoConexao.CONECTADO);
        assertEquals(1, agente.tokens.size());

        GerenciadorServidores.ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new GerenciadorServidores.OpcoesDeRemocao(false, true));

        assertTrue(r.existia());
        assertTrue(r.tokenRevogado(), r.avisos().toString());
        assertFalse(r.chaveTiradaDoServidor());
        assertEquals(0, agente.tokens.size());
        assertTrue(pc.containers.containsKey("painel-lab"), "tirar o servidor do app NÃO remove o laboratório (isso é \"Remover laboratório local\")");
        assertTrue(cofre.servidores().isEmpty());
    }
}
