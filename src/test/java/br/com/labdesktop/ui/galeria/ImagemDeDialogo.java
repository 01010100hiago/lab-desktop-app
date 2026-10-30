package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.ExemplosDaGaleria;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.dialogos.Dialogo;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Apoio das imagens de galeria dos DIÁLOGOS: renderiza o diálogo de verdade nos dois temas, com a moldura de diálogo do macOS ({@link MolduraDeJanela.Tipo#DIALOGO}), e
 * antes de gravar confere que a tela é a certa (os textos esperados à vista, nada de "Carregando"), que nada transborda e que nenhum dado do computador de teste
 * (caminhos do usuário, nome do usuário do Mac, loopback com porta) aparece na imagem.
 */
final class ImagemDeDialogo {

    /** Altura da "tela" do teste: grande o bastante para o diálogo aparecer inteiro, sem a rolagem de um notebook. */
    private static final int ALTURA_DA_TELA = 1500;
    private static final int LARGURA_MINIMA = 560;
    private static final Pattern LOOPBACK_COM_PORTA = Pattern.compile("127\\.0\\.0\\.1:\\d+");

    private ImagemDeDialogo() { }

    /** Grava {@code <nome>-claro.png} e {@code <nome>-escuro.png} em build/galeria/, depois de conferir que {@code esperados} estão à vista. */
    static void gerar(Dialogo d, String nome, String... esperados) {
        gerar(d, nome, LARGURA_MINIMA, esperados);
    }

    static void gerar(Dialogo d, String nome, int larguraMinima, String... esperados) {
        gerar(d, nome, larguraMinima, true, esperados);
    }

    /**
     * Para a tela que fala do loopback de propósito (o agente "escuta só em 127.0.0.1:7000"): não há endereço de teste a trocar, e trocar o loopback por um 192.0.2.x
     * tornaria a frase falsa. O resto das conferências vale igual.
     */
    static void gerarComLoopbackDoTexto(Dialogo d, String nome, String... esperados) {
        gerar(d, nome, LARGURA_MINIMA, false, esperados);
    }

    private static void gerar(Dialogo d, String nome, int larguraMinima, boolean trocarEnderecos, String... esperados) {
        Dialogo.definirAlturaDaTelaParaTeste(ALTURA_DA_TELA);
        try {
            Visual.tema(Tema.CLARO);
            Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(d.raiz()));
            if (trocarEnderecos) {
                ExemplosDaGaleria.aplicar(d.raiz());
            }
            conferir(d.raiz(), nome, trocarEnderecos, esperados);
            salvar(d, nome + "-claro", larguraMinima, false);
            Visual.tema(Tema.ESCURO);
            Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(d.raiz()));
            if (trocarEnderecos) {
                ExemplosDaGaleria.aplicar(d.raiz());
            }
            conferir(d.raiz(), nome + " (escuro)", trocarEnderecos, esperados);
            salvar(d, nome + "-escuro", larguraMinima, true);
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
            Visual.tema(Tema.CLARO);
            Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(d.raiz()));
        }
    }

    /** Uma janela inteira (a do app) nos dois temas, com a moldura de janela. */
    static void gerarJanela(JComponent painel, String nome, int largura, int altura, String... esperados) {
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(painel));
        ExemplosDaGaleria.aplicar(painel);
        conferir(painel, nome, esperados);
        Visual.galeriaNosDoisTemas(painel, largura, altura, nome, "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
    }

    private static void salvar(Dialogo d, String arquivo, int larguraMinima, boolean escuro) {
        JComponent raiz = d.raiz();
        int largura = Math.max(larguraMinima, Visual.naEdt(() -> raiz.getPreferredSize().width)); // a largura em que a janela abriria (o pack)
        int altura = Visual.alturaPreferida(raiz, largura);
        List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(raiz));
        assertTrue(achados.isEmpty(), arquivo + ": " + achados);
        Visual.salvarNaGaleria(Visual.renderizar(raiz, largura, altura), arquivo, d.titulo(), escuro, MolduraDeJanela.Tipo.DIALOGO);
    }

    /** Os textos esperados à vista e nenhum dado do computador de teste. */
    static void conferir(Component raiz, String nome, String... esperados) {
        conferir(raiz, nome, true, esperados);
    }

    private static void conferir(Component raiz, String nome, boolean proibirLoopbackComPorta, String... esperados) {
        String tudo = textos(raiz);
        for (String e : esperados) {
            assertTrue(tudo.contains(e), nome + ": faltou \"" + e + "\" na tela:\n" + tudo);
        }
        assertFalse(tudo.contains("Carregando"), nome + ": ainda carregando:\n" + tudo);
        assertFalse(tudo.contains("/Users/") || tudo.contains("/var/folders") || tudo.contains("/private/tmp") || tudo.contains("/tmp/"), nome + ": caminho do computador de teste:\n" + tudo);
        String usuarioDoMac = ComputadorDeTeste.usuario();
        assertFalse(tudo.contains(usuarioDoMac), nome + ": o nome do usuário do computador de teste aparece:\n" + tudo);
        assertFalse(proibirLoopbackComPorta && LOOPBACK_COM_PORTA.matcher(tudo).find(), nome + ": endereço de loopback com porta:\n" + tudo);
    }

    /** O texto do primeiro campo de texto com este {@code name} (ou {@code null}). */
    static String textoDoCampoComNome(Component raiz, String nome) {
        if (nome.equals(raiz.getName()) && raiz instanceof JTextComponent t) {
            return t.getText();
        }
        if (raiz instanceof Container k) {
            for (Component f : k.getComponents()) {
                String r = textoDoCampoComNome(f, nome);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Todo o texto que o usuário VÊ (o escondido não conta; o campo de senha não mostra o texto). */
    static String textos(Component raiz) {
        StringBuilder sb = new StringBuilder();
        Visual.naEdt(() -> coletar(raiz, sb));
        return sb.toString();
    }

    private static void coletar(Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof JPasswordField) {
            sb.append("••••\n");
        } else if (c instanceof JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        } else if (c instanceof Selo s) {
            sb.append(s.texto()).append('\n');
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletar(f, sb);
            }
        }
    }
}
