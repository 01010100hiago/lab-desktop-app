package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Os pedaços do estimador de bits, um a um (a régua inteira está em {@link ForcaDeSenhaTest}). */
class EstimadorDeSenhaTest {

    private static double bits(String senha) {
        try (EstimadorDeSenha e = new EstimadorDeSenha(senha.toCharArray(), ListaDeSenhasComuns.padrao())) {
            return e.estimar().bits();
        }
    }

    private static int palavras(String senha) {
        try (EstimadorDeSenha e = new EstimadorDeSenha(senha.toCharArray(), ListaDeSenhasComuns.padrao())) {
            return e.estimar().palavras();
        }
    }

    private static void entre(double minimo, double maximo, double valor, String o_que) {
        assertTrue(valor >= minimo && valor <= maximo, o_que + ": " + valor + " bits, esperado entre " + minimo + " e " + maximo);
    }

    @Test
    void palavraDaListaCustaSoOSeuRank() {
        entre(0.9, 1.1, bits("password"), "password é a 2ª senha mais comum: 1 bit");
        entre(1.9, 2.1, bits("Password"), "a inicial maiúscula dobra os palpites");
        entre(1.9, 2.1, bits("PASSWORD"), "tudo maiúsculo também só dobra");
        assertTrue(bits("pAsSwOrD") > 5, "caixa misturada tem mais variações: " + bits("pAsSwOrD"));
    }

    @Test
    void letrasTrocadasPorSimbolosCustamUmPoucoMasContinuamBaratas() {
        double leet = bits("p@ssw0rd");
        assertTrue(leet > bits("password"), "a troca tem um custo: " + leet);
        assertTrue(leet < 12, "mas é barata: " + leet);
        assertTrue(bits("Passw0rd!!!!!") < 20, "" + bits("Passw0rd!!!!!"));
    }

    @Test
    void palavraDeTrasParaFrenteTambemEBarata() {
        entre(1.9, 6, bits("drowssap"), "reversa: rank 2 vezes 2");
    }

    @Test
    void numeroOuAnoNoFimSoSomaOPedaco() {
        double base = bits("iloveyou");
        double comAno = bits("iloveyou1990");
        entre(base + 2, base + 10, comAno, "ano no fim");
        double comSequencia = bits("iloveyou123456");
        entre(base, base + 4, comSequencia, "123456 é a senha mais comum do mundo: só a ordem soma");
    }

    @Test
    void sequenciasTecladoERepeticaoSaoBaratos() {
        entre(2, 7, bits("abcdef"), "sequência de letras");
        entre(2, 7, bits("987654"), "sequência decrescente");
        entre(2, 9, bits("asdfgh"), "trecho de teclado");
        entre(4, 14, bits("!!!!!!!!"), "símbolo repetido");
        entre(4, 20, bits("abcabcabc"), "bloco repetido três vezes");
    }

    @Test
    void anosEDatasSaoBaratos() {
        entre(5, 12, bits("2024"), "ano");
        entre(10, 17, bits("25121990"), "ddmmaaaa");
        entre(10, 17, bits("25/12/1990"), "dd/mm/aaaa");
        entre(10, 17, bits("251290"), "ddmmaa");
        assertTrue(bits("73920481") > 20, "oito dígitos que não são data valem mais: " + bits("73920481"));
    }

    @Test
    void sorteioPuroCustaOAlfabetoDeCadaCaractere() {
        // 16 letras minúsculas sem vogais suficientes: 16 x log2(26) = 75,2
        entre(70, 76, bits("xkqjvzmwpltrhdgs"), "letras ao acaso");
        // 16 caracteres de um alfabeto de 94: bem mais
        assertTrue(bits("Zr8!mQ#4vLp2Xt9w") > 70, "" + bits("Zr8!mQ#4vLp2Xt9w"));
    }

    @Test
    void palavraRaraQueParecePalavraNaoGanhaCreditoDeSorteio() {
        double rara = bits("jabuticabeira");
        entre(18, 30, rara, "uma palavra desconhecida custa como uma palavra de uma lista grande");
        assertTrue(rara < 13 * 4.7 - 20, "bem menos que 13 letras ao acaso (" + 13 * 4.7 + "): " + rara);
        assertTrue(bits("Jabuticabeira#99") < 40, "" + bits("Jabuticabeira#99"));
    }

    @Test
    void separadorEntrePalavrasCustaPoucoEAOrdemSoma() {
        double junto = bits("cavalo") + bits("verde");
        double frase = bits("cavalo verde");
        entre(junto, junto + 4, frase, "uma palavra, um espaço e outra palavra");
    }

    @Test
    void contaPalavrasDistintasDeTresOuMaisLetras() {
        assertEquals(4, palavras("cavalo bateria grampo correto"));
        assertEquals(4, palavras("cavalo-bateria_grampo.correto"));
        assertEquals(1, palavras("tomate tomate Tomate TOMATE"), "repetida não conta, com ou sem maiúscula");
        assertEquals(2, palavras("ab cd ef gh joao maria"), "palavras de uma ou duas letras não contam");
        assertEquals(3, palavras("coração ação árvore"), "acentos não atrapalham");
        assertEquals(0, palavras("1234 5678 !!!!"));
    }

    @Test
    void limiteDeTamanhoNaoQuebraNemDemora() {
        String longa = "palavra-comum-".repeat(40) + "fim";
        long inicio = System.nanoTime();
        double b = bits(longa);
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(b > 0);
        assertTrue(ms < 2_000, "demorou " + ms + " ms");
    }

    @Test
    void fecharZeraAsCopiasDobradasSemTocarNaSenha() {
        char[] senha = "Corinthians10".toCharArray();
        char[] original = senha.clone();
        EstimadorDeSenha e = new EstimadorDeSenha(senha, ListaDeSenhasComuns.padrao());
        e.estimar();
        e.close();
        assertEquals(new String(original), new String(senha), "a senha do chamador não é do estimador");
    }
}
