package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Com as ações desligadas, a lista de projetos é somente leitura. Mesmo que um botão apareça por
 * engano, nenhuma chamada de escrita sai do app para o servidor: um clique em "parar" num projeto de um servidor real roda
 * {@code compose down} de até 12 min, e "Abrir Claude" abre uma sessão de verdade com {@code pg_dump} antes.
 */
@ExtendWith(VerificaEdt.class)
class FluxosDeProjetoSomenteLeituraTest {

    private CenarioDeUi cenario;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private FluxosDeProjeto fluxos;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi();
        fluxos = Visual.naEdt(() -> new FluxosDeProjeto(cenario.lab, avisos, dialogos));
    }

    @AfterEach
    void desmontar() throws Exception {
        cenario.close();
    }

    @Test
    void porPadraoAsAcoesNaoEstaoLiberadas() {
        assertFalse(fluxos.liberadas());
    }

    @Test
    void nenhumaAcaoDeEscritaChegaAoServidorEOAvisoExplicaPorQue() {
        Limite limite = new Limite(30, 5.0, "ok", false, null, null, null);
        Visual.naEdt(() -> {
            fluxos.iniciar("app-loja");
            fluxos.parar("app-loja");
            fluxos.reiniciar("app-loja");
            fluxos.abrirSessao("app-loja");
            fluxos.fecharSessao("app-loja");
            fluxos.enviarCodigoDeLogin("app-loja", "abc#123.XY");
            fluxos.pedirReabrir("app-loja");
            fluxos.reabrirSessao("app-loja");
            fluxos.retomar("app-loja", limite);
            fluxos.definirLimite("app-loja", limite, null);
        });

        assertEquals(10, avisos.todos.stream().filter(a -> a.tipo().equals("atencao") && a.texto().contains("somente leitura")).count(),
            "cada tentativa avisa o usuário: " + avisos.todos);
        assertTrue(avisos.disse("atencao", "somente leitura"), avisos.todos.toString());
        assertTrue(dialogos.mostrados.isEmpty(), "nem diálogo de confirmação abre: " + dialogos.mostrados);
        assertTrue(Visual.naEdt(() -> fluxos.ocupadas("app-loja").isEmpty()), "nada ficou ocupado");
        Espera.dormir(400);
        assertTrue(cenario.agente.http.pedidos.stream().noneMatch(p -> !"GET".equals(p.metodo())),
            "nenhum POST ou PUT chegou ao agente: " + cenario.agente.http.pedidos);
    }

    @Test
    void liberadasLigaAsAcoesDeVerdade() {
        FluxosDeProjeto liberado = Visual.naEdt(() -> new FluxosDeProjeto(cenario.lab, avisos, dialogos, true));
        assertTrue(liberado.liberadas());
        Visual.naEdt(() -> liberado.fecharSessao("app-loja"));
        Espera.ate(java.time.Duration.ofSeconds(15), () -> "a ação saiu", () -> cenario.recebeu("POST", "/api/projetos/app-loja/claude/fechar"));
    }

    @Test
    void aPropriedadeLabdesktopAcoesLigaOQueAAplicacaoPassaAoConstrutor() {
        String antes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        try {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
            assertFalse(FluxosDeProjeto.liberadasPorPropriedade(), "desligada por padrão");
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
            assertTrue(FluxosDeProjeto.liberadasPorPropriedade());
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "false");
            assertFalse(FluxosDeProjeto.liberadasPorPropriedade());
        } finally {
            if (antes == null) {
                System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
            } else {
                System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, antes);
            }
        }
    }
}
