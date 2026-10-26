package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoLimite;
import br.com.labdesktop.ui.dialogos.DialogoLimiteExcedido;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Todas as ações de projeto, de ponta a ponta contra o servidor SSH embutido e o agente falso: o botão fica ocupado,
 * a chamada certa chega ao agente, a mensagem sai e o diálogo do 409 abre com os números. Nada de rede na EDT.
 */
@ExtendWith(VerificaEdt.class)
class FluxosDeProjetoTest {

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private FluxosDeProjeto fluxos;
    private final List<String> mudancasDeOcupacao = new ArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi();
        fluxos = Visual.naEdt(() -> new FluxosDeProjeto(cenario.lab, avisos, dialogos, true));
        Visual.naEdt(() -> fluxos.observar(mudancasDeOcupacao::add));
    }

    @AfterEach
    void desmontar() throws Exception {
        cenario.close();
    }

    private void esperarAviso(String tipo, String trecho) {
        Espera.ate(Duration.ofSeconds(15), () -> "aviso " + tipo + " '" + trecho + "' (houve " + avisos.todos + ")",
            () -> avisos.disse(tipo, trecho));
    }

    private void esperarLivre(String projeto) {
        Espera.ate(Duration.ofSeconds(15), () -> "livre: " + projeto, () -> Visual.naEdt(() -> fluxos.ocupadas(projeto).isEmpty()));
    }

    @Test
    void iniciarOcupaOBotaoChamaOAgenteEDizQueEstaNoAr() {
        Visual.naEdt(() -> fluxos.iniciar("app-loja"));
        assertTrue(Visual.naEdt(() -> fluxos.ocupado("app-loja", FluxosDeProjeto.INICIAR)), "ocupado já no clique, antes da rede");
        esperarAviso("ok", "app-loja: no ar.");
        esperarLivre("app-loja");
        assertTrue(cenario.recebeu("POST", "/api/projetos/app-loja/iniciar"));
        assertEquals(List.of("app-loja", "app-loja"), mudancasDeOcupacao, "avisou ao ocupar e ao livrar");
    }

    @Test
    void cliqueRepetidoEnquantoARodaNaoDisparaOutraChamada() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/parar") ? Resposta.comAtraso(Resposta.json(200, "{\"ok\":true}"), 600) : null;
        Visual.naEdt(() -> {
            fluxos.parar("app-loja");
            fluxos.parar("app-loja");
            fluxos.parar("app-loja");
        });
        esperarAviso("ok", "app-loja: parado.");
        esperarLivre("app-loja");
        assertEquals(1, cenario.pedidos("POST", "/api/projetos/app-loja/parar").size());
    }

    @Test
    void pararReiniciarAbrirEFecharChamamAsRotasCertas() {
        Visual.naEdt(() -> fluxos.parar("vendas"));
        esperarAviso("ok", "vendas: parado.");
        Visual.naEdt(() -> fluxos.reiniciar("vendas"));
        esperarAviso("ok", "vendas: reiniciado.");
        Visual.naEdt(() -> fluxos.abrirSessao("vendas"));
        esperarAviso("ok", "sessão do Claude aberta");
        assertTrue(avisos.disse("ok", "use o botão Terminal na aba Sessão do Claude"), "manda o usuário para o terminal da aba Sessão do Claude");
        Visual.naEdt(() -> fluxos.fecharSessao("vendas"));
        esperarAviso("ok", "sessão do Claude encerrada");
        esperarLivre("vendas");
        assertTrue(cenario.recebeu("POST", "/api/projetos/vendas/parar"));
        assertTrue(cenario.recebeu("POST", "/api/projetos/vendas/reiniciar"));
        assertTrue(cenario.recebeu("POST", "/api/projetos/vendas/claude/abrir"));
        assertTrue(cenario.recebeu("POST", "/api/projetos/vendas/claude/fechar"));
    }

    @Test
    void abrirSessaoPausadaDa409EAMensagemDoServidorVaiNumAviso() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/claude/abrir")
            ? Resposta.json(409, "{\"erro\":\"Sessão pausada pelo limite (22% de 20%). Use Retomar.\"}") : null;
        Visual.naEdt(() -> fluxos.abrirSessao("app-loja"));
        esperarAviso("atencao", "Sessão pausada pelo limite (22% de 20%). Use Retomar.");
        esperarLivre("app-loja");
    }

    @Test
    void erroDoServidorViraAvisoDeErroComOProjeto() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/iniciar") ? Resposta.json(500, "{\"erro\":\"[docker-compose.yml] porta em uso\"}") : null;
        Visual.naEdt(() -> fluxos.iniciar("app-loja"));
        esperarAviso("erro", "Erro em app-loja: [docker-compose.yml] porta em uso");
        esperarLivre("app-loja");
    }

    @Test
    void semRespostaDoAgenteDizQueAAcaoPodeTerFuncionado() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/reiniciar") ? Resposta.semResposta() : null;
        Visual.naEdt(() -> fluxos.reiniciar("app-loja"));
        esperarAviso("atencao", "A ação pode ter funcionado");
        esperarLivre("app-loja");
    }

    @Test
    void reabrirPedeConfirmacaoEOCancelarNaoChamaNada() {
        Visual.naEdt(() -> fluxos.pedirReabrir("app-loja"));
        DialogoConfirmar d = dialogos.ultimoComo(DialogoConfirmar.class);
        assertEquals("Reabrir sessão do Claude", d.titulo());
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        Espera.naoAcontece(Duration.ofMillis(500), "reabrir sem confirmação", () -> cenario.recebeu("POST", "/api/projetos/app-loja/claude/reabrir"));

        Visual.naEdt(() -> fluxos.pedirReabrir("app-loja"));
        DialogoConfirmar d2 = dialogos.ultimoComo(DialogoConfirmar.class);
        Visual.naEdt(() -> d2.botaoConfirmar().doClick());
        esperarAviso("ok", "app-loja: sessão reaberta");
        assertTrue(cenario.recebeu("POST", "/api/projetos/app-loja/claude/reabrir"));
    }

    private static Limite limiteDePausa() {
        return new Limite(20, 22.4, "pausado", false, null, null, null);
    }

    @Test
    void retomarQueAindaEstouraAbreODialogoComOsNumerosEOLiberarRepeteComLiberarTrue() {
        // o agente de teste responde 409 com os 4 números a todo retomar, e 200 só com liberar:true
        cenario.agente.excecoes = p -> {
            if (p.caminho().endsWith("/limite/retomar") && p.corpo().contains("\"liberar\":true")) {
                return Resposta.json(200, "{\"ok\":true,\"limite\":{\"teto_pct\":20,\"usado_pp\":22.4,\"estado\":\"ok\",\"liberado\":true},"
                    + "\"sessao\":{\"aberta\":true,\"container\":\"lab-claude-app-loja\",\"url\":\"/sessao/app-loja\"}}");
            }
            return null;
        };
        Visual.naEdt(() -> fluxos.retomar("app-loja", limiteDePausa()));
        Espera.ate(Duration.ofSeconds(15), () -> "diálogo do 409", () -> dialogos.ultimo() instanceof DialogoLimiteExcedido);
        DialogoLimiteExcedido d = dialogos.ultimoComo(DialogoLimiteExcedido.class);
        assertEquals("Consumiu 22% de 20%", d.tituloDoTexto());
        assertTrue(d.ofereceAumentar());
        esperarLivre("app-loja");
        var primeiro = cenario.pedidos("POST", "/api/projetos/app-loja/limite/retomar");
        assertEquals(1, primeiro.size());
        assertTrue(primeiro.get(0).corpo().contains("\"liberar\":false") && primeiro.get(0).corpo().contains("\"reabrir\":true"));

        Visual.naEdt(() -> d.botaoLiberar().doClick());
        esperarAviso("ok", "app-loja: liberada até a semana virar; sessão retomada.");
        var todos = cenario.pedidos("POST", "/api/projetos/app-loja/limite/retomar");
        assertEquals(2, todos.size());
        assertTrue(todos.get(1).corpo().contains("\"liberar\":true"), todos.get(1).corpo());
    }

    @Test
    void aumentarOLimiteSalvaOTetoETentaRetomarDeNovo() {
        cenario.agente.excecoes = p -> {
            if (p.caminho().endsWith("/limite") && p.metodo().equals("PUT")) {
                return Resposta.json(200, "{\"ok\":true,\"limite\":{\"teto_pct\":40,\"usado_pp\":22.4,\"estado\":\"ok\"}}");
            }
            // depois de o teto subir, o segundo retomar passa
            long retomadas = cenario.pedidos("POST", "/api/projetos/app-loja/limite/retomar").size();
            if (p.caminho().endsWith("/limite/retomar") && retomadas > 1) {
                return Resposta.json(200, "{\"ok\":true,\"limite\":{\"estado\":\"ok\"},\"sessao\":{\"aberta\":true}}");
            }
            return null;
        };
        Visual.naEdt(() -> fluxos.retomar("app-loja", limiteDePausa()));
        Espera.ate(Duration.ofSeconds(15), () -> "diálogo do 409", () -> dialogos.ultimo() instanceof DialogoLimiteExcedido);
        Visual.naEdt(() -> dialogos.ultimoComo(DialogoLimiteExcedido.class).botaoAumentar().doClick());

        Espera.ate(Duration.ofSeconds(10), () -> "diálogo Definir limite", () -> dialogos.ultimo() instanceof DialogoLimite);
        DialogoLimite limite = dialogos.ultimoComo(DialogoLimite.class);
        Visual.naEdt(() -> limite.campoDoValor().setText("40"));
        Visual.naEdt(() -> limite.botaoSalvar().doClick());
        esperarAviso("ok", "app-loja: limite de 40% da semana definido.");
        esperarAviso("ok", "app-loja: sessão retomada.");
        var put = cenario.pedidos("PUT", "/api/projetos/app-loja/limite");
        assertEquals(1, put.size());
        assertEquals("{\"teto_pct\":40}", put.get(0).corpo());
        assertEquals(2, cenario.pedidos("POST", "/api/projetos/app-loja/limite/retomar").size());
    }

    @Test
    void conflitoSemNumerosNaoAbreODialogoSoDizOMotivo() {
        cenario.agente.excecoes = p -> p.caminho().endsWith("/limite/retomar")
            ? Resposta.json(409, "{\"erro\":\"A sessão não estava pausada, mas já estoura o teto.\"}") : null;
        Visual.naEdt(() -> fluxos.retomar("app-loja", limiteDePausa()));
        esperarAviso("atencao", "A sessão não estava pausada, mas já estoura o teto.");
        assertTrue(dialogos.mostrados.isEmpty(), "sem os quatro números não há o que mostrar no diálogo");
    }

    @Test
    void definirLimiteEnviaOPutERemoveOTetoComNull() {
        cenario.agente.excecoes = p -> p.metodo().equals("PUT") ? Resposta.json(200, "{\"ok\":true,\"limite\":{\"estado\":\"ok\"}}") : null;
        Visual.naEdt(() -> fluxos.definirLimite("vendas", new Limite(30, 5.0, "ok", false, null, null, null), null));
        DialogoLimite d = dialogos.ultimoComo(DialogoLimite.class);
        Visual.naEdt(() -> d.caixaSemLimite().doClick());
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        esperarAviso("ok", "vendas: limite removido.");
        assertEquals("{\"teto_pct\":null}", cenario.pedidos("PUT", "/api/projetos/vendas/limite").get(0).corpo());
    }

    @Test
    void enviarCodigoDeLoginChamaARotaEOCodigoNuncaApareceNoAviso() {
        Visual.naEdt(() -> fluxos.enviarCodigoDeLogin("app-loja", "abc#123.XY"));
        esperarAviso("ok", "app-loja: código enviado para o terminal.");
        var p = cenario.pedidos("POST", "/api/projetos/app-loja/claude/codigo");
        assertEquals(1, p.size());
        assertEquals("{\"codigo\":\"abc#123.XY\"}", p.get(0).corpo());
        assertFalse(avisos.todos.stream().anyMatch(a -> a.texto().contains("abc#123")), "o código não vai para a mensagem");
    }

    @Test
    void codigoDeLoginForaDoFormatoNaoChegaAoAgente() {
        Visual.naEdt(() -> fluxos.enviarCodigoDeLogin("app-loja", "ab"));
        Espera.ate(Duration.ofSeconds(10), () -> "erro", () -> avisos.disse("erro", "Cole exatamente o código"));
        assertFalse(cenario.recebeu("POST", "/api/projetos/app-loja/claude/codigo"));
    }
}
