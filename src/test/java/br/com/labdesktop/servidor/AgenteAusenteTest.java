package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.servidor.AssistenteDeServidor.Conclusao;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.AssistenteDeServidor.Etapa;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A regra "NADA SE INSTALA SOZINHO", e o fluxo "perguntar, nunca erro": conectar a um servidor SEM agente só executa LEITURAS
 * por SSH (a pergunta "o agente está instalado?"), o assistente termina na pergunta sem aviso de falha e sem registrar token, e o
 * servidor fica usável só por SSH. E o que muda quando o agente diz que é do perfil monitor (sem Claude, sem limites).
 */
class AgenteAusenteTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String DISPOSITIVO = "mac-de-teste";
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        agente.fora = true; // o servidor responde por SSH e não há agente escutando
        agente.diagnostico = "ausente";
        ssh.comandoRemoto(agente.comandosPorSsh());
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    private ServidorLab cadastrar() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        ServidorLab lab = assistente.cadastrar(new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", agente.porta()), chave.id(), sonda.pin());
        ssh.aceitar(cofre.keyPairDoServidor(lab.id()).getPublic());
        return lab;
    }

    private void assertSoLeituras() {
        for (String c : ssh.comandos) {
            assertTrue(ComandosRemotos.ehLeitura(c), "conectar a um servidor sem agente executou um comando que NÃO é de leitura: " + c);
        }
    }

    @Test
    void conectarAUmServidorSemAgenteNaoExecutaNenhumaEscritaSoAPerguntaDeLeitura() throws Exception {
        ServidorLab lab = cadastrar();
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);

        assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, lab.estado().conexao());
        assertTrue(lab.estado().situacaoDoAgente().oferecerInstalar());
        assertEquals(1, agente.comandosDeDiagnostico.size(), "UMA pergunta de leitura: " + agente.comandosDeDiagnostico);
        Thread.sleep(1500); // dá tempo de qualquer rotina de fundo fazer algo que não devia
        assertSoLeituras();
        assertTrue(agente.comandosCli.isEmpty(), "nenhum comando do agente (registrar acesso, 2FA): " + agente.comandosCli);
        assertTrue(agente.tokens.isEmpty());
        assertTrue(lab.estado().semToken(), "nada foi gravado no cofre");
        assertEquals(1, agente.comandosDeDiagnostico.size(), "a pergunta não se repete a cada tentativa de reconexão");
    }

    @Test
    void servidorComAgenteInstaladoMasSemRespostaNaoOferecePorInstalar() throws Exception {
        agente.diagnostico = "existe:running"; // o agente está lá, só não responde (reiniciando, travado)
        ServidorLab lab = cadastrar();
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.INSTALADO_SEM_RESPOSTA);
        assertFalse(lab.estado().situacaoDoAgente().oferecerInstalar());
        assertSoLeituras();
    }

    @Test
    void semDockerOuSemPermissaoTambemOferecemInstalarEExplicam() throws Exception {
        agente.diagnostico = "sem-docker";
        ServidorLab lab = cadastrar();
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);

        assertEquals(SituacaoDoAgente.NAO_INSTALADO, LeitorDaSituacaoDoAgente.interpretar("sem-docker"));
        assertEquals(SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER, LeitorDaSituacaoDoAgente.interpretar("sem-permissao"));
        assertTrue(SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER.oferecerInstalar());
        assertEquals(SituacaoDoAgente.DESCONHECIDA, LeitorDaSituacaoDoAgente.interpretar("erro"));
        assertEquals(SituacaoDoAgente.DESCONHECIDA, LeitorDaSituacaoDoAgente.interpretar("{\"ok\":false}"));
        assertEquals(SituacaoDoAgente.INSTALADO_SEM_RESPOSTA, LeitorDaSituacaoDoAgente.interpretar("existe:exited"));
    }

    @Test
    void oAssistenteTerminaNaPerguntaSemErroSemFalhaESemRegistrarOToken() throws Exception {
        ServidorLab lab = cadastrar();
        List<Etapa> etapas = new ArrayList<>();

        Conclusao c = assistente.concluir(lab, DISPOSITIVO, etapas::add); // não lança: não é erro

        assertEquals(Conclusao.AGENTE_NAO_INSTALADO, c);
        assertEquals(List.of(Etapa.CONECTANDO), etapas, "não chegou a registrar token, testar o agente nem ler limites");
        assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, lab.estado().conexao(), "o SSH segue de pé: terminal, Segurança e Arquivos funcionam");
        assertSoLeituras();
        assertTrue(agente.comandosCli.isEmpty(), "o registro do token só roda depois que o agente existir: " + agente.comandosCli);
        assertTrue(agente.tokens.isEmpty());
        assertTrue(lab.estado().semToken());
        assertEquals(1, gerenciador.servidores().size(), "o servidor ficou no cofre: o usuário pode usá-lo só por SSH");
        assertTrue(cofre.servidor(lab.id()).isPresent());
    }

    @Test
    void depoisQueOAgenteApareceOMesmoAssistenteConclui() throws Exception {
        ServidorLab lab = cadastrar();
        assertEquals(Conclusao.AGENTE_NAO_INSTALADO, assistente.concluir(lab, DISPOSITIVO, e -> { }));

        agente.fora = false; // o usuário (ou o instalador) pôs o agente no ar
        agente.diagnostico = "existe:running";
        Conclusao c = assistente.concluir(lab, DISPOSITIVO, e -> { });

        assertEquals(Conclusao.PRONTO, c);
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(1, agente.tokens.size());
        assertTrue(agente.comandosCli.stream().anyMatch(l -> l.endsWith("token-registrar")));
    }

    // ---------------------------------------------------------------------------------------------
    // recursos: o que o agente diz que o servidor tem
    // ---------------------------------------------------------------------------------------------

    @Test
    void agenteMonitorNaoTemClaudeNemLimitesEOAssistentePulaALeituraDosLimites() throws Exception {
        agente.fora = false;
        agente.perfil = "monitor";
        ServidorLab lab = cadastrar();
        List<Etapa> etapas = new ArrayList<>();

        assertEquals(Conclusao.PRONTO, assistente.concluir(lab, DISPOSITIVO, etapas::add));

        assertEquals(List.of(Etapa.CONECTANDO, Etapa.REGISTRANDO_TOKEN, Etapa.TESTANDO_AGENTE, Etapa.PRONTO), etapas, "sem 'lendo os limites'");
        EstadoServidor e = lab.estado();
        assertEquals(Boolean.FALSE, e.temClaude());
        assertFalse(e.temProjetos());
        assertTrue(e.agenteDoMonitor());
        assertEquals("monitor", e.versao().perfil());
        Thread.sleep(1200);
        assertEquals(0, agente.chamadasDeLimites.get(), "o tópico de limites não é consultado (o monitor responderia 404 a cada minuto)");
        assertNull(e.limites());
    }

    @Test
    void agenteDoLabTemClaudeEProjetosELimites() throws Exception {
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = cadastrar();
        assertEquals(Conclusao.PRONTO, assistente.concluir(lab, DISPOSITIVO, e -> { }));
        EstadoServidor e = lab.estado();
        assertEquals(Boolean.TRUE, e.temClaude());
        assertTrue(e.temProjetos());
        assertFalse(e.agenteDoMonitor());
        assertTrue(agente.chamadasDeLimites.get() >= 1);
        assertTrue(e.limites() != null);
    }

    @Test
    void agenteAntigoSemRecursosMantemOComportamentoDeAntes() throws Exception {
        agente.fora = false;
        agente.perfil = null; // não manda perfil nem recursos
        ServidorLab lab = cadastrar();
        assertEquals(Conclusao.PRONTO, assistente.concluir(lab, DISPOSITIVO, e -> { }));
        EstadoServidor e = lab.estado();
        assertNull(e.temClaude(), "desconhecido: quem pergunta decide o que assume (hoje, a presença de sessao no detalhe)");
        assertTrue(e.temProjetos(), "desconhecido nunca esconde a aba Projetos");
        assertFalse(e.agenteDoMonitor());
        assertTrue(e.limites() != null, "os limites seguem sendo lidos como antes");
    }
}
