package br.com.labdesktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.ErroApi.NaoEncontrado;
import br.com.labdesktop.api.ErroApi.RespostaInvalida;
import br.com.labdesktop.api.ErroApi.ServidorErro;
import br.com.labdesktop.api.modelo.Detalhe;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.api.modelo.RespostaRetomar;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.util.Agendador;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O {@link ClienteApi} contra um {@code HttpServer} do JDK que devolve os JSONs do mock do front e simula as
 * manias do agente real (Host, Upgrade, 401, 409...). A parte de 2FA está em {@link ClienteApi2faTest}.
 */
class ClienteApiTest {

    private AgenteFalso agente;
    private TokenAgente token;
    private ClienteApi api;

    @BeforeEach
    void abrir() throws IOException {
        agente = new AgenteFalso();
        token = TokenAgente.gerar();
        api = new ClienteApi("lab", agente.porta(), token, PedidoDeCodigo2fa.NUNCA);
    }

    @AfterEach
    void fechar() {
        api.close();
        agente.close();
    }

    private static final String VERSAO_JSON =
        "{\"api\":1,\"painel\":\"v22.5.0\",\"exigeToken\":false,\"cliente\":\"mac-de-teste\",\"totp\":\"desligado\"}";

    // ------------------------------------------------------------------------------------------------
    // Cabeçalhos e protocolo
    // ------------------------------------------------------------------------------------------------

    @Test
    void versaoMandaOsCabecalhosDoContratoEParseia() throws Exception {
        agente.responder(p -> Resposta.json(200, VERSAO_JSON));

        VersaoAgente v = api.versao();

        assertEquals(1, v.api());
        assertEquals("v22.5.0", v.painel());
        assertEquals("mac-de-teste", v.cliente());
        assertEquals("desligado", v.totp());
        assertFalse(v.exigeToken());
        assertTrue(v.compativel());

        AgenteFalso.Pedido p = agente.pedidos.get(0);
        assertEquals("GET", p.metodo());
        assertEquals("/api/versao", p.caminho());
        assertEquals("HTTP/1.1", p.protocolo(), "HTTP/1.1 explícito");
        assertEquals(token.cabecalhoAuthorization(), p.cabecalho("Authorization"));
        assertTrue(p.cabecalho("Authorization").startsWith("Bearer lab_"));
        assertEquals("application/json", p.cabecalho("Accept"));
        assertTrue(p.cabecalho("User-Agent").startsWith("LabDesktop/"), p.cabecalho("User-Agent"));
        assertEquals("127.0.0.1:" + agente.porta(), p.cabecalho("Host"));
        assertNull(p.cabecalho("Origin"), "o app nativo não manda Origin");
        assertNull(p.cabecalho("Sec-Fetch-Site"));
        assertNull(p.cabecalho("Upgrade"));
        assertTrue(p.cabecalho("Connection") == null || !p.cabecalho("Connection").contains("Upgrade"),
            "Connection: " + p.cabecalho("Connection"));
        assertNull(p.cabecalho("HTTP2-Settings"));
    }

    @Test
    void nuncaPedeUpgradeParaH2cEOAgenteFalsoQueDestroiOSocketNaoPrecisaAgir() throws Exception {
        agente.responder(p -> Resposta.json(200, VERSAO_JSON));
        for (int i = 0; i < 5; i++) {
            assertNotNull(api.versao());
        }
        assertEquals(0, agente.upgradesRecebidos.get(), "um Upgrade teria derrubado o socket no agente real");
        assertEquals(5, agente.pedidos.size());
    }

    @Test
    void semTokenNaoMandaAuthorization() throws Exception {
        try (ClienteApi semToken = new ClienteApi("lab", agente.porta(), null, PedidoDeCodigo2fa.NUNCA)) {
            agente.responder(p -> Resposta.json(200, VERSAO_JSON));
            semToken.versao();
            assertNull(agente.pedidos.get(0).cabecalho("Authorization"));
        }
    }

    @Test
    void naoSegueRedirect() {
        agente.responder(p -> Resposta.json(302, "").comCabecalho("Location", "http://127.0.0.1:1/outro"));
        ServidorErro e = assertThrows(ServidorErro.class, () -> api.versao());
        assertEquals(302, e.status());
        assertEquals(1, agente.pedidos.size());
    }

