package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.api.modelo.RecursosDoAgente;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.servidor.VisaoProjetos;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O botão "Novo projeto" e o convite da lista vazia: só com as ações liberadas e um servidor que cria projeto (o agente do lab ou Este computador),
 * nunca com o perfil monitor nem sem agente. PNGs claro e escuro, e a lista vazia sem rolagem em 1100 x 700.
 */
@ExtendWith(VerificaEdt.class)
class TelaProjetosNovoProjetoTest {

    private static EstadoServidor estado(VersaoAgente v, boolean local) {
        return new EstadoServidor("s1", local ? "Este computador" : "lab", EstadoConexao.CONECTADO, null, 0, null, null, 1, Estado2fa.DESLIGADO, "mac-de-teste", v, null,
            SituacaoDoAgente.DESCONHECIDA, local);
    }

    private static VersaoAgente lab() {
        return new VersaoAgente(1, "v22", false, "mac", "desligado", "lab", RecursosDoAgente.TUDO_COM_GUIA_IA, null);
    }

    private static VersaoAgente labAntigo() {
        return new VersaoAgente(1, "v22", false, "mac", "desligado"); // o lab de antes dos recursos
    }

    private static VersaoAgente monitor() {
        return new VersaoAgente(1, "v22", false, "mac", "desligado", "monitor", RecursosDoAgente.SO_MONITOR, "0.1.0-abc1234");
    }

    private static VisaoProjetos vazia() {
        return new VisaoProjetos(new ListaProjetos(List.of(), null), null);
    }

    private TelaProjetos tela(boolean acoes, EstadoServidor e, boolean vazia) {
        TelaProjetos t = TelaProjetosTest.nova(new AvisosGravados(), new DialogosGravados(), acoes);
        Visual.naEdt(() -> {
            t.aoMudarEstado(e);
            t.aoReceberProjetos(vazia ? vazia() : TelaProjetosTest.visaoDoMock());
        });
        return t;
    }

    @Test
    void comAsAcoesLiberadasEOAgenteDoLabHaBotaoEConvite() {
        Visual.tema(Tema.CLARO);
        TelaProjetos t = tela(true, estado(lab(), false), true);
        assertTrue(Visual.naEdt(() -> t.botaoNovoProjeto().isVisible()));
        assertEquals("Novo projeto", Visual.naEdt(() -> t.botaoNovoProjeto().getText()));
        assertEquals("vazio", t.cartaoAtual());
        assertTrue(Visual.naEdt(() -> t.botaoDoConvite().isVisible()));
        assertEquals("Criar o primeiro projeto", Visual.naEdt(() -> t.botaoDoConvite().getText()));
        assertTrue(t.textoDoVazio().contains("Crie um projeto novo ou importe"), t.textoDoVazio());
        assertTrue(t.textoDoVazio().contains("/srv/lab/projects"));
        AtomicInteger cliques = new AtomicInteger();
        Visual.naEdt(() -> t.aoPedirNovoProjeto(cliques::incrementAndGet));
        Visual.naEdt(() -> t.botaoNovoProjeto().doClick());
        Visual.naEdt(() -> t.botaoDoConvite().doClick());
        assertEquals(2, cliques.get(), "o botão do cabeçalho e o convite abrem o mesmo diálogo");
    }

    @Test
    void oLabDeAntesDosRecursosTambemCria() {
        TelaProjetos t = tela(true, estado(labAntigo(), false), false);
        assertTrue(Visual.naEdt(() -> t.botaoNovoProjeto().isVisible()), "o agente do lab de produção ainda não diz recursos, e cria projeto");
    }

    @Test
    void semAsAcoesLiberadasNadaDisso() {
        TelaProjetos t = tela(false, estado(lab(), false), true);
        assertFalse(Visual.naEdt(() -> t.botaoNovoProjeto().isVisible()), "a trava labdesktop.acoes vale também para Novo projeto");
        assertFalse(Visual.naEdt(() -> t.botaoDoConvite().isVisible()));
        assertTrue(t.textoDoVazio().startsWith("Os projetos aparecem aqui quando houver pastas em"), t.textoDoVazio());
        TelaProjetos local = tela(false, estado(null, true), true);
        assertFalse(Visual.naEdt(() -> local.botaoNovoProjeto().isVisible()));
    }

    @Test
    void perfilMonitorESemAgenteNaoCriam() {
        TelaProjetos m = tela(true, estado(monitor(), false), true);
        assertFalse(Visual.naEdt(() -> m.botaoNovoProjeto().isVisible()));
        assertFalse(Visual.naEdt(() -> m.botaoDoConvite().isVisible()));
        TelaProjetos sem = tela(true, estado(null, false), true);
        assertFalse(Visual.naEdt(() -> sem.botaoNovoProjeto().isVisible()), "o agente nem respondeu ainda");
        assertFalse(Visual.naEdt(() -> sem.botaoDoConvite().isVisible()));
    }

    @Test
    void esteComputadorSempreCriaMesmoComOAgenteDeMonitor() {
        TelaProjetos t = tela(true, estado(monitor(), true), true);
        assertTrue(Visual.naEdt(() -> t.botaoNovoProjeto().isVisible()));
        assertTrue(Visual.naEdt(() -> t.botaoDoConvite().isVisible()));
        TelaProjetos semAgente = tela(true, estado(null, true), true);
        assertTrue(Visual.naEdt(() -> semAgente.botaoNovoProjeto().isVisible()), "o agente local pode estar parado: criar projeto não depende dele");
    }

    @Test
    void asPaginasAparecemComBotaoNoCabecalhoEConviteNaListaVazia() {
        for (Tema tema : List.of(Tema.CLARO, Tema.ESCURO)) {
            Visual.tema(tema);
            String sufixo = tema == Tema.CLARO ? "claro" : "escuro";
            TelaProjetos vazia = tela(true, estado(lab(), false), true);
            Visual.salvar(Visual.renderizar(vazia, 1100, 700), "projetos-lista-vazia-com-convite-" + sufixo);
            assertFalse(Visual.naEdt(() -> vazia.rolagemDaLista().getVerticalScrollBar().isVisible()), "a lista vazia cabe em 1100 x 700 sem rolagem");
            TelaProjetos cheia = tela(true, estado(lab(), false), false);
            Visual.salvar(Visual.renderizar(cheia, 1100, 700), "projetos-lista-com-botao-novo-projeto-" + sufixo);
            TelaProjetos local = tela(true, estado(monitor(), true), true);
            Visual.salvar(Visual.renderizar(local, 1100, 700), "projetos-lista-vazia-local-" + sufixo);
            assertTrue(Visual.naEdt(() -> cheia.botaoNovoProjeto().isShowing() || cheia.botaoNovoProjeto().isVisible()));
        }
        Visual.tema(Tema.CLARO);
    }
}
