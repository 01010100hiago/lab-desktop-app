package br.com.labdesktop.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.seguranca.ForcaDeSenha.Avaliacao;
import br.com.labdesktop.seguranca.ForcaDeSenha.Nivel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A régua da senha-mestra: o cofre guarda a chave SSH do lab (equivalente a root) atrás de UMA senha, atacável offline,
 * então a régua recusa senha de dicionário mesmo com tamanho, número no fim ou letras trocadas por símbolos.
 */
class ForcaDeSenhaTest {

    private static Avaliacao a(String senha) {
        return ForcaDeSenha.avaliar(senha.toCharArray());
    }

    private static void recusada(String senha) {
        Avaliacao r = a(senha);
        assertFalse(r.aceita(), senha + " deveria ser recusada (" + r.bits() + " bits): " + r.mensagem());
        assertEquals(Nivel.FRACA, r.nivel(), senha);
    }

    private static void aceita(String senha) {
        Avaliacao r = a(senha);
        assertTrue(r.aceita(), senha + " deveria ser aceita (" + r.bits() + " bits): " + r.mensagem());
    }

    @Test
    void oMinimoEOMesmoDoCofre() {
        assertEquals(CofreDeChaves.SENHA_MINIMA, ForcaDeSenha.MINIMO);
    }

    @Test
    void vazioECurtoSaoFracosEMostramQuantoFalta() {
        assertFalse(a("").aceita());
        assertEquals(Nivel.FRACA, a("").nivel());
        Avaliacao curta = a("Abc123!xyz"); // 10
        assertFalse(curta.aceita());
        assertEquals(Nivel.FRACA, curta.nivel());
        assertTrue(curta.mensagem().contains("faltam 2"), curta.mensagem());
        assertFalse(ForcaDeSenha.avaliar(null).aceita());
        assertFalse(a("12345678901").aceita(), "11 caracteres não bastam");
    }

    @Test
    void doze_caracteres_nao_bastam_mais_nem_quando_sao_aleatorios() {
        Avaliacao r = a("k9#Vd2!xQm@7"); // 12 caracteres sorteados: forte, mas curto demais para a regra do cofre
        assertFalse(r.aceita());
        assertTrue(r.bits() >= ForcaDeSenha.PISO_EM_BITS, "o motivo é o tamanho, não o dicionário: " + r.bits());
        assertTrue(r.mensagem().contains("16 caracteres"), r.mensagem());
        assertTrue(r.mensagem().contains("4 palavras"), r.mensagem());
        assertFalse(a("k9#Vd2!xQm@7pZ4").aceita(), "15 ainda não");
        aceita("k9#Vd2!xQm@7pZ4w");
    }

    @Test
    void repeticaoESequenciaNaoPassamMesmoComTamanho() {
        for (String ruim : new String[] {"aaaaaaaaaaaaaaaa", "abababababababab", "123456789012", "abcdefghijkl",
            "zyxwvutsrqpo", "qwertyuiopas", "111111111111", "ABCabcABCabcABC", "012345678901234567890"}) {
            Avaliacao r = a(ruim);
            assertFalse(r.aceita(), ruim + " deveria ser recusada");
            assertEquals(Nivel.FRACA, r.nivel());
            assertTrue(r.mensagem().contains("previsível"), r.mensagem());
        }
    }

    /** As seis senhas do achado: 12 caracteres, nenhuma sequência, e todas eram aceitas como "razoáveis". */
    @Test
    void senhasDeDicionarioComNumeroNoFimSaoRecusadas() {
        for (String comum : List.of("Password1234", "iloveyou1234", "qwerty123456", "12345678abcd", "Corinthians10",
            "Passw0rd!!!!!")) {
            Avaliacao r = a(comum);
            assertFalse(r.aceita(), comum + " deveria ser recusada: " + r.mensagem());
            assertEquals(Nivel.FRACA, r.nivel(), comum);
            assertTrue(r.bits() < ForcaDeSenha.PISO_EM_BITS / 2.0, comum + " vale " + r.bits() + " bits");
        }
    }

    @Test
    void oTamanhoSozinhoNaoSalvaAVariacaoDeUmaSenhaConhecida() {
        for (String comum : List.of(
            "Password12345678",            // palavra mais sequência
            "iloveyou12345678!",
            "Corinthians2024!!!!",         // nome de time, ano e repetição de símbolo
            "P@ssw0rd2024!!!!!!",          // leet, ano e repetição
            "Senha123456789012",
            "Flamengo1981!!!!!!!",
            "Brasil@2026Brasil@2026",      // palavra repetida
            "drowssap123456789!",          // "password" de trás para a frente
            "qwerty123456qwerty",
            "letmein1letmein1letmein",
            "Admin2026Admin2026",
            "dono25/12/1990!!",          // nome e data com barras
            "minha-senha-bem-longa-9",     // uma frase de quatro palavras, mas das que todo mundo escreve
            "the quick brown fox")) {
            recusada(comum);
        }
    }

