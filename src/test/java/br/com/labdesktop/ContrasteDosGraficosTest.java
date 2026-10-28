package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.tendencias.Metrica;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.telas.GraficosParaTeste;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.util.HashSet;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * As linhas dos seis gráficos de tendência nos DOIS temas: cada linha tem contraste de pelo menos 3:1 contra a superfície onde é desenhada (o mínimo para elemento gráfico, WCAG
 * 1.4.11), e os seis gráficos se distinguem sem depender só de matiz (o nome, a seta de Download e Upload, o traço do Upload, e a luminosidade: nunca duas linhas da mesma cor e do
 * mesmo traço).
 */
class ContrasteDosGraficosTest {

    @BeforeAll
    static void prepara() {
        System.setProperty("java.awt.headless", "true");
        GerenciadorTema.preparar();
    }

    private static void instalar(Tema tema) throws Exception {
        SwingUtilities.invokeAndWait(() -> new GerenciadorTema(tema, false).instalar());
    }

    @Test
    void cadaLinhaTemPeloMenosTresPorUmContraAsuperficieNosDoisTemas() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            instalar(tema);
            Color superficie = Tokens.cor(Tokens.SUPERFICIE);
            for (Metrica m : Metrica.values()) {
                Color linha = Tokens.cor(GraficosParaTeste.token(m));
                double c = TesteContraste.contraste(linha, superficie);
                assertTrue(c >= 3.0, tema + ": a linha de " + m.nome() + " tem contraste " + String.format("%.2f", c) + " contra a superfície (mínimo 3)");
            }
        }
    }

    @Test
    void osSeisGraficosNuncaTemAMesmaCorEOMesmoTracoENomesDiferentes() {
        Set<String> estilos = new HashSet<>();
        Set<String> nomes = new HashSet<>();
        for (Metrica m : Metrica.values()) {
            assertTrue(estilos.add(GraficosParaTeste.token(m) + "|" + GraficosParaTeste.tracejada(m)), "estilo repetido em " + m.nome());
            assertTrue(nomes.add(m.nome()));
        }
        assertEquals(6, estilos.size());
        assertTrue(GraficosParaTeste.tracejada(Metrica.UPLOAD) && !GraficosParaTeste.tracejada(Metrica.DOWNLOAD), "Download sólido e Upload tracejado");
    }

    @Test
    void asCoresDasLinhasNaoTemMatizAzulNosDoisTemas() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            instalar(tema);
            for (Metrica m : Metrica.values()) {
                Color c = Tokens.cor(GraficosParaTeste.token(m));
                float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
                float matiz = hsb[0] * 360f;
                boolean azul = matiz >= 165 && matiz <= 275 && hsb[1] >= 0.15 && hsb[2] >= 0.15;
                assertTrue(!azul, tema + " " + m.nome() + " tem matiz azul/ciano: " + matiz);
            }
        }
    }
}
