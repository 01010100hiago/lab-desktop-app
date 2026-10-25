package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A linha da lista de projetos como porta de entrada da página do projeto: clicar em qualquer ponto (menos nos botões e no campo
 * do código de login) ou apertar Enter com a linha focada avisa o nome do projeto; cursor de mão e foco desenhado.
 */
@ExtendWith(VerificaEdt.class)
class LinhaProjetoVisualAbrirTest {

    private static final String JSON = "{\"nome\":\"x-api\",\"descricao\":\"API principal\",\"composes\":[\"docker-compose.yml\"],"
        + "\"containers\":[{\"nome\":\"a\",\"estado\":\"running\"}],\"rodando\":true,\"urls\":[\"lab-x.example.com\"],"
        + "\"sessaoClaude\":false,\"acessoDocker\":true,\"cpu\":2.0,\"mem\":1000000,"
        + "\"login\":{\"url\":\"https://claude.ai/oauth/authorize?x=1\"}}";

    private final AvisosGravados avisos = new AvisosGravados();
    private final List<String> abertos = new ArrayList<>();

    private LinhaProjetoVisual linha(boolean acoes) {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = Visual.naEdt(() -> new LinhaProjetoVisual(new FluxosDeProjeto(null, avisos, new DialogosGravados(), acoes),
            new NavegadorGravado(avisos), (p, c) -> { }, abertos::add));
        Visual.naEdt(() -> l.atualizar(LinhaDeProjeto.de(FixturesDeApi.de(JSON, Projeto.class)), null));
        return l;
    }

    private static void clicar(Component alvo, int botao) {
        Visual.naEdt(() -> alvo.dispatchEvent(new MouseEvent(alvo, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 3, 3, 1, false,
            botao)));
    }

    @Test
    void cliqueNoNomeNaDescricaoNoEstadoENoFundoAbre() {
        LinhaProjetoVisual l = linha(true);
        for (Component alvo : new Component[] {Visual.naEdt(l::rotuloDoNome), Visual.naEdt(l::rotuloDaDescricao),
            Visual.naEdt(l::rotuloDoEstado), l}) {
            clicar(alvo, MouseEvent.BUTTON1);
        }
        assertEquals(List.of("x-api", "x-api", "x-api", "x-api"), abertos);
    }

    @Test
    void botoesECampoDeCodigoNaoAbrem() {
        LinhaProjetoVisual l = linha(true);
        for (Component alvo : new Component[] {l.botaoCompose(), l.botaoAbrirClaude(), l.botaoMenu(), l.botaoUrl(), l.botaoReabrir(),
            l.botaoRetomar(), l.botaoAbrirLogin(), l.botaoEnviarCodigo(), l.campoDoCodigo()}) {
            clicar(alvo, MouseEvent.BUTTON1);
        }
        assertTrue(abertos.isEmpty(), "um clique num controle faz o que o controle faz: " + abertos);
    }

    @Test
    void soOBotaoEsquerdoAbre() {
        LinhaProjetoVisual l = linha(true);
        clicar(Visual.naEdt(l::rotuloDoNome), MouseEvent.BUTTON3);
        clicar(Visual.naEdt(l::rotuloDoNome), MouseEvent.BUTTON2);
        assertTrue(abertos.isEmpty());
    }

    @Test
    void enterComALinhaFocadaAbre() {
        LinhaProjetoVisual l = linha(true);
        Visual.naEdt(() -> l.getActionMap().get("abrir-projeto").actionPerformed(null));
        assertEquals(List.of("x-api"), abertos);
    }

    @Test
    void semAcoesAindaAbre() {
        LinhaProjetoVisual l = linha(false);
        clicar(Visual.naEdt(l::rotuloDoNome), MouseEvent.BUTTON1);
        assertEquals(List.of("x-api"), abertos, "abrir a página só lê: vale também no modo somente leitura");
    }

    @Test
    void naoAbreAntesDeTerUmProjeto() {
        Visual.tema(Tema.CLARO);
        LinhaProjetoVisual l = Visual.naEdt(() -> new LinhaProjetoVisual(new FluxosDeProjeto(null, avisos, new DialogosGravados(), true),
            new NavegadorGravado(avisos), (p, c) -> { }, abertos::add));
        clicar(l, MouseEvent.BUTTON1);
        assertTrue(abertos.isEmpty());
    }

    @Test
    void lembraFocoCursorDeMaoEDescricaoAcessivel() {
        LinhaProjetoVisual l = linha(true);
        assertTrue(Visual.naEdt(l::isFocusable));
        assertEquals(java.awt.Cursor.HAND_CURSOR, Visual.naEdt(() -> l.getCursor().getType()));
        assertEquals(java.awt.Cursor.HAND_CURSOR, Visual.naEdt(() -> l.rotuloDoNome().getCursor().getType()));
    }

    @Test
    void oFocoDesenhaUmContornoPorCimaDoConteudo() {
        LinhaProjetoVisual l = linha(true);
        BufferedImage sem = Visual.renderizar(l, 1100, 80);
        Visual.naEdt(() -> l.simularFoco(true));
        BufferedImage com = Visual.renderizar(l, 1100, 80);
        int antes = Visual.contarPixels(sem, Tokens.cor(Tokens.FOCO), 6);
        int foco = Visual.contarPixels(com, Tokens.cor(Tokens.FOCO), 6);
        assertTrue(foco > antes + 200, "contorno de foco desenhado: " + foco + " pixels contra " + antes);
    }
}
