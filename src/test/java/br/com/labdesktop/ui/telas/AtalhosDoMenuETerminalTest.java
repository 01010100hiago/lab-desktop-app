package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.MenuApp;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.terminal.ConfigTerminal;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Cmd é do app, o resto é do terminal" (ARQUITETURA 7.4): nenhum item do menu pode usar um atalho que o JediTerm tem no macOS (Cmd+C,
 * Cmd+V, Cmd+K, Cmd+setas...), senão o menu da tela do macOS engole o atalho antes de o terminal vê-lo. E nenhum atalho do app usa
 * Ctrl, Alt, Esc ou Tab, que são do terminal.
 */
@ExtendWith(VerificaEdt.class)
class AtalhosDoMenuETerminalTest {

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private PainelPrincipal painel;
    private MenuApp menu;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi();
        AvisosGravados avisos = new AvisosGravados();
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
        PreferenciasAtuais prefs = new PreferenciasAtuais(CaminhosApp.comPasta(pasta), Preferencias.padrao());
        menu = Visual.naEdt(() -> new MenuApp(null, painel, new GerenciadorTema(Tema.CLARO, false), prefs, () -> { }, () -> { }));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(painel::encerrar);
        cenario.close();
    }

    private List<JMenuItem> itens() {
        List<JMenuItem> todos = new ArrayList<>();
        JMenuBar barra = menu.barra();
        for (int i = 0; i < barra.getMenuCount(); i++) {
            coletar(barra.getMenu(i), todos);
        }
        return todos;
    }

    private static void coletar(JMenu menu, List<JMenuItem> todos) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item instanceof JMenu sub) {
                coletar(sub, todos);
            } else if (item != null) {
                todos.add(item);
            }
        }
    }

    private static Set<KeyStroke> atalhosDoJediTerm() {
        ConfigTerminal c = new ConfigTerminal();
        Set<KeyStroke> s = new HashSet<>();
        for (TerminalActionPresentation p : List.of(c.getOpenUrlActionPresentation(), c.getCopyActionPresentation(), c.getPasteActionPresentation(),
            c.getClearBufferActionPresentation(), c.getPageUpActionPresentation(), c.getPageDownActionPresentation(), c.getLineUpActionPresentation(),
            c.getLineDownActionPresentation(), c.getFindActionPresentation(), c.getSelectAllActionPresentation())) {
            s.addAll(p.getKeyStrokes());
        }
        return s;
    }

    @Test
    void oVigiaEnxergaOsAtalhosNativosDoJediTerm() {
        // só no macOS: no Linux e no Windows o JediTerm usa Ctrl+Shift+C, Ctrl+Shift+V e afins (o app é um app de Mac; a CI roda em Linux)
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"), "atalhos nativos do JediTerm são os do macOS");
        // sanidade do próprio teste: no macOS o JediTerm usa Cmd+C, Cmd+V e Cmd+K
        Set<KeyStroke> nativos = atalhosDoJediTerm();
        assertTrue(nativos.contains(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK)), nativos.toString());
        assertTrue(nativos.contains(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK)), nativos.toString());
        assertTrue(nativos.contains(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.META_DOWN_MASK)), nativos.toString());
    }

    @Test
    void nenhumItemDeMenuUsaUmAtalhoDoJediTerm() {
        Set<KeyStroke> nativos = atalhosDoJediTerm();
        // no Mac o JediTerm usa Cmd+C, Cmd+V e Cmd+K; no Windows e no Linux, Ctrl+Shift+C e Ctrl+Shift+V (e os do leitor acima): o Ctrl puro é do programa que roda no terminal
        int comando = br.com.labdesktop.ui.componentes.Teclas.comando();
        nativos.add(KeyStroke.getKeyStroke(KeyEvent.VK_C, comando));
        nativos.add(KeyStroke.getKeyStroke(KeyEvent.VK_V, comando));
        nativos.add(KeyStroke.getKeyStroke(KeyEvent.VK_K, comando));
        nativos.add(KeyStroke.getKeyStroke(KeyEvent.VK_C, comando | InputEvent.SHIFT_DOWN_MASK));
        nativos.add(KeyStroke.getKeyStroke(KeyEvent.VK_V, comando | InputEvent.SHIFT_DOWN_MASK));
        List<String> colisoes = new ArrayList<>();
        int comAtalho = 0;
        for (JMenuItem item : itens()) {
            KeyStroke a = Visual.naEdt(item::getAccelerator);
            if (a == null) {
                continue;
            }
            comAtalho++;
            if (nativos.contains(a)) {
                colisoes.add(item.getText() + " usa " + a);
            }
        }
        assertTrue(comAtalho >= 10, "o menu tem os atalhos de sempre: " + comAtalho);
        assertTrue(colisoes.isEmpty(), "atalhos do menu que o menu do macOS engoliria antes do terminal: " + colisoes);
    }

    @Test
    void todoAtalhoDoMenuUsaATeclaDeComandoDoSistemaESemAltEscOuTab() {
        int comando = br.com.labdesktop.ui.componentes.Teclas.comando();
        int outra = comando == InputEvent.META_DOWN_MASK ? InputEvent.CTRL_DOWN_MASK : InputEvent.META_DOWN_MASK;
        for (JMenuItem item : itens()) {
            KeyStroke a = Visual.naEdt(item::getAccelerator);
            if (a == null) {
                continue;
            }
            if (a.getKeyCode() == KeyEvent.VK_F1 && a.getModifiers() == 0) {
                continue; // a Ajuda: F1 é o padrão do Windows e do Linux (não é tecla do terminal que o menu engula: o terminal usa F1 só dentro dele)
            }
            assertTrue((a.getModifiers() & comando) != 0, item.getText() + ": todo atalho do app é com a tecla de comando do sistema (" + a + ")");
            assertEquals(0, a.getModifiers() & (outra | InputEvent.ALT_DOWN_MASK), item.getText() + " usa a tecla do outro sistema ou o Alt");
            assertFalse(a.getKeyCode() == KeyEvent.VK_ESCAPE || a.getKeyCode() == KeyEvent.VK_TAB, item.getText() + " usa Esc ou Tab");
        }
    }

    @Test
    void oMenuOferecaAsAbasDoServidorComATeclaDeComandoENumeroNaOrdemDaBarraETerminalEhAOitava() {
        // Cmd+1 a Cmd+9 contam as abas na ordem da barra (a ordem padrão: Resumo, Segurança, Containers, Processos, Máquina, Projetos, Arquivos, Terminal, Cloudflare)
        JMenuItem terminal = itens().stream().filter(i -> "Terminal".equals(i.getText())).findFirst().orElseThrow();
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_8, br.com.labdesktop.ui.componentes.Teclas.comando()), Visual.naEdt(terminal::getAccelerator));
    }
}