    // ------------------------------------------------------------------------------------------------
    // DTOs contra o mock do front (tolerância)
    // ------------------------------------------------------------------------------------------------

    @Test
    void limitesDoMockParseiaETolerarDataComMacro() throws Exception {
        agente.responder(p -> Resposta.json(200, MocksDoFront.ler("limites.json")));

        LimitesConta l = api.limites();

        assertTrue(l.disponivel());
        assertFalse(Boolean.TRUE.equals(l.antiga()));
        assertEquals(82.0, l.cincoHoras().usadoPct());
        assertEquals(92.0, l.semana().usadoPct());
        assertNull(l.semana().zeraEm(), "'@+5d3h' é macro do mock, não data: vira null só neste campo");
        assertEquals(90, l.freio().pct());
        assertTrue(l.freio().ativo());
        assertEquals(3, l.pausados().size());
        assertEquals("app-loja", l.pausados().get(0).projeto());
        assertEquals("teto", l.pausados().get(0).motivo());
        assertEquals(7, l.porProjeto().size());
        assertEquals("pausado", l.porProjeto().get("app-loja").estado());
        assertEquals(20, l.porProjeto().get("fit-app").tetoPct());
        assertEquals(5.1, l.porProjeto().get("_fora_do_lab").usadoPp());
        // regras da faixa de crédito (ARQUITETURA 8.2)
        assertEquals(92.0, l.piorJanelaPct());
        assertTrue(l.faixaDeCredito());
        assertTrue(l.emPerigo());
    }

    @Test
    void faixaDeCreditoSoComLeituraDisponivelENaoAntiga() throws Exception {
        agente.responder(p -> Resposta.json(200,
            "{\"disponivel\":true,\"antiga\":true,\"semana\":{\"usado_pct\":99},\"cinco_horas\":{\"usado_pct\":10}}"));
        LimitesConta antiga = api.limites();
        assertFalse(antiga.leituraUtil());
        assertNull(antiga.piorJanelaPct());
        assertFalse(antiga.faixaDeCredito());

        agente.responder(p -> Resposta.json(200,
            "{\"freio\":{\"pct\":null,\"ativo\":false},\"pausados\":[],\"por_projeto\":{},\"disponivel\":false}"));
        LimitesConta semLeitura = api.limites();
        assertFalse(semLeitura.faixaDeCredito());
        assertFalse(semLeitura.freio().ativo());
        assertNull(semLeitura.freio().pct());

        agente.responder(p -> Resposta.json(200,
            "{\"disponivel\":true,\"antiga\":false,\"semana\":{\"usado_pct\":74.9},\"cinco_horas\":{\"usado_pct\":75}}"));
        LimitesConta limiar = api.limites();
        assertTrue(limiar.faixaDeCredito(), "75 % já mostra a faixa");
        assertFalse(limiar.emPerigo());
    }

    @Test
    void projetosDoMockComMemEmTextoNaoDerrubaOTopico() throws Exception {
        agente.responder(p -> Resposta.json(200, MocksDoFront.ler("projetos.json")));

        ListaProjetos lista = api.projetos();

        assertEquals(5, lista.projetos().size());
        Projeto fit = lista.projetos().get(0);
        assertEquals("fit-app", fit.nome());
        assertEquals(EstadoProjeto.NO_AR, fit.estado());
        assertTrue(fit.temSessaoClaude());
        assertEquals(2, fit.containers().size());
        assertNull(fit.containers().get(0).mem(), "'186MiB' (texto do mock) vira null; o agente real manda bytes");
        assertEquals(3.4, fit.containers().get(0).cpu());
        assertEquals(282591232L, fit.mem());
        assertEquals(16.0, fit.limite().usadoPp());
        assertEquals(20, fit.limite().tetoPct());
        assertEquals("atencao", fit.limite().estado());
        assertNull(fit.limite().zeraEm());
        assertEquals(Instant.parse("2026-09-23T12:47:40.190Z"), fit.git().quando());
        assertTrue(fit.git().sujo());
        assertEquals(List.of("fit.example.com"), fit.urls());
        assertEquals(0.385, fit.tokens().semanaCustoUsd());

        assertEquals(EstadoProjeto.PARCIAL, lista.projetos().get(1).estado()); // app-loja: 2 de 3 rodando
        assertTrue(lista.projetos().get(1).pausado());
        assertEquals(EstadoProjeto.NO_AR, lista.projetos().get(2).estado());
        assertEquals(EstadoProjeto.PARADO, lista.projetos().get(3).estado());  // ponto-api
        assertEquals(EstadoProjeto.PARADO, lista.projetos().get(4).estado());  // bazar

        // O bloco servidor traz limites_plano e NÃO traz historico nem processos.
        assertNotNull(lista.servidor());
        assertEquals("servidor-exemplo", lista.servidor().hostname());
        assertEquals(54.5, lista.servidor().temperatura());
        assertNotNull(lista.servidor().limitesPlano());
        assertTrue(lista.servidor().limitesPlano().disponivel());
    }

