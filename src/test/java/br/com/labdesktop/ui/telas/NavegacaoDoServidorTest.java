package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Clicar no servidor na barra lateral abre a PÁGINA do servidor (abas Visão geral e Projetos), com servidor VIVO: SSH
 * embutido, agente de teste e a fixture sintética do detalhe. Prova também a carga: o detalhe e a segurança só são
 * consultados enquanto a Visão geral está assinada.
 */
@ExtendWith(VerificaEdt.class)
class NavegacaoDoServidorTest {

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private PainelPrincipal painel;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi();
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(() -> painel.encerrar());
        cenario.close();
    }

    private TelaServidor pagina() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    @Test
    void aoAbrirOAppMostraAPaginaDoServidorNaVisaoGeral() {
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertNotNull(pagina());
        assertEquals(Aba.VISAO_GERAL, Visual.naEdt(pagina()::abaAtual));
        assertSame(pagina().projetos(), Visual.naEdt(() -> painel.navegacao().telaDe(CofreFalso.ID)), "a TelaProjetos é a mesma");
    }

    @Test
    void cliqueNoServidorDaBarraAbrePaginaDoServidorELembraAAbaEscolhida() {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));

        Visual.naEdt(() -> painel.barra().item(CofreFalso.ID).clicarComOBotaoEsquerdo());
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertEquals(CofreFalso.ID, Visual.naEdt(() -> painel.barra().selecionado()));
        assertEquals(Aba.VISAO_GERAL, Visual.naEdt(pagina()::abaAtual), "a primeira vez abre a visão geral");

        Visual.naEdt(() -> pagina().abas().botao("projetos").doClick());
        assertEquals(Aba.PROJETOS, Visual.naEdt(pagina()::abaAtual));

        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        Visual.naEdt(() -> painel.barra().item(CofreFalso.ID).clicarComOBotaoEsquerdo());
        assertEquals(Aba.PROJETOS, Visual.naEdt(pagina()::abaAtual), "voltou ao servidor: a aba escolhida ficou lembrada");
        assertEquals(Aba.PROJETOS, Visual.naEdt(pagina()::abaMarcada));
    }

    @Test
    void osAtalhosDoMenuEscolhemAAba() {
        Visual.naEdt(painel::mostrarProjetos);
        assertEquals(Aba.PROJETOS, Visual.naEdt(pagina()::abaAtual));
        Visual.naEdt(painel::mostrarVisaoGeral);
        assertEquals(Aba.VISAO_GERAL, Visual.naEdt(pagina()::abaAtual));
        Visual.naEdt(painel::mostrarServidores);
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
        Visual.naEdt(painel::mostrarVisaoGeral);
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
    }

    @Test
    void aVisaoGeralRecebeOsDadosPeloTunelEAConsultaParaQuandoSolta() {
        var visao = pagina().visaoGeral();
        assertEquals(0, cenario.agente.consultasDeDetalhe.get(), "nada é consultado com a visão geral fora da tela");
        Visual.naEdt(visao::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "detalhe=" + cenario.agente.consultasDeDetalhe.get(),
            () -> cenario.agente.consultasDeDetalhe.get() >= 1 && cenario.agente.consultasDeServidor.get() >= 1);
        Espera.ate(Duration.ofSeconds(20), () -> "kpis", () -> Visual.naEdt(() -> visao.faixaDeKpis().cartoes().get(1).textoDoValor())
            .contains("°C"));
        Espera.ate(Duration.ofSeconds(20), () -> "detalhes da máquina", () -> !Visual.naEdt(() -> visao.detalhesDaMaquina().linhasDeSensores()).isEmpty());
        Espera.ate(Duration.ofSeconds(20), () -> "segurança: " + cenario.agente.comandosDeSeguranca,
            () -> Visual.naEdt(() -> visao.blocoDeSeguranca().textoCompleto()).contains("tentativas de entrada nas últimas 24 h"));
        assertTrue(Visual.naEdt(() -> visao.blocoDeSeguranca().textoCompleto()).contains("Porta 2222 (SSH)"));
        assertFalse(cenario.agente.comandosDeSeguranca.isEmpty());
        for (String c : cenario.agente.comandosDeSeguranca) {
            assertTrue(c.startsWith("sudo -n ") || c.startsWith("ss ") || c.startsWith("systemctl "), "só os comandos fixos: " + c);
        }

        Visual.naEdt(visao::soltar);
        int detalhe = cenario.agente.consultasDeDetalhe.get();
        int servidor = cenario.agente.consultasDeServidor.get();
        int comandos = cenario.agente.comandosDeSeguranca.size();
        Espera.naoAcontece(Duration.ofMillis(1500), "consulta depois de soltar", () -> cenario.agente.consultasDeDetalhe.get() > detalhe + 1
            || cenario.agente.consultasDeServidor.get() > servidor + 1 || cenario.agente.comandosDeSeguranca.size() > comandos + 7);
    }

    @Test
    void semSudoABlocoDeSegurancaDizQueNaoConseguiuLer() {
        cenario.agente.segurancaSemSudo = true;
        var visao = pagina().visaoGeral();
        Visual.naEdt(visao::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "segurança sem sudo",
            () -> Visual.naEdt(() -> visao.blocoDeSeguranca().textoCompleto())
                .contains("precisa de sudo sem senha para o usuário"));
        Visual.naEdt(visao::soltar);
    }
}
