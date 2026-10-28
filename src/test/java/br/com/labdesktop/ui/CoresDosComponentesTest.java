package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As cores que o usuário VÊ nos botões e nos controles, medidas nos pixels ({@link AuditoriaDeCores}): o conjunto de todas as variantes de botão do app,
 * como botão comum e como botão do Enter (padrão do diálogo), sobre cada superfície do tema, nos dois temas, e também trocando o tema com a árvore JÁ
 * montada (o caso real: o usuário troca claro e escuro com a janela aberta).
 */
@ExtendWith(VerificaEdt.class)
class CoresDosComponentesTest {

    private static final Tema[] TEMAS = {Tema.CLARO, Tema.ESCURO};

    /** Cada fábrica de botão do app (e os controles padrão do Swing que as telas usam). */
    private static Map<String, Supplier<JComponent>> fabricas() {
        Map<String, Supplier<JComponent>> m = new LinkedHashMap<>();
        m.put("primario", () -> Botoes.primario("Confirmar", null));
        m.put("primario com icone", () -> Botoes.primario("Salvar", "atualizar"));
        m.put("secundario", () -> Botoes.secundario("Cancelar", null));
        m.put("perigo", () -> Botoes.perigo("Apagar", null));
        m.put("fantasma", () -> Botoes.fantasma("Voltar", null));
        m.put("link", () -> Botoes.link("Ver detalhes"));
        m.put("mini", () -> Botoes.mini("Abrir", null));
        m.put("miniBaixo", () -> Botoes.miniBaixo("Abrir", null));
        m.put("miniPrimario", () -> Botoes.miniPrimario("Criar", null));
        m.put("miniPerigo", () -> Botoes.miniPerigo("Remover", null));
        m.put("icone", () -> Botoes.icone("atualizar", "Atualizar"));
        m.put("iconePrimario", () -> Botoes.iconePrimario("atualizar", "Atualizar"));
        m.put("iconeFantasma", () -> Botoes.iconeFantasma("atualizar", "Atualizar"));
        m.put("filtro", () -> Botoes.filtro("No ar", null));
        m.put("caixa", () -> new JCheckBox("Mostrar ocultos", true));
        m.put("caixa desmarcada", () -> new JCheckBox("Mostrar ocultos", false));
        m.put("opcao", () -> new JRadioButton("Copiar", true));
        m.put("opcao desmarcada", () -> new JRadioButton("Copiar", false));
        m.put("campo", () -> new JTextField("texto digitado", 12));
        m.put("lista suspensa", () -> new JComboBox<>(new String[] {"Primeira", "Segunda"}));
        return m;
    }

    private static final String[] SUPERFICIES = {Tokens.BG, Tokens.SUPERFICIE, Tokens.SUPERFICIE2, Tokens.SUPERFICIE3};

    private record Montagem(JRootPane raiz, JComponent controle) { }

