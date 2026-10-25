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
 * Clicar no servidor na barra lateral abre a PÁGINA do servidor (abas Resumo, Segurança, Containers, Processos, Máquina e
 * Projetos), com servidor VIVO: SSH embutido, agente de teste e a fixture sintética do detalhe. Prova também a carga: cada
 * aba só consulta o que usa, e só enquanto está assinada.
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
    void aoAbrirOAppMostraAPaginaDoServidorNoResumo() {
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertNotNull(pagina());
        assertEquals(Aba.RESUMO, Visual.naEdt(pagina()::abaAtual));
        assertSame(pagina().projetos(), Visual.naEdt(() -> painel.navegacao().telaDe(CofreFalso.ID)), "a TelaProjetos é a mesma");
    }

    @Test
    void cliqueNoServidorDaBarraAbrePaginaDoServidorELembraAAbaEscolhida() {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));

        Visual.naEdt(() -> painel.barra().item(CofreFalso.ID).clicarComOBotaoEsquerdo());
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
        assertEquals(CofreFalso.ID, Visual.naEdt(() -> painel.barra().selecionado()));
        assertEquals(Aba.RESUMO, Visual.naEdt(pagina()::abaAtual), "a primeira vez abre o Resumo");

        for (Aba escolhida : new Aba[] {Aba.SEGURANCA, Aba.PROJETOS, Aba.MAQUINA}) {
            Visual.naEdt(() -> pagina().abas().botao(escolhida.id()).doClick());
            assertEquals(escolhida, Visual.naEdt(pagina()::abaAtual));
            Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
            Visual.naEdt(() -> painel.barra().item(CofreFalso.ID).clicarComOBotaoEsquerdo());
            assertEquals(escolhida, Visual.naEdt(pagina()::abaAtual), "voltou ao servidor: a aba escolhida ficou lembrada");
            assertEquals(escolhida, Visual.naEdt(pagina()::abaMarcada));
        }
    }

    @Test
    void osAtalhosDoMenuEscolhemAAba() {
        Visual.naEdt(painel::mostrarProjetos);
        assertEquals(Aba.PROJETOS, Visual.naEdt(pagina()::abaAtual));
        Visual.naEdt(painel::mostrarResumo);
        assertEquals(Aba.RESUMO, Visual.naEdt(pagina()::abaAtual));
        Visual.naEdt(painel::mostrarServidores);
        assertEquals("servidores", Visual.naEdt(() -> painel.navegacao().atual()));
        Visual.naEdt(painel::mostrarResumo);
        assertEquals("servidor:" + CofreFalso.ID, Visual.naEdt(() -> painel.navegacao().atual()));
    }

    private void esperarParar(Duration quanto) {
        int servidor = cenario.agente.consultasDeServidor.get();
        int detalhe = cenario.agente.consultasDeDetalhe.get();
        int comandos = cenario.agente.comandosDeSeguranca.size();
        Espera.naoAcontece(quanto, "consulta depois de soltar", () -> cenario.agente.consultasDeServidor.get() > servidor + 1
            || cenario.agente.consultasDeDetalhe.get() > detalhe + 1 || cenario.agente.comandosDeSeguranca.size() > comandos + 7);
    }

    @Test
    void oResumoRecebeServidorDetalheESegurancaEParaDeConsultarQuandoSolto() {
        var resumo = pagina().resumo();
        assertEquals(0, cenario.agente.consultasDeDetalhe.get(), "nada é consultado com a aba fora da tela");
        assertEquals(0, cenario.agente.comandosDeSeguranca.size());
        Visual.naEdt(resumo::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "contadores: servidor=" + cenario.agente.consultasDeServidor + " detalhe="
            + cenario.agente.consultasDeDetalhe, () -> cenario.agente.consultasDeDetalhe.get() >= 1 && cenario.agente.consultasDeServidor.get() >= 1);
        Espera.ate(Duration.ofSeconds(20), () -> "kpis", () -> Visual.naEdt(() -> resumo.faixaDeKpis().cartoes().get(1).textoDoValor()).contains("%"));
        Espera.ate(Duration.ofSeconds(20), () -> "segurança: " + cenario.agente.comandosDeSeguranca,
            () -> Visual.naEdt(() -> resumo.faixaDeKpis().cartoes().get(5).textoDoValor()).matches("\\d+\\+?"));
        for (String c : cenario.agente.comandosDeSeguranca) {
            assertTrue(c.startsWith("sudo -n ") || c.startsWith("ss ") || c.startsWith("systemctl "), "só os comandos fixos: " + c);
        }
        Visual.naEdt(resumo::soltar);
        esperarParar(Duration.ofMillis(1500));
    }

    @Test
    void containersSoConsultaOServidor() {
        Visual.naEdt(pagina().containers()::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "servidor", () -> cenario.agente.consultasDeServidor.get() >= 1);
        Espera.ate(Duration.ofSeconds(20), () -> "resumo de containers", () -> !Visual.naEdt(() -> pagina().containers().bloco().textoDoResumo()).equals("—"));
        Espera.naoAcontece(Duration.ofMillis(1500), "detalhe ou segurança na aba Containers",
            () -> cenario.agente.consultasDeDetalhe.get() > 0 || !cenario.agente.comandosDeSeguranca.isEmpty());
        Visual.naEdt(pagina().containers()::soltar);
    }

    @Test
    void processosEMaquinaConsultamServidorEDetalheMasNuncaASeguranca() {
        for (PaginaDoServidor p : new PaginaDoServidor[] {pagina().processos(), pagina().maquina()}) {
            Visual.naEdt(p::assinar);
        }
        Espera.ate(Duration.ofSeconds(20), () -> "servidor e detalhe",
            () -> cenario.agente.consultasDeDetalhe.get() >= 1 && cenario.agente.consultasDeServidor.get() >= 1);
        Espera.ate(Duration.ofSeconds(20), () -> "sensores", () -> !Visual.naEdt(() -> pagina().maquina().detalhes().linhasDeSensores()).isEmpty());
        Espera.naoAcontece(Duration.ofMillis(1500), "segurança fora do Resumo e da Segurança", () -> !cenario.agente.comandosDeSeguranca.isEmpty());
        Visual.naEdt(pagina().processos()::soltar);
        Visual.naEdt(pagina().maquina()::soltar);
    }

    @Test
    void segurancaSoRodaOsComandosENuncaConsultaOServidor() {
        var seguranca = pagina().seguranca();
        Visual.naEdt(seguranca::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "segurança: " + cenario.agente.comandosDeSeguranca,
            () -> Visual.naEdt(() -> seguranca.textosDoResumo()).get(0).contains("tentativas de entrada"));
        assertTrue(Visual.naEdt(() -> seguranca.textoDoPainel(PaginaSeguranca.SUB_FIREWALL)).contains("Porta 2222 (SSH)"));
        assertFalse(cenario.agente.comandosDeSeguranca.isEmpty());
        assertEquals(0, cenario.agente.consultasDeServidor.get(), "a aba Segurança não consulta /api/servidor");
        assertEquals(0, cenario.agente.consultasDeDetalhe.get());
        Visual.naEdt(seguranca::soltar);
        esperarParar(Duration.ofMillis(1500));
    }

    @Test
    void semSudoASegurancaDizQueNaoConseguiuLer() {
        cenario.agente.segurancaSemSudo = true;
        var seguranca = pagina().seguranca();
        Visual.naEdt(seguranca::assinar);
        Espera.ate(Duration.ofSeconds(20), () -> "segurança sem sudo",
            () -> Visual.naEdt(() -> seguranca.textoDoPainel(PaginaSeguranca.SUB_TENTATIVAS))
                .contains("precisa de sudo sem senha para o usuário"));
        Visual.naEdt(seguranca::soltar);
    }
}
