package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextoSeguroTest {

    /** O teste do Swing: BasicHTML trata como HTML o texto que começa com {@code <html>}. */
    private static boolean swingTrataComoHtml(String s) {
        return s.length() >= 6 && s.charAt(0) == '<' && s.charAt(5) == '>'
            && s.substring(1, 5).equalsIgnoreCase("html");
    }

    @Test
    void htmlNaDescricaoDeixaDeSerHtmlParaOSwing() {
        for (String perigoso : new String[] {"<html><img src=http://x/y.png>", "<HTML>oi", "  <html>oi", "<html lang=pt>"}) {
            String limpo = TextoSeguro.limpar(perigoso);
            assertFalse(swingTrataComoHtml(limpo), limpo);
            assertFalse(swingTrataComoHtml(TextoSeguro.limparLinha(perigoso)), perigoso);
            // O conteúdo continua legível como texto.
            assertTrue(limpo.contains(perigoso.strip().substring(0, 5)), limpo);
        }
    }

    @Test
    void htmlDepoisDeControleTambemENeutralizado() {
        // O controle sai primeiro e o que sobra começaria com <html>: precisa ser pego depois da limpeza.
        String limpo = TextoSeguro.limpar("\u0000‮<html>oi");
        assertFalse(swingTrataComoHtml(limpo), limpo);
    }

    @Test
    void textoComumNaoMuda() {
        assertEquals("Projeto de teste: ação, coração, é!", TextoSeguro.limpar("Projeto de teste: ação, coração, é!"));
        assertEquals("a < b e c > d", TextoSeguro.limpar("a < b e c > d"));
        assertEquals("linha1\nlinha2\tcoluna", TextoSeguro.limpar("linha1\nlinha2\tcoluna"));
    }

    @Test
    void removeControlesC0C1EBidi() {
        assertEquals("abcd", TextoSeguro.limpar("a\u0000b\u001Bc\u0085d\u007F"));
        // U+202E inverte o texto à direita (esconde extensão); U+2066..2069 são os isolados bidi.
        assertEquals("fotogpj.exe", TextoSeguro.limpar("foto‮gpj.exe"));
        assertEquals("ab", TextoSeguro.limpar("a⁦⁧⁨⁩‎‏؜b"));
    }

    @Test
    void retornoDeCarroViraQuebraDeLinha() {
        assertEquals("a\nb\nc", TextoSeguro.limpar("a\r\nb\rc"));
        assertEquals("a b c", TextoSeguro.limparLinha("a\r\nb\rc"));
    }

    @Test
    void limparLinhaTiraQuebrasETabsEApara() {
        assertEquals("a b c", TextoSeguro.limparLinha("  a\nb\tc  "));
        assertEquals("", TextoSeguro.limparLinha(null));
        assertEquals("", TextoSeguro.limpar(null));
    }

    @Test
    void escaparHtml() {
        assertEquals("&lt;b&gt;&amp;&quot;&#39;", TextoSeguro.escaparHtml("<b>&\"'"));
    }

    @Test
    void truncarNaoCortaParSubstituto() {
        assertEquals("abc", TextoSeguro.truncar("abc", 3));
        assertEquals("ab…", TextoSeguro.truncar("abcdef", 3));
        String comEmoji = "a😀b😀c"; // a😀b😀c
        String cortado = TextoSeguro.truncar(comEmoji, 3);
        assertFalse(Character.isHighSurrogate(cortado.charAt(cortado.length() - 2)), cortado);
    }
}
