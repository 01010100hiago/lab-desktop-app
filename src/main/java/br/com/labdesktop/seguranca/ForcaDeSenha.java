package br.com.labdesktop.seguranca;

import java.util.HashSet;
import java.util.Set;

/**
 * A régua da senha-mestra (ARQUITETURA 5.4). A senha-mestra é a ÚNICA proteção do cofre (chave SSH do lab, que dá
 * acesso equivalente a root, e os tokens), e o arquivo do cofre pode sair do Mac (backup, iCloud, disco roubado): quem o
 * tiver ataca a senha OFFLINE contra o Argon2id, cerca de 1 s por palpite. Por isso a régua não aceita só "12 caracteres
 * que não são sequência". Uma senha vale quando passa em tudo:
 * <ol>
 *   <li>no mínimo {@value #MINIMO} caracteres (o piso do cofre, {@code CofreDeChaves.SENHA_MINIMA});</li>
 *   <li>nada de caractere repetido, de poucos caracteres diferentes ou de sequência inteira (1234..., abcd...);</li>
 *   <li>no mínimo {@value #PISO_EM_BITS} bits no {@link EstimadorDeSenha}: senha, palavra, nome ou data da lista de
 *       senhas comuns (com inicial maiúscula, letras trocadas por símbolos, número ou ano no fim, repetição) NÃO passa,
 *       mesmo com tamanho; {@code Password1234}, {@code Corinthians10} e {@code Passw0rd!!!!!} ficam bem abaixo;</li>
 *   <li>e ({@value #MINIMO_SEM_FRASE} caracteres ou mais) OU (uma frase de {@value #PALAVRAS_DA_FRASE} palavras
 *       diferentes): é a regra simples de explicar, que cobre o que a estimativa não sabe (palavra rara fora das listas).</li>
 * </ol>
 * A tela de criação mostra o nível com TEXTO (nunca só cor) e só libera o botão quando a senha é {@code aceita}.
 *
 * <p>Trabalha só sobre o {@code char[]} do {@code JPasswordField}: a senha nunca vira {@code String} (as cópias dobradas
 * que o estimador faz são zeradas ao fim). Como não há recuperação, o texto ajuda o usuário a escolher uma frase longa com
 * espaços, que é a mais forte e a mais fácil de lembrar.
 */
public final class ForcaDeSenha {

    /** Tamanho mínimo, o mesmo do cofre. */
    public static final int MINIMO = 12;
    /** Quantos caracteres bastam sem ser uma frase de palavras. */
    public static final int MINIMO_SEM_FRASE = 16;
    /** Quantas palavras diferentes (3 letras ou mais) fazem uma frase, dispensando o mínimo de {@value #MINIMO_SEM_FRASE}. */
    public static final int PALAVRAS_DA_FRASE = 4;
    /**
     * Abaixo disto, em bits estimados, a senha é recusada. 2^48 palpites, mesmo a 10 mil por segundo (muito mais do que o
     * Argon2id de 256 MiB deixa um atacante fazer), levariam cerca de 900 anos.
     */
    public static final int PISO_EM_BITS = 48;
    /** A partir disto a senha é "forte". */
    public static final int FORTE_EM_BITS = 70;

    /** O nível mostrado ao usuário. */
    public enum Nivel { FRACA, RAZOAVEL, FORTE }

    /**
     * @param nivel    fraca, razoável ou forte
     * @param aceita   pode ser usada como senha-mestra
     * @param mensagem frase em pt-BR para o usuário ("Use pelo menos 12 caracteres (faltam 4).")
     * @param bits     a estimativa de bits de um atacante com dicionário (0 quando nem chegou a estimar)
     */
    public record Avaliacao(Nivel nivel, boolean aceita, String mensagem, double bits) { }

    /** As linhas do teclado emendadas (a sequência continua de uma linha na seguinte: "qwertyuiopasdf"). */
    private static final String[] TECLADO = {"qwertyuiopasdfghjklzxcvbnm", "1234567890"};

    private ForcaDeSenha() { }

    /**
     * Carrega a lista de senhas comuns (1,3 MB do jar) sem avaliar nada. A tela de criação chama isto numa thread de
     * fundo ao abrir, para a primeira tecla digitada não pagar a leitura na EDT. Idempotente e seguro entre threads.
     */
    public static void preaquecer() {
        ListaDeSenhasComuns.padrao();
    }

