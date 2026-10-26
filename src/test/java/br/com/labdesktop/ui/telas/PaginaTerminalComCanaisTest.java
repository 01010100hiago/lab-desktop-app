package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CenarioDeTerminal;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.terminal.EstadoDoTerminal.Tipo;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import br.com.labdesktop.ui.terminal.PainelTerminal;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A aba Terminal com canais de verdade: cada aba abre um shell no servidor SSH embutido, e fechar a aba fecha o canal. Sem o vigia de
 * EDT dos outros testes de tela: o emulador do JediTerm pede repintura da thread dele.
 */
class PaginaTerminalComCanaisTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private CenarioDeTerminal cenario;
    private PaginaTerminal pagina;
    private final LimiteDeAbas limite = new LimiteDeAbas();
    private final AvisosGravados avisos = new AvisosGravados();

    @BeforeEach
    void preparar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = CenarioDeTerminal.conectado();
        cenario.terminal.roteiro(ServidorDeTerminalFalso::roteiroQueNaoLe);
        ContextoDeTerminal ctx = new ContextoDeTerminal(new TransporteSsh(cenario.conexao), limite, Preferencias::padrao, url -> { },
            Agendador.doFundo());
        pagina = Visual.naEdt(() -> new PaginaTerminal(ctx, avisos));
        Visual.renderizar(pagina, 1000, 600);
    }

    @AfterEach
    void fechar() {
        Visual.naEdt(pagina::encerrar);
        cenario.close();
    }

    /** Abre a aba e liga o terminal (a janela de verdade o liga ao aparecer). */
    private PainelTerminal abrirAba() {
        assertTrue(Visual.naEdt(() -> pagina.novoTerminal()));
        PainelTerminal t = Visual.naEdt(() -> pagina.terminais().get(pagina.terminais().size() - 1));
        Visual.layoutar(t);
        Visual.naEdt(() -> {
            t.setSize(900, 500);
            t.iniciar();
        });
        return t;
    }

    @Test
    void cadaAbaAbreUmShellDoServidorEFecharAAbaFechaOCanal() {
        PainelTerminal a = abrirAba();
        PainelTerminal b = abrirAba();
        Espera.ate(PRAZO, () -> "shells=" + cenario.terminal.shellsAbertos.get(), () -> cenario.terminal.shellsAbertos.get() == 2);
        Espera.ate(PRAZO, "as duas ativas", () -> a.sessao().estado().ativa() && b.sessao().estado().ativa());
        assertTrue(cenario.terminal.comandos.isEmpty(), "shell do servidor, não o docker exec da sessão do Claude");
        assertEquals("xterm-256color", cenario.terminal.tiposDeTerminal.get(0));

        Visual.naEdt(() -> pagina.botaoFechar().doClick(0)); // fecha a escolhida (a segunda)
        Espera.ate(PRAZO, () -> "canais encerrados=" + cenario.terminal.canaisEncerrados.get(), () -> cenario.terminal.canaisEncerrados.get() == 1);
        assertEquals(1, limite.emUso());
        assertTrue(a.sessao().estado().ativa(), "a outra continua viva");
    }

    @Test
    void oitoShellsAbremAoMesmoTempoEANonaNemChegaAoServidor() {
        for (int i = 0; i < 8; i++) {
            abrirAba();
        }
        Espera.ate(PRAZO, () -> "shells=" + cenario.terminal.shellsAbertos.get(), () -> cenario.terminal.shellsAbertos.get() == 8);
        assertFalse(Visual.naEdt(() -> pagina.novoTerminal()), "a nona");
        assertEquals(LimiteDeAbas.mensagemDoLimite(), Visual.naEdt(pagina::textoDoAviso));
        Espera.naoAcontece(Duration.ofMillis(400), "a nona chegou ao servidor", () -> cenario.terminal.shellsAbertos.get() > 8);
    }

    @Test
    void servidorQueRecusaMaisUmCanalMostraATextoDoLimiteDeSessoesNaAba() {
        cenario.terminal.limitarSessoesA(2); // o MaxSessions do servidor acaba antes do nosso limite de 8
        abrirAba();
        abrirAba();
        PainelTerminal terceira = abrirAba();
        Espera.ate(PRAZO, () -> "estado=" + terceira.sessao().estado(), () -> terceira.sessao().estado().tipo() == Tipo.SEM_CONEXAO);
        Espera.ate(PRAZO, "faixa", () -> Visual.naEdt(terceira::faixaVisivel));
        String texto = Visual.naEdt(terceira::textoDaFaixaAgora);
        assertTrue(texto.contains("limite de sessões do SSH"), texto);
        assertTrue(Visual.naEdt(() -> terceira.botaoReconectar().isVisible()));
        Espera.naoAcontece(Duration.ofMillis(2600), "tentou de novo sozinho depois de uma recusa do servidor",
            () -> cenario.terminal.canaisAbertos.get() > 2);
    }

    @Test
    void encerrarAPaginaFechaOsCanaisTodos() {
        for (int i = 0; i < 3; i++) {
            abrirAba();
        }
        Espera.ate(PRAZO, "três shells", () -> cenario.terminal.shellsAbertos.get() == 3);
        Visual.naEdt(pagina::encerrar);
        Espera.ate(PRAZO, () -> "encerrados=" + cenario.terminal.canaisEncerrados.get(), () -> cenario.terminal.canaisEncerrados.get() == 3);
        assertEquals(0, limite.emUso());
    }

    @Test
    void oTituloDoProgramaViraORotuloDaAbaLimpoECurto() {
        cenario.terminal.roteiro(s -> {
            s.escrever("\u001b]0;labuser@lab: /srv/lab\u0007");
            ServidorDeTerminalFalso.roteiroQueNaoLe(s);
        });
        abrirAba();
        Espera.ate(PRAZO, () -> "rotulo=" + Visual.naEdt(() -> pagina.abas().botao("t1").getText()),
            () -> Visual.naEdt(() -> pagina.abas().botao("t1").getText()).equals("labuser@lab: /srv/lab"));
    }
}
