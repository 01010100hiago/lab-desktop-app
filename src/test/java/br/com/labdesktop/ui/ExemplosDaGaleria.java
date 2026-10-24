package br.com.labdesktop.ui;

import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

/**
 * Deixa os textos da imagem da galeria com cara de exemplo: os testes conectam num SSH embutido, em 127.0.0.1 e numa porta qualquer, e o cartão do servidor mostra isso;
 * na imagem do README o endereço vira um de documentação (192.0.2.x, porta 22 ou 2222), a porta do painel vira 7000 e cada impressão digital {@code SHA256:...} vira uma
 * impressão SINTÉTICA diferente (o SHA-256 de um texto de fixture, em base64: parece uma impressão de verdade, mas é reproduzível e não é de servidor nenhum). Só troca o
 * texto na tela, depois que os testes conferiram os valores de verdade.
 */
public final class ExemplosDaGaleria {

    private static final Pattern ENDERECO = Pattern.compile("127\\.0\\.0\\.1:\\d+");
    private static final Pattern PORTA_DO_PAINEL = Pattern.compile("painel na porta \\d+");
    private static final Pattern IMPRESSAO = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    /** Quantos endereços e impressões já "foram usados" antes desta tela (para um cartão que na lista de servidores não é o primeiro ter os mesmos exemplos). */
    private static volatile int[] deslocamento = {0, 0};

    private ExemplosDaGaleria() { }

    /** Faz a próxima tela começar a numerar os endereços e as impressões de exemplo depois dos {@code enderecos} e {@code impressoes} primeiros; {@code (0, 0)} volta ao normal. */
    public static void definirDeslocamento(int enderecos, int impressoes) {
        deslocamento = new int[] {enderecos, impressoes};
    }

    /** Troca os textos de todos os rótulos e áreas de texto sob {@code raiz} (na EDT). */
    public static void aplicar(Container raiz) {
        int[] contadores = deslocamento.clone(); // endereços e impressões já trocados, na ordem em que aparecem
        Visual.naEdt(() -> percorrer(raiz, contadores));
    }

    private static void percorrer(Component c, int[] contadores) {
        if (c instanceof JLabel l && l.getText() != null) {
            String novo = trocar(l.getText(), contadores);
            if (!novo.equals(l.getText())) {
                l.setText(novo);
            }
        } else if (c instanceof JTextComponent t && t.getText() != null) {
            String novo = trocar(t.getText(), contadores);
            if (!novo.equals(t.getText())) {
                t.setText(novo);
            }
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                percorrer(f, contadores);
            }
        }
    }

    static String trocar(String texto, int[] contadores) {
        Matcher m = ENDERECO.matcher(texto);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int i = contadores[0]++;
            m.appendReplacement(sb, Matcher.quoteReplacement("192.0.2." + (10 + i) + ":" + (i % 2 == 0 ? "22" : "2222")));
        }
        m.appendTail(sb);
        String r = PORTA_DO_PAINEL.matcher(sb.toString()).replaceAll("painel na porta 7000").replace("mac-vps-1", "mac-de-teste");
        m = IMPRESSAO.matcher(r);
        sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(impressaoSintetica(++contadores[1])));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code SHA256:} mais o SHA-256 de "fixture-sintetica-galeria-N" em base64 sem padding . */
    static String impressaoSintetica(int n) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("fixture-sintetica-galeria-" + n).getBytes(StandardCharsets.UTF_8));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