    /** O controle sobre a superfície; {@code padrao}: ele é o botão do Enter de um diálogo (o FlatLaf pinta o botão padrão do seu jeito). */
    private static Montagem montar(Supplier<JComponent> fabrica, String superficie, boolean padrao) {
        JComponent controle = fabrica.get();
        Painel p = new Painel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 16, 16));
        p.fundo(superficie);
        p.add(controle);
        JRootPane raiz = new JRootPane();
        raiz.setContentPane(p);
        if (padrao && controle instanceof JButton b) {
            raiz.setDefaultButton(b);
        }
        return new Montagem(raiz, controle);
    }

    private static List<String> relatar(String contexto, List<Medida> medidas) {
        List<String> ruins = new ArrayList<>();
        for (Medida m : medidas) {
            if (m.falhou()) {
                ruins.add(contexto + " | " + m);
            }
        }
        return ruins;
    }

    @Test
    void todaVarianteDeBotaoEnxergaSeNosDoisTemasEmTodoEstado() throws IOException {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        for (Tema tema : TEMAS) {
            Visual.tema(tema);
            for (var f : fabricas().entrySet()) {
                for (String s : SUPERFICIES) {
                    for (boolean padrao : new boolean[] {false, true}) {
                        Montagem mt = Visual.naEdt(() -> montar(f.getValue(), s, padrao));
                        List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(mt.raiz(), 300, 90, true));
                        String ctx = tema + " | " + f.getKey() + " | " + s + (padrao ? " | botão do Enter" : "");
                        medidas.forEach(m -> todas.add(ctx + " | " + m));
                        ruins.addAll(relatar(ctx, medidas));
                    }
                }
            }
        }
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/auditoria-de-cores-matriz.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-matriz-ruins.txt"), ruins);
        assertTrue(todas.size() > 800, "a matriz mediu " + todas.size() + " coisas: menos que o esperado, alguma fábrica sumiu?");
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }

    /** A galeria para o olho: cada variante nos estados normal, mouse em cima, apertado, desabilitado e botão do Enter, sobre a superfície, nos dois temas. */
    @Test
    void galeriaDeBotoesPorEstadoNosDoisTemas() {
        String[] variantes = {"primario", "secundario", "perigo", "fantasma", "link", "mini", "miniPrimario", "miniPerigo", "icone", "iconePrimario", "filtro"};
        String[] estados = {"normal", "mouse em cima", "apertado", "desabilitado", "botão do Enter"};
        for (Tema tema : TEMAS) {
            Visual.tema(tema);
            Painel grade = Visual.naEdt(() -> {
                Painel p = new Painel(new java.awt.GridLayout(variantes.length + 1, estados.length + 1, 14, 10));
                p.fundo(Tokens.SUPERFICIE);
                p.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
                p.add(new JLabel(""));
                for (String e : estados) {
                    p.add(new JLabel(e));
                }
                for (String v : variantes) {
                    p.add(new JLabel(v));
                    for (String e : estados) {
                        JComponent c = fabricas().get(v).get();
                        if (c instanceof AbstractButton b) {
                            switch (e) {
                                case "mouse em cima" -> b.getModel().setRollover(true);
                                case "apertado" -> {
                                    b.getModel().setRollover(true);
                                    b.getModel().setArmed(true);
                                    b.getModel().setPressed(true);
                                }
                                case "desabilitado" -> b.setEnabled(false);
                                default -> { }
                            }
                        }
                        if (e.equals("botão do Enter")) {
                            // o Enter só vale dentro de uma janela: um painel de janela por botão, para o FlatLaf pintá-lo como padrão
                            JRootPane raiz = new JRootPane();
                            Painel dentro = new Painel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
                            dentro.fundo(Tokens.SUPERFICIE);
                            dentro.add(c);
                            raiz.setContentPane(dentro);
                            if (c instanceof JButton jb) {
                                raiz.setDefaultButton(jb);
                            }
                            p.add(raiz);
                        } else {
                            Painel dentro = new Painel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
                            dentro.fundo(Tokens.SUPERFICIE);
                            dentro.add(c);
                            p.add(dentro);
                        }
                    }
                }
                return p;
            });
            Visual.salvar(Visual.renderizar(grade, 1000, 560), "botoes-por-estado-" + tema.name().toLowerCase());
        }
    }

    @Test
    void trocarOTemaComOBotaoJaMontadoDaAsMesmasCoresDeUmBotaoNovo() throws IOException {
        List<String> diferencas = new ArrayList<>();
        int comparadas = 0;
        for (Tema de : TEMAS) {
            Tema para = de == Tema.CLARO ? Tema.ESCURO : Tema.CLARO;
            for (var f : fabricas().entrySet()) {
                for (boolean padrao : new boolean[] {false, true}) {
                    Visual.tema(de);
                    Montagem velha = Visual.naEdt(() -> montar(f.getValue(), Tokens.SUPERFICIE, padrao));
                    Visual.naEdt(() -> AuditoriaDeCores.auditar(velha.raiz(), 300, 90, true)); // pinta uma vez no tema de antes
                    Visual.tema(para);
                    Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(velha.raiz()));
                    List<Medida> trocada = Visual.naEdt(() -> AuditoriaDeCores.auditar(velha.raiz(), 300, 90, true));
                    Montagem nova = Visual.naEdt(() -> montar(f.getValue(), Tokens.SUPERFICIE, padrao));
                    List<Medida> fresca = Visual.naEdt(() -> AuditoriaDeCores.auditar(nova.raiz(), 300, 90, true));
                    for (int i = 0; i < Math.min(trocada.size(), fresca.size()); i++) {
                        comparadas++;
                        Medida a = trocada.get(i);
                        Medida b = fresca.get(i);
                        if (!a.fundo().equals(b.fundo()) || !a.tinta().equals(b.tinta())) {
                            diferencas.add(de + "->" + para + " | " + f.getKey() + (padrao ? " | PADRAO" : "") + " | " + a.estado() + " | trocada fundo "
                                + AuditoriaDeCores.hex(a.fundo()) + " tinta " + AuditoriaDeCores.hex(a.tinta()) + " | fresca fundo "
                                + AuditoriaDeCores.hex(b.fundo()) + " tinta " + AuditoriaDeCores.hex(b.tinta()));
                        }
                    }
                }
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-troca.txt"), diferencas);
        assertTrue(comparadas > 250, "poucas comparações: " + comparadas);
        assertTrue(diferencas.isEmpty(), diferencas.size() + " medidas diferentes de uma montagem nova (cor guardada de antes da troca):\n"
            + String.join("\n", diferencas.stream().limit(15).toList()));
    }
}
