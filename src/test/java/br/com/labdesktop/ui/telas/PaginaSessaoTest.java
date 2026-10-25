package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.TelaProjetoTest.Cena;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A aba Sessão do Claude nos estados aberta, fechada, desatualizada, pausada e com login pendente; a tela em texto somente
 * leitura; a caixa de mensagem e os botões Enter e Esc. As chamadas ao agente (mensagem, teclas, abrir, fechar) estão em
 * {@code NavegacaoDoProjetoTest}, contra o servidor embutido.
 */
@ExtendWith(VerificaEdt.class)
class PaginaSessaoTest {

    private static Cena cena(boolean acoes, DetalheProjeto d) {
        Visual.tema(Tema.CLARO);
        return new Cena(acoes, Aba.SESSAO).com(EstadoConexao.CONECTADO, d);
    }

    private static DetalheProjeto fechada() {
        return TelaProjetoTest.detalheCom("\"aberta\": true", "\"aberta\": false");
    }

    private static String selo(br.com.labdesktop.ui.componentes.Selo s) {
        return Visual.naEdt(s::texto);
    }

    // ---------------------------------------------------------------------------------------------
    // Estados
    // ---------------------------------------------------------------------------------------------

    @Test
    void sessaoAbertaDizDesdeQuandoEQueEstaAtualizada() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        assertEquals("SESSÃO ABERTA", selo(p.seloDaSituacao()));
        assertEquals("aberta desde 24/10 14:10 (há 4 h)", Visual.naEdt(p::textoDoQuando));
        assertTrue(Visual.naEdt(p.seloDeAtualizacao()::isVisible));
        assertEquals("ATUALIZADA", selo(p.seloDeAtualizacao()));
        assertFalse(p.loginPendente());
    }

    @Test
    void botoesDaSessaoAbertaSaoFecharReabrirEOTerminalEmBreve() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        assertFalse(Visual.naEdt(p.botaoAbrir()::isVisible));
        assertTrue(Visual.naEdt(p.botaoFechar()::isVisible));
        assertTrue(Visual.naEdt(p.botaoReabrir()::isVisible));
        assertFalse(Visual.naEdt(p.botaoRetomar()::isVisible));
        assertEquals("Terminal (em breve)", Visual.naEdt(() -> p.botaoTerminal().getText()));
        assertFalse(Visual.naEdt(p.botaoTerminal()::isEnabled), "o terminal interativo é de outra etapa");
        assertTrue(Visual.naEdt(() -> p.botaoTerminal().getToolTipText()).contains("próxima etapa"));
    }

    @Test
    void sessaoFechadaOfereceAbrirENaoTemTela() {
        Cena c = cena(true, fechada());
        PaginaSessao p = c.tela.sessao();
        assertEquals("SESSÃO FECHADA", selo(p.seloDaSituacao()));
        assertEquals("Nenhuma sessão do Claude aberta neste projeto.", Visual.naEdt(p::textoDoQuando));
        assertTrue(Visual.naEdt(p.botaoAbrir()::isVisible));
        assertFalse(Visual.naEdt(p.botaoFechar()::isVisible));
        assertFalse(Visual.naEdt(p.botaoReabrir()::isVisible));
        assertFalse(Visual.naEdt(p.seloDeAtualizacao()::isVisible));
        assertEquals("A sessão está fechada, então não há tela para ver. Use Abrir sessão para começar.", Visual.naEdt(p::textoDaMensagemDaTela));
        assertEquals("", Visual.naEdt(p::textoDaTela));
        assertFalse(Visual.naEdt(p.campoDaMensagem()::isEnabled), "sem sessão não há para quem escrever");
        assertFalse(Visual.naEdt(p.botaoEnviar()::isEnabled));
        assertFalse(Visual.naEdt(p.botaoEnter()::isEnabled));
        assertFalse(Visual.naEdt(p.botaoEsc()::isEnabled));
    }

    @Test
    void sessaoDesatualizadaTemSeloComOQueFalta() {
        Cena c = cena(true, TelaProjetoTest.detalheCom("\"atualizada\": true, \"faltando\": []",
            "\"atualizada\": false, \"faltando\": [\"imagem antiga (Claude desatualizado)\"]"));
        PaginaSessao p = c.tela.sessao();
        assertEquals("DESATUALIZADA", selo(p.seloDeAtualizacao()));
        assertTrue(Visual.naEdt(() -> p.seloDeAtualizacao().getToolTipText()).contains("Falta: imagem antiga (Claude desatualizado)"));
    }

    @Test
    void sessaoPausadaPeloLimiteOfereceRetomarEExplica() {
        Cena c = cena(true, TelaProjetoTest.detalhePausado());
        PaginaSessao p = c.tela.sessao();
        assertEquals("PAUSADA PELO LIMITE", selo(p.seloDaSituacao()));
        assertTrue(Visual.naEdt(p::textoDoQuando).startsWith("22% de 20% da semana"), Visual.naEdt(p::textoDoQuando));
        assertTrue(Visual.naEdt(p::textoDoQuando).endsWith("A sessão só volta com Retomar."));
        assertTrue(Visual.naEdt(p.botaoRetomar()::isVisible));
        assertFalse(Visual.naEdt(p.botaoAbrir()::isVisible), "pausada: o caminho é Retomar");
        assertEquals("A sessão está pausada: não há tela para ver. Use Retomar para voltar.", Visual.naEdt(p::textoDaMensagemDaTela));
    }

    @Test
    void sessaoPausadaPeloFreio() {
        String json = TelaProjetoTest.detalheEmUmaLinha().replace("\"aberta\": true", "\"aberta\": false")
            .replace("\"estado\": \"ok\"", "\"estado\": \"pausado\"").replace("\"pausa\": null", "\"pausa\": {\"motivo\": \"freio\", \"em\": null}");
        Cena c = cena(true, FixturesDeApi.de(json, DetalheProjeto.class));
        assertEquals("PAUSADA PELO FREIO", selo(c.tela.sessao().seloDaSituacao()));
    }

    // ---------------------------------------------------------------------------------------------
    // A tela da sessão
    // ---------------------------------------------------------------------------------------------

    @Test
    void antesDaPrimeiraTelaDizQueEstaLendo() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        assertEquals("Lendo a tela da sessão…", Visual.naEdt(c.tela.sessao()::textoDaMensagemDaTela));
        assertFalse(Visual.naEdt(c.tela.sessao().seloDeAtividade()::isVisible));
    }

    @Test
    void telaExecutandoApareceEmTextoComOsSimbolosDeTerminalTrocados() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-executando")));
        String tela = Visual.naEdt(p::textoDaTela);
        assertTrue(tela.contains("Bash(npm test)"), tela);
        assertTrue(tela.contains("12 passaram, 0 falharam"));
        assertFalse(tela.contains("⎿") || tela.contains("✻"), "símbolos que a fonte não tem não viram quadradinho: " + tela);
        assertTrue(tela.contains("└"), "o ⎿ ganhou um parecido");
        assertEquals("EXECUTANDO", selo(p.seloDeAtividade()));
        assertTrue(Visual.naEdt(() -> p.seloDeAtividade().getToolTipText()).startsWith("Executando: Pensando…"));
    }

    @Test
    void perguntaPendenteEEsperandoVoce() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-pergunta")));
        assertEquals("PERGUNTA PENDENTE", selo(p.seloDeAtividade()));
        assertTrue(Visual.naEdt(p::textoDaTela).contains("Quer continuar?"));
        assertTrue(Visual.naEdt(() -> p.seloDeAtividade().getToolTipText()).contains("Quer continuar?"));
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-esperando")));
        assertEquals("ESPERANDO VOCÊ", selo(p.seloDeAtividade()));
    }

    @Test
    void telaSomenteLeituraNaoEEditavel() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        var area = Visual.naEdt(() -> (br.com.labdesktop.ui.componentes.AreaMonoespacada) c.tela.sessao().rolagemDaTela().getViewport().getView());
        assertFalse(Visual.naEdt(area::isEditable));
        assertEquals(Boolean.TRUE, Visual.naEdt(() -> area.getClientProperty("html.disable")));
    }

    @Test
    void servidorDizendoQueNaoEstaAbertaMostraAExplicacao() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(null));
        assertTrue(Visual.naEdt(p::textoDaMensagemDaTela).startsWith("O servidor diz que a sessão não está aberta."));
        assertEquals("", Visual.naEdt(p::textoDaTela));
    }

    @Test
    void telaVaziaNaoEUmaAreaPretaSemExplicacao() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.de("{\"tela\":\"  \"}", br.com.labdesktop.api.modelo.TelaClaude.class)));
        assertEquals("A tela da sessão está vazia agora.", Visual.naEdt(p::textoDaMensagemDaTela));
    }

    @Test
    void fecharASessaoDepoisDeVerATelaLimpaATela() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-executando")));
        assertFalse(Visual.naEdt(p::textoDaTela).isEmpty());
        c.com(EstadoConexao.CONECTADO, fechada());
        assertEquals("", Visual.naEdt(p::textoDaTela), "sessão fechada não deixa a tela velha");
        c.com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        assertEquals("Lendo a tela da sessão…", Visual.naEdt(p::textoDaMensagemDaTela), "reaberta: lê de novo, não mostra a de antes");
    }

    // ---------------------------------------------------------------------------------------------
    // Login pendente
    // ---------------------------------------------------------------------------------------------

    @Test
    void telaPedindoOCodigoMostraOLoginPendenteComOCampo() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-login")));
        assertTrue(p.loginPendente());
        assertEquals("LOGIN PENDENTE", selo(p.seloDeLogin()));
        assertTrue(Visual.naEdt(p.faixaDeLogin()::isVisible));
        assertTrue(Visual.naEdt(p.campoDoCodigo()::isVisible));
        assertFalse(Visual.naEdt(p.botaoEnviarCodigo()::isEnabled), "sem código digitado, Enviar espera");
        Visual.naEdt(() -> p.campoDoCodigo().setText("abc#123.XY"));
        assertTrue(Visual.naEdt(p.botaoEnviarCodigo()::isEnabled));
        assertFalse(Visual.naEdt(p.botaoAbrirLogin()::isVisible), "o endereço de login só vem da lista; sem ele não há o que abrir");
    }

    @Test
    void comOEnderecoDeLoginDaListaOBotaoAbreSoOQuePassaNaLista() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        c.urlDeLogin = "https://claude.ai/oauth/authorize?code=true&client_id=exemplo";
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-executando")));
        assertTrue(p.loginPendente(), "a lista já tinha visto o login");
        assertTrue(Visual.naEdt(p.botaoAbrirLogin()::isVisible));
        Visual.naEdt(() -> p.botaoAbrirLogin().doClick());
        assertEquals(List.of("https://claude.ai/oauth/authorize?code=true&client_id=exemplo"),
            c.navegador.abertas.stream().map(java.net.URI::toString).toList());
        c.urlDeLogin = "https://claude.ai.malvado.example.org/oauth";
        Visual.naEdt(() -> p.botaoAbrirLogin().doClick());
        assertEquals(1, c.navegador.abertas.size(), "host fora da lista não abre");
        assertTrue(c.avisos.disse("atencao", "lista de endereços permitidos"));
    }

    @Test
    void semAcoesOLoginPendenteSoInforma() {
        Cena c = cena(false, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.receberTela(FixturesDeApi.telaSintetica("tela-login")));
        assertTrue(Visual.naEdt(p.faixaDeLogin()::isVisible));
        assertFalse(Visual.naEdt(p.campoDoCodigo()::isVisible));
        assertFalse(Visual.naEdt(p.botaoEnviarCodigo()::isVisible));
    }

    @Test
    void sessaoSemLoginNaoMostraAFaixa() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.sessao().receberTela(FixturesDeApi.telaSintetica("tela-executando")));
        assertFalse(Visual.naEdt(c.tela.sessao().faixaDeLogin()::isVisible));
        assertFalse(Visual.naEdt(c.tela.sessao().seloDeLogin()::isVisible));
    }

    // ---------------------------------------------------------------------------------------------
    // Mensagem e teclas
    // ---------------------------------------------------------------------------------------------

    @Test
    void comSessaoAbertaEAcoesAsCaixasDeMensagemEstaoLigadas() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        assertTrue(Visual.naEdt(p.campoDaMensagem()::isEnabled));
        assertTrue(Visual.naEdt(p.botaoEnviar()::isEnabled));
        assertTrue(Visual.naEdt(p.botaoEnter()::isEnabled));
        assertTrue(Visual.naEdt(p.botaoEsc()::isEnabled));
        assertEquals("Enter", Visual.naEdt(() -> p.botaoEnter().getText()));
        assertEquals("Esc", Visual.naEdt(() -> p.botaoEsc().getText()));
        assertTrue(Visual.naEdt(() -> p.botaoEnter().getToolTipText()).contains("Aperta Enter"));
    }

    @Test
    void semAcoesNaoHaComoEscreverEATelaAvisa() {
        Cena c = cena(false, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        assertFalse(Visual.naEdt(p.campoDaMensagem()::isEnabled));
        assertFalse(Visual.naEdt(p.botaoEnviar()::isEnabled));
        assertTrue(Visual.naEdt(p::somenteLeituraVisivel));
        for (var b : List.of(p.botaoAbrir(), p.botaoFechar(), p.botaoReabrir(), p.botaoRetomar())) {
            assertFalse(Visual.naEdt(b::isVisible), b.getText());
        }
        assertEquals("Somente leitura nesta versão", Visual.naEdt(() -> p.campoDaMensagem()
            .getClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT)));
    }

    @Test
    void mensagemMaiorQueOMaximoNaoSaiEAvisa() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.campoDaMensagem().setText("x".repeat(4001)));
        Visual.naEdt(() -> p.botaoEnviar().doClick());
        assertTrue(c.avisos.disse("atencao", "passa de 4000 caracteres"), c.avisos.todos.toString());
        assertEquals(4001, Visual.naEdt(() -> p.campoDaMensagem().getText().length()), "o que o usuário escreveu não se perde");
        assertTrue(Visual.naEdt(() -> c.fluxos.ocupadas("orbita").isEmpty()), "nada foi enviado");
    }

    @Test
    void mensagemVaziaNaoFazNada() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaSessao p = c.tela.sessao();
        Visual.naEdt(() -> p.campoDaMensagem().setText("   "));
        Visual.naEdt(() -> p.botaoEnviar().doClick());
        assertTrue(c.avisos.todos.isEmpty());
        assertTrue(Visual.naEdt(() -> c.fluxos.ocupadas("orbita").isEmpty()));
    }

    @Test
    void asAcoesDeSessaoPedemConfirmacaoQuandoInterrompemATarefa() {
        Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.sessao().botaoReabrir().doClick());
        assertNotNull(c.dialogos.ultimoComo(br.com.labdesktop.ui.dialogos.DialogoConfirmar.class), "Reabrir avisa que a tarefa é interrompida");
    }

    // ---------------------------------------------------------------------------------------------
    // Cabe, e a tela cresce com a janela
    // ---------------------------------------------------------------------------------------------

    @Test
    void cabeSemRolagemDaPaginaEAreaDaTelaCresceComAJanela() {
        int[] alturaDaTela = new int[2];
        int i = 0;
        for (int[] j : new int[][] {{1100, 700}, {1440, 860}}) {
            Cena c = cena(true, FixturesDeApi.detalheDoProjetoSintetico());
            Visual.naEdt(() -> c.tela.sessao().receberTela(FixturesDeApi.telaSintetica("tela-login")));
            Visual.renderizar(c.tela, j[0] - BarraServidores.LARGURA, j[1] - 28);
            assertFalse(Visual.naEdt(() -> c.tela.sessao().rolagem().getVerticalScrollBar().isVisible()), "sem rolagem em " + j[0]);
            assertFalse(Visual.naEdt(() -> c.tela.sessao().rolagem().rola()));
            alturaDaTela[i++] = Visual.naEdt(() -> c.tela.sessao().rolagemDaTela().getHeight());
        }
        assertTrue(alturaDaTela[0] >= 200, "mesmo com a faixa de login aberta a tela tem altura: " + alturaDaTela[0]);
        assertTrue(alturaDaTela[1] > alturaDaTela[0] + 100, "a tela cresce com a janela: " + alturaDaTela[0] + " e " + alturaDaTela[1]);
    }
}
