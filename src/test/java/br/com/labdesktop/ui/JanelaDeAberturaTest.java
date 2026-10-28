package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Versao;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A janelinha de abertura: sem tela não faz nada (autoteste e modo sem cabeça seguem), usa as cores do TEMA (não um escuro fixo), fecha sozinha no prazo e é fechada por quem pode matar a
 * partida.
 */
class JanelaDeAberturaTest {

    @AfterEach
    void limpar() {
        JanelaDeAbertura.fecharAAtual();
    }

    /** A janela de mentira: registra o que foi pedido. */
    private static final class Registro implements JanelaDeAbertura.Apresentacao {
        final List<String> eventos = new ArrayList<>();
        JanelaDeAbertura.Cores cores;

        @Override
        public void abrir(JanelaDeAbertura.Cores c) {
            cores = c;
            eventos.add("abriu");
        }

        @Override
        public void fechar() {
            eventos.add("fechou");
        }
    }

    @Test
    void semTelaMostrarEFecharNaoFazemNadaENaoLancam() {
        System.setProperty("java.awt.headless", "true");
        assertDoesNotThrow(() -> {
            JanelaDeAbertura j = JanelaDeAbertura.mostrar(true);
            j.fechar();
            j.fechar(); // idempotente
        });
    }

    @Test
    void asCoresSaoAsDoTemaClaroEDoEscuro() throws IOException {
        assertEquals(token("FlatLightLaf", "Lab.superficie"), JanelaDeAbertura.Cores.claro().fundo());
        assertEquals(token("FlatLightLaf", "Lab.borda"), JanelaDeAbertura.Cores.claro().borda());
        assertEquals(token("FlatLightLaf", "Lab.texto"), JanelaDeAbertura.Cores.claro().texto());
        assertEquals(token("FlatLightLaf", "Lab.texto2"), JanelaDeAbertura.Cores.claro().textoApagado());
        assertEquals(token("FlatDarkLaf", "Lab.superficie"), JanelaDeAbertura.Cores.escuro().fundo());
        assertEquals(token("FlatDarkLaf", "Lab.borda"), JanelaDeAbertura.Cores.escuro().borda());
        assertEquals(token("FlatDarkLaf", "Lab.texto"), JanelaDeAbertura.Cores.escuro().texto());
        assertEquals(token("FlatDarkLaf", "Lab.texto2"), JanelaDeAbertura.Cores.escuro().textoApagado());
        assertEquals(JanelaDeAbertura.Cores.escuro(), JanelaDeAbertura.Cores.de(true));
        assertEquals(JanelaDeAbertura.Cores.claro(), JanelaDeAbertura.Cores.de(false));
    }

    private static Color token(String tema, String chave) throws IOException {
        Properties p = new Properties();
        try (InputStream in = JanelaDeAberturaTest.class.getResourceAsStream("/br/com/labdesktop/ui/tema/" + tema + ".properties")) {
            p.load(in);
        }
        return Color.decode(p.getProperty(chave).trim());
    }

    @Test
    void fechaSozinhaNoPrazoDeSeguranca() {
        Registro r = new Registro();
        List<Runnable> agendadas = new ArrayList<>();
        List<Duration> esperas = new ArrayList<>();
        JanelaDeAbertura j = JanelaDeAbertura.mostrar(JanelaDeAbertura.Cores.claro(), r, (espera, acao) -> {
            esperas.add(espera);
            agendadas.add(acao);
        });
        assertTrue(j.aberta());
        assertEquals(List.of(Duration.ofSeconds(20)), esperas);
        agendadas.get(0).run(); // passou o prazo
        assertFalse(j.aberta());
        assertEquals(List.of("abriu", "fechou"), r.eventos);
    }

    @Test
    void fecharAAtualFechaAJanelaAbertaEUmaVezSo() {
        Registro r = new Registro();
        JanelaDeAbertura j = JanelaDeAbertura.mostrar(JanelaDeAbertura.Cores.escuro(), r, (e, a) -> { });
        assertEquals(JanelaDeAbertura.Cores.escuro(), r.cores);
        JanelaDeAbertura.fecharAAtual();
        JanelaDeAbertura.fecharAAtual();
        j.fechar();
        assertEquals(List.of("abriu", "fechou"), r.eventos);
    }

    @Test
    void fecharAAtualSemJanelaNaoFazNada() {
        assertDoesNotThrow(JanelaDeAbertura::fecharAAtual);
    }

    @Test
    void oDesenhoUsaAsCoresDoTemaENomeiaOApp() {
        Visual.preparar();
        for (boolean escuro : new boolean[] {false, true}) {
            JanelaDeAbertura.Cores cores = JanelaDeAbertura.Cores.de(escuro);
            JanelaDeAbertura.PainelDeAbertura p = Visual.naEdt(() -> new JanelaDeAbertura.PainelDeAbertura(cores));
            assertEquals(Versao.NOME + ": abrindo", Visual.naEdt(() -> p.getAccessibleContext().getAccessibleName()));
            BufferedImage img = Visual.renderizar(p, 360, 170);
            Visual.salvar(img, "janelinha-de-abertura-" + (escuro ? "escuro" : "claro"));
            // o fundo (a cor mais comum) é o da superfície do tema, e nenhum pixel é azul vibrante
            assertEquals(cores.fundo().getRGB(), img.getRGB(30, 30));
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    Color c = new Color(img.getRGB(x, y));
                    float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
                    boolean azulOuCiano = hsb[0] * 360 >= 165 && hsb[0] * 360 <= 275 && hsb[1] >= 0.25f && hsb[2] >= 0.15f;
                    assertTrue(!azulOuCiano, "pixel azulado em " + x + "," + y + ": " + c);
                }
            }
        }
    }
}
