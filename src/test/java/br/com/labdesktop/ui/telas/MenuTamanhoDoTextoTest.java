package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.MenuApp;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Visualizar, "Aumentar texto / Diminuir texto / Tamanho normal": atalhos, o efeito na escala e a preferência lembrada. */
@ExtendWith(VerificaEdt.class)
class MenuTamanhoDoTextoTest {

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private PainelPrincipal painel;
    private PreferenciasAtuais preferencias;
    private MenuApp menu;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi();
        AvisosGravados avisos = new AvisosGravados();
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
        // com pasta de verdade: a gravação em segundo plano das preferências não pode falhar por falta de caminho
        preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(pasta), Preferencias.padrao());
        menu = Visual.naEdt(() -> new MenuApp(null, painel, new GerenciadorTema(Tema.CLARO, false), preferencias, () -> { }, () -> { }));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(() -> {
            Fontes.definirPercentual(100);
            painel.encerrar();
        });
        cenario.close();
    }

    @Test
    void osAtalhosSaoCmdMaisCmdMenosECmdZero() {
        int cmd = br.com.labdesktop.ui.componentes.Teclas.comando(); // o MenuApp usa a tecla de comando do sistema
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, cmd), Visual.naEdt(menu.itemAumentarTexto()::getAccelerator));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, cmd), Visual.naEdt(menu.itemDiminuirTexto()::getAccelerator));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_0, cmd), Visual.naEdt(menu.itemTamanhoNormal()::getAccelerator));
        assertEquals("Aumentar texto", Visual.naEdt(menu.itemAumentarTexto()::getText));
        assertEquals("Diminuir texto", Visual.naEdt(menu.itemDiminuirTexto()::getText));
        assertEquals("Tamanho normal", Visual.naEdt(menu.itemTamanhoNormal()::getText));
    }

    @Test
    void aumentarDiminuirEVoltarAoNormalMudamAEscalaELembramNasPreferencias() {
        assertEquals(100, Fontes.percentual());
        assertFalse(Visual.naEdt(menu.itemDiminuirTexto()::isEnabled), "já está no menor");
        assertFalse(Visual.naEdt(menu.itemTamanhoNormal()::isEnabled), "já está normal");

        Visual.naEdt(() -> menu.itemAumentarTexto().doClick());
        assertEquals(115, Fontes.percentual());
        assertEquals(115, preferencias.get().escalaTexto());
        assertTrue(Visual.naEdt(menu.itemDiminuirTexto()::isEnabled));
        assertTrue(Visual.naEdt(menu.itemTamanhoNormal()::isEnabled));

        Visual.naEdt(() -> menu.itemAumentarTexto().doClick());
        assertEquals(130, Fontes.percentual());
        assertFalse(Visual.naEdt(menu.itemAumentarTexto()::isEnabled), "130 % é o maior");
        assertEquals(130, preferencias.get().escalaTexto());

        Visual.naEdt(() -> menu.itemDiminuirTexto().doClick());
        assertEquals(115, Fontes.percentual());

        Visual.naEdt(() -> menu.itemTamanhoNormal().doClick());
        assertEquals(100, Fontes.percentual());
        assertEquals(100, preferencias.get().escalaTexto());
        assertFalse(Visual.naEdt(menu.itemTamanhoNormal()::isEnabled));
    }
}
