package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.EstadoDeCarga.Fase;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O "carregando" com um servidor LENTO de mentira (o agente demora 1,5 s para responder as leituras): o indicador aparece na hora, antes da resposta (menos de
 * 100 ms depois de a tela pedir os dados), fica enquanto o servidor pensa e some quando o dado chega. Vale para a lista de projetos, para as abas do servidor e
 * para a página de um projeto. PNGs do estado carregando nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class CarregandoNasTelasTest {

    private static final long ATRASO_DO_SERVIDOR_MS = 1500;
    private static final long LIMITE_PARA_APARECER_MS = 100;

    private final AvisosGravados avisos = new AvisosGravados();

    private PainelPrincipal montar(CenarioDeUi cenario) {
        cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
        return Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
    }

    @Test
    void aListaDeProjetosMostraCarregandoNaHoraEAntesDaRespostaDoServidor() throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenarioDeUi cenario = new CenarioDeUi()) {
            cenario.agente.atrasoDasLeiturasMs = ATRASO_DO_SERVIDOR_MS;
            PainelPrincipal painel = montar(cenario);
            TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
            TelaProjetos lista = Visual.naEdt(() -> ts.projetos());
            long t0 = System.nanoTime();
            Visual.naEdt(() -> painel.mostrarProjetos());
            Visual.naEdt(lista::assinar);
            String frase = Visual.naEdt(() -> lista.indicadorDeCarga().textoDaFrase());
            String cartao = Visual.naEdt(lista::cartaoAtual);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals("esqueleto", cartao, "o cartão do carregando está à frente");
            assertEquals("Carregando projetos...", frase);
            assertTrue(Visual.naEdt(() -> lista.nomesExibidos().isEmpty()), "a resposta do servidor ainda não chegou");
            assertTrue(ms < LIMITE_PARA_APARECER_MS, "o indicador levou " + ms + " ms para aparecer (limite " + LIMITE_PARA_APARECER_MS + ")");

            for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
                Visual.tema(t);
                Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
                Visual.salvar(Visual.renderizar(painel, 1440, 860), "carregando-lista-de-projetos-" + t.name().toLowerCase());
            }
            assertFalse(Visual.naEdt(() -> lista.nomesExibidos()).contains("app-loja"), "ainda sem resposta depois de pintar");

            Espera.ate(Duration.ofSeconds(20), "a lista", () -> Visual.naEdt(() -> lista.nomesExibidos()).contains("app-loja"));
            assertEquals("lista", Visual.naEdt(lista::cartaoAtual));
            assertEquals(Fase.PRONTO, lista.indicadorDeCarga().estado().fase(), "chegou o dado: o carregando para os relógios");
            Visual.naEdt(painel::encerrar);
        }
    }

    @Test
    void asAbasDoServidorMostramCarregandoAteOPrimeiroDado() throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenarioDeUi cenario = new CenarioDeUi()) {
            cenario.agente.atrasoDasLeiturasMs = ATRASO_DO_SERVIDOR_MS;
            PainelPrincipal painel = montar(cenario);
            TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
            long t0 = System.nanoTime();
            Visual.naEdt(() -> painel.navegacao().mostrarServidor(cenario.lab, TelaServidor.Aba.CONTAINERS));
            Visual.naEdt(() -> ts.containers().assinar());
            boolean mostra = Visual.naEdt(() -> ts.containers().mostraEsqueleto());
            String frase = Visual.naEdt(() -> ts.containers().indicadorDeCarga().textoDaFrase());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(mostra, "o carregando está à vista logo ao abrir a aba");
            assertEquals("Carregando as informações do servidor...", frase);
            assertTrue(ms < LIMITE_PARA_APARECER_MS, "o indicador levou " + ms + " ms para aparecer");
            assertFalse(Visual.naEdt(() -> ts.containers().mostraCorpo()), "o conteúdo só entra quando o dado chega");
            Espera.ate(Duration.ofSeconds(20), "o dado", () -> Visual.naEdt(() -> ts.containers().mostraCorpo()));
            assertFalse(Visual.naEdt(() -> ts.containers().mostraEsqueleto()), "chegou: o carregando some");
            Visual.naEdt(painel::encerrar);
        }
    }

    @Test
    void aPaginaDeUmProjetoMostraCarregandoOProjetoNaHora() throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenarioDeUi cenario = new CenarioDeUi()) {
            cenario.agente.atrasoDasLeiturasMs = ATRASO_DO_SERVIDOR_MS;
            PainelPrincipal painel = montar(cenario);
            TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
            long t0 = System.nanoTime();
            Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
            TelaProjeto tp = Visual.naEdt(() -> ts.projetoAberto());
            Visual.naEdt(tp::assinar);
            Visual.naEdt(() -> tp.pagina(TelaProjeto.Aba.VISAO_GERAL).assinar());
            boolean mostra = Visual.naEdt(() -> tp.pagina(TelaProjeto.Aba.VISAO_GERAL).mostraEsqueleto());
            String frase = Visual.naEdt(() -> tp.pagina(TelaProjeto.Aba.VISAO_GERAL).indicadorDeCarga().textoDaFrase());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(mostra);
            assertEquals("Carregando o projeto...", frase);
            assertTrue(ms < LIMITE_PARA_APARECER_MS + 150, "abrir o projeto e mostrar o carregando levou " + ms + " ms");
            for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
                Visual.tema(t);
                Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
                Visual.salvar(Visual.renderizar(painel, 1440, 860), "carregando-pagina-do-projeto-" + t.name().toLowerCase());
            }
            Espera.ate(Duration.ofSeconds(20), "o detalhe", () -> Visual.naEdt(() -> tp.visaoGeral().mostraCorpo()));
            assertFalse(Visual.naEdt(() -> tp.visaoGeral().mostraEsqueleto()));
            Visual.naEdt(painel::encerrar);
        }
    }
}
