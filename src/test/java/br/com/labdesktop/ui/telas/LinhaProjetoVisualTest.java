package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Uma linha da lista de projetos, desenhada e atualizada no lugar. */
@ExtendWith(VerificaEdt.class)
class LinhaProjetoVisualTest {

    private final AvisosGravados avisos = new AvisosGravados();
    private final NavegadorGravado navegador = new NavegadorGravado(avisos);
    private final List<String[]> codigos = new ArrayList<>();

    private static String json(String extras) {
        return "{\"nome\":\"x-api\",\"descricao\":\"API principal\",\"composes\":[\"docker-compose.yml\"],"
            + "\"containers\":[{\"nome\":\"a\",\"estado\":\"running\"}],\"rodando\":true,\"urls\":[\"lab-x.example.com\"],"
            + "\"sessaoClaude\":false,\"acessoDocker\":true,\"cpu\":2.0,\"mem\":1000000" + extras + "}";
    }

    /** Com as ações de escrita liberadas (o padrão da aplicação). */
    private LinhaProjetoVisual linha() {
        return Visual.naEdt(() -> new LinhaProjetoVisual(new FluxosDeProjeto(null, avisos, new DialogosGravados(), true), navegador,
            (p, c) -> codigos.add(new String[] {p, c})));
    }

    /** Somente leitura. */
    private LinhaProjetoVisual linhaSomenteLeitura() {
        return Visual.naEdt(() -> new LinhaProjetoVisual(new FluxosDeProjeto(null, avisos, new DialogosGravados()), navegador,
            (p, c) -> codigos.add(new String[] {p, c})));
    }

    private static LinhaDeProjeto modelo(String extras) {
        return LinhaDeProjeto.de(FixturesDeApi.de(json(extras), Projeto.class));
    }

    private static Limite limiteDe(String extras) {
        return FixturesDeApi.de(json(extras), Projeto.class).limite();
    }

