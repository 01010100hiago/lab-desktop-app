package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.MenuDoServidor;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoSobre;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.InformacoesDoApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * O que o {@link TrocaDeTemaNaJanelaTest} não cobria. TODAS as seções de Ajustes (e os tópicos da Ajuda que têm tabela, divisor e árvore), os menus que
 * se abrem com o botão direito e o diálogo Sobre aberto: cada um nasce ANTES da troca, o tema muda, e o resultado tem de ser igual (pixel a pixel, folga de 1 por mil) ao mesmo
 * componente montado direto no tema novo. O que fica com a cor de antes (uma borda criada com a cor do tema da hora, um menu guardado) aparece como diferença.
 */
@ExtendWith(VerificaEdt.class)
class TrocaDeTemaEmAjustesPopupsEDialogosTest {

    private static final double TOLERANCIA_POR_MIL = 0.05; // estas telas não têm nada que ande sozinho: qualquer pixel diferente é cor de antes da troca

    @TempDir
    Path pasta;

    private final AvisosGravados avisos = new AvisosGravados();

    private static double porMil(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return 1000.0;
        }
        long diferentes = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    diferentes++;
                }
            }
        }
        return diferentes * 1000.0 / ((long) a.getWidth() * a.getHeight());
    }

    private record Janela(PainelPrincipal painel, TelaAjustes ajustes) { }

    private Janela montar(CenarioDeUi cenario, GerenciadorTema tema, PreferenciasAtuais prefs) {
        TelaAjustes[] guardada = new TelaAjustes[1];
        PainelPrincipal p = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos),
            br.com.labdesktop.arquivos.ServicosDeArquivos.descartaveis(), prefs::get));
        Visual.naEdt(() -> p.instalarAjustes(() -> {
            guardada[0] = new TelaAjustes(new AjustesDoApp(tema, prefs, p, new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), avisos));
            return guardada[0];
        }));
        Visual.naEdt(() -> p.mostrarAjustes(TelaAjustes.Secao.APARENCIA)); // cria a tela
        return new Janela(p, guardada[0]);
    }

    /** O que se mede: o que a tela mostra, sem janela (a tela Ajustes inteira, ou o componente solto). */
    private record Alvo(String nome, Supplier<JComponent> componente, int largura, int altura) { }

    @Test
    void secoesDeAjustesMenusEDialogoSobreDepoisDeTrocarOTemaSaemIguaisAoTemaNovo() throws Exception {
        List<String> ruins = new ArrayList<>();
        StringBuilder tabela = new StringBuilder();
        for (Tema de : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Tema para = de == Tema.CLARO ? Tema.ESCURO : Tema.CLARO;
            try (CenarioDeUi cenario = new CenarioDeUi()) {
                CaminhosApp caminhos = CaminhosApp.comPasta(pasta.resolve(de.name()));
                InformacoesDoApp info = InformacoesDoApp.coletar(caminhos);
                Visual.tema(de);
                GerenciadorTema tema = new GerenciadorTema(de, false, false);
                Visual.naEdt(tema::instalar);
                Janela velha = montar(cenario, tema, new PreferenciasAtuais(caminhos, Preferencias.padrao()));
                PainelDoRegistro registroVelho = Visual.naEdt(() -> new PainelDoRegistro(true, null, new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), avisos,
                    () -> java.time.ZoneId.of("America/Sao_Paulo")));
                Map<String, JComponent> velhos = new LinkedHashMap<>();
                velhos.put("popup do servidor", Visual.naEdt(() -> MenuDoServidor.popup(cenario.lab, velha.painel().acoes())));
                DialogoSobre sobreVelho = Visual.naEdt(() -> new DialogoSobre(info));
                velhos.put("dialogo Sobre", Visual.naEdt(sobreVelho::raiz));
                // a troca de verdade, com tudo o que já existe montado
                Visual.naEdt(() -> {
                    tema.definirEscolha(para);
                    SwingUtilities.updateComponentTreeUI(velha.painel());
                    SwingUtilities.updateComponentTreeUI(velhos.get("dialogo Sobre")); // um diálogo aberto está numa janela e o FlatLaf a atualiza junto
                    // o menu do servidor é aberto (e atualizado) na hora de mostrar; o do registro foi criado ANTES e fica guardado
                    SwingUtilities.updateComponentTreeUI(velhos.get("popup do servidor"));
                });
                // o menu do registro é montado quando o usuário clica com o botão direito: DEPOIS da troca, a partir do painel que já existia
                velhos.put("popup do registro", Visual.naEdt(registroVelho::menuDeContexto));

                Janela nova = montar(cenario, tema, new PreferenciasAtuais(caminhos, Preferencias.padrao()));
                PainelDoRegistro registroNovo = Visual.naEdt(() -> new PainelDoRegistro(true, null, new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), avisos,
                    () -> java.time.ZoneId.of("America/Sao_Paulo")));
                Map<String, JComponent> novos = new LinkedHashMap<>();
                novos.put("popup do registro", Visual.naEdt(registroNovo::menuDeContexto));
                novos.put("popup do servidor", Visual.naEdt(() -> MenuDoServidor.popup(cenario.lab, nova.painel().acoes())));
                novos.put("dialogo Sobre", Visual.naEdt(() -> new DialogoSobre(info).raiz()));

                for (var e : velhos.entrySet()) {
                    comparar(de, para, e.getKey(), e.getValue(), novos.get(e.getKey()), 420, 260, tabela, ruins);
                }
                List<String> secoes = new ArrayList<>();
                for (TelaAjustes.Secao s : TelaAjustes.Secao.values()) {
                    secoes.add("secao " + s.id());
                }
                secoes.addAll(List.of("ajuda indice", "ajuda atalhos", "ajuda conectar", "ajuda publicar"));
                for (String nome : secoes) {
                    Visual.naEdt(() -> abrirNaTela(velha, nome));
                    Visual.naEdt(() -> abrirNaTela(nova, nome));
                    comparar(de, para, nome, velha.painel(), nova.painel(), 1440, 860, tabela, ruins);
                }
                Visual.naEdt(velha.painel()::encerrar);
                Visual.naEdt(nova.painel()::encerrar);
            }
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/troca-de-tema-ajustes-popups-dialogos.txt"), tabela.toString());
        assertTrue(ruins.isEmpty(), "diferentes depois de trocar o tema com tudo já montado:\n" + String.join("\n", ruins));
    }

    private static void abrirNaTela(Janela j, String nome) {
        if (nome.startsWith("secao ")) {
            j.painel().mostrarAjustes(TelaAjustes.Secao.valueOf(nome.substring("secao ".length()).toUpperCase(java.util.Locale.ROOT)));
        } else {
            j.painel().mostrarAjustes(TelaAjustes.Secao.AJUDA);
            j.ajustes().abrirTopicoDeAjuda(nome.substring("ajuda ".length()));
        }
    }

    private static void comparar(Tema de, Tema para, String nome, JComponent velho, JComponent novo, int largura, int altura, StringBuilder tabela, List<String> ruins) {
        Dimension tamanho = new Dimension(largura, altura);
        if (!(velho instanceof PainelPrincipal)) {
            Dimension pv = Visual.naEdt(velho::getPreferredSize);
            tamanho = new Dimension(Math.max(120, pv.width), Math.max(40, pv.height));
        }
        BufferedImage a = Visual.renderizar(velho, tamanho.width, tamanho.height);
        BufferedImage b = Visual.renderizar(novo, tamanho.width, tamanho.height);
        double diferenca = porMil(a, b);
        tabela.append(String.format("%s -> %s | %-18s | %.3f por mil pixels diferentes%n", de, para, nome, diferenca));
        if (diferenca > TOLERANCIA_POR_MIL) {
            ruins.add(String.format("%s -> %s | %s | %.3f por mil", de, para, nome, diferenca));
            String base = "troca-de-tema-" + de.name().toLowerCase() + "-" + nome.replace(' ', '-').toLowerCase();
            Visual.salvar(a, base + "-trocada");
            Visual.salvar(b, base + "-montada");
        }
    }
}
