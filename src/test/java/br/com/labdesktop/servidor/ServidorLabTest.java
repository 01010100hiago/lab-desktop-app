package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SegredoTotp;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Fundo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O {@link ServidorLab} inteiro: SSH embutido do MINA + agente falso (HTTP e linha de comando compartilhando o
 * mesmo estado) + cofre falso. A conexão, o túnel, o {@code ClienteApi}, o Sondador e o 2FA de ponta a ponta.
 */
class ServidorLabTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final String NOME_DO_MAC = "mac-de-teste";

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreFalso cofre;
    private TokenAgente token;
    private ServidorLab lab;
    private final List<EstadoServidor> estados = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        token = TokenAgente.gerar();
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta())
            .comToken(token, NOME_DO_MAC);
        ssh.aceitar(cofre.par().getPublic());
        ssh.comandoRemoto(agente.comandosPorSsh());
        agente.tokens.put(token.hashSha256Hex(), NOME_DO_MAC);
    }

    @AfterEach
    void desmontar() throws Exception {
        if (lab != null) {
            lab.fechar().join();
        }
        agente.close();
        ssh.close();
    }

    /** Cria o ServidorLab com SSH rápido e entrega inline (o teste enxerga os efeitos sem EDT). */
    private ServidorLab criar(PedidoDeCodigo2fa pedido) {
        lab = new ServidorLab(CofreFalso.ID, cofre, pedido, ConfigSsh.rapida(), Agendador.doFundo(), Runnable::run,
            Fundo.ioVirtual());
        lab.observarEstado(estados::add);
        return lab;
    }

    private void aguardarEstado(EstadoConexao esperado) {
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == esperado);
    }

    // ------------------------------------------------------------------------------------------------

    @Test
    void conectaESobeOEstadoComVersaoELimitesSemNenhumaTelaAssinando() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);

        // LIMITES está sempre assinado dentro do ServidorLab (faixa de crédito, ícone, notificações).
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().limites() != null);
        EstadoServidor e = lab.estado();
        assertEquals("lab", e.nome());
        assertEquals(NOME_DO_MAC, e.versao().cliente());
        assertTrue(e.versao().compativel());
        assertFalse(e.agenteIncompativel());
        assertEquals(Estado2fa.DESLIGADO, e.estado2fa());
        assertEquals(92.0, e.pctDaSemana());
        assertEquals(3, e.sessoesPausadas());
        assertTrue(e.limites().faixaDeCredito());
        assertNull(e.semConexaoDesde());
        assertFalse(e.semToken());
        assertEquals(1, estados.stream().filter(s -> s.conexao() == EstadoConexao.CONECTADO).findFirst().orElseThrow().geracao());

        // O agente recebeu a credencial do cofre.
        assertTrue(agente.http.pedidos.stream().allMatch(p -> token.cabecalhoAuthorization().equals(p.cabecalho("Authorization"))));
        // Sem tela assinando, ninguém consultou os projetos (o docker exec por sessão custa caro).
        Espera.dormir(500);
        assertEquals(0, agente.consultasDeProjetos.get());
    }

    @Test
    void assinarProjetosConsultaListaERaizEPararDeAssinarPara() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);

        List<VisaoProjetos> recebidos = new CopyOnWriteArrayList<>();
        Inscricao assinatura = lab.assinar(Topico.PROJETOS, recebidos::add);

        Espera.ate(PRAZO, "recebe a visão de projetos", () -> !recebidos.isEmpty());
        VisaoProjetos v = recebidos.get(0);
        assertEquals(5, v.lista().projetos().size());
        assertEquals(EstadoProjeto.NO_AR, v.lista().projetos().get(0).estado());
        assertNotNull(v.raiz(), "a sessão raiz vem junto");
        assertEquals("Sessão do lab", v.raiz().titulo());
        assertEquals(1, agente.consultasDeProjetos.get());

        assinatura.close();
        lab.definirJanela(EstadoJanela.ATIVA);
        Espera.dormir(300);
        assertEquals(1, agente.consultasDeProjetos.get());
    }

    @Test
    void janelaEscondidaNaoConsultaProjetosEVoltarAtivaConsulta() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        lab.definirJanela(EstadoJanela.ESCONDIDA);

        List<VisaoProjetos> recebidos = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.PROJETOS, recebidos::add);
        Espera.dormir(600);
        assertEquals(0, agente.consultasDeProjetos.get(), "janela escondida: só LIMITES");

        lab.definirJanela(EstadoJanela.ATIVA);
        Espera.ate(PRAZO, "projetos ao voltar à janela ativa", () -> agente.consultasDeProjetos.get() == 1);
    }

    /** Critério 6 de ponta a ponta: o agente de teste conta o que chegou com a janela minimizada. */
    @Test
    void janelaMinimizadaSoDeixaChegarOsLimitesAoAgente() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        List<VisaoProjetos> recebidos = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.PROJETOS, recebidos::add);
        Espera.ate(PRAZO, "projetos com a janela ativa", () -> agente.consultasDeProjetos.get() >= 1);

        lab.definirJanela(EstadoJanela.de(true, true, false)); // minimizada
        Espera.dormir(300); // uma consulta em voo, se houver, termina
        int projetosAntes = agente.consultasDeProjetos.get();
        Espera.dormir(1_500);
        assertEquals(projetosAntes, agente.consultasDeProjetos.get(), "minimizada: nenhuma consulta de /api/projetos");

        lab.definirJanela(EstadoJanela.de(true, false, true));
        Espera.ate(PRAZO, "projetos de volta ao restaurar", () -> agente.consultasDeProjetos.get() > projetosAntes);
    }

    @Test
    void agenteForaDoArComSshDePeVaiParaSshOkSemAgenteEVolta() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        List<VisaoProjetos> recebidos = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.PROJETOS, recebidos::add);
        Espera.ate(PRAZO, "primeira visão", () -> recebidos.size() == 1);

        long geracaoAntes = lab.estado().geracao();
        agente.fora = true; // o agente reiniciou: o SSH segue de pé
        // Uma consulta que falha por rede pede à conexão para verificar: SSH vivo + agente mudo = SSH_OK_SEM_AGENTE.
        lab.assinar(Topico.detalhe("app-loja"), valor -> { });
        aguardarEstado(EstadoConexao.SSH_OK_SEM_AGENTE);
        assertNotNull(lab.tunel().orElse(null), "o túnel e o SSH continuam");
        assertNotNull(lab.estado().limites(), "o último snapshot fica");

        agente.fora = false;
        aguardarEstado(EstadoConexao.CONECTADO);
        // E o Sondador volta a consultar sozinho depois que o agente voltou.
        Espera.ate(PRAZO, "consulta depois da volta", () -> agente.consultasDeProjetos.get() >= 2);
        assertEquals(geracaoAntes, lab.estado().geracao(), "o SSH e o túnel nunca foram reabertos");
    }

    @Test
    void agenteAnteriorAoAppNaoTemApiVersaoENaoEhAgenteForaEVoltaComOPainelPublicado() {
        agente.agenteAntigo = true;
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();

        aguardarEstado(EstadoConexao.SSH_OK_SEM_AGENTE);
        assertEquals(br.com.labdesktop.ssh.Falha.Tipo.AGENTE_DESATUALIZADO, lab.estado().falha().tipo());
        assertTrue(lab.tunel().isPresent(), "o SSH e o túnel seguem de pé");
        assertNull(lab.estado().versao(), "sem a rota, não há versão para mostrar");

        agente.agenteAntigo = false; // o usuário publicou o painel atualizado
        aguardarEstado(EstadoConexao.CONECTADO);
        assertNull(lab.estado().falha());
        assertEquals(NOME_DO_MAC, lab.estado().versao().cliente());
    }

    @Test
    void tokenRecusadoQuandoOAgenteNaoReconheceEOCofreNaoMudaSozinho() {
        agente.tokens.clear(); // o token do cofre não está mais registrado no agente
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.TOKEN_RECUSADO);
        assertNotNull(lab.estado().falha());
        assertNotNull(lab.tunel().orElse(null), "o SSH segue para dar para registrar um token novo");
    }

    // ------------------------------------------------------------------------------------------------
    // Registro do token por SSH (CONTRATO-API seção 6)
    // ------------------------------------------------------------------------------------------------

    @Test
    void registrarTokenPorSshTestaEGravaNoCofreSoDepois() throws Exception {
        agente.tokens.clear();
        agente.exigeToken = true;
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
        ssh.aceitar(cofre.par().getPublic());
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.TOKEN_RECUSADO); // sem token: o agente (que exige a credencial) responde 401
        assertTrue(lab.estado().semToken());

        lab.registrarToken(NOME_DO_MAC);

        // O cofre guardou o token e o nome; o agente tem só o HASH; e a conexão voltou sozinha.
        TokenAgente guardado = cofre.tokenDoServidor(CofreFalso.ID).orElseThrow();
        assertEquals(1, agente.tokens.size());
        assertEquals(NOME_DO_MAC, agente.tokens.get(guardado.hashSha256Hex()));
        assertEquals(NOME_DO_MAC, cofre.servidor(CofreFalso.ID).orElseThrow().nomeDoDispositivo());
        // O token em claro nunca foi na linha de comando nem no stdin: só o hash.
        String texto = guardado.cabecalhoAuthorization().substring("Bearer ".length());
        assertTrue(agente.comandosCli.stream().noneMatch(c -> c.contains(texto)));
        assertTrue(agente.entradasCli.stream().noneMatch(c -> c.contains(texto)));
        assertTrue(agente.entradasCli.get(0).contains(guardado.hashSha256Hex()));
        assertEquals("docker exec -i painel-lab node acesso.js token-registrar", agente.comandosCli.get(0));
        aguardarEstado(EstadoConexao.CONECTADO);
        assertEquals(NOME_DO_MAC, lab.estado().versao().cliente());
        assertFalse(lab.estado().semToken());
    }

    @Test
    void primeiroServidorSemTokenConectaComExigeTokenDesligadoERegistraDepois() throws Exception {
        // Agente que ainda aceita chamadas sem credencial: sem token ele deixa passar; o assistente registra o token e só então grava.
        agente.tokens.clear();
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
        ssh.aceitar(cofre.par().getPublic());
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        assertTrue(lab.estado().semToken());
        assertNull(lab.estado().versao().cliente(), "sem token o agente não sabe quem é o cliente");

        lab.registrarToken(NOME_DO_MAC);

        assertEquals(NOME_DO_MAC, cofre.servidor(CofreFalso.ID).orElseThrow().nomeDoDispositivo());
        assertEquals(1, agente.tokens.size());
        // A conexão refaz o teste do agente já com o token, e o agente passa a saber quem é o cliente.
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> NOME_DO_MAC.equals(
            lab.estado().versao() == null ? null : lab.estado().versao().cliente()));
        assertFalse(lab.estado().semToken());

        // Regressão: a API antiga (sem token) foi fechada ao registrar; o Sondador precisa ter recebido a nova,
        // senão uma tela que assina depois disto ficaria sem dados com a conexão "CONECTADO".
        List<VisaoProjetos> recebidos = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.PROJETOS, recebidos::add);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> !recebidos.isEmpty());
        assertTrue(agente.http.pedidos.stream().anyMatch(p -> p.cabecalho("Authorization") != null),
            "as consultas seguintes já levam a credencial nova");
    }

    @Test
    void registrarTokenQueNaoValidaRevogaOOrfaoENaoGravaNoCofre() throws Exception {
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
        ssh.aceitar(cofre.par().getPublic());
        agente.tokens.clear();
        agente.exigeToken = true;
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.TOKEN_RECUSADO);
        lab.definirPrazoDoTesteDoToken(Duration.ofMillis(900));
        agente.recusaTodosOsTokens = true; // o agente nunca passa a reconhecer o token novo

        ErroProvisionamento e = assertThrows(ErroProvisionamento.class, () -> lab.registrarToken(NOME_DO_MAC));

        assertEquals("token_nao_validou", e.codigo());
        assertTrue(cofre.tokenDoServidor(CofreFalso.ID).isEmpty(), "nada vai ao cofre antes do teste passar");
        assertTrue(agente.tokens.isEmpty(), "o registro órfão foi revogado");
        assertTrue(agente.comandosCli.stream().anyMatch(c -> c.endsWith("token-revogar")));
    }

    @Test
    void registrarTokenComNomeInvalidoNemTentaERegistrarSemSessaoFalhaClaro() {
        criar(PedidoDeCodigo2fa.NUNCA); // ainda não iniciado: sem túnel
        assertThrows(IllegalArgumentException.class, () -> lab.registrarToken("Nome Inválido!"));
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class, () -> lab.registrarToken(NOME_DO_MAC));
        assertEquals(ErroProvisionamento.SEM_SESSAO, e.codigo());
        assertTrue(agente.comandosCli.isEmpty());
    }

    @Test
    void reprovisionarSubstituiOTokenQueOAgenteJaTinhaComOMesmoNome() throws Exception {
        // Cofre recriado: o agente guarda o hash de um token que o app já não tem, com o nome deste Mac.
        agente.tokens.clear();
        String hashAntigo = "a".repeat(64);
        agente.tokens.put(hashAntigo, NOME_DO_MAC);
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
        ssh.aceitar(cofre.par().getPublic());
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO); // agente sem exigência de credencial: passa sem token

        // Registrar direto esbarra no nome já usado por outro hash (CONTRATO-API seção 6)...
        ErroProvisionamento conflito = assertThrows(ErroProvisionamento.class, () -> lab.registrarToken(NOME_DO_MAC));
        assertEquals(ErroProvisionamento.TOKEN_CONFLITO, conflito.codigo());
        assertTrue(cofre.tokenDoServidor(CofreFalso.ID).isEmpty());
        assertEquals(NOME_DO_MAC, agente.tokens.get(hashAntigo), "o registro antigo segue intacto depois do conflito");

        // ...e reprovisionar revoga a entrada pelo nome e registra a nova.
        lab.reprovisionarToken(NOME_DO_MAC);

        TokenAgente guardado = cofre.tokenDoServidor(CofreFalso.ID).orElseThrow();
        assertEquals(1, agente.tokens.size());
        assertEquals(NOME_DO_MAC, agente.tokens.get(guardado.hashSha256Hex()));
        assertFalse(agente.tokens.containsKey(hashAntigo));
        List<String> cli = agente.comandosCli.stream().map(c -> c.substring(c.lastIndexOf(' ') + 1)).toList();
        assertEquals(List.of("token-registrar", "token-revogar", "token-registrar"), cli);
        assertEquals(NOME_DO_MAC, agente.entradasCli.get(1).strip(), "o nome vai por stdin, nunca na linha de comando");
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> NOME_DO_MAC.equals(
            lab.estado().versao() == null ? null : lab.estado().versao().cliente()));
    }

    @Test
    void reprovisionarSemEntradaAntigaToleraTokenInexistente() throws Exception {
        agente.tokens.clear();
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
        ssh.aceitar(cofre.par().getPublic());
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);

        lab.reprovisionarToken(NOME_DO_MAC);

        assertEquals(1, agente.tokens.size());
        assertEquals(NOME_DO_MAC, cofre.servidor(CofreFalso.ID).orElseThrow().nomeDoDispositivo());
    }

    @Test
    void reprovisionarSemSessaoNaoRevogaNadaEComNomeInvalidoNemTenta() {
        criar(PedidoDeCodigo2fa.NUNCA); // sem iniciar: sem túnel
        assertThrows(IllegalArgumentException.class, () -> lab.reprovisionarToken("Nome Inválido!"));
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class, () -> lab.reprovisionarToken(NOME_DO_MAC));
        assertEquals(ErroProvisionamento.SEM_SESSAO, e.codigo());
        assertTrue(agente.comandosCli.isEmpty(), "sem sessão, nem a revogação sai: o usuário não fica sem token por nada");
        assertEquals(NOME_DO_MAC, agente.tokens.get(token.hashSha256Hex()));
    }

    // ------------------------------------------------------------------------------------------------
    // 2FA de ponta a ponta
    // ------------------------------------------------------------------------------------------------

    @Test
    void totpRequeridoAbreOPedidoUmaVezConfirmaEConectaSemPerderAChamada() {
        agente.totpAtivo = true; // 2FA ativo e este Mac ainda não confirmado
        AtomicInteger pedidos = new AtomicInteger();
        PedidoDeCodigo2fa pedido = (servidor, aviso) -> {
            pedidos.incrementAndGet();
            assertEquals("lab", servidor);
            return Optional.of(AgenteDoLab.CODIGO_CERTO);
        };
        criar(pedido).iniciar();

        aguardarEstado(EstadoConexao.CONECTADO);
        assertEquals(1, pedidos.get(), "um único diálogo");
        assertEquals(Estado2fa.ATIVO, lab.estado().estado2fa());
        assertEquals(Estado2fa.ATIVO, cofre.servidor(CofreFalso.ID).orElseThrow().estado2fa(), "a dica foi para o cofre");
        assertTrue(agente.dispositivosConfirmados.contains(NOME_DO_MAC));
        // A chamada original (o GET /api/versao da conexão) não se perdeu: depois dela o app já consulta.
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().limites() != null);
    }

    @Test
    void cancelarOCodigoLevaAAguardando2faSemLacoEDigitarCodigoRetoma() {
        agente.totpAtivo = true;
        AtomicInteger pedidos = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean cancelar = new java.util.concurrent.atomic.AtomicBoolean(true);
        PedidoDeCodigo2fa pedido = (servidor, aviso) -> {
            pedidos.incrementAndGet();
            return cancelar.get() ? Optional.empty() : Optional.of(AgenteDoLab.CODIGO_CERTO);
        };
        criar(pedido).iniciar();

        aguardarEstado(EstadoConexao.AGUARDANDO_2FA);
        assertEquals(1, pedidos.get());
        Espera.naoAcontece(Duration.ofMillis(1200), "novo pedido sem o usuário pedir", () -> pedidos.get() > 1);
        assertNotNull(lab.tunel().orElse(null), "o túnel segue de pé; o terminal não depende do agente");

        // O botão "Digitar código" da faixa de aviso: agora o usuário digita.
        cancelar.set(false);
        lab.digitarCodigo2fa();
        aguardarEstado(EstadoConexao.CONECTADO);
        assertEquals(2, pedidos.get());
    }

    @Test
    void configurar2faPorSshGeraConfirmaEDesativa() throws Exception {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        assertEquals(Estado2fa.DESLIGADO, lab.estado().estado2fa());

        try (SegredoTotp segredo = lab.iniciarConfiguracao2fa()) {
            assertEquals(AgenteDoLab.SEGREDO, segredo.segredo());
            assertTrue(segredo.uri().startsWith("otpauth://totp/Lab%20Desktop:lab?secret="));
            assertFalse(segredo.toString().contains(AgenteDoLab.SEGREDO));

            Optional<Instant> ate = lab.confirmarConfiguracao2fa("123 456");
            assertEquals(Optional.of(Instant.parse("2026-11-19T12:00:00.000Z")), ate);
        }
        assertEquals(Estado2fa.ATIVO, lab.estado().estado2fa());
        assertTrue(agente.totpAtivo);
        // O código foi só por stdin, junto do nome do dispositivo deste Mac; nunca na linha de comando.
        assertTrue(agente.entradasCli.contains("123456\n" + NOME_DO_MAC + "\n"));
        assertTrue(agente.comandosCli.stream().noneMatch(c -> c.contains("123456")));
        // E este Mac continua entrando sem pedir um segundo código (foi confirmado na mesma operação).
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());

        lab.desativar2fa(AgenteDoLab.CODIGO_CERTO);
        assertFalse(agente.totpAtivo);
        assertEquals(Estado2fa.DESLIGADO, lab.estado().estado2fa());
        assertEquals(Estado2fa.DESLIGADO, cofre.servidor(CofreFalso.ID).orElseThrow().estado2fa());
    }

    @Test
    void codigoErradoNaConfiguracaoViraErroComCodigoDoAgente() throws Exception {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        lab.iniciarConfiguracao2fa().close();
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class, () -> lab.confirmarConfiguracao2fa("000000"));
        assertEquals(ErroProvisionamento.TOTP_INCORRETO, e.codigo());
        assertFalse(agente.totpAtivo, "pendente não exige nada");
        assertEquals(Estado2fa.DESLIGADO, lab.estado().estado2fa());
    }

    // ------------------------------------------------------------------------------------------------
    // Ações
    // ------------------------------------------------------------------------------------------------

    @Test
    void acaoTerminadaForcaORefreshDosTopicosAfetadosSemEsperarOIntervalo() throws Exception {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        List<VisaoProjetos> recebidos = new CopyOnWriteArrayList<>();
        lab.assinar(Topico.PROJETOS, recebidos::add);
        Espera.ate(PRAZO, "primeira consulta", () -> agente.consultasDeProjetos.get() == 1);
        int limitesAntes = agente.chamadasDeLimites.get();

        lab.acoes().iniciar("vendas");

        // O intervalo normal seria de 10 s; o refresh forçado chega em instantes.
        Espera.ate(Duration.ofSeconds(5), "refresh de projetos depois da ação", () -> agente.consultasDeProjetos.get() == 2);
        Espera.ate(Duration.ofSeconds(5), "refresh dos limites depois da ação", () -> agente.chamadasDeLimites.get() > limitesAntes);
        assertEquals("POST /api/projetos/vendas/iniciar",
            agente.http.pedidos.stream().filter(p -> p.metodo().equals("POST")).map(p -> p.metodo() + " " + p.caminho()).findFirst().orElseThrow());
    }

    @Test
    void acaoQueFalhaCom409TambemForcaORefreshEPropagaOConflito() throws Exception {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        lab.assinar(Topico.PROJETOS, valor -> { });
        Espera.ate(PRAZO, "primeira consulta", () -> agente.consultasDeProjetos.get() == 1);

        Conflito c = assertThrows(Conflito.class, () -> lab.acoes().retomar("app-loja", false, true));

        assertTrue(c.aindaEstoura());
        Espera.ate(Duration.ofSeconds(5), "refresh mesmo com a ação recusada", () -> agente.consultasDeProjetos.get() == 2);
    }

    @Test
    void acaoSemConexaoFalhaComErroDeRedeSemTocarNaRede() {
        criar(PedidoDeCodigo2fa.NUNCA); // não iniciado
        assertThrows(br.com.labdesktop.api.ErroApi.ErroRede.class, () -> lab.acoes().iniciar("vendas"));
        assertTrue(agente.http.pedidos.isEmpty());
    }

    @Test
    void oObservadorDeEstadoRecebeAsMudancasEmOrdem() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        List<EstadoConexao> vistos = estados.stream().map(EstadoServidor::conexao).distinct().toList();
        assertEquals(EstadoConexao.DESCONECTADO, vistos.get(0));
        assertTrue(vistos.indexOf(EstadoConexao.CONECTANDO) < vistos.indexOf(EstadoConexao.CONECTADO), vistos.toString());
    }

    @Test
    void naoEhPossivelCriarServidorQueOCofreNaoTem() {
        assertThrows(IllegalArgumentException.class,
            () -> new ServidorLab("nao-existe", cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida()));
    }

    @Test
    void limitesDoEstadoSaoOsDoMock() {
        criar(PedidoDeCodigo2fa.NUNCA).iniciar();
        aguardarEstado(EstadoConexao.CONECTADO);
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().limites() != null);
        LimitesConta l = lab.estado().limites();
        assertEquals(90, l.freio().pct());
        assertInstanceOf(LimitesConta.class, l);
    }
}