    @Test
    void mostraEstadoNomeEDescricaoEEscondeOQueNaoSeAplica() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        Visual.naEdt(() -> l.atualizar(modelo(""), null));
        assertEquals("no ar", Visual.naEdt(() -> l.rotuloDoEstado().getText()));
        assertEquals("x-api", Visual.naEdt(() -> l.rotuloDoNome().getText()));
        assertEquals("API principal", Visual.naEdt(() -> l.rotuloDaDescricao().getText()));
        assertFalse(Visual.naEdt(() -> l.botaoRetomar().isVisible()));
        assertFalse(Visual.naEdt(() -> l.botaoReabrir().isVisible()));
        assertFalse(Visual.naEdt(() -> l.faixaDeLogin().isVisible()));
        assertTrue(Visual.naEdt(() -> l.botaoAbrirClaude().isVisible()), "sem sessão e sem pausa: o botão de abrir Claude");
        assertEquals("Parar x-api", Visual.naEdt(() -> l.botaoCompose().getToolTipText()));
    }

    @Test
    void menuDaLinhaTemAdicionarDominioLigadoComAsAcoesEDesligadoComAExplicacaoSem() {
        Visual.tema(Tema.CLARO);
        List<String> pedidos = new ArrayList<>();
        LinhaProjetoVisual ligada = linha();
        Visual.naEdt(() -> ligada.aoAdicionarDominio(pedidos::add));
        Visual.naEdt(() -> ligada.atualizar(modelo(""), null));
        javax.swing.JMenuItem item = Visual.naEdt(() -> (javax.swing.JMenuItem) java.util.Arrays.stream(ligada.montarMenu().getComponents())
            .filter(c -> c instanceof javax.swing.JMenuItem m && "Adicionar domínio…".equals(m.getText())).findFirst().orElseThrow());
        assertTrue(Visual.naEdt(item::isEnabled));
        Visual.naEdt(() -> item.doClick());
        assertEquals(List.of("x-api"), pedidos, "o item leva o nome do projeto ao assistente");

        LinhaProjetoVisual desligada = linhaSomenteLeitura();
        Visual.naEdt(() -> desligada.aoAdicionarDominio(pedidos::add));
        Visual.naEdt(() -> desligada.atualizar(modelo(""), null));
        assertTrue(Visual.naEdt(() -> desligada.botaoMenu().isVisible()), "o menu existe mesmo sem as ações, por causa deste item");
        javax.swing.JMenuItem apagado = Visual.naEdt(() -> (javax.swing.JMenuItem) java.util.Arrays.stream(desligada.montarMenu().getComponents())
            .filter(c -> c instanceof javax.swing.JMenuItem m && "Adicionar domínio…".equals(m.getText())).findFirst().orElseThrow());
        assertFalse(Visual.naEdt(apagado::isEnabled), "aparece desligado, não some");
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", Visual.naEdt(() -> apagado.getToolTipText()));
        assertEquals(1, Visual.naEdt(() -> desligada.montarMenu().getComponentCount()), "sem as ações, só este item");
    }

    /**
     * No modo somente leitura a linha mostra o estado, mas
     * nenhum botão que mude o servidor (um clique em "parar" num servidor real roda compose down de até 12 min).
     */
    @Test
    void somenteLeituraMostraOEstadoEEscondeTodoBotaoQueMudaOServidor() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linhaSomenteLeitura();

        Visual.naEdt(() -> l.atualizar(modelo(""), null)); // no ar, sem sessão
        assertEquals("no ar", Visual.naEdt(() -> l.rotuloDoEstado().getText()));
        assertFalse(Visual.naEdt(() -> l.botaoCompose().isVisible()), "iniciar e parar ficam escondidos");
        assertFalse(Visual.naEdt(() -> l.botaoAbrirClaude().isVisible()), "Abrir Claude: sem terminal nativo ainda");
        assertFalse(Visual.naEdt(() -> l.botaoMenu().isVisible()));
        assertNull(Visual.naEdt(l::montarMenu), "nem o menu de ações");

        String pausada = ",\"limite\":{\"teto_pct\":20,\"usado_pp\":22,\"estado\":\"pausado\",\"pausa\":{\"motivo\":\"teto\",\"em\":\"2026-10-24T13:00:00Z\"}}";
        Visual.naEdt(() -> l.atualizar(modelo(pausada), limiteDe(pausada)));
        assertTrue(Visual.naEdt(() -> l.seloClaude().isVisible()), "a pausa continua informada");
        assertEquals("PAUSADA", Visual.naEdt(() -> l.seloClaude().texto()), "o selo diz só 'pausada': cabe na coluna");
        assertEquals("pelo limite", Visual.naEdt(() -> l.motivoDaPausa().getText()), "e o motivo vem numa linha própria");
        assertFalse(Visual.naEdt(() -> l.botaoRetomar().isVisible()), "Retomar é ação de escrita");

        String velha = ",\"sessaoInfo\":{\"atualizada\":false,\"faltando\":[\"imagem antiga (Claude desatualizado)\"]}";
        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(json(velha).replace("\"sessaoClaude\":false", "\"sessaoClaude\":true"),
            Projeto.class)), null));
        assertTrue(Visual.naEdt(() -> l.seloDesatualizada().isVisible()), "o selo informa");
        assertFalse(Visual.naEdt(() -> l.botaoReabrir().isVisible()), "Reabrir é ação de escrita");
    }

    @Test
    void somenteLeituraMantemOAlertaDeLoginMasSemEnviarCodigo() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linhaSomenteLeitura();
        String login = ",\"login\":{\"url\":\"https://claude.ai/oauth/authorize?x=1\"}";

        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(json(login), Projeto.class)), null));

        assertTrue(Visual.naEdt(() -> l.faixaDeLogin().isVisible()), "o alerta de login pendente continua");
        assertNull(Visual.naEdt(() -> l.campoDoCodigo().getParent()), "sem campo para colar o código: enviar é ação de escrita");
        assertNull(Visual.naEdt(() -> l.botaoEnviarCodigo().getParent()));
        assertNull(Visual.naEdt(() -> l.botaoAbrirLogin().getParent()));
        assertTrue(codigos.isEmpty());
        Visual.salvar(Visual.renderizar(l, 1200, Visual.alturaPreferida(l, 1200)), "linha-somente-leitura-login");
    }

    @Test
    void textoHostilDoServidorNuncaViraHtmlNaTela() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        String hostil = "{\"nome\":\"x\",\"descricao\":\"<html><body><img src='http://evil/x.png'>oi\",\"composes\":[\"c.yml\"],"
            + "\"containers\":[{\"estado\":\"running\"}],\"git\":{\"branch\":\"<html>main\",\"commit\":\"abc\"}}";
        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(hostil, Projeto.class)), null));
        String texto = Visual.naEdt(() -> l.rotuloDaDescricao().getText());
        assertFalse(TextoSeguro.pareceHtml(texto), texto);
        assertEquals(Boolean.TRUE, Visual.naEdt(() -> l.rotuloDaDescricao().getClientProperty("html.disable")));
        assertEquals(Boolean.TRUE, Visual.naEdt(() -> l.rotuloDoNome().getClientProperty("html.disable")));
        // o tooltip também passa pelo limpador: não é HTML
        assertFalse(TextoSeguro.pareceHtml(Visual.naEdt(() -> l.rotuloDaDescricao().getToolTipText())));
        BufferedImage img = Visual.renderizar(l, 1200, Visual.alturaPreferida(l, 1200));
        Visual.salvar(img, "linha-texto-hostil");
    }

    @Test
    void aMesmaLinhaNaoRedesenhaQuandoOModeloNaoMudou() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        Visual.naEdt(() -> l.atualizar(modelo(""), null));
        Visual.naEdt(() -> l.rotuloDoNome().setText("MEXIDO À MÃO"));
        Visual.naEdt(() -> l.atualizar(modelo(""), null));
        assertEquals("MEXIDO À MÃO", Visual.naEdt(() -> l.rotuloDoNome().getText()), "modelo igual: nada é tocado");
        Visual.naEdt(() -> l.atualizar(modelo("").equals(l.modelo()) ? LinhaDeProjeto.de(FixturesDeApi.de(json("").replace("2.0", "9.0"), Projeto.class)) : null, null));
        assertEquals("x-api", Visual.naEdt(() -> l.rotuloDoNome().getText()), "modelo diferente: a linha é preenchida de novo");
    }

    @Test
    void sessaoPausadaMostraSeloEOBotaoRetomarENaoOferecerAbrir() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        String pausada = ",\"limite\":{\"teto_pct\":20,\"usado_pp\":22,\"estado\":\"pausado\",\"pausa\":{\"motivo\":\"teto\",\"em\":\"2026-10-24T13:00:00Z\"}}";
        Visual.naEdt(() -> l.atualizar(modelo(pausada), limiteDe(pausada)));
        assertTrue(Visual.naEdt(() -> l.seloClaude().isVisible()));
        assertEquals("PAUSADA", Visual.naEdt(() -> l.seloClaude().texto()));
        assertEquals("pelo limite", Visual.naEdt(() -> l.motivoDaPausa().getText()));
        assertEquals(Gravidade.PERIGO, Visual.naEdt(() -> l.seloClaude().gravidade()));
        assertTrue(Visual.naEdt(() -> l.botaoRetomar().isVisible()));
        assertFalse(Visual.naEdt(() -> l.botaoAbrirClaude().isVisible()), "o servidor recusaria abrir (409)");
        String freio = ",\"limite\":{\"teto_pct\":null,\"usado_pp\":2,\"estado\":\"pausado\",\"pausa\":{\"motivo\":\"freio\"}}";
        Visual.naEdt(() -> l.atualizar(modelo(freio), limiteDe(freio)));
        assertEquals(Gravidade.ATENCAO, Visual.naEdt(() -> l.seloClaude().gravidade()), "freio global é âmbar, teto é terracota");
        assertEquals("pelo freio", Visual.naEdt(() -> l.motivoDaPausa().getText()));
        BufferedImage img = Visual.renderizar(l, 1200, Visual.alturaPreferida(l, 1200));
        Visual.salvar(img, "linha-pausada");
    }

    @Test
    void sessaoDesatualizadaTemSeloComOQueFaltaEBotaoReabrir() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        String velha = ",\"sessaoInfo\":{\"atualizada\":false,\"faltando\":[\"imagem antiga (Claude desatualizado)\"]}";
        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(json(velha).replace("\"sessaoClaude\":false", "\"sessaoClaude\":true"),
            Projeto.class)), null));
        assertEquals("ABERTA", Visual.naEdt(() -> l.seloClaude().texto()));
        assertTrue(Visual.naEdt(() -> l.seloDesatualizada().isVisible()));
        assertEquals("Falta: imagem antiga (Claude desatualizado)", Visual.naEdt(() -> l.seloDesatualizada().getToolTipText()));
        assertTrue(Visual.naEdt(() -> l.botaoReabrir().isVisible()));
    }

    @Test
    void loginPendenteMostraAFaixaEOCodigoSobreviveAoPollingDe10Segundos() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        String login = ",\"login\":{\"url\":\"https://claude.ai/oauth/authorize?x=1\"}";
        LinhaDeProjeto m1 = LinhaDeProjeto.de(FixturesDeApi.de(json(login), Projeto.class));
        Visual.naEdt(() -> l.atualizar(m1, null));
        assertTrue(Visual.naEdt(() -> l.faixaDeLogin().isVisible()));
        assertEquals("LOGIN PENDENTE", Visual.naEdt(() -> l.seloClaude().texto()));

        assertFalse(Visual.naEdt(() -> l.botaoEnviarCodigo().isEnabled()), "campo vazio: Enviar desabilitado");
        Visual.naEdt(() -> l.campoDoCodigo().setText("  abc#123.XY  "));
        assertTrue(Visual.naEdt(() -> l.botaoEnviarCodigo().isEnabled()));

        // o polling traz outro número: a linha é a MESMA e o que foi digitado continua lá
        LinhaDeProjeto m2 = LinhaDeProjeto.de(FixturesDeApi.de(json(login).replace("2.0", "7.5"), Projeto.class));
        assertNotEquals(m1, m2);
        Visual.naEdt(() -> l.atualizar(m2, null));
        assertEquals("  abc#123.XY  ", Visual.naEdt(() -> l.campoDoCodigo().getText()));

        Visual.naEdt(() -> l.botaoEnviarCodigo().doClick());
        assertEquals(1, codigos.size());
        assertEquals("x-api", codigos.get(0)[0]);
        assertEquals("abc#123.XY", codigos.get(0)[1], "o código vai aparado");

        Visual.naEdt(() -> l.botaoAbrirLogin().doClick());
        assertEquals(1, navegador.abertas.size());
        assertEquals("claude.ai", navegador.abertas.get(0).getHost());
        BufferedImage img = Visual.renderizar(l, 1200, Visual.alturaPreferida(l, 1200));
        Visual.salvar(img, "linha-login-pendente");
    }

    @Test
    void soAbreEnderecoPublicoDaListaDePermitidos() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        Visual.naEdt(() -> l.atualizar(modelo(""), null));
        Visual.naEdt(() -> l.botaoUrl().doClick());
        assertEquals(List.of("https://lab-x.example.com"), navegador.abertas.stream().map(Object::toString).toList());

        String fora = json("").replace("lab-x.example.com", "malvado.example.org");
        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(fora, Projeto.class)), null));
        Visual.naEdt(() -> l.botaoUrl().doClick());
        assertEquals(1, navegador.abertas.size(), "um host fora de *.example.com NÃO é aberto");
        assertTrue(avisos.disse("atencao", "lista de endereços permitidos"));
    }

    @Test
    void oMenuMaisAcoesTemSoOQueValePraLinha() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        String tudo = ",\"sessaoClaude\":true,\"sessaoInfo\":{\"atualizada\":true,\"faltando\":[]},"
            + "\"limite\":{\"teto_pct\":20,\"usado_pp\":2,\"estado\":\"ok\"}";
        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(json(tudo).replace("\"sessaoClaude\":false,", ""), Projeto.class)), null));
        JPopupMenu menu = Visual.naEdt(l::montarMenu);
        List<String> itens = Visual.naEdt(() -> java.util.Arrays.stream(menu.getComponents())
            .filter(c -> c instanceof javax.swing.JMenuItem).map(c -> ((javax.swing.JMenuItem) c).getText()).toList());
        assertEquals(List.of("Reiniciar", "Reiniciar sessão (continua a conversa)", "Definir limite", "Fechar sessão"), itens);

        Visual.naEdt(() -> l.atualizar(modelo(""), null));
        List<String> sem = Visual.naEdt(() -> java.util.Arrays.stream(l.montarMenu().getComponents())
            .filter(c -> c instanceof javax.swing.JMenuItem).map(c -> ((javax.swing.JMenuItem) c).getText()).toList());
        assertEquals(List.of("Reiniciar"), sem);
    }

    @Test
    void passarOMouseAcendeAlinhaComOFundoDoToken() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = linha();
        Visual.naEdt(() -> l.atualizar(modelo(""), null));
        BufferedImage normal = Visual.renderizar(l, 1200, 80);
        Visual.naEdt(() -> l.simularHover(true));
        assertTrue(Visual.naEdt(l::estaDeHover));
        BufferedImage sobMouse = Visual.renderizar(l, 1200, 80);
        // o fundo da linha passa de transparente (cai no creme) para a superfície 2
        assertEquals(Tokens.cor(Tokens.BG).getRGB(), normal.getRGB(600, 4));
        assertEquals(Tokens.cor(Tokens.SUPERFICIE2).getRGB(), sobMouse.getRGB(600, 4));
        assertNotNull(Visual.naEdt(() -> l.botaoMenu()));
    }
}
