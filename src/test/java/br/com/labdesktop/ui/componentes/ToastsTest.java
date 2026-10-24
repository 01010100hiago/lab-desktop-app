package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractButton;
import javax.swing.JRootPane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Os avisos flutuantes: limite de 4 na tela, canto inferior direito, ação e fechar. */
@ExtendWith(VerificaEdt.class)
class ToastsTest {

    private JRootPane raiz;
    private Toasts toasts;

    @BeforeEach
    void montar() {
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> {
            raiz = new JRootPane();
            raiz.setSize(1000, 700);
            raiz.getLayeredPane().setSize(1000, 700);
            toasts = Toasts.instalar(raiz);
        });
    }

    private static void botoes(Component c, List<AbstractButton> saida) {
        if (c instanceof AbstractButton b) {
            saida.add(b);
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                botoes(f, saida);
            }
        }
    }

    private AbstractButton botao(String nome) {
        List<AbstractButton> todos = new ArrayList<>();
        botoes(raiz.getLayeredPane(), todos);
        return todos.stream()
            .filter(b -> nome.equals(b.getText()) || nome.equals(b.getAccessibleContext().getAccessibleName()))
            .findFirst().orElse(null);
    }

    @Test
    void ficamNoCantoInferiorDireitoEmpilhadosParaCima() {
        Visual.naEdt(() -> {
            toasts.ok("Primeiro");
            toasts.erro("Segundo");
            Component[] cartoes = raiz.getLayeredPane().getComponentsInLayer(javax.swing.JLayeredPane.POPUP_LAYER);
            assertEquals(2, cartoes.length);
            for (Component c : cartoes) {
                assertTrue(c.getX() + c.getWidth() <= 1000 - 20 + 1, "dentro da margem direita: " + c.getBounds());
                assertTrue(c.getY() + c.getHeight() <= 700 - 20 + 1, "dentro da margem inferior: " + c.getBounds());
            }
            assertTrue(cartoes[0].getY() != cartoes[1].getY(), "um acima do outro");
        });
        assertEquals("Segundo", Visual.naEdt(toasts::ultimoTexto));
    }

    @Test
    void nuncaPassaDeQuatroNaTelaOMaisAntigoSai() {
        Visual.naEdt(() -> {
            for (int i = 1; i <= 6; i++) {
                toasts.info("aviso " + i);
            }
        });
        assertEquals(4, Visual.naEdt(toasts::naTela));
        assertEquals("aviso 6", Visual.naEdt(toasts::ultimoTexto));
    }

    @Test
    void oBotaoFecharTiraSoAqueleAviso() {
        Visual.naEdt(() -> {
            toasts.info("fica");
            toasts.atencao("sai");
        });
        assertEquals(2, Visual.naEdt(toasts::naTela));
        Visual.naEdt(() -> {
            // o último a entrar é o último na camada; o botão de fechar dele é o último da lista de botões
            List<AbstractButton> todos = new ArrayList<>();
            botoes(raiz.getLayeredPane(), todos);
            todos.stream().filter(b -> "Fechar o aviso".equals(b.getAccessibleContext().getAccessibleName()))
                .reduce((a, b) -> a).orElseThrow().doClick();
        });
        assertEquals(1, Visual.naEdt(toasts::naTela));
    }

    @Test
    void avisoComAcaoRodaAAcaoUmaVezEFecha() {
        AtomicInteger vezes = new AtomicInteger();
        Visual.naEdt(() -> toasts.comAcao("A sessão pausou", "Retomar", vezes::incrementAndGet));
        AbstractButton b = Visual.naEdt(() -> botao("Retomar"));
        assertNotNull(b, "o botão da ação aparece");
        Visual.naEdt(() -> { b.doClick(); });
        assertEquals(1, vezes.get());
        assertEquals(0, Visual.naEdt(toasts::naTela));
        assertNull(Visual.naEdt(toasts::ultimoTexto));
    }

    @Test
    void textoDoServidorNaoViraHtml() {
        Visual.naEdt(() -> toasts.erro("<html><img src='http://exemplo.invalid/x.png'>oi</html>"));
        Visual.naEdt(() -> {
            List<javax.swing.JLabel> rotulos = new ArrayList<>();
            coletarRotulos(raiz.getLayeredPane(), rotulos);
            assertTrue(rotulos.stream().noneMatch(r -> r.getText() != null && r.getText().startsWith("<html")
                && Boolean.TRUE.equals(r.getClientProperty("html.disable")) == false),
                "todo texto de aviso entra com html.disable");
        });
    }

    private static void coletarRotulos(Component c, List<javax.swing.JLabel> saida) {
        if (c instanceof javax.swing.JLabel l) {
            saida.add(l);
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletarRotulos(f, saida);
            }
        }
    }
}
