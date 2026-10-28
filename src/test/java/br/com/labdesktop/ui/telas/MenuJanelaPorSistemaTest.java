package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.ComportamentoDaJanela;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.MenuApp;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.Pendencias;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** O menu Janela por sistema: fora do Mac não existe "Fechar a janela" (Ctrl+W não pode sair do app); no Mac é Cmd+W e esconde. */
@ExtendWith(VerificaEdt.class)
class MenuJanelaPorSistemaTest {

    @TempDir
    Path pasta;

    private List<String> itensDaJanela(SistemaOperacional so) throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenarioDeUi cenario = new CenarioDeUi()) {
            AvisosGravados avisos = new AvisosGravados();
            PainelPrincipal painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
            PreferenciasAtuais preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(pasta), Preferencias.padrao());
            MenuApp menu = Visual.naEdt(() -> new MenuApp(null, painel, new GerenciadorTema(Tema.CLARO, false), preferencias, () -> { }, () -> { },
                new ComportamentoDaJanela(so, () -> { })));
            List<String> nomes = new ArrayList<>();
            Visual.naEdt(() -> {
                for (int i = 0; i < menu.barra().getMenuCount(); i++) {
                    JMenu m = menu.barra().getMenu(i);
                    if ("Janela".equals(m.getText())) {
                        for (int j = 0; j < m.getItemCount(); j++) {
                            JMenuItem it = m.getItem(j);
                            if (it != null) {
                                nomes.add(it.getText());
                            }
                        }
                    }
                }
            });
            Visual.naEdt(painel::encerrar);
            return nomes;
        }
    }

    @Test
    void foraDoMacNaoHaFecharAJanelaNemCtrlW() throws Exception {
        for (SistemaOperacional so : new SistemaOperacional[] {SistemaOperacional.WINDOWS, SistemaOperacional.LINUX}) {
            List<String> itens = itensDaJanela(so);
            assertEquals(List.of("Minimizar"), itens, so.name());
        }
    }

    @Test
    void noMacFecharAJanelaContinuaComCmdW() throws Exception {
        List<String> itens = itensDaJanela(SistemaOperacional.MAC);
        assertTrue(itens.contains("Fechar a janela"), itens.toString());
        assertFalse(itens.isEmpty());
    }

    @Test
    void semServidorAbertoNaoHaPendencias() throws Exception {
        Visual.tema(Tema.CLARO);
        try (CenarioDeUi cenario = new CenarioDeUi()) {
            AvisosGravados avisos = new AvisosGravados();
            PainelPrincipal painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
            assertTrue(Visual.naEdt(painel::pendencias).vazio());
            assertEquals(Pendencias.NENHUMAS, Visual.naEdt(painel::pendencias));
            Visual.naEdt(painel::encerrar);
        }
    }
}
