package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AuditoriaDeCores.Estado;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O botão APERTADO tem de ter uma cor própria, diferente da do "mouse em cima" (antes o primário usava o mesmo token nos dois e o clique
 * não mudava nada), e o botão da seta da lista suspensa não pode ser um quadrado que parece um botão primário preto (grafite no claro, quase branco no escuro).
 */
@ExtendWith(VerificaEdt.class)
class EstadosDoBotaoTest {

    private static final Tema[] TEMAS = {Tema.CLARO, Tema.ESCURO};

    private static double distancia(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
    }

    private static List<Medida> medir(Supplier<JComponent> fabrica, boolean padrao) {
        JComponent controle = fabrica.get();
        Painel p = new Painel(new FlowLayout(FlowLayout.LEFT, 16, 16));
        p.fundo(Tokens.SUPERFICIE);
        p.add(controle);
        JRootPane raiz = new JRootPane();
        raiz.setContentPane(p);
        if (padrao && controle instanceof javax.swing.JButton b) {
            raiz.setDefaultButton(b);
        }
        return AuditoriaDeCores.auditar(raiz, 300, 90, true);
    }

    private static Color fundo(List<Medida> medidas, Estado estado) {
        return medidas.stream().filter(m -> m.estado() == estado).findFirst().orElseThrow().fundo();
    }

    @Test
    void apertadoTemCorPropriaDiferenteDoMouseEmCimaNosDoisTemas() throws IOException {
        Map<String, Supplier<JComponent>> variantes = new LinkedHashMap<>();
        variantes.put("primario", () -> Botoes.primario("Confirmar", null));
        variantes.put("miniPrimario", () -> Botoes.miniPrimario("Criar", null));
        variantes.put("iconePrimario", () -> Botoes.iconePrimario("atualizar", "Atualizar"));
        variantes.put("secundario", () -> Botoes.secundario("Cancelar", null));
        variantes.put("fantasma", () -> Botoes.fantasma("Voltar", null));
        variantes.put("perigo", () -> Botoes.perigo("Apagar", null));
        List<String> linhas = new ArrayList<>();
        List<String> iguais = new ArrayList<>();
        for (Tema tema : TEMAS) {
            Visual.tema(tema);
            for (var v : variantes.entrySet()) {
                for (boolean padrao : new boolean[] {false, true}) {
                    List<Medida> m = Visual.naEdt(() -> medir(v.getValue(), padrao));
                    Color emCima = fundo(m, Estado.MOUSE_EM_CIMA);
                    Color apertado = fundo(m, Estado.APERTADO);
                    double d = distancia(emCima, apertado);
                    linhas.add(tema + " | " + v.getKey() + (padrao ? " | Enter" : "") + " | mouse em cima " + AuditoriaDeCores.hex(emCima) + " | apertado "
                        + AuditoriaDeCores.hex(apertado) + " | distancia " + (int) d);
                    // o contorno do perigo no escuro é um véu quase transparente: basta ser diferente
                    double minimo = v.getKey().equals("perigo") && !padrao ? 3 : 24;
                    if (d < minimo) {
                        iguais.add(linhas.get(linhas.size() - 1));
                    }
                }
            }
        }
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/estados-do-botao.txt"), linhas);
        assertTrue(iguais.isEmpty(), "apertado parecido demais com o mouse em cima:\n" + String.join("\n", iguais));
    }

    /** A cor mais frequente da área (o fundo do botão: a seta e a borda são minoria). */
    private static Color maisComum(BufferedImage img, int x0, int y0, int x1, int y1) {
        Map<Integer, Integer> contagem = new java.util.HashMap<>();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                contagem.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
            }
        }
        return new Color(contagem.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey());
    }

    @Test
    void aSetaDaListaSuspensaNaoEUmQuadradoPrimario() {
        for (Tema tema : TEMAS) {
            Visual.tema(tema);
            JComboBox<String> combo = Visual.naEdt(() -> new JComboBox<>(new String[] {"Primeira", "Segunda"}));
            Painel p = Visual.naEdt(() -> {
                Painel pp = new Painel(new FlowLayout(FlowLayout.LEFT, 16, 16));
                pp.fundo(Tokens.SUPERFICIE);
                pp.add(combo);
                return pp;
            });
            BufferedImage img = Visual.renderizar(p, 220, 70);
            Visual.salvar(img, "lista-suspensa-" + tema.name().toLowerCase());
            Color superficie = Tokens.cor(Tokens.SUPERFICIE);
            // o pixel do botão da seta: perto da borda direita do campo, no meio da altura
            java.awt.Rectangle r = Visual.naEdt(() -> combo.getBounds());
            Color botao = maisComum(img, r.x + r.width - 24, r.y + 4, r.x + r.width - 4, r.y + r.height - 4);
            double c = AuditoriaDeCores.contraste(botao, superficie);
            try {
                Files.writeString(Path.of("build/estados-do-botao-lista.txt"), tema + " | botao da seta " + AuditoriaDeCores.hex(botao) + " sobre " + AuditoriaDeCores.hex(superficie) + " | contraste " + String.format("%.2f", c) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            assertTrue(c < 1.5, tema + ": o botão da seta " + AuditoriaDeCores.hex(botao) + " destoa da superfície " + AuditoriaDeCores.hex(superficie) + " (contraste " + c + ")");
            // e a seta se enxerga nele
            Color seta = Tokens.cor("ComboBox.buttonArrowColor");
            assertTrue(AuditoriaDeCores.contraste(seta, botao) >= 3.0, tema + ": seta sem contraste");
        }
    }
}