    @Test
    void estadosDeProjetoSegueARegraDoFront() throws Exception {
        String json = "{\"projetos\":["
            + "{\"nome\":\"a\",\"composes\":[],\"containers\":[]},"
            + "{\"nome\":\"b\",\"composes\":[\"docker-compose.yml\"],\"containers\":[]},"
            + "{\"nome\":\"c\",\"composes\":[\"docker-compose.yml\"],\"containers\":[{\"nome\":\"c1\",\"estado\":\"running\"}]},"
            + "{\"nome\":\"d\",\"composes\":[\"docker-compose.yml\"],\"containers\":[{\"estado\":\"running\"},{\"estado\":\"exited\"}]},"
            + "{\"nome\":\"e\",\"composes\":[\"docker-compose.yml\"],\"containers\":[{\"estado\":\"exited\"}]}]}";
        agente.responder(p -> Resposta.json(200, json));
        List<Projeto> ps = api.projetos().projetos();
        assertEquals(List.of(EstadoProjeto.SEM_COMPOSE, EstadoProjeto.NUNCA_SUBIU, EstadoProjeto.NO_AR,
            EstadoProjeto.PARCIAL, EstadoProjeto.PARADO), ps.stream().map(Projeto::estado).toList());
        assertEquals("sem compose", EstadoProjeto.SEM_COMPOSE.rotulo());
    }

    @Test
    void detalheDeProjetoComumDoMock() throws Exception {
        agente.responder(p -> Resposta.json(200, MocksDoFront.ler("projetos/app-loja.json")));

        Detalhe d = api.detalhe("app-loja");

        DetalheProjeto proj = assertInstanceOf(DetalheProjeto.class, d);
        assertEquals("app-loja", proj.nome());
        assertEquals("/api/projetos/app-loja", agente.pedidos.get(0).caminho());
        assertTrue(proj.readme().startsWith("# app-loja"));
        assertEquals("docker-compose.yml", proj.composes().get(0).arquivo());
        assertEquals("app-loja", proj.composes().get(0).projetoCompose());
        assertEquals(3, proj.containers().size());
        assertEquals(142606336L, proj.containers().get(0).mem());
        assertEquals(1073741824L, proj.containers().get(0).memLimite());
        assertEquals(18, proj.containers().get(0).pids());
        assertEquals("running", proj.containers().get(0).estado());
        assertNull(proj.containers().get(2).cpu());
        assertFalse(proj.sessao().estaAberta());
        assertEquals("/sessao/app-loja", proj.sessao().url());
        assertSame(proj.sessao(), d.sessao());
        assertEquals("pausado", proj.limite().estado());
        assertNull(proj.limite().pausa().em(), "macro '@-35m' do mock");
        assertEquals("teto", proj.limite().pausa().motivo());
        assertNotNull(api.projeto("app-loja"));
    }

    @Test
    void todosOsDetalhesDoMockParseiam() throws Exception {
        // Um mock por forma de projeto do front: com e sem sessão, com e sem git, pausado ou não, com macros de data.
        List<String> projetos = List.of("app-loja", "bazar", "fit-app", "vendas", "ponto-api");
        agente.responder(p -> {
            String nome = p.caminho().substring("/api/projetos/".length());
            return Resposta.json(200, MocksDoFront.ler("projetos/" + nome + ".json"));
        });

        for (String nome : projetos) {
            DetalheProjeto d = api.projeto(nome);

            assertEquals(nome, d.nome());
            assertFalse(d.composes().isEmpty(), nome);
            assertNotNull(d.sessao(), nome + ": o detalhe comum sempre traz o objeto sessao");
            assertNotNull(d.limite(), nome);
            assertTrue(d.containers().stream().allMatch(c -> c.nome() != null && !c.nome().isBlank()), nome);
            assertTrue(d.containers().stream().allMatch(c -> c.mem() == null || c.mem() >= 0), nome);
            assertTrue(d.readme() == null || !d.readme().contains("\u0000"), nome);
        }
        assertEquals(projetos.size(), agente.pedidos.size());
    }