    /**
     * Uma palavra rara que não está na lista (aqui, "jabuticabeira") não pode valer 13 letras ao acaso: o atacante a
     * encontra numa lista de palavras do idioma. O trecho que PARECE palavra custa como um sorteio numa lista grande.
     */
    @Test
    void palavraRaraForaDaListaQueParecePalavraNaoGanhaCreditoDeSorteio() {
        for (String rara : List.of("Jabuticabeira#99", "senhaJabuticabeira", "minhasenhajabuticabeira",
            "jabuticabeirajabuticabeira", "Jabuticabeira2026!!!")) {
            recusada(rara);
        }
        aceita("jabuticabeira goiaba cambuci pitanga"); // quatro palavras raras juntas: valem
    }

    @Test
    void trocaDeLetrasPorSimbolosNaoEscondeAPalavra() {
        for (String leet : List.of("P4$$w0rd-P4$$w0rd", "L3tM31n1234567890", "1l0v3y0u!!!!!!!!!", "C0r1nth14ns!!!!!!!",
            "Fl4m3ng0-2024-2024", "S3nh4S3nh4S3nh4S3nh4")) {
            recusada(leet);
        }
    }

    @Test
    void frasesDeQuatroPalavrasEAleatoriasLongasPassam() {
        aceita("cavalo bateria grampo correto");
        aceita("trovao-lampiao-xicara-folheto");
        aceita("batata frita com queijo derretido");
        aceita("Zr8!mQ#4vLp2Xt9w");
        aceita("xkqjvzmwpltrhdgs");
        assertEquals(Nivel.FORTE, a("cavalo bateria grampo correto").nivel());
        assertEquals(Nivel.FORTE, a("trovao-lampiao-xicara-folheto").nivel());
        assertEquals(Nivel.FORTE, a("Zr8!mQ#4vLp2Xt9w").nivel());
    }

    @Test
    void forteERazoavelSeparamPeloPisoDeBits() {
        Avaliacao media = a("amarelo azul verde rosa roxo"); // cinco palavras, mas todas da lista das mais comuns
        assertTrue(media.aceita(), media.mensagem() + " (" + media.bits() + " bits)");
        assertEquals(Nivel.RAZOAVEL, media.nivel());
        assertTrue(media.bits() >= ForcaDeSenha.PISO_EM_BITS && media.bits() < ForcaDeSenha.FORTE_EM_BITS, "" + media.bits());
        assertTrue(media.mensagem().contains("frase longa"), media.mensagem());
    }

    @Test
    void fraseDeQuatroPalavrasPrecisaDeQuatroPalavrasDiferentes() {
        recusada("tomate tomate tomate tomate");
        Avaliacao tres = a("trovao lampiao xicara"); // 21 caracteres, mas só três palavras: vale pelo tamanho
        assertTrue(tres.aceita(), tres.mensagem());
        Avaliacao curtaDeQuatro = a("rua lua sol mar"); // quatro palavras de três letras, 15 caracteres, todas comuns
        assertFalse(curtaDeQuatro.aceita());
    }

    @Test
    void mensagemDeSenhaComumFalaDeFraseEDeNumeroNoFim() {
        Avaliacao r = a("Password1234");
        assertTrue(r.mensagem().contains("comum"), r.mensagem());
        assertTrue(r.mensagem().contains("frase de 4 palavras"), r.mensagem());
        assertTrue(r.mensagem().contains("número no fim"), r.mensagem());
    }

    @Test
    void entradasEstranhasNaoQuebram() {
        char[] longa = new char[5000];
        java.util.Arrays.fill(longa, 'x');
        assertFalse(ForcaDeSenha.avaliar(longa).aceita(), "só um caractere repetido");
        StringBuilder frase = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            frase.append("palavra").append(i).append(' ');
        }
        assertNotNull(a(frase.toString()));
        aceita("пароль-кошка-дерево-ночь"); // cirílico: nada na lista, tamanho e palavras bastam
        assertNotNull(a("😀😀😀😀😀😀😀😀😀😀😀😀😀😀"));
        assertNotNull(a("            "));
        assertNotNull(a("\0\0\0\0\0\0\0\0\0\0\0\0\0"));
    }

    @Test
    void avaliarUmaSenhaDeTamanhoNormalNaoDemora() {
        ForcaDeSenha.preaquecer();
        char[] s = "Zr8!mQ#4vLp2Xt9w-cavalo bateria".toCharArray();
        long inicio = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            ForcaDeSenha.avaliar(s);
        }
        long msPorChamada = (System.nanoTime() - inicio) / 1_000_000 / 50;
        assertTrue(msPorChamada < 50, "a régua roda a cada tecla, na EDT: " + msPorChamada + " ms por chamada");
    }

    @Test
    void naoCriaStringDaSenhaNemAlteraOArray() {
        char[] senha = "cavalo bateria grampo".toCharArray();
        char[] copia = senha.clone();
        ForcaDeSenha.avaliar(senha);
        assertEquals(new String(copia), new String(senha));
    }
}
