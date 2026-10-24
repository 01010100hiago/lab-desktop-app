package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A sparkline: sem pontos, um ponto, sessenta pontos e tudo igual. */
@ExtendWith(VerificaEdt.class)
class SparklineTest {

    private static Sparkline com(List<? extends Number> v) {
        Sparkline s = Visual.naEdt(Sparkline::new);
        Visual.naEdt(() -> s.definir(v));
        return s;
    }

    @Test
    void semPontosNaoTemCurvaEPintaSoAlinhaBase() {
        Visual.tema(Tema.CLARO);
        Sparkline s = com(List.of());
        assertEquals(0, s.quantidadeDePontos());
        assertTrue(s.pontos(200, 44).isEmpty());
        BufferedImage img = Visual.renderizar(s, 200, 44);
        // só a linha da base (a cor do acento com opacidade baixa), nada de área nem de ponto final
        int linhaDaBase = 0;
        for (int x = 0; x < 200; x++) {
            if ((img.getRGB(x, 43) >>> 24) > 0 && img.getRGB(x, 43) != img.getRGB(x, 20)) {
                linhaDaBase++;
            }
        }
        assertTrue(linhaDaBase > 150, "a linha-base cobre a largura");
    }

    @Test
    void umSoPontoTambemNaoTemCurva() {
        Visual.tema(Tema.CLARO);
        Sparkline s = com(List.of(0.5));
        assertEquals(1, s.quantidadeDePontos());
        assertTrue(s.pontos(200, 44).isEmpty());
        Visual.renderizar(s, 200, 44); // não lança
    }

    @Test
    void descartaNulosNanEInfinitos() {
        Sparkline s = com(Arrays.asList(1.0, null, Double.NaN, 2.0, Double.POSITIVE_INFINITY, 3.0));
        assertEquals(List.of(1.0, 2.0, 3.0), s.valores());
    }

    @Test
    void sessentaPontosGeramSessentaPontosDentroDaAreaEPintamACor() {
        Visual.tema(Tema.CLARO);
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            v.add(0.3 + 0.2 * Math.sin(i / 5.0));
        }
        Sparkline s = com(v);
        Visual.naEdt(() -> s.cor(Tokens.ATENCAO).escala(0.0, 1.0));
        var pts = s.pontos(300, 56);
        assertEquals(60, pts.size());
        for (var p : pts) {
            assertTrue(p.x >= 0 && p.x <= 300 && p.y >= 0 && p.y <= 56, "cabe na área: " + p);
        }
        assertTrue(pts.get(0).x < pts.get(59).x, "o tempo corre para a direita");
        BufferedImage img = Visual.renderizar(s, 300, 56);
        assertTrue(Visual.contarPixels(img, Tokens.cor(Tokens.ATENCAO), 12) > 150, "a linha usa o token da cor");
    }

    @Test
    void serieConstanteFicaNoMeioSemDividirPorZero() {
        Visual.tema(Tema.CLARO);
        Sparkline s = com(Arrays.asList(5.0, 5.0, 5.0, 5.0));
        var pts = s.pontos(100, 40);
        assertEquals(4, pts.size());
        for (var p : pts) {
            assertTrue(Double.isFinite(p.y));
            assertEquals(pts.get(0).y, p.y, 1e-9, "tudo igual: a mesma altura");
            assertTrue(p.y > 5 && p.y < 35, "no meio da faixa: " + p.y);
        }
        Visual.renderizar(s, 100, 40);
    }

    @Test
    void escalaFixaComValorForaDaFaixaFicaLimitadaAArea() {
        Sparkline s = com(List.of(-5.0, 0.5, 9.0));
        Visual.naEdt(() -> s.escala(0.0, 1.0));
        for (var p : s.pontos(100, 40)) {
            assertTrue(p.y >= 3 && p.y <= 37, "limitado à área útil: " + p.y);
        }
    }

    @Test
    void referenciaETemaEscuroPintamSemErro() {
        Visual.tema(Tema.ESCURO);
        Sparkline s = com(List.of(60.0, 70.0, 90.0, 82.0));
        Visual.naEdt(() -> s.escala(30.0, 100.0).referencia(85.0).altura(70));
        BufferedImage img = Visual.renderizar(s, 240, 70);
        Visual.salvar(img, "sparkline-escuro");
        assertEquals(70, s.getPreferredSize().height);
    }

    @Test
    void descricaoDeAcessibilidade() {
        Sparkline s = com(List.of(1.0, 2.0));
        Visual.naEdt(() -> s.descrever("Histórico da CPU"));
        assertEquals("Histórico da CPU", s.getAccessibleContext().getAccessibleName());
    }
}