    private static void assertSame(Object esperado, Object atual) {
        org.junit.jupiter.api.Assertions.assertSame(esperado, atual);
    }

    @Test
    void sessaoRaizTemFormaPropriaEOLabAtendePorDetalhe() throws Exception {
        agente.responder(p -> Resposta.json(200, MocksDoFront.ler("projetos/lab.json")));

        Detalhe d = api.detalhe("lab");

        DetalheRaiz raiz = assertInstanceOf(DetalheRaiz.class, d);
        assertTrue(raiz.raiz());
        assertEquals("Sessão do lab", raiz.titulo());
        assertNull(raiz.sessao());
        assertFalse(raiz.temLoginPendente());
        assertTrue(raiz.limite().pausado());
        assertEquals("freio", raiz.limite().pausa().motivo());
        assertNotNull(api.sessaoRaiz());
        // projeto("lab") espera a forma comum: a forma da raiz é resposta inválida, não ClassCastException.
        assertThrows(RespostaInvalida.class, () -> api.projeto("lab"));
    }

    @Test
    void servidorDoMockTrazMetricasEProcessos() throws Exception {
        agente.responder(p -> Resposta.json(200, MocksDoFront.ler("servidor.json")));

        InfoServidor s = api.servidor();

        assertEquals("servidor-exemplo", s.hostname());
        assertEquals(0.27, s.cpu().uso());
        assertEquals(8, s.cpu().porNucleo().size());
        assertEquals(54.5, s.temperatura());
        assertEquals(33654927360L, s.memoria().total());
        assertEquals(536870912L, s.memoria().swap().usada());
        assertEquals(2, s.discos().size());
        assertEquals("eth0", s.rede().interfaceDeRede());
        assertFalse(s.processos().isEmpty());
        assertEquals("node", s.processos().get(0).nome());
        assertEquals(41822, s.processos().get(0).pid());
        assertEquals("lab-claude-fit-app", s.processos().get(0).container());
        assertEquals("lab-claude-fit-app", s.sessoes().get(0).container());
        assertEquals(Instant.parse("2026-09-23T16:47:40.190Z"), s.quando());
    }

