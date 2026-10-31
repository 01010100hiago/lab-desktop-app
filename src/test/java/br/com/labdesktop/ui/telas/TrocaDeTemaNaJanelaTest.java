package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AjustesDoApp;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * O caso real do usuário: a janela JÁ montada num tema e o tema trocado com ela aberta. Cada tela pintada DEPOIS da troca tem de ser igual (pixel a pixel, com uma folga
 * mínima para o que anda sozinho, como o arco que gira) à mesma tela de uma janela montada direto no tema novo. É o que pega o contorno que reaparece em volta das áreas
 * rolantes, a margem que o texto ganha de volta e a cor que ficou guardada de antes da troca.
 */
@ExtendWith(VerificaEdt.class)
class TrocaDeTemaNaJanelaTest {

    /** Até este tanto de pixels diferentes (por mil) vale como "igual": o arco que gira e o "Leitura das 13:43:04" (três segundos depois na outra janela) mudam sozinhos. */
    private static final double TOLERANCIA_POR_MIL = 1.0;

    @TempDir
    Path pasta;

    private final AvisosGravados avisos = new AvisosGravados();

    private PainelPrincipal montar(CenarioDeUi cenario, GerenciadorTema tema, PreferenciasAtuais prefs) {
        PainelPrincipal p = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos),
            br.com.labdesktop.arquivos.ServicosDeArquivos.descartaveis(), prefs::get));
        Visual.naEdt(() -> p.instalarAjustes(() -> new TelaAjustes(new AjustesDoApp(tema, prefs, p, new CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("t")), avisos))));
        return p;
    }

    private static double porMil(BufferedImage a, BufferedImage b) {
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

    private void esperarDados(PainelPrincipal painel, TelaServidor ts) {
        Visual.naEdt(() -> painel.mostrarProjetos());
        Visual.naEdt(() -> ts.projetos().assinar());
        Espera.ate(Duration.ofSeconds(20), "lista", () -> Visual.naEdt(() -> ts.projetos().nomesExibidos()).contains("app-loja"));
    }

    @Test
    void cadaTelaDepoisDeTrocarOTemaComAJanelaAbertaEIgualAUmaJanelaMontadaNoTemaNovo() throws Exception {
        List<String> ruins = new ArrayList<>();
        StringBuilder tabela = new StringBuilder();
        for (Tema de : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Tema para = de == Tema.CLARO ? Tema.ESCURO : Tema.CLARO;
            try (CenarioDeUi cenario = new CenarioDeUi()) {
                cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
                CaminhosApp caminhos = CaminhosApp.comPasta(pasta.resolve(de.name()));
                PreferenciasAtuais prefs = new PreferenciasAtuais(caminhos, Preferencias.padrao());
                Visual.tema(de);
                GerenciadorTema tema = new GerenciadorTema(de, false, false);
                Visual.naEdt(tema::instalar);
                PainelPrincipal velha = montar(cenario, tema, prefs);
                TelaServidor tsVelha = Visual.naEdt(() -> velha.navegacao().telaServidorDe(CofreFalso.ID));
                esperarDados(velha, tsVelha);
                assinarTudo(velha, cenario, tsVelha);
                // a página de um projeto também nasce ANTES da troca (é a que o usuário tem aberta quando troca o tema)
                TelaProjeto projetoVelho = abrirProjeto(velha, cenario, tsVelha);
                Visual.naEdt(() -> velha.mostrarAjustes(TelaAjustes.Secao.NAVEGACAO));
                // a troca de verdade: o gerenciador instala o tema novo e a janela inteira se atualiza (é o que o FlatLaf faz com cada janela aberta)
                Visual.naEdt(() -> {
                    tema.definirEscolha(para);
                    SwingUtilities.updateComponentTreeUI(velha);
                });
                // a janela nova, montada já no tema novo
                PainelPrincipal nova = montar(cenario, tema, new PreferenciasAtuais(caminhos, Preferencias.padrao()));
                TelaServidor tsNova = Visual.naEdt(() -> nova.navegacao().telaServidorDe(CofreFalso.ID));
                esperarDados(nova, tsNova);
                assinarTudo(nova, cenario, tsNova);
                Espera.dormir(1500);
                TelaProjeto projetoNovo = abrirProjeto(nova, cenario, tsNova);
                Visual.naEdt(() -> velha.navegacao().voltarDosAjustes()); // volta à página do projeto que já estava aberta, a mesma de antes da troca
                List<String> telas = new ArrayList<>();
                for (TelaProjeto.Aba aba : TelaProjeto.Aba.values()) {
                    if (aba != TelaProjeto.Aba.INTERNET) { // o botão Conectar da Internet espera o estado da Cloudflare chegar (assíncrono): diferença de dados, não de tema
                        telas.add("projeto " + aba);
                    }
                }
                for (TelaServidor.Aba aba : TelaServidor.Aba.values()) {
                    telas.add("aba " + aba);
                }
                telas.add("ajustes");
                for (String t : telas) {
                    if (t.startsWith("projeto ")) {
                        TelaProjeto.Aba aba = TelaProjeto.Aba.valueOf(t.substring("projeto ".length()));
                        for (TelaProjeto tp : List.of(projetoVelho, projetoNovo)) {
                            Visual.naEdt(() -> {
                                tp.mostrarAba(aba);
                                if (tp.pagina(aba) != null) {
                                    tp.pagina(aba).assinar();
                                }
                            });
                        }
                        Espera.dormir(500);
                    } else {
                        Visual.naEdt(() -> mostrar(velha, cenario, t));
                        Visual.naEdt(() -> mostrar(nova, cenario, t));
                    }
                    // As duas janelas têm o que anda sozinho (o arco que gira, a hora da leitura, dados que chegam por rede): num runner lento uma das duas ainda está uns quadros atrás. Um defeito de
                    // verdade (cor guardada de antes da troca, contorno que volta) dá a MESMA diferença em toda tentativa; por isso vale a primeira em que as duas coincidem, até 20 s.
                    BufferedImage a;
                    BufferedImage b;
                    double diferenca;
                    long limite = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    do {
                        a = Visual.renderizar(velha, 1440, 860);
                        b = Visual.renderizar(nova, 1440, 860);
                        diferenca = porMil(a, b);
                        if (diferenca > TOLERANCIA_POR_MIL) {
                            Espera.dormir(250);
                        }
                    } while (diferenca > TOLERANCIA_POR_MIL && System.nanoTime() < limite);
                    tabela.append(String.format("%s -> %s | %-18s | %.3f por mil pixels diferentes%n", de, para, t, diferenca));
                    if (diferenca > TOLERANCIA_POR_MIL) {
                        ruins.add(String.format("%s -> %s | %s | %.3f por mil", de, para, t, diferenca));
                        Visual.salvar(a, "troca-de-tema-" + de.name().toLowerCase() + "-" + t.replace(' ', '-').toLowerCase() + "-trocada");
                        Visual.salvar(b, "troca-de-tema-" + de.name().toLowerCase() + "-" + t.replace(' ', '-').toLowerCase() + "-montada");
                    }
                }
                Visual.naEdt(velha::encerrar);
                Visual.naEdt(nova::encerrar);
            }
        }
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/troca-de-tema-na-janela.txt"), tabela.toString());
        assertTrue(ruins.isEmpty(), "telas diferentes depois de trocar o tema com a janela aberta:\n" + String.join("\n", ruins));
    }

    /** Cada aba do servidor pede os dados dela (a janela de verdade o faz sozinha ao ficar visível) e espera o servidor de mentira responder. */
    private static void assinarTudo(PainelPrincipal painel, CenarioDeUi cenario, TelaServidor ts) {
        for (TelaServidor.Aba aba : TelaServidor.Aba.values()) {
            Visual.naEdt(() -> painel.navegacao().mostrarServidor(cenario.lab, aba));
            var pagina = Visual.naEdt(() -> ts.pagina(aba));
            if (pagina != null) {
                Visual.naEdt(pagina::assinar);
            }
        }
        Espera.dormir(1500);
    }

    private static TelaProjeto abrirProjeto(PainelPrincipal painel, CenarioDeUi cenario, TelaServidor ts) {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
        TelaProjeto tp = Visual.naEdt(() -> ts.projetoAberto());
        Visual.naEdt(tp::assinar);
        return tp;
    }

    private static void mostrar(PainelPrincipal p, CenarioDeUi cenario, String tela) {
        if (tela.equals("ajustes")) {
            p.mostrarAjustes(TelaAjustes.Secao.NAVEGACAO);
            return;
        }
        if (p.navegacao().emAjustes()) {
            p.navegacao().voltarDosAjustes();
        }
        p.navegacao().mostrarServidor(cenario.lab, TelaServidor.Aba.valueOf(tela.substring("aba ".length())));
    }
}
