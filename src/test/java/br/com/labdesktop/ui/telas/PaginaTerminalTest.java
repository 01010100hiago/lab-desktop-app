package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.terminal.CanalDeTerminal;
import br.com.labdesktop.terminal.ComandoDeTerminal;
import br.com.labdesktop.terminal.LimiteDeAbas;
import br.com.labdesktop.terminal.TamanhoDeTerminal;
import br.com.labdesktop.terminal.TransporteDeTerminal;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import br.com.labdesktop.ui.tema.Fontes;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.ArrayList;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A aba Terminal da página do servidor: abas de terminal, o limite de 8 por servidor (dividido com os terminais das sessões do
 * Claude) e o fechamento. Sem abrir canal nenhum: o transporte aqui recusa (os canais de verdade estão em
 * {@code PaginaTerminalComCanaisTest}).
 */
@ExtendWith(VerificaEdt.class)
class PaginaTerminalTest {

    /** Um transporte que nunca abre nada (as abas existem, o canal não). */
    private static final TransporteDeTerminal SEM_CANAL = new TransporteDeTerminal() {
        @Override
        public CanalDeTerminal abrir(ComandoDeTerminal comando, TamanhoDeTerminal tamanho) throws IOException {
            throw new IOException("sem canal nos testes de abas");
        }

        @Override
        public Runnable observarDisponibilidade(Consumer<Disponibilidade> ouvinte) {
            return () -> { };
        }
    };

    private final AvisosGravados avisos = new AvisosGravados();
    private final LimiteDeAbas limite = new LimiteDeAbas();

    private PaginaTerminal pagina() {
        Visual.tema(Tema.CLARO);
        ContextoDeTerminal ctx = new ContextoDeTerminal(SEM_CANAL, limite, Preferencias::padrao, url -> { }, Agendador.doFundo());
        return Visual.naEdt(() -> new PaginaTerminal(ctx, avisos));
    }

    @AfterEach
    void voltarA100() {
        Visual.naEdt(() -> Fontes.definirPercentual(100));
    }

    /** Nenhum texto da aba (abas, botões, contagem, aviso e a faixa de cada terminal) tem fonte menor que 12 px, em 100 % e em 130 %. */
    @Test
    void nenhumTextoDaAbaTemFonteMiuda() {
        for (int percentual : new int[] {100, 130}) {
            Visual.naEdt(() -> Fontes.definirPercentual(percentual));
            PaginaTerminal p = pagina();
            for (int i = 0; i < 9; i++) {
                Visual.naEdt(() -> p.novoTerminal()); // a nona deixa o aviso do limite à vista
            }
            Visual.renderizar(p, 1000, 600);
            Visual.naEdt(() -> p.terminais().forEach(t -> t.iniciar())); // sem canal: a faixa de reconexão aparece em cada um
            Visual.renderizar(p, 1000, 600);
            float minimo = (float) Math.floor(Fontes.MINIMO * percentual / 100f * 2) / 2f;
            List<String> miudos = new ArrayList<>();
            Visual.naEdt(() -> varrer(p, minimo, miudos));
            assertTrue(miudos.isEmpty(), percentual + "%: " + miudos);
            Visual.naEdt(p::encerrar);
        }
    }

