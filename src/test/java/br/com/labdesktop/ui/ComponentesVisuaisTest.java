package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import br.com.labdesktop.ui.VerificaEdt;

/** Os componentes-base desenham com os tokens do tema e acompanham a troca de tema (claro e escuro). */
@ExtendWith(VerificaEdt.class)
class ComponentesVisuaisTest {

    private static JPanel amostra() {
        return Visual.naEdt(() -> {
            JPanel p = new Painel(new BorderLayout());
            p.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
            Painel cartao = new Painel(new BorderLayout(0, 12)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
            cartao.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
            BarraSeveridade b1 = new BarraSeveridade(BarraSeveridade.Tamanho.GROSSA);
            b1.definir(0.42, Severidade.NORMAL, "42%");
            BarraSeveridade b2 = new BarraSeveridade(BarraSeveridade.Tamanho.GROSSA);
            b2.definir(0.78, Severidade.doCredito(0.78), "78%");
            BarraSeveridade b3 = new BarraSeveridade(BarraSeveridade.Tamanho.GROSSA);
            b3.definir(0.93, Severidade.doCredito(0.93), "93%");
            b3.marca(0.9);
            cartao.add(Caixa.coluna(10,
                Rotulo.titulo("Componentes do tema"),
                Rotulo.secundario("Texto secundário e uma dica logo abaixo"),
                Rotulo.dica("Dica em 12 pt"),
                Caixa.linha(8, new Selo("no ar", Gravidade.OK), new Selo("parcial", Gravidade.ATENCAO),
                    new Selo("parado", Gravidade.NEUTRO), new Selo("sessão aberta", Gravidade.ACENTO),
                    new Selo("pausada pelo limite", Gravidade.PERIGO, "pausa")),
                b1, b2, b3,
                Caixa.linha(8, Botoes.primario("Salvar", "ok"), Botoes.secundario("Cancelar", null),
                    Botoes.perigo("Fechar sessão", "fechar"), Botoes.mini("Atualizar", "atualizar"),
                    Botoes.icone("copiar", "Copiar"), Botoes.link("Ver detalhes")),
                new TextoCorrido("Um parágrafo que quebra a linha na largura que o layout der, sem precisar de duas passadas do Swing.", 420)),
                BorderLayout.CENTER);
            p.add(cartao, BorderLayout.CENTER);
            return p;
        });
    }

    @Test
    void desenhaNosDoisTemasEOsPixelsSaoOsDosTokens() {
        BufferedImage[] imagens = new BufferedImage[2];
        int i = 0;
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            JPanel p = amostra();
            imagens[i] = Visual.renderizar(p, 560, 360);
            Visual.salvar(imagens[i], "componentes-" + tema.name().toLowerCase());
            // O fundo do cartão e o preenchimento da barra normal saem nas cores dos tokens do tema.
            assertTrue(Visual.contarPixels(imagens[i], Tokens.cor(Tokens.SUPERFICIE), 0) > 20_000, "cartão em " + tema);
            assertTrue(Visual.contarPixels(imagens[i], Tokens.cor(Tokens.ACENTO), 6) > 400, "barra normal em " + tema);
            assertTrue(Visual.contarPixels(imagens[i], Tokens.cor(Tokens.ATENCAO), 6) > 200, "barra em 78% é âmbar em " + tema);
            assertTrue(Visual.contarPixels(imagens[i], Tokens.cor(Tokens.PERIGO), 6) > 200, "barra em 93% é terracota em " + tema);
            i++;
        }
        assertNotEquals(imagens[0].getRGB(5, 5), imagens[1].getRGB(5, 5), "claro e escuro têm fundos diferentes");
    }

    @Test
    void aMesmaInstanciaAcompanhaATrocaDeTemaSemSerRefeita() {
        Visual.tema(Tema.CLARO);
        JPanel p = amostra();
        BufferedImage claro = Visual.renderizar(p, 560, 360);
        assertTrue(Visual.contarPixels(claro, new Color(0xFFFFFF), 0) > 20_000, "o cartão claro é branco");
        Visual.tema(Tema.ESCURO);
        // numa janela de verdade o FlatLaf.updateUI() já faz isto; o componente solto do teste precisa pedir
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(p));
        BufferedImage escuro = Visual.renderizar(p, 560, 360);
        assertTrue(Visual.contarPixels(escuro, new Color(0x161512), 0) > 20_000,
            "o MESMO cartão passou ao grafite do tema escuro, sem ser recriado");
        assertTrue(Visual.contarPixels(escuro, new Color(0xFFFFFF), 0) < 300, "não sobrou superfície branca do tema claro");
    }

    @Test
    void osIconesSeguemACorDoTextoDoComponente() {
        Visual.tema(Tema.CLARO);
        JButton b = Visual.naEdt(() -> Botoes.primario("Salvar", "ok-circulo"));
        JPanel linha = Visual.naEdt(() -> Caixa.linha(0, b));
        BufferedImage img = Visual.renderizar(linha, 140, 40);
        Visual.salvar(img, "botao-primario");
        // texto e ícone brancos sobre o bronze: há pixels brancos e bronze, e nenhum azul
        assertTrue(Visual.contarPixels(img, Tokens.cor(Tokens.ACENTO), 4) > 400);
        assertTrue(Visual.contarPixels(img, Color.WHITE, 12) > 40);
    }

    @Test
    void todosOsIconesDoPainelCarregamEPintam() {
        Visual.tema(Tema.CLARO);
        java.util.List<String> nomes = Icones.nomes();
        assertTrue(nomes.size() >= 48, "o script de portar deveria ter gerado os 48 ícones do front web original e o extra");
        for (String nome : nomes) {
            Visual.naEdt(() -> {
                Rotulo r = new Rotulo(" ").icone(Icones.de(nome, 24));
                BufferedImage img = Visual.renderizar(r, 40, 40);
                // cada ícone pinta ao menos alguns pixels não transparentes da cor do texto (grafite)
                int pintados = 0;
                for (int y = 0; y < 40; y++) {
                    for (int x = 0; x < 40; x++) {
                        if (img.getRGB(x, y) != Tokens.cor(Tokens.BG).getRGB()) {
                            pintados++;
                        }
                    }
                }
                assertTrue(pintados > 10, "ícone " + nome + " não pintou nada");
            });
        }
    }
}
