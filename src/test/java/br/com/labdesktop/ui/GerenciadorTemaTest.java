package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import br.com.labdesktop.ui.componentes.Rotulo;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import java.awt.Color;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A troca de tema em execução: os tokens lidos na pintura mudam de cor sem refazer componente nenhum. */
@ExtendWith(VerificaEdt.class)
class GerenciadorTemaTest {

    private static boolean escuro() {
        return Visual.naEdt(FlatLaf::isLafDark);
    }

    private static Color fundo() {
        return Visual.naEdt(() -> Tokens.cor(Tokens.BG));
    }

    @Test
    void escolhaFixaInstalaOTemaPedidoEATrocaEmExecucao() {
        Visual.preparar();
        GerenciadorTema g = new GerenciadorTema(Tema.CLARO, false);
        Visual.naEdt(g::instalar);
        assertFalse(escuro());
        assertFalse(g.escuroAgora());
        Color claro = fundo();

        AtomicInteger avisos = new AtomicInteger();
        g.aoMudar(avisos::incrementAndGet);
        Visual.naEdt(() -> g.definirEscolha(Tema.ESCURO));
        assertTrue(escuro());
        assertTrue(g.escuroAgora());
        assertNotEquals(claro, fundo(), "o token do fundo troca junto com o tema");
        assertEquals(1, avisos.get(), "quem guarda imagem do tema é avisado da troca");

        Visual.naEdt(() -> g.definirEscolha(Tema.CLARO));
        assertFalse(escuro());
        assertEquals(claro, fundo());
        assertEquals(2, avisos.get());
    }

    @Test
    void oTemaDoSistemaSoMandaQuandoAEscolhaESistema() {
        Visual.preparar();
        GerenciadorTema fixo = new GerenciadorTema(Tema.CLARO, false);
        Visual.naEdt(fixo::instalar);
        Visual.naEdt(() -> fixo.definirSistemaEscuro(true));
        assertFalse(escuro(), "a escolha clara ignora o sistema escuro");

        GerenciadorTema seguindo = new GerenciadorTema(Tema.SISTEMA, true, false);
        Visual.naEdt(seguindo::instalar);
        assertTrue(escuro(), "já nasce no tema do sistema, sem instalar o claro antes");
        Visual.naEdt(() -> seguindo.definirSistemaEscuro(false));
        assertFalse(escuro());
        assertEquals(Tema.SISTEMA, seguindo.escolha(), "SISTEMA continua SISTEMA depois de seguir o sistema");
    }

    @Test
    void escolhaNulaViraSistema() {
        assertEquals(Tema.SISTEMA, new GerenciadorTema(null, false).escolha());
        GerenciadorTema g = new GerenciadorTema(Tema.ESCURO, false);
        g.definirEscolha(null);
        assertEquals(Tema.SISTEMA, g.escolha());
    }

    @Test
    void asFontesDoAppSaoInterEJetBrainsMono() {
        Visual.tema(Tema.CLARO);
        assertEquals("Inter", Visual.naEdt(() -> UIManager.getFont("Label.font").getFamily()));
        assertEquals(FlatJetBrainsMonoFont.FAMILY, Visual.naEdt(
            () -> new Rotulo("abc").mono(12f).getFont().getFamily()), "texto técnico (hash, caminho, código) em mono");
    }
}
