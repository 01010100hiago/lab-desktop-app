package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.GerenciadorDePacotes;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.instalador.ModoDaInstalacao;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaVerificacao;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.ProtecaoDoServidor.ServidorProtegidoException;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSimulado;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Servidor protegido" no coração do app, com o SSH embutido do repo, um agente falso e o cofre em arquivo temporário (nunca o cofre real nem um
 * servidor real): a operação do gerenciador, a guarda única do {@link ServidorLab}, o reconhecimento do agente do lab instalado por fora (marca automática, uma
 * vez, e o usuário que desmarca) e o instalador recusando de verdade quando um agente do lab responde.
 */
class ServidorProtegidoNoServidorLabTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {5, 4, 3, 2, 1, 0, 1, 2, 3});

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private ServidorSimulado simulado;
    private CofreDeChaves cofre;
    private CaminhosApp caminhos;
    private GerenciadorServidores gerenciador;
    private final List<String> avisosDaMarcaAutomatica = new CopyOnWriteArrayList<>();
    private final List<List<ServidorLab>> listas = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Path pessoal = tmp.resolve("pessoal");
        Files.createDirectories(pessoal);
        simulado = new ServidorSimulado(ParametrosDaInstalacao.padrao(7000), PACOTE, pessoal);
        simulado.noGrupoDocker = true;
        agente = new AgenteDoLab();
        agente.fora = true;
        agente.diagnostico = "ausente";
        ssh = ServidorSshDeTeste.ed25519().comSftp(pessoal);
        ssh.comandoRemoto(simulado.comoManipulador(agente.comandosPorSsh()));
        caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = novoGerenciador();
    }

    private GerenciadorServidores novoGerenciador() {
        GerenciadorServidores g = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        g.observarProtecaoAutomatica(lab -> avisosDaMarcaAutomatica.add(lab.id()));
        g.observarLista(listas::add);
        return g;
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    private ServidorLab servidor(boolean protegido) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
        cofre.adicionarChave(chave);
        ssh.aceitar(chave.paraKeyPair().getPublic());
        TokenAgente token = TokenAgente.gerar();
        agente.tokens.put(token.hashSha256Hex(), "mac-vps");
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), "vps-1", "127.0.0.1", ssh.porta(), "labuser", chave.id(), agente.porta(),
            List.of(ssh.pin()), Transporte.DIRETO, "mac-vps", Estado2fa.DESLIGADO, protegido, false);
        cofre.adicionarServidor(cfg, token);
        ServidorLab lab = gerenciador.adicionar(cfg.id());
        lab.iniciar();
        return lab;
    }

    private ConfigServidor noCofre(ServidorLab lab) {
        return cofre.servidor(lab.id()).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------
    // A operação do gerenciador
    // ---------------------------------------------------------------------------------------------

    @Test
    void definirProtegidoPersisteAtualizaOEstadoEAvisaQuemObservaALista() throws Exception {
        ServidorLab lab = servidor(false);
        assertFalse(lab.estado().protegido());
        assertFalse(lab.protegido());
        int antes = listas.size();

        assertTrue(gerenciador.definirProtegido(lab.id(), true));

        assertTrue(noCofre(lab).protegido(), "gravado no cofre");
        Espera.ate(PRAZO, "o estado passou a protegido", () -> lab.estado().protegido());
        assertTrue(lab.protegido());
        Espera.ate(PRAZO, "a lista foi avisada", () -> listas.size() > antes);
        assertFalse(gerenciador.definirProtegido(lab.id(), true), "ligar de novo não muda nada");

        int meio = listas.size();
        assertTrue(gerenciador.definirProtegido(lab.id(), false));
        assertFalse(noCofre(lab).protegido());
        assertTrue(noCofre(lab).protecaoAutomaticaRecusada(), "o usuário desligou uma proteção ligada");
        Espera.ate(PRAZO, "o estado voltou a desprotegido", () -> !lab.estado().protegido());
        Espera.ate(PRAZO, "a lista foi avisada de novo", () -> listas.size() > meio);
    }

    @Test
    void definirProtegidoNaoReconecta() throws Exception {
        agente.fora = false;
        agente.perfil = "monitor";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        long geracao = lab.estado().geracao();

        gerenciador.definirProtegido(lab.id(), true);

        Espera.ate(PRAZO, "marcou", () -> lab.estado().protegido());
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao(), "a conexão seguiu de pé");
        assertEquals(geracao, lab.estado().geracao(), "e não houve reconexão");
    }

    @Test
    void oEditarGravaAMarcaSemReconectarEDadosSemACaixaNaoMexemNela() throws Exception {
        agente.fora = false;
        agente.perfil = "monitor";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        long geracao = lab.estado().geracao();
        ConfigServidor c = noCofre(lab);
        Dados mesmos = new Dados(c.nome(), c.host(), c.porta(), c.usuario(), c.portaAgente(), true);

        GerenciadorServidores.ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), mesmos);

        assertTrue(r.mudouAlgo());
        assertTrue(r.config().protegido());
        assertTrue(noCofre(lab).protegido());
        Espera.ate(PRAZO, "o estado ficou protegido", () -> lab.estado().protegido());
        assertEquals(geracao, lab.estado().geracao(), "só a marca mudou: nada reconectou");

        // quem monta os dados sem perguntar (cinco argumentos) nunca desliga a proteção por esquecimento
        GerenciadorServidores.ResultadoDaEdicao igual = gerenciador.atualizar(lab.id(), new Dados(c.nome(), c.host(), c.porta(), c.usuario(), c.portaAgente()));
        assertFalse(igual.mudouAlgo());
        assertTrue(noCofre(lab).protegido());

        // mudar o nome sem tocar na caixa continua protegido e esta sim reconecta
        GerenciadorServidores.ResultadoDaEdicao renomeado = gerenciador.atualizar(lab.id(), new Dados("outro-nome", c.host(), c.porta(), c.usuario(), c.portaAgente()));
        assertTrue(renomeado.mudouAlgo());
        assertTrue(noCofre(lab).protegido());
        assertEquals("outro-nome", noCofre(lab).nome());
        Espera.ate(PRAZO, "reconectou", () -> lab.estado().geracao() > geracao);

        // desligar pela caixa
        gerenciador.atualizar(lab.id(), new Dados("outro-nome", c.host(), c.porta(), c.usuario(), c.portaAgente(), false));
        assertFalse(noCofre(lab).protegido());
        assertTrue(noCofre(lab).protecaoAutomaticaRecusada());
    }

    @Test
    void cadastrarComACaixaMarcadaJaNasceProtegidoAntesDeQualquerConexao() throws Exception {
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        var chave = assistente.gerarChave("lab");
        var sonda = assistente.sondar("127.0.0.1", ssh.porta());

        ServidorLab protegido = assistente.cadastrar(new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", agente.porta(), true), chave.id(), sonda.pin());
        assertTrue(noCofre(protegido).protegido());
        assertTrue(protegido.protegido());
        assertTrue(protegido.estado().protegido(), "o primeiro retrato já é protegido: a primeira conexão nunca chega a perguntar");

        ServidorLab comum = assistente.cadastrar(new Dados("lab2", "127.0.0.2", ssh.porta(), "labuser", agente.porta()), chave.id(), sonda.pin());
        assertFalse(noCofre(comum).protegido(), "sem a caixa, desprotegido (padrão)");
        ServidorLab explicito = assistente.cadastrar(new Dados("lab3", "127.0.0.3", ssh.porta(), "labuser", agente.porta(), false), chave.id(), sonda.pin());
        assertFalse(noCofre(explicito).protegido());
    }

    @Test
    void esteComputadorNaoTemAMarca() throws Exception {
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        ServidorLab local = assistente.cadastrarLocal("Este computador", 7070);
        assertThrows(IllegalArgumentException.class, () -> gerenciador.definirProtegido(local.id(), true));
        assertFalse(local.protegido());
        assertFalse(noCofre(local).protegido());
    }

    // ---------------------------------------------------------------------------------------------
    // A guarda única
    // ---------------------------------------------------------------------------------------------

    @Test
    void protegidoRecusaMontarOInstaladorDoAgenteComAFraseCerta() throws Exception {
        ServidorLab lab = servidor(true);
        ServidorProtegidoException e = assertThrows(ServidorProtegidoException.class, () -> lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7000), PACOTE));
        assertEquals("Este servidor é protegido: o app não instala, atualiza nem remove nada aqui. Para mudar, abra Editar servidor.", e.getMessage());
        assertThrows(ServidorProtegidoException.class, lab::exigirNaoProtegido);
    }

    @Test
    void semAMarcaOInstaladorDoAgenteMontaComoSempre() throws Exception {
        ServidorLab lab = servidor(false);
        InstaladorDeAgente inst = lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7000), PACOTE);
        assertNotNull(inst);
        lab.exigirNaoProtegido();
        // desligar e ligar de novo muda a resposta na hora
        gerenciador.definirProtegido(lab.id(), true);
        assertThrows(ServidorProtegidoException.class, () -> lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7000), PACOTE));
        gerenciador.definirProtegido(lab.id(), false);
        assertNotNull(lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7000), PACOTE));
    }

    @Test
    void oInstaladorDoGitDeUmServidorProtegidoNaoInstalaMasEntregaOLeitor() throws Exception {
        ServidorLab lab = servidor(true);
        InstaladorDeGit git = lab.instaladorDeGit(); // montar e conferir é leitura: não recusa
        InstaladorDeGit.Previa previa = new InstaladorDeGit.Previa(new InstaladorDeGit.Estado(false, ""), InstaladorDeGit.Situacao.PODE_INSTALAR, GerenciadorDePacotes.APT_GET,
            "Debian", true, false, List.of("apt-get install -y git"), List.of());
        simulado.limparRegistro();

        InstaladorDeGit.Resultado r = git.instalar(previa, passo -> { }, new br.com.labdesktop.arquivos.Cancelamento());

        assertFalse(r.instalou());
        assertEquals(ProtecaoDoServidor.RECUSA, r.mensagem());
        assertTrue(simulado.escritas().isEmpty(), "nenhum comando foi ao servidor: " + simulado.escritas());
        assertTrue(simulado.comandos.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // O agente do lab instalado por fora: marca automática
    // ---------------------------------------------------------------------------------------------

    @Test
    void agenteDoLabRespondendoMarcaOServidorUmaVezEAvisaUmaVez() throws Exception {
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = servidor(false);

        Espera.ate(PRAZO, () -> "marca no cofre; estado=" + lab.estado(), () -> noCofre(lab).protegido());
        Espera.ate(PRAZO, "avisou o usuário", () -> !avisosDaMarcaAutomatica.isEmpty());
        assertTrue(lab.estado().protegido());
        assertFalse(noCofre(lab).protecaoAutomaticaRecusada(), "foi o app que marcou, não o usuário");

        // mais respostas do mesmo agente: nada de novo, nenhum aviso a mais
        for (int i = 0; i < 3; i++) {
            lab.consultarVersao();
        }
        Thread.sleep(300);
        assertEquals(List.of(lab.id()), avisosDaMarcaAutomatica, "o aviso sai uma única vez");
        assertTrue(noCofre(lab).protegido());
    }

    @Test
    void agenteMonitorQueOAppInstalouNaoLigaAMarca() throws Exception {
        agente.fora = false;
        agente.perfil = "monitor";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        lab.consultarVersao();
        Thread.sleep(300);
        assertFalse(noCofre(lab).protegido());
        assertFalse(lab.protegido());
        assertTrue(avisosDaMarcaAutomatica.isEmpty());
        assertTrue(lab.estado().agenteDoMonitor());
        assertTrue(lab.estado().ofereceAtualizarERemoverAgente(), "o monitor do app continua com Atualizar e Remover");
    }

    @Test
    void agenteAntigoSemPerfilContaComoDeForaPoisSoOLabExistiaAntes() throws Exception {
        agente.fora = false; // perfil nulo: responde como o agente de antes dos perfis
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, () -> "marca no cofre; estado=" + lab.estado(), () -> noCofre(lab).protegido());
        assertFalse(lab.estado().agenteDoMonitor());
    }

    @Test
    void desmarcarNaoReligaSozinhoNemNestaSessaoNemNaProxima() throws Exception {
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, "marca automática", () -> noCofre(lab).protegido());
        Espera.ate(PRAZO, "aviso", () -> avisosDaMarcaAutomatica.size() == 1);

        gerenciador.definirProtegido(lab.id(), false); // o usuário desmarcou em Editar servidor
        lab.consultarVersao(); // o agente do lab responde de novo
        Thread.sleep(300);
        assertFalse(noCofre(lab).protegido(), "não religou");
        assertFalse(lab.protegido());
        assertFalse(lab.estado().protegido());
        assertEquals(1, avisosDaMarcaAutomatica.size(), "e não avisou outra vez");

        // outra sessão: o cofre guardou a recusa
        String id = lab.id();
        gerenciador.fechar().join();
        gerenciador = novoGerenciador();
        ServidorLab novo = gerenciador.servidor(id).orElseThrow();
        novo.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + novo.estado(), () -> novo.estado().conexao() == EstadoConexao.CONECTADO);
        novo.consultarVersao();
        Thread.sleep(300);
        assertFalse(noCofre(novo).protegido(), "a marca automática não volta na sessão seguinte");
        assertFalse(novo.estado().protegido());
        assertEquals(1, avisosDaMarcaAutomatica.size());
    }

    @Test
    void semPermissaoNoDockerDepoisDeOAgenteDoLabTerRespondidoNaoOfereceInstalar() throws Exception {
        ssh.comandoRemoto(agente.comandosPorSsh()); // aqui a leitura "o agente existe?" é a do agente falso, sem o servidor simulado na frente
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, "marca automática", () -> noCofre(lab).protegido());

        // o agente cai e o usuário não consegue ler o Docker: sem a proteção o app ofereceria instalar
        agente.diagnostico = "sem-permissao";
        agente.fora = true;
        lab.aoMudarOAgenteNoServidor(); // o agente caiu: a conferência falha e a leitura por SSH diz "sem permissão"
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.SEM_PERMISSAO_NO_DOCKER);
        assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, lab.estado().conexao());
        assertTrue(lab.estado().situacaoDoAgente().oferecerInstalar(), "a leitura em si ainda diz que dá para instalar");
        assertFalse(lab.estado().ofereceInstalarAgente(), "mas o servidor é protegido");
        assertTrue(lab.estado().agenteFaltaMasServidorProtegido());
    }

    @Test
    void naoEsperaOCofreParaProtegerNaHora() throws Exception {
        // a marca vai ao cofre em segundo plano; a proteção da sessão vale já no estado (nunca há um instante em que se ofereça instalar)
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().protegido() || lab.protegido());
        assertTrue(lab.protegido());
        assertThrows(ServidorProtegidoException.class, lab::exigirNaoProtegido);
    }

    // ---------------------------------------------------------------------------------------------
    // O instalador recusa se um agente do lab responde, mesmo sem a marca
    // ---------------------------------------------------------------------------------------------

    @Test
    void comAgenteDoLabRespondendoEADonaTendoDesmarcadoOInstaladorAindaRecusaAntesDeEscrever() throws Exception {
        agente.fora = false;
        agente.perfil = "lab";
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, "marca automática", () -> noCofre(lab).protegido());
        gerenciador.definirProtegido(lab.id(), false); // o usuário desmarcou; o agente do lab continua lá, respondendo
        assertFalse(lab.protegido());
        simulado.limparRegistro();

        InstaladorDeAgente inst = lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7000), PACOTE).comSonda(Duration.ofSeconds(2), Duration.ofMillis(50));
        var relatorio = inst.verificar(ModoDaInstalacao.INSTALAR);

        ResultadoDaVerificacao container = relatorio.de(ResultadoDaVerificacao.Item.CONTAINER);
        assertEquals(ResultadoDaVerificacao.Estado.PROBLEMA, container.estado());
        assertTrue(container.frase().contains("agente do lab"), container.frase());
        assertTrue(relatorio.bloqueado());
        ResultadoDaInstalacao r = inst.instalar(ObservadorDaInstalacao.NENHUM, Cancelamento.NUNCA);
        assertEquals(ResultadoDaInstalacao.Desfecho.RECUSADA, r.desfecho());
        assertTrue(simulado.escritas().isEmpty(), "nada foi escrito no servidor: " + simulado.escritas());
        assertTrue(simulado.envios.isEmpty());
        assertEquals(Set.of(), Set.copyOf(simulado.envios));
    }

    @Test
    void semAgenteDeForaRespondendoOInstaladorSegueComoSempre() throws Exception {
        ServidorLab lab = servidor(false);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().situacaoDoAgente() == SituacaoDoAgente.NAO_INSTALADO);
        InstaladorDeAgente inst = lab.instaladorDoAgente(ParametrosDaInstalacao.padrao(7000), PACOTE).comSonda(Duration.ofSeconds(10), Duration.ofMillis(50));
        var relatorio = inst.verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(relatorio.bloqueado(), relatorio.problemas().toString());
    }
}
