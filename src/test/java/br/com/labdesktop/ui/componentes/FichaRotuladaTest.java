package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.FichaRotulada.Item;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A ficha rotulada: cada dado com o seu rótulo, só o que existe, e some inteira sem dados. */
@ExtendWith(VerificaEdt.class)
class FichaRotuladaTest {

    @Test
    void mostraOsParesRotuloValorNaOrdemEFicaVisivel() {
        Visual.tema(Tema.CLARO);
        FichaRotulada f = Visual.naEdt(() -> new FichaRotulada(2, 16));
        assertFalse(Visual.naEdt(f::isVisible), "sem dados, nada na tela");
        Visual.naEdt(() -> f.definir(List.of(Item.de("Código", "Branch main, tudo salvo"), Item.de("Mexido", "há 3 dias"),
            Item.de("Pasta", "/srv/lab/projects/app-loja"))));
        assertTrue(Visual.naEdt(f::isVisible));
        assertEquals(List.of(List.of("Código", "Branch main, tudo salvo"), List.of("Mexido", "há 3 dias"),
            List.of("Pasta", "/srv/lab/projects/app-loja")), Visual.naEdt(f::pares));
    }

    @Test
    void semDadosASomeInteiraDeNovo() {
        Visual.tema(Tema.CLARO);
        FichaRotulada f = Visual.naEdt(() -> new FichaRotulada(4, 12));
        Visual.naEdt(() -> f.definir(List.of(Item.de("CPU", "12%"))));
        assertTrue(Visual.naEdt(f::isVisible));
        Visual.naEdt(() -> f.definir(List.of()));
        assertFalse(Visual.naEdt(f::isVisible));
        assertEquals(List.of(), Visual.naEdt(f::pares));
    }

    @Test
    void oTextoQueNaoCabeVaiParaADicaSemQuebrarALargura() {
        Visual.tema(Tema.CLARO);
        String longo = "um valor muito comprido ".repeat(12).strip();
        FichaRotulada f = Visual.naEdt(() -> new FichaRotulada(2, 16));
        Visual.naEdt(() -> f.definir(List.of(Item.de("Descrição", longo), Item.de("Outro", "curto"))));
        Visual.renderizar(f, 400, 200);
        assertTrue(Visual.naEdt(() -> f.getPreferredSize().height) < 120, "uma linha de rótulo e uma de valor por dado, sem quebrar em várias");
        assertEquals(longo, Visual.naEdt(() -> primeiraDica(f)));
    }

    private static String primeiraDica(java.awt.Component c) {
        if (c instanceof javax.swing.JComponent j && j.getToolTipText() != null) {
            return j.getToolTipText();
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component filho : k.getComponents()) {
                String d = primeiraDica(filho);
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }
}
