package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A caixa e a bolinha DESMARCADAS têm de se ver sobre cada superfície, nos dois temas (WCAG 1.4.11: pelo menos 3:1 entre o contorno do controle e o fundo). No tema do Mac ({@code FlatMacLaf})
 * a caixa é "preenchida" e, por padrão, SEM contorno: no escuro ela virava um quadradinho cinza quase da cor do fundo.
 */
@ExtendWith(VerificaEdt.class)
class ControlesDesmarcadosTest {

    private static final String[] SUPERFICIES = {Tokens.BG, Tokens.SUPERFICIE, Tokens.SUPERFICIE2, Tokens.SUPERFICIE3};

    /** O maior contraste entre qualquer pixel da área do ícone (os 20 primeiros pixels) e o fundo. */
    private static double maiorContrasteDoIcone(JComponent controle, String superficie) {
        Painel p = new Painel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        p.fundo(superficie);
        p.add(controle);
        p.setSize(120, 30);
        p.doLayout();
        BufferedImage img = Visual.renderizar(p, 120, 30);
        Color fundo = new Color(img.getRGB(119, 2), true);
        double melhor = 1;
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 20; x++) {
                melhor = Math.max(melhor, AuditoriaDeCores.contraste(new Color(img.getRGB(x, y), true), fundo));
            }
        }
        return melhor;
    }

    @Test
    void caixaEBolinhaDesmarcadasTemContornoComTresParaUmNosDoisTemas() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            for (String s : SUPERFICIES) {
                double caixa = Visual.naEdt(() -> maiorContrasteDoIcone(new JCheckBox(), s));
                double bolinha = Visual.naEdt(() -> maiorContrasteDoIcone(new JRadioButton(), s));
                assertTrue(caixa >= 3.0, "caixa desmarcada em " + t + " sobre " + s + ": " + caixa);
                assertTrue(bolinha >= 3.0, "bolinha desmarcada em " + t + " sobre " + s + ": " + bolinha);
            }
        }
        Visual.tema(Tema.CLARO);
    }
}
