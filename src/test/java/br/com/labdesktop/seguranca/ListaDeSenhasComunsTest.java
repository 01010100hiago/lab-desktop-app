package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A lista de senhas comuns embarcada e a busca por trecho de {@code char[]} (sem {@code String} da senha). */
class ListaDeSenhasComunsTest {

    private static ListaDeSenhasComuns de(String texto) throws Exception {
        return ListaDeSenhasComuns.ler(new ByteArrayInputStream(texto.getBytes(StandardCharsets.UTF_8)));
    }

    private static int plano(ListaDeSenhasComuns l, String trecho, int de, int ate) {
        return l.rankPlano(trecho.toCharArray(), de, ate);
    }

    @Test
    void aListaEmbarcadaTemDezenasDeMilharesDeEntradasEAsMaisUsadasNoTopo() {
        ListaDeSenhasComuns l = ListaDeSenhasComuns.padrao();
        assertTrue(l.entradas() >= 30_000, "entradas: " + l.entradas());
        assertTrue(l.maiorPalavra() > 8 && l.maiorPalavra() <= ListaDeSenhasComuns.MAIOR_PALAVRA_ACEITA);
        assertEquals(2, plano(l, "password", 0, 8));
        assertEquals(1, plano(l, "123456", 0, 6));
        assertTrue(plano(l, "iloveyou", 0, 8) <= 100);
        assertTrue(plano(l, "qwerty", 0, 6) <= 10);
        assertTrue(plano(l, "corinthians", 0, 11) > 0, "nome de time está na lista");
        assertTrue(plano(l, "flamengo", 0, 8) <= 20, "complemento em português do Brasil");
        assertTrue(plano(l, "senha", 0, 5) <= 5);
        assertEquals(0, plano(l, "xkqjvzmwpltrhdgs", 0, 16), "sequência sorteada não está na lista");
    }

    @Test
    void buscaPorTrechoDoArrayEDeixaOResto() throws Exception {
        ListaDeSenhasComuns l = de("# comentário\nmanga\t10\nverde\t20\n");
        char[] senha = "xxmangaverdeyy".toCharArray();
        assertEquals(10, l.rankPlano(senha, 2, 7));
        assertEquals(20, l.rankPlano(senha, 7, 12));
        assertEquals(0, l.rankPlano(senha, 2, 12), "o trecho precisa ser a palavra inteira");
        assertEquals(0, l.rankPlano(senha, 3, 7));
        assertEquals(0, l.rankPlano(senha, 5, 5), "trecho vazio");
    }

    @Test
    void palavraRepetidaFicaComOMenorRankEAsDobrasSeEncontram() throws Exception {
        ListaDeSenhasComuns l = de("password\t40\nPassword\t7\nPASSWORD\t9\nPassw0rd\t300\n");
        assertEquals(7, plano(l, "password", 0, 8));
        // a dobra leet junta "password" e "passw0rd" no mesmo trecho e guarda o menor rank
        char[] leet = new char[8];
        String senha = "p@ssw0rd";
        for (int i = 0; i < 8; i++) {
            leet[i] = Dobra.leet(Dobra.plano(senha.charAt(i)));
        }
        assertEquals("password", new String(leet));
        assertEquals(7, l.rankLeet(leet, 0, 8));
    }

    @Test
    void linhasInvalidasSaoIgnoradasSemQuebrarALeitura() throws Exception {
        ListaDeSenhasComuns l = de("sem-tab\nruim\tabc\nzero\t0\nneg\t-3\n\t5\nbom\t4\n");
        assertEquals(1, l.entradas());
        assertEquals(4, plano(l, "bom", 0, 3));
    }

    @Test
    void acentosCaemNaDobraPlana() throws Exception {
        ListaDeSenhasComuns l = de("coração\t3\n");
        char[] digitada = "CORAÇÃO".toCharArray();
        char[] plana = new char[digitada.length];
        for (int i = 0; i < plana.length; i++) {
            plana[i] = Dobra.plano(digitada[i]);
        }
        assertEquals("coracao", new String(plana));
        assertEquals(3, l.rankPlano(plana, 0, plana.length));
    }
}
