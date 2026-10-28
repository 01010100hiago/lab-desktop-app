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
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Visualizar, Aparência: Seguir o sistema (o padrão), Claro e Escuro. A troca vale NA HORA (sem reiniciar: as cores dos componentes
 * saem dos tokens a cada pintura) e fica lembrada nas preferências.
 */
@ExtendWith(VerificaEdt.class)
class MenuAparenciaTest {

    @TempDir
    Path pasta;

    private CenarioDeUi cenario;
    private PainelPrincipal painel;
    private PreferenciasAtuais preferencias;
    private GerenciadorTema tema;
    private MenuApp menu;

    @BeforeEach
    void montar() throws Exception {
        cenario = new CenarioDeUi();
        AvisosGravados avisos = new AvisosGravados();
        // o sistema está CLARO neste teste; a escolha é a padrão das preferências
        tema = new GerenciadorTema(Preferencias.padrao().tema(), false, false);
        Visual.naEdt(tema::instalar);
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
        preferencias = new PreferenciasAtuais(CaminhosApp.comPasta(pasta), Preferencias.padrao());
        menu = Visual.naEdt(() -> new MenuApp(null, painel, tema, preferencias, () -> { }, () -> { }));
    }

    @AfterEach
    void desmontar() throws Exception {
        Visual.naEdt(painel::encerrar);
        cenario.close();
    }

    @Test
    void oPadraoESeguirOSistemaECadaEscolhaTrocaOTemaNaHoraELembra() {
        assertEquals(Tema.SISTEMA, preferencias.get().tema(), "o padrão não é mais o escuro");
        assertEquals("Seguir o sistema", Visual.naEdt(menu.itemDeTema(Tema.SISTEMA)::getText));
        assertTrue(Visual.naEdt(menu.itemDeTema(Tema.SISTEMA)::isSelected));
        assertFalse(Visual.naEdt(tema::escuroAgora), "o sistema está claro");
        Color fundoClaro = Visual.naEdt(() -> Tokens.cor(Tokens.BG));
        assertEquals(new Color(0xF6F7F8), fundoClaro);

        Visual.naEdt(() -> menu.itemDeTema(Tema.ESCURO).doClick());
        assertTrue(Visual.naEdt(tema::escuroAgora));
        assertEquals(Tema.ESCURO, preferencias.get().tema());
        assertEquals(new Color(0x16181B), Visual.naEdt(() -> Tokens.cor(Tokens.BG)), "as cores já são as do escuro, sem reiniciar");

        Visual.naEdt(() -> menu.itemDeTema(Tema.CLARO).doClick());
        assertFalse(Visual.naEdt(tema::escuroAgora));
        assertEquals(Tema.CLARO, preferencias.get().tema());
        assertEquals(fundoClaro, Visual.naEdt(() -> Tokens.cor(Tokens.BG)));

        Visual.naEdt(() -> menu.itemDeTema(Tema.SISTEMA).doClick());
        assertEquals(Tema.SISTEMA, preferencias.get().tema());
    }

    @Test
    void comAEscolhaSistemaOTemaSegueOQueOSistemaDiz() {
        Visual.naEdt(() -> tema.definirSistemaEscuro(true));
        assertTrue(Visual.naEdt(tema::escuroAgora), "o macOS foi para o escuro e o app foi junto");
        Visual.naEdt(() -> tema.definirSistemaEscuro(false));
        assertFalse(Visual.naEdt(tema::escuroAgora));
    }
}