    public static Avaliacao avaliar(char[] senha) {
        int n = senha == null ? 0 : senha.length;
        if (n == 0) {
            return new Avaliacao(Nivel.FRACA, false, "Digite a senha-mestra: pelo menos " + MINIMO + " caracteres.", 0);
        }
        if (n < MINIMO) {
            return new Avaliacao(Nivel.FRACA, false, "Use pelo menos " + MINIMO + " caracteres (faltam " + (MINIMO - n) + ").", 0);
        }
        if (previsivel(senha)) {
            return new Avaliacao(Nivel.FRACA, false,
                "Essa senha é previsível (repetição ou sequência). Escolha uma frase com palavras que só você junta.", 0);
        }
        EstimadorDeSenha.Resultado r;
        try (EstimadorDeSenha estimador = new EstimadorDeSenha(senha, ListaDeSenhasComuns.padrao())) {
            r = estimador.estimar();
        }
        if (r.bits() < PISO_EM_BITS) {
            return new Avaliacao(Nivel.FRACA, false,
                "Essa senha é comum ou fácil de adivinhar (palavra, nome, data ou sequência conhecidos, mesmo com número "
                    + "no fim ou letras trocadas por símbolos). Escolha uma frase de " + PALAVRAS_DA_FRASE
                    + " palavras que só você junta.", r.bits());
        }
        if (n < MINIMO_SEM_FRASE && r.palavras() < PALAVRAS_DA_FRASE) {
            return new Avaliacao(Nivel.FRACA, false,
                "Para proteger o cofre, use " + MINIMO_SEM_FRASE + " caracteres ou mais, ou uma frase de " + PALAVRAS_DA_FRASE
                    + " palavras separadas por espaço.", r.bits());
        }
        if (r.bits() >= FORTE_EM_BITS) {
            return new Avaliacao(Nivel.FORTE, true, "Forte. Lembre-se: sem esta senha não há recuperação.", r.bits());
        }
        return new Avaliacao(Nivel.RAZOAVEL, true,
            "Aceitável. Uma frase longa, com espaços, é mais forte e mais fácil de lembrar.", r.bits());
    }

    /** Um caractere só, só dois caracteres diferentes, ou uma sequência (1234..., abcd..., qwerty...) que ocupa tudo. */
    static boolean previsivel(char[] s) {
        Set<Character> distintos = new HashSet<>();
        for (char c : s) {
            distintos.add(Character.toLowerCase(c));
        }
        if (distintos.size() <= 2) {
            return true;
        }
        if (distintos.size() <= 3 && s.length >= 12 && blocoRepetido(s)) {
            return true;
        }
        return sequenciaCrescenteOuDecrescente(s) || sequenciaDeTeclado(s);
    }

    private static boolean blocoRepetido(char[] s) {
        for (int bloco = 1; bloco <= s.length / 2; bloco++) {
            boolean repete = true;
            for (int i = bloco; i < s.length && repete; i++) {
                repete = Character.toLowerCase(s[i]) == Character.toLowerCase(s[i - bloco]);
            }
            if (repete) {
                return true;
            }
        }
        return false;
    }

    /** 1234567890123, abcdefghijkl, zyxw...: dá a volta (9 depois de 0, z depois de a) para não escapar na emenda. */
    private static boolean sequenciaCrescenteOuDecrescente(char[] s) {
        boolean sobe = true;
        boolean desce = true;
        for (int i = 1; i < s.length && (sobe || desce); i++) {
            char a = Character.toLowerCase(s[i - 1]);
            char b = Character.toLowerCase(s[i]);
            int modulo;
            if (a >= '0' && a <= '9' && b >= '0' && b <= '9') {
                modulo = 10;
            } else if (a >= 'a' && a <= 'z' && b >= 'a' && b <= 'z') {
                modulo = 26;
            } else {
                return false;
            }
            int d = ((b - a) % modulo + modulo) % modulo;
            sobe &= d == 1;
            desce &= d == modulo - 1;
        }
        return sobe || desce;
    }

    private static boolean sequenciaDeTeclado(char[] s) {
        for (String linha : TECLADO) {
            String invertida = new StringBuilder(linha).reverse().toString();
            if (cabeEm(s, linha) || cabeEm(s, invertida)) {
                return true;
            }
        }
        return false;
    }

    /** Todos os caracteres da senha formam um trecho corrido de {@code linha} (sem criar String da senha). */
    private static boolean cabeEm(char[] s, String linha) {
        for (int inicio = 0; inicio + s.length <= linha.length(); inicio++) {
            boolean ok = true;
            for (int i = 0; i < s.length && ok; i++) {
                ok = linha.charAt(inicio + i) == Character.toLowerCase(s[i]);
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
