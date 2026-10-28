package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ui.Visual;
import java.awt.event.HierarchyEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import org.junit.jupiter.api.Test;

/** O relógio do "girando" só anda com o botão na tela: antes repintava até a ação terminar, mesmo com a aba trocada. */
class IconeOcupadoAnimacaoTest {

    /** Um componente que o teste põe "na tela" ou "fora dela" sem janela de verdade. */
    private static final class Alvo extends JComponent {
        private static final long serialVersionUID = 1L;
        final AtomicBoolean naTela = new AtomicBoolean();
        final AtomicInteger repinturas = new AtomicInteger();

        @Override
        public boolean isShowing() {
            return naTela.get();
        }

        @Override
        public void repaint() {
            repinturas.incrementAndGet();
        }

        void mostrar(boolean sim) {
            naTela.set(sim);
            dispatchEvent(new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, getParent(), HierarchyEvent.SHOWING_CHANGED));
        }
    }

    @Test
    void foraDaTelaNaoRepintaEVoltaQuandoReaparece() throws Exception {
        Visual.preparar();
        Alvo alvo = Visual.naEdt(Alvo::new);
        Runnable parar = Visual.naEdt(() -> IconeOcupado.animar(alvo));
        Thread.sleep(250);
        assertEquals(0, alvo.repinturas.get(), "fora da tela o relógio nem começa");

        Visual.naEdt(() -> alvo.mostrar(true));
        Espera.ate(Duration.ofSeconds(5), "repintar com o botão na tela", () -> alvo.repinturas.get() >= 3);

        Visual.naEdt(() -> alvo.mostrar(false)); // a aba foi trocada
        int aoSair = alvo.repinturas.get();
        Thread.sleep(300);
        assertTrue(alvo.repinturas.get() <= aoSair + 1, "depois de sair da tela o relógio para (" + aoSair + " -> " + alvo.repinturas.get() + ")");

        Visual.naEdt(parar::run);
        Visual.naEdt(() -> alvo.mostrar(true));
        int depois = alvo.repinturas.get();
        Thread.sleep(250);
        assertEquals(depois, alvo.repinturas.get(), "parado de vez: reaparecer não o liga de novo");
    }
}