    @Test
    void camposAusentesEDesconhecidosNaoQuebramNada() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"projetos\":[{\"nome\":\"x\",\"campoNovo\":{\"a\":1}}],\"extra\":[1,2]}"));
        ListaProjetos l = api.projetos();
        assertEquals(1, l.projetos().size());
        Projeto x = l.projetos().get(0);
        assertEquals("x", x.nome());
        assertTrue(x.containers().isEmpty());
        assertNull(x.limite());
        assertNull(x.git());
        assertEquals(EstadoProjeto.SEM_COMPOSE, x.estado());
        assertNull(l.servidor());
    }

    @Test
    void tipoErradoEmUmCampoVirNullSoNaqueleCampo() throws Exception {
        agente.responder(p -> Resposta.json(200,
            "{\"projetos\":[{\"nome\":\"x\",\"cpu\":{\"o\":1},\"mem\":\"muito\",\"tamanho\":\"12\",\"backups\":2.9}]}"));
        Projeto x = api.projetos().projetos().get(0);
        assertNull(x.cpu());
        assertNull(x.mem());
        assertEquals(12L, x.tamanho(), "texto numérico é aceito");
        assertEquals(2, x.backups());
    }

    @Test
    void textoHostilDoServidorEhNeutralizadoNaConstrucaoDoDto() throws Exception {
        String json = "{\"projetos\":[{\"nome\":\"x\",\"descricao\":\"<html><img src=http://evil/x>\","
            + "\"git\":{\"branch\":\"main\\u202Egpj.exe\"},\"containers\":[{\"nome\":\"c\\u001b[31m1\",\"status\":\"Up\\r\\nX\"}]}]}";
        agente.responder(p -> Resposta.json(200, json));
        Projeto x = api.projetos().projetos().get(0);
        assertFalse(br.com.labdesktop.seguranca.TextoSeguro.pareceHtml(x.descricao()), x.descricao());
        assertFalse(x.git().branch().contains("‮"));
        assertFalse(x.containers().get(0).nome().contains("\u001b"));
        assertFalse(x.containers().get(0).status().contains("\n"));
    }

    // ------------------------------------------------------------------------------------------------
    // Erros
    // ------------------------------------------------------------------------------------------------

    @Test
    void erro401SemCodigoEhTokenRecusado() {
        agente.responder(p -> Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}"));
        NaoAutorizado e = assertThrows(NaoAutorizado.class, () -> api.limites());
        assertEquals(401, e.status());
        assertNull(e.codigo());
        assertTrue(e.tokenRecusado());
        assertFalse(e.totpRequerido());
        assertEquals("Token ausente ou inválido.", e.mensagem());
        assertEquals(1, agente.pedidos.size(), "sem codigo, nada é repetido");
    }

    @Test
    void erro404DeSessaoNaoAbertaEFluxoNormal() {
        agente.responder(p -> Resposta.json(404, "{\"erro\":\"Sessão não está aberta.\"}"));
        NaoEncontrado e = assertThrows(NaoEncontrado.class, () -> api.detalhe("vendas"));
        assertTrue(e.sessaoNaoAberta());
        assertEquals(404, e.status());
    }

    @Test
    void erro409DoRetomarTrazOsNumerosDoDialogo() {
        agente.responder(p -> {
            // o mock marca o status dentro do corpo (_status)
            return Resposta.json(409, MocksDoFront.ler("projetos/app-loja-retomar.POST.json"));
        });
        Conflito c = assertThrows(Conflito.class, () -> api.retomar("app-loja", false, true));
        assertTrue(c.aindaEstoura());
        assertEquals(22.4, c.usadoPp());
        assertEquals(20, c.tetoPct());
        assertEquals(92.0, c.pctConta());
        assertEquals(90, c.freioPct());
        assertTrue(c.mensagem().startsWith("Ainda acima do limite"));
        AgenteFalso.Pedido p = agente.pedidos.get(0);
        assertEquals("POST", p.metodo());
        assertEquals("/api/projetos/app-loja/limite/retomar", p.caminho());
        assertEquals("application/json", p.cabecalho("Content-Type"));
        assertEquals("{\"liberar\":false,\"reabrir\":true}", p.corpo());
    }

    @Test
    void erro409SemNumerosNaoEhAindaEstoura() {
        agente.responder(p -> Resposta.json(409, "{\"erro\":\"Sessão pausada pelo limite (teto). Use Retomar.\"}"));
        Conflito c = assertThrows(Conflito.class, () -> api.abrirSessao("app-loja"));
        assertFalse(c.aindaEstoura());
        assertNull(c.tetoPct());
    }

    @Test
    void retomarOkTrazALimiteEASessao() throws Exception {
        agente.responder(p -> Resposta.json(200,
            "{\"ok\":true,\"limite\":{\"teto_pct\":30,\"usado_pp\":10,\"estado\":\"ok\"},"
                + "\"sessao\":{\"aberta\":true,\"container\":\"lab-claude-app-loja\",\"url\":\"/sessao/app-loja\"}}"));
        RespostaRetomar r = api.retomar("app-loja", true, true);
        assertTrue(r.ok());
        assertEquals("ok", r.limite().estado());
        assertEquals("lab-claude-app-loja", r.sessao().container());
        assertEquals("{\"liberar\":true,\"reabrir\":true}", agente.pedidos.get(0).corpo());
    }

    @Test
    void erro503CalculandoEHost421EmTextoPuroViramServidorErro() {
        agente.responder(p -> Resposta.json(503, "{\"erro\":\"Ainda calculando o consumo de tokens — tenta de novo em alguns segundos.\"}"));
        ServidorErro calculando = assertThrows(ServidorErro.class, () -> api.limites());
        assertTrue(calculando.calculando());

        agente.responder(p -> Resposta.texto(421, "Host não permitido."));
        ServidorErro host = assertThrows(ServidorErro.class, () -> api.limites());
        assertEquals(421, host.status());
        assertEquals("Host não permitido.", host.mensagem());
        assertNull(host.corpo());
    }

    @Test
    void erro500ComTokenNoCorpoSaiMascarado() {
        String vazado = token.cabecalhoAuthorization();
        agente.responder(p -> Resposta.json(500, "{\"erro\":\"falhou com " + vazado + " e lab_" + "Z".repeat(43) + "\"}"));
        ServidorErro e = assertThrows(ServidorErro.class, () -> api.iniciar("app-loja"));
        assertFalse(e.mensagem().contains(token.cabecalhoAuthorization().substring(7)), e.mensagem());
        assertFalse(e.mensagem().contains("ZZZZZZZZ"), e.mensagem());
    }

    @Test
    void respostaForaDoContratoViraRespostaInvalidaSemVazarOJson() {
        agente.responder(p -> Resposta.json(200, "{ isto não é json"));
        assertThrows(RespostaInvalida.class, () -> api.limites());

        agente.responder(p -> Resposta.json(200, "[1,2,3]"));
        assertThrows(RespostaInvalida.class, () -> api.limites());

        agente.responder(p -> Resposta.json(200, "{\"projetos\":\"não é lista\"}"));
        RespostaInvalida e = assertThrows(RespostaInvalida.class, () -> api.projetos());
        assertFalse(e.mensagem().contains("não é lista"));

        agente.responder(p -> Resposta.json(200, ""));
        assertThrows(RespostaInvalida.class, () -> api.limites());
    }

    @Test
    void respostaAcimaDoTetoDe2MiBEhRecusada() {
        String enorme = "{\"projetos\":[],\"lixo\":\"" + "a".repeat(LeituraLimitada.TETO_JSON + 10) + "\"}";
        agente.responder(p -> Resposta.json(200, enorme));
        RespostaInvalida e = assertThrows(RespostaInvalida.class, () -> api.projetos());
        assertTrue(e.mensagem().contains("grande demais"), e.mensagem());
    }

    // ------------------------------------------------------------------------------------------------
    // Rede e prazos
    // ------------------------------------------------------------------------------------------------

    @Test
    void portaSemNinguemEhErroDeRede() throws IOException {
        int livre;
        try (ServerSocket s = new ServerSocket(0)) {
            livre = s.getLocalPort();
        }
        try (ClienteApi morto = new ClienteApi("lab", livre, token, PedidoDeCodigo2fa.NUNCA)) {
            ErroRede e = assertThrows(ErroRede.class, morto::versao);
            assertEquals(0, e.status());
            assertNotNull(e.getCause());
        }
    }

    @Test
    void prazoPorRotaEstouraComErroDeRede() {
        try (ClienteApi curto = new ClienteApi("lab", agente.porta(), token, PedidoDeCodigo2fa.NUNCA,
            TimeoutsPorRota.limitadoA(Duration.ofMillis(400)), Agendador.doFundo())) {
            agente.responder(p -> Resposta.comAtraso(Resposta.json(200, VERSAO_JSON), 1500));
            long inicio = System.nanoTime();
            ErroRede e = assertThrows(ErroRede.class, curto::versao);
            assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 1400, "deveria desistir em ~400 ms");
            assertTrue(e.mensagem().contains("não respondeu"), e.mensagem());
        }
    }

    @Test
    void prazoCobreTambemOCorpoQueEmperra() {
        try (ClienteApi curto = new ClienteApi("lab", agente.porta(), token, PedidoDeCodigo2fa.NUNCA,
            TimeoutsPorRota.limitadoA(Duration.ofMillis(500)), Agendador.doFundo())) {
            agente.responder(p -> Resposta.corpoQueEmperra());
            long inicio = System.nanoTime();
            assertThrows(ErroRede.class, curto::limites);
            assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 3000,
                "o vigia tem de fechar o fluxo parado");
        }
    }

    @Test
    void tabelaDePrazosDoContrato() {
        TimeoutsPorRota t = TimeoutsPorRota.padrao();
        assertEquals(Duration.ofSeconds(5), t.de(Rota.VERSAO));
        assertEquals(Duration.ofSeconds(15), t.de(Rota.LIMITES));
        assertEquals(Duration.ofSeconds(20), t.de(Rota.SERVIDOR));
        assertEquals(Duration.ofSeconds(25), t.de(Rota.LOGS));
        assertEquals(Duration.ofMinutes(6), t.de(Rota.ABRIR_SESSAO));
        assertEquals(Duration.ofMinutes(6), t.de(Rota.REABRIR_SESSAO));
        assertEquals(Duration.ofMinutes(6), t.de(Rota.RETOMAR));
        assertEquals(Duration.ofMinutes(12), t.de(Rota.COMPOSE));
        assertEquals(Duration.ofMinutes(3), t.de(Rota.REINICIAR_CONTAINER));
        assertEquals(Duration.ofSeconds(15), t.de(Rota.CONFIRMAR_2FA));
        assertEquals(Duration.ofMillis(300), TimeoutsPorRota.limitadoA(Duration.ofMillis(300)).de(Rota.COMPOSE));
    }

    @Test
    void postNuncaEhReenviadoSozinhoDepoisDeFalhaDeRede() throws Exception {
        agente.responder(p -> Resposta.semResposta());
        assertThrows(ErroRede.class, () -> api.iniciar("app-loja"));
        Espera.dormir(500); // se houvesse reenvio automático, apareceria aqui
        assertEquals(1, agente.pedidos.size());

        assertThrows(ErroRede.class, () -> api.definirLimite("app-loja", 20));
        Espera.dormir(300);
        assertEquals(2, agente.pedidos.size());
    }

    // ------------------------------------------------------------------------------------------------
    // Ações: rotas, métodos e corpos
    // ------------------------------------------------------------------------------------------------

    @Test
    void acoesUsamMetodoRotaECorpoDoContrato() throws Exception {
        agente.responder(p -> {
            if (p.caminho().endsWith("/limite")) {
                return Resposta.json(200, "{\"ok\":true,\"limite\":{\"teto_pct\":20,\"usado_pp\":1.5,\"estado\":\"ok\"}}");
            }
            if (p.caminho().contains("/claude/abrir") || p.caminho().contains("/claude/reabrir")) {
                return Resposta.json(200, "{\"ok\":true,\"url\":\"/sessao/vendas\"}");
            }
            return Resposta.json(200, "{\"ok\":true,\"saida\":\"[docker-compose.yml] ok\"}");
        });

        assertEquals("[docker-compose.yml] ok", api.iniciar("vendas").saida());
        api.parar("vendas");
        api.reiniciar("vendas");
        assertEquals("/sessao/vendas", api.abrirSessao("vendas").url());
        api.fecharSessao("vendas");
        api.reabrirSessao("vendas");
        assertEquals(1.5, api.definirLimite("vendas", 20).limite().usadoPp());
        api.definirLimite("vendas", null);

        List<String> chamadas = agente.pedidos.stream().map(p -> p.metodo() + " " + p.caminho()).toList();
        assertEquals(List.of(
            "POST /api/projetos/vendas/iniciar",
            "POST /api/projetos/vendas/parar",
            "POST /api/projetos/vendas/reiniciar",
            "POST /api/projetos/vendas/claude/abrir",
            "POST /api/projetos/vendas/claude/fechar",
            "POST /api/projetos/vendas/claude/reabrir",
            "PUT /api/projetos/vendas/limite",
            "PUT /api/projetos/vendas/limite"), chamadas);
        assertEquals("{\"teto_pct\":20}", agente.pedidos.get(6).corpo());
        assertEquals("{\"teto_pct\":null}", agente.pedidos.get(7).corpo());
        assertEquals("{}", agente.pedidos.get(0).corpo());
    }

    @Test
    void nomeInvalidoNemSaiDoApp() {
        assertThrows(IllegalArgumentException.class, () -> api.iniciar("../etc"));
        assertThrows(IllegalArgumentException.class, () -> api.detalhe("Maiuscula"));
        assertThrows(IllegalArgumentException.class, () -> api.abrirSessao("a b"));
        assertThrows(IllegalArgumentException.class, () -> api.definirLimite("vendas", 0));
        assertThrows(IllegalArgumentException.class, () -> api.definirLimite("vendas", 101));
        assertTrue(agente.pedidos.isEmpty());
    }

    @Test
    void depoisDeFecharNaoHaChamadas() {
        api.close();
        assertThrows(ErroRede.class, () -> api.versao());
        assertTrue(agente.pedidos.isEmpty());
    }

    // ------------------------------------------------------------------------------------------------
    // O histórico de métricas
    // ------------------------------------------------------------------------------------------------

    @Test
    void historicoPedeAJanelaCertaEParseiaAGradeComNulos() throws Exception {
        Instant agoraDoAgente = Instant.parse("2026-10-28T15:00:00Z");
        agente.responder(p -> Resposta.json(200, br.com.labdesktop.teste.HistoricoSintetico.json(br.com.labdesktop.tendencias.JanelaDeTempo.UMA_HORA, agoraDoAgente, 10)));
        br.com.labdesktop.api.modelo.HistoricoDoAgente h = api.historico("1h");
        assertEquals("/api/servidor/historico?janela=1h", agente.pedidos.get(0).caminhoComQuery());
        assertEquals("GET", agente.pedidos.get(0).metodo());
        assertEquals(20, h.passoS());
        assertEquals(180, h.cpu().size());
        assertNull(h.cpu().get(0), "célula sem dado vem como null");
        assertNull(h.discoUso().get(9));
        assertNotNull(h.discoUso().get(10));
        assertEquals(agoraDoAgente, h.quando());
        assertEquals(agoraDoAgente.minusSeconds(3600), h.inicio());
        assertEquals(180, h.discoEscritaMaxBps().size());
    }

    @Test
    void historicoAceitaSoOsCincoCodigosENaoVaiARedeComOutro() throws Exception {
        for (String ruim : new String[] {null, "", "2h", "1H", "constructor", "1d&x=1", "../x", "10m "}) {
            assertThrows(IllegalArgumentException.class, () -> api.historico(ruim), String.valueOf(ruim));
        }
        assertEquals(0, agente.pedidos.size(), "nenhuma chamada foi à rede");
    }

    @Test
    void historicoDeAgenteDeAntesDa404ENaoDaErroDeTela() {
        agente.responder(p -> Resposta.texto(404, "não encontrado"));
        assertInstanceOf(NaoEncontrado.class, assertThrows(ErroApi.class, () -> api.historico("10m")));
    }

    @Test
    void historicoComCampoFaltandoOuListaForaDeTipoNaoQuebra() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"janela\":\"10m\",\"passo_s\":10,\"cpu\":[0.1,\"texto\",null],\"rx_bps\":[1,2]}"));
        br.com.labdesktop.api.modelo.HistoricoDoAgente h = api.historico("10m");
        assertEquals(3, h.cpu().size());
        assertNull(h.cpu().get(1), "valor com tipo errado vira null, não derruba");
        assertEquals(List.of(), h.mem(), "campo que não veio vira lista vazia");
        assertNull(h.inicio());
    }

    @Test
    void versaoComRecursosHistoricoELidaEAgenteSemElaNaoTem() throws Exception {
        agente.responder(p -> Resposta.json(200, "{\"api\":1,\"painel\":\"v22\",\"exigeToken\":false,\"cliente\":\"x\",\"totp\":\"desligado\",\"perfil\":\"monitor\","
            + "\"recursos\":{\"claude\":false,\"projetos\":false,\"monitor\":true,\"guia_ia\":false,\"historico\":true},\"agente\":\"0.1.0-abc\"}"));
        assertTrue(api.versao().entendeHistorico());
        agente.responder(p -> Resposta.json(200, "{\"api\":1,\"painel\":\"v22\",\"exigeToken\":false,\"cliente\":\"x\",\"totp\":\"desligado\",\"perfil\":\"lab\","
            + "\"recursos\":{\"claude\":true,\"projetos\":true,\"monitor\":true,\"guia_ia\":true}}"));
        assertFalse(api.versao().entendeHistorico(), "o agente mais antigo não diz historico");
        agente.responder(p -> Resposta.json(200, VERSAO_JSON));
        assertFalse(api.versao().entendeHistorico(), "agente mais antigo ainda: sem recursos");
        agente.responder(p -> Resposta.json(200, "{\"api\":1,\"perfil\":\"lab\",\"recursos\":{\"historico\":false}}"));
        assertFalse(api.versao().entendeHistorico(), "false explícito não conta");
    }
}