    private static void varrer(Component c, float minimo, List<String> ruins) {
        if (!c.isVisible()) {
            return;
        }
        String texto = c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText() : null;
        if (texto != null && !texto.isEmpty() && c.getFont() != null && c.getFont().getSize2D() < minimo) {
            ruins.add("'" + texto + "' com " + c.getFont().getSize2D());
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                varrer(f, minimo, ruins);
            }
        }
    }

    @Test
    void semContextoDizQueONaoEstaDisponivel() {
        Visual.tema(Tema.CLARO);
        PaginaTerminal p = Visual.naEdt(() -> new PaginaTerminal(null, avisos));
        assertEquals("O terminal não está disponível: este servidor ainda não tem conexão.", Visual.naEdt(p::textoDoVazio));
        assertFalse(Visual.naEdt(p.botaoNovo()::isEnabled));
        assertFalse(Visual.naEdt(() -> p.novoTerminal()));
        assertTrue(p.terminais().isEmpty());
    }

    @Test
    void comecaVaziaEBotaoNovoAbreOPrimeiroTerminal() {
        PaginaTerminal p = pagina();
        assertTrue(p.terminais().isEmpty());
        assertTrue(Visual.naEdt(() -> p.textoDoVazio()).startsWith("Nenhum terminal aberto"));
        assertFalse(Visual.naEdt(p.botaoFechar()::isVisible), "sem terminal não há o que fechar");
        Visual.naEdt(() -> p.botaoNovo().doClick(0));
        assertEquals(1, p.terminais().size());
        assertEquals("Terminal 1", Visual.naEdt(() -> p.abas().botao(p.selecionadaId()).getText()));
        assertEquals("1 de 8", Visual.naEdt(p::textoDaContagem));
        assertEquals(1, limite.emUso());
        assertTrue(Visual.naEdt(p.botaoFechar()::isVisible));
    }

    @Test
    void oitoAbasCabemANonaRecebeMensagemClaraENadaAbre() {
        PaginaTerminal p = pagina();
        for (int i = 1; i <= 8; i++) {
            assertTrue(Visual.naEdt(() -> p.novoTerminal()), "aba " + i);
        }
        assertEquals(8, p.terminais().size());
        assertEquals("8 de 8", Visual.naEdt(p::textoDaContagem));
        assertEquals("", Visual.naEdt(p::textoDoAviso));

        assertFalse(Visual.naEdt(() -> p.novoTerminal()), "a nona é recusada");
        assertEquals(8, p.terminais().size());
        assertEquals(LimiteDeAbas.mensagemDoLimite(), Visual.naEdt(p::textoDoAviso));
        assertTrue(Visual.naEdt(p::textoDoAviso).contains("8 terminais"));
        assertTrue(avisos.disse("atencao", "8 terminais"), "também em aviso rápido");
        assertEquals(8, limite.emUso());
    }

    @Test
    void fecharUmaAbaDevolveAVagaELimpaAMensagemDoLimite() {
        PaginaTerminal p = pagina();
        for (int i = 1; i <= 8; i++) {
            Visual.naEdt(() -> p.novoTerminal());
        }
        Visual.naEdt(() -> p.novoTerminal()); // a nona, recusada: a mensagem aparece
        assertFalse(Visual.naEdt(p::textoDoAviso).isEmpty());

        Visual.naEdt(() -> p.botaoFechar().doClick(0));
        assertEquals(7, p.terminais().size());
        assertEquals(7, limite.emUso());
        assertEquals("", Visual.naEdt(p::textoDoAviso), "abriu uma vaga: a mensagem não vale mais");
        assertTrue(Visual.naEdt(() -> p.novoTerminal()), "agora cabe de novo");
        assertEquals("8 de 8", Visual.naEdt(p::textoDaContagem));
    }

    @Test
    void fecharAEscolhidaPassaAEscolhaParaAVizinhaEDescartaOTerminal() {
        PaginaTerminal p = pagina();
        for (int i = 0; i < 3; i++) {
            Visual.naEdt(() -> p.novoTerminal());
        }
        var painelDoMeio = p.terminais().get(1);
        Visual.naEdt(() -> p.abas().selecionar("t2"));
        assertEquals("t2", Visual.naEdt(p::selecionadaId));
        Visual.naEdt(() -> p.fecharTerminal("t2"));
        assertTrue(Visual.naEdt(painelDoMeio::descartado), "o terminal fechado é descartado de verdade");
        assertEquals(2, p.terminais().size());
        assertEquals("t3", Visual.naEdt(p::selecionadaId), "a vizinha que ocupou o lugar");
        assertFalse(Visual.naEdt(() -> p.abas().tem("t2")));
        assertEquals("2 de 8", Visual.naEdt(p::textoDaContagem));

        // fechar uma que NÃO é a escolhida não mexe na escolha
        Visual.naEdt(() -> p.fecharTerminal("t1"));
        assertEquals("t3", Visual.naEdt(p::selecionadaId));
        // a última: a página volta ao estado vazio
        Visual.naEdt(() -> p.fecharTerminal("t3"));
        assertTrue(p.terminais().isEmpty());
        assertNull(Visual.naEdt(p::terminalSelecionado));
        assertEquals(0, limite.emUso());
        assertEquals("", Visual.naEdt(p::textoDaContagem));
        assertTrue(Visual.naEdt(p::textoDoVazio).startsWith("Nenhum terminal aberto"));
    }

    @Test
    void osNumerosDasAbasNaoSaoReaproveitados() {
        PaginaTerminal p = pagina();
        Visual.naEdt(() -> p.novoTerminal());
        Visual.naEdt(() -> p.novoTerminal());
        Visual.naEdt(() -> p.fecharTerminal("t1"));
        Visual.naEdt(() -> p.novoTerminal());
        assertFalse(Visual.naEdt(() -> p.abas().tem("t1")));
        assertEquals("Terminal 2", Visual.naEdt(() -> p.abas().botao("t2").getText()));
        assertEquals("Terminal 3", Visual.naEdt(() -> p.abas().botao("t3").getText()), "o número 1 não volta");
    }

    @Test
    void encerrarFechaTodosEDevolveAsVagas() {
        PaginaTerminal p = pagina();
        for (int i = 0; i < 3; i++) {
            Visual.naEdt(() -> p.novoTerminal());
        }
        var todos = p.terminais();
        Visual.naEdt(p::encerrar);
        assertTrue(todos.stream().allMatch(t -> Visual.naEdt(t::descartado)));
        assertEquals(0, limite.emUso());
        assertTrue(p.terminais().isEmpty());
        assertFalse(Visual.naEdt(() -> p.novoTerminal()), "depois de encerrada, nada abre");
        Visual.naEdt(p::encerrar); // idempotente
        assertEquals(0, limite.emUso());
    }

    @Test
    void oLimiteEDoServidorInteiroEDivideComOsTerminaisDasSessoesDoClaude() {
        PaginaTerminal p = pagina();
        // 5 terminais de sessões do Claude (os dos projetos) já usam vagas do mesmo contador
        for (int i = 0; i < 5; i++) {
            assertTrue(limite.reservar());
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(Visual.naEdt(() -> p.novoTerminal()));
        }
        assertFalse(Visual.naEdt(() -> p.novoTerminal()), "5 das sessões mais 3 do shell: a próxima é a nona");
        assertEquals(3, p.terminais().size());
    }
}
