package br.com.labdesktop.seguranca;

import java.util.Arrays;

/**
 * Estima quantos BITS um atacante precisa para adivinhar uma senha, no estilo do zxcvbn (Dropbox): em vez de contar
 * tipos de caractere, procura como a senha seria montada do jeito mais barato por quem a adivinha e soma o custo de
 * cada pedaço. "Password1234" não vale as 12 letras e números que parece: é a palavra mais usada do mundo, com a
 * inicial maiúscula, mais uma sequência.
 *
 * <h2>Os pedaços que a senha pode ter (programação dinâmica, o caminho mais barato vence)</h2>
 * <ul>
 *   <li><b>Palavra da lista de senhas comuns</b> ({@link ListaDeSenhasComuns}): custa o rank dela, vezes as variações de
 *       caixa (inicial maiúscula, tudo maiúsculo, mistura), de "leet" (a troca de letras por {@code 4 @ 3 0 1 ! $ 5 7},
 *       veja {@link Dobra}) e vezes 2 se estiver escrita de trás para a frente. Cobre também a senha comum com número
 *       ou símbolo no fim: a senha é a palavra mais os pedaços seguintes.</li>
 *   <li><b>Sequência</b> ({@code abcd}, {@code 9876}, {@code 13579}) e <b>trecho de teclado</b> ({@code qwerty},
 *       {@code poiuy}), com custo pelo ponto de partida e pelo tamanho.</li>
 *   <li><b>Ano e data</b> ({@code 1990}, {@code 25121990}, {@code 25/12/90}).</li>
 *   <li><b>Repetição</b> de um bloco ({@code abcabc}, {@code !!!!}): custa o bloco mais o número de vezes.</li>
 *   <li><b>Palavra fora da lista</b>: uma corrida de 5 a 20 letras que PARECE palavra (vogais e consoantes alternadas, veja
 *       {@link #pareceUmaPalavra}) custa como um sorteio numa lista de cerca de um milhão de palavras (18 bits mais 0,7 por
 *       letra acima de 4), não como letras ao acaso: {@code Jabuticabeira#99} não ganha 60 bits por uma palavra rara.</li>
 *   <li><b>Separador</b> entre palavras (espaço, hífen, sublinhado, ponto): 2 bits, porque só há quatro.</li>
 *   <li><b>Caractere solto</b>: o que não casa com nada custa o tamanho do alfabeto dele (letra 26, dígito 10, símbolo
 *       33).</li>
 * </ul>
 * No fim soma {@code log2(m!)}, porque o atacante também não sabe em que ordem os {@code m} pedaços vêm.
 *
 * <p><b>Limite honesto</b>: é uma estimativa, não uma prova. Palavra rara que não parece palavra (sigla, junção sem vogais)
 * conta como letras ao acaso, e uma senha que o atacante tenha de verdade numa lista vazada específica vale 1 palpite
 * qualquer que seja a nota. Por isso a tela exige TAMBÉM 16 caracteres ou uma frase de 4 palavras, e recomenda a frase.
 *
 * <p>Trabalha sobre cópias dobradas da senha ({@code char[]}) e as zera em {@link #close()}; a senha em si só é lida.
 * Nunca cria {@code String} dela. Olha no máximo os primeiros {@value #LIMITE} caracteres: o resto só acrescenta bits.
 */
final class EstimadorDeSenha implements AutoCloseable {

    /** Resultado: os bits estimados e quantas palavras distintas (3 ou mais letras) a senha tem. */
    record Resultado(double bits, int palavras) { }

    /** Um trecho do caminho mais barato: custo, quantos "pedaços" tem e se termina em caractere solto. */
    private record Trecho(double bits, int grupos, boolean terminaSolto) { }

    static final int LIMITE = 128;
    private static final double LOG2 = Math.log(2);
    private static final int MINIMO_DA_PALAVRA = 3;
    private static final double BITS_DO_SEPARADOR = 2.0;
    private static final double BITS_DO_ANO = 7.0;         // 1900 a 2099, com folga
    private static final double BITS_DIA_E_MES = 8.6;      // 365 dias
    private static final double BITS_DATA_ANO_CURTO = 15.2; // 365 dias x 100 anos
    private static final double BITS_DATA_ANO_LONGO = 15.5; // 365 dias x 131 anos
    private static final double BITS_DO_DIGITO = Math.log(10) / LOG2;
    private static final double BITS_DO_SIMBOLO = Math.log(33) / LOG2;
    private static final double BITS_DE_OUTRO = 6.0;        // letra fora do ASCII sem equivalente (cirílico, emoji...)
    private static final int PONTOS_DE_PARTIDA_DO_TECLADO = 44;
    private static final int MINIMO_DA_PALAVRA_DESCONHECIDA = 5;
    private static final int MAXIMO_DA_PALAVRA_DESCONHECIDA = 20;
    private static final double BITS_DE_UMA_PALAVRA_DESCONHECIDA = 18.0; // lista de ~1 milhão de palavras, com folga
    private static final double BITS_POR_LETRA_ACIMA_DE_4 = 0.7;
    private static final String[] LINHAS_DO_TECLADO = {"qwertyuiopasdfghjklzxcvbnm", "1234567890"};

    private final ListaDeSenhasComuns lista;
    private final char[] original; // só leitura: é a senha do chamador, não uma cópia
    private final int n;
    private final char[] plano;
    private final char[] leet;
    private final char[] planoInverso;
    private final char[] leetInverso;
    private final int[] maiusculas;   // somas acumuladas, tamanho n + 1
    private final int[] minusculas;
    private final int[] substitutos;
    private final int[] letrasComSubstituto;
    private final double bitsDaLetra;

    EstimadorDeSenha(char[] senha, ListaDeSenhasComuns lista) {
        this.lista = lista;
        this.original = senha;
        this.n = Math.min(senha.length, LIMITE);
        this.plano = new char[n];
        this.leet = new char[n];
        this.planoInverso = new char[n];
        this.leetInverso = new char[n];
        this.maiusculas = new int[n + 1];
        this.minusculas = new int[n + 1];
        this.substitutos = new int[n + 1];
        this.letrasComSubstituto = new int[n + 1];
        for (int i = 0; i < n; i++) {
            char c = senha[i];
            plano[i] = Dobra.plano(c);
            leet[i] = Dobra.leet(plano[i]);
            planoInverso[n - 1 - i] = plano[i];
            leetInverso[n - 1 - i] = leet[i];
            maiusculas[i + 1] = maiusculas[i] + (Character.isUpperCase(c) ? 1 : 0);
            minusculas[i + 1] = minusculas[i] + (Character.isLowerCase(c) ? 1 : 0);
            substitutos[i + 1] = substitutos[i] + (Dobra.ehSubstituto(plano[i]) ? 1 : 0);
            letrasComSubstituto[i + 1] = letrasComSubstituto[i] + (Dobra.letraComSubstituto(plano[i]) ? 1 : 0);
        }
        // A caixa também carrega informação, mas na maioria das senhas é só a inicial; meio bit a mais por letra solta
        // quando a senha mistura as duas, sem tratar maiúsculas como sorteio puro.
        boolean mista = maiusculas[n] >= 2 && minusculas[n] >= 2;
        this.bitsDaLetra = Math.log(26) / LOG2 + (mista ? 0.5 : 0.0);
    }

    /** Zera as cópias dobradas da senha. */
    @Override
    public void close() {
        for (char[] a : new char[][] {plano, leet, planoInverso, leetInverso}) {
            Arrays.fill(a, '\0');
        }
    }

    Resultado estimar() {
        if (n == 0) {
            return new Resultado(0, 0);
        }
        Trecho t = melhor(0, n, true);
        double bits = t.bits() + log2DoFatorial(t.grupos());
        return new Resultado(Math.max(0, bits), palavrasDistintas());
    }

    // ---------------------------------------------------------------------------------------------
    // O caminho mais barato
    // ---------------------------------------------------------------------------------------------

    private Trecho melhor(int de, int ate, boolean permiteRepeticao) {
        int tam = ate - de;
        double[] custo = new double[tam + 1];
        int[] grupos = new int[tam + 1];
        boolean[] solto = new boolean[tam + 1];
        Arrays.fill(custo, Double.POSITIVE_INFINITY);
        custo[0] = 0;
        for (int i = 0; i < tam; i++) {
            int b = de + i;
            double base = custo[i];
            int g = grupos[i];

            // Caractere solto: dois soltos seguidos são UM pedaço para a conta da ordem.
            relaxar(custo, grupos, solto, i + 1, base + bitsDoCaractere(b), solto[i] ? g : g + 1, true);

            if (ehSeparador(original[b]) && b > de && b < ate - 1) {
                relaxar(custo, grupos, solto, i + 1, base + BITS_DO_SEPARADOR, g, false);
            }
            palavrasDaLista(custo, grupos, solto, i, b, ate, base, g);
            palavraDesconhecida(custo, grupos, solto, i, b, ate, base, g);
            sequencia(custo, grupos, solto, i, b, ate, base, g);
            tecladoEmLinha(custo, grupos, solto, i, b, ate, base, g);
            anoEData(custo, grupos, solto, i, b, ate, base, g);
            if (permiteRepeticao) {
                repeticao(custo, grupos, solto, i, b, de, ate, base, g);
            }
        }
        return new Trecho(custo[tam], grupos[tam], solto[tam]);
    }

    private static void relaxar(double[] custo, int[] grupos, boolean[] solto, int destino, double novo, int g, boolean ehSolto) {
        if (novo < custo[destino] - 1e-9) {
            custo[destino] = novo;
            grupos[destino] = g;
            solto[destino] = ehSolto;
        }
    }

    private double bitsDoCaractere(int b) {
        char c = original[b];
        if (Character.isLetter(c)) {
            return c < 0x80 || Dobra.plano(c) < 0x80 ? bitsDaLetra : BITS_DE_OUTRO;
        }
        if (c >= '0' && c <= '9') {
            return BITS_DO_DIGITO;
        }
        return c < 0x80 ? BITS_DO_SIMBOLO : BITS_DE_OUTRO;
    }

    private static boolean ehSeparador(char c) {
        return c == ' ' || c == '-' || c == '_' || c == '.';
    }

    // ----- palavras da lista -----

    private void palavrasDaLista(double[] custo, int[] grupos, boolean[] solto, int i, int b, int ate, double base, int g) {
        int fimMaximo = Math.min(ate, b + lista.maiorPalavra());
        for (int e = b + MINIMO_DA_PALAVRA; e <= fimMaximo; e++) {
            boolean comSubstituto = substitutos[e] - substitutos[b] > 0;
            double caixa = variacoesDeCaixa(b, e);
            double candidato = Double.POSITIVE_INFINITY;
            int rank = lista.rankPlano(plano, b, e);
            if (rank > 0) {
                candidato = rank * caixa;
            }
            int rankInverso = lista.rankPlano(planoInverso, n - e, n - b);
            if (rankInverso > 0) {
                candidato = Math.min(candidato, rankInverso * caixa * 2);
            }
            if (comSubstituto) {
                double variacoes = variacoesDeLeet(b, e);
                int rankLeet = lista.rankLeet(leet, b, e);
                if (rankLeet > 0) {
                    candidato = Math.min(candidato, rankLeet * caixa * variacoes);
                }
                int rankLeetInverso = lista.rankLeet(leetInverso, n - e, n - b);
                if (rankLeetInverso > 0) {
                    candidato = Math.min(candidato, rankLeetInverso * caixa * variacoes * 2);
                }
            }
            if (candidato != Double.POSITIVE_INFINITY) {
                relaxar(custo, grupos, solto, i + (e - b), base + log2(candidato), g + 1, false);
            }
        }
    }

    /** Quantas maneiras o atacante tem de escrever as letras do trecho com maiúsculas, dado quantas há (zxcvbn). */
    private double variacoesDeCaixa(int b, int e) {
        int maiusc = maiusculas[e] - maiusculas[b];
        int minusc = minusculas[e] - minusculas[b];
        if (maiusc == 0) {
            return 1;
        }
        if (minusc == 0) {
            return 2; // tudo maiúsculo
        }
        if (maiusc == 1 && (Character.isUpperCase(original[b]) || Character.isUpperCase(original[e - 1]))) {
            return 2; // só a inicial ou só a última letra
        }
        return somaDeCombinacoes(maiusc, minusc);
    }

    /** Quantas maneiras de trocar letras por dígitos e símbolos resultam no que está escrito. */
    private double variacoesDeLeet(int b, int e) {
        int trocadas = substitutos[e] - substitutos[b];
        int naoTrocadas = letrasComSubstituto[e] - letrasComSubstituto[b];
        if (trocadas == 0) {
            return 1;
        }
        return Math.max(2, somaDeCombinacoes(trocadas, naoTrocadas));
    }

    /** {@code soma de C(a+b, i)} para {@code i} de 1 a {@code min(a, b)}. */
    private static double somaDeCombinacoes(int a, int b) {
        int total = a + b;
        double soma = 0;
        for (int i = 1; i <= Math.min(a, b); i++) {
            soma += combinacoes(total, i);
        }
        return Math.max(soma, 1);
    }

    private static double combinacoes(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
        }
        return r;
    }

    /** Uma corrida de letras que parece palavra mas não está na lista: custa como uma palavra de uma lista grande. */
    private void palavraDesconhecida(double[] custo, int[] grupos, boolean[] solto, int i, int b, int ate, double base, int g) {
        // Em qualquer ponto da corrida de letras: "senhaJabuticabeira" é uma palavra da lista seguida de uma palavra rara.
        if (!ehLetraAscii(plano[b]) || !Character.isLetter(original[b])) {
            return;
        }
        int fimDaCorrida = b;
        while (fimDaCorrida < ate && ehLetraAscii(plano[fimDaCorrida]) && Character.isLetter(original[fimDaCorrida])) {
            fimDaCorrida++;
        }
        int maximo = Math.min(fimDaCorrida, b + MAXIMO_DA_PALAVRA_DESCONHECIDA);
        for (int e = b + MINIMO_DA_PALAVRA_DESCONHECIDA; e <= maximo; e++) {
            if (pareceUmaPalavra(b, e)) {
                double bits = BITS_DE_UMA_PALAVRA_DESCONHECIDA + BITS_POR_LETRA_ACIMA_DE_4 * (e - b - 4)
                    + log2(variacoesDeCaixa(b, e));
                relaxar(custo, grupos, solto, i + (e - b), base + bits, g + 1, false);
            }
        }
    }

    private static boolean ehLetraAscii(char p) {
        return p >= 'a' && p <= 'z';
    }

    /**
     * Heurística de "isto é uma palavra de língua natural": 25 % a 65 % de vogais, no máximo 4 consoantes seguidas e no
     * máximo 3 vogais seguidas. Letras sorteadas quase nunca passam (só 19 % delas são vogais); palavras de verdade quase
     * sempre passam.
     */
    private boolean pareceUmaPalavra(int b, int e) {
        int vogais = 0;
        int vogaisSeguidas = 0;
        int consoantesSeguidas = 0;
        for (int k = b; k < e; k++) {
            if ("aeiou".indexOf(plano[k]) >= 0) {
                vogais++;
                vogaisSeguidas++;
                consoantesSeguidas = 0;
                if (vogaisSeguidas > 3) {
                    return false;
                }
            } else {
                consoantesSeguidas++;
                vogaisSeguidas = 0;
                if (consoantesSeguidas > 4) {
                    return false;
                }
            }
        }
        double fracao = (double) vogais / (e - b);
        return fracao >= 0.25 && fracao <= 0.65;
    }

    // ----- sequência e teclado -----

    private void sequencia(double[] custo, int[] grupos, boolean[] solto, int i, int b, int ate, double base, int g) {
        if (b + 2 >= ate) {
            return;
        }
        int passo = plano[b + 1] - plano[b];
        if (passo == 0 || Math.abs(passo) > 5 || !mesmaClasse(plano[b], plano[b + 1])) {
            return;
        }
        int e = b + 2;
        while (e < ate && plano[e] - plano[e - 1] == passo && mesmaClasse(plano[e - 1], plano[e])) {
            e++;
        }
        double partida = pontoDePartidaDaSequencia(plano[b]);
        for (int f = b + 3; f <= e; f++) {
            double palpites = partida * (f - b) * (passo < 0 ? 2 : 1);
            relaxar(custo, grupos, solto, i + (f - b), base + log2(palpites), g + 1, false);
        }
    }

    private static boolean mesmaClasse(char x, char y) {
        return (x >= 'a' && x <= 'z' && y >= 'a' && y <= 'z') || (x >= '0' && x <= '9' && y >= '0' && y <= '9');
    }

    private static double pontoDePartidaDaSequencia(char primeiro) {
        if ("azAZ019".indexOf(primeiro) >= 0) {
            return 4;
        }
        return primeiro >= '0' && primeiro <= '9' ? 10 : 26;
    }

    private void tecladoEmLinha(double[] custo, int[] grupos, boolean[] solto, int i, int b, int ate, double base, int g) {
        for (String linha : LINHAS_DO_TECLADO) {
            int posicao = linha.indexOf(plano[b]);
            if (posicao < 0) {
                continue;
            }
            for (int sentido : new int[] {1, -1}) {
                int e = b + 1;
                int p = posicao;
                while (e < ate) {
                    int proxima = p + sentido;
                    if (proxima < 0 || proxima >= linha.length() || linha.charAt(proxima) != plano[e]) {
                        break;
                    }
                    p = proxima;
                    e++;
                }
                for (int f = b + 4; f <= e; f++) {
                    double palpites = (double) PONTOS_DE_PARTIDA_DO_TECLADO * (f - b) * 2;
                    relaxar(custo, grupos, solto, i + (f - b), base + log2(palpites), g + 1, false);
                }
            }
        }
    }

    // ----- ano e data -----

    private void anoEData(double[] custo, int[] grupos, boolean[] solto, int i, int b, int ate, double base, int g) {
        if (!ehDigito(original[b])) {
            return;
        }
        int corrida = 0;
        while (b + corrida < ate && ehDigito(original[b + corrida])) {
            corrida++;
        }
        if (corrida >= 4) {
            double bits = Double.POSITIVE_INFINITY;
            int valor = numero(b, 4);
            if (valor >= 1900 && valor <= 2099) {
                bits = BITS_DO_ANO;
            }
            if (diaEMesValidos(numero(b, 2), numero(b + 2, 2)) || diaEMesValidos(numero(b + 2, 2), numero(b, 2))) {
                bits = Math.min(bits, BITS_DIA_E_MES);
            }
            if (bits < Double.POSITIVE_INFINITY) {
                relaxar(custo, grupos, solto, i + 4, base + bits, g + 1, false);
            }
        }
        if (corrida >= 6 && dataCurtaValida(b)) {
            relaxar(custo, grupos, solto, i + 6, base + BITS_DATA_ANO_CURTO, g + 1, false);
        }
        if (corrida >= 8 && dataLongaValida(b)) {
            relaxar(custo, grupos, solto, i + 8, base + BITS_DATA_ANO_LONGO, g + 1, false);
        }
        // com separadores: 25/12/1990, 25-12-90, 25.12.1990
        for (int anoComDigitos : new int[] {2, 4}) {
            int fim = dataComSeparador(b, ate, anoComDigitos);
            if (fim > 0) {
                relaxar(custo, grupos, solto, i + (fim - b), base
                    + (anoComDigitos == 4 ? BITS_DATA_ANO_LONGO : BITS_DATA_ANO_CURTO), g + 1, false);
            }
        }
    }

    private static boolean ehDigito(char c) {
        return c >= '0' && c <= '9';
    }

    private int numero(int de, int tamanho) {
        int v = 0;
        for (int k = 0; k < tamanho; k++) {
            v = v * 10 + (original[de + k] - '0');
        }
        return v;
    }

    private static boolean diaEMesValidos(int dia, int mes) {
        return dia >= 1 && dia <= 31 && mes >= 1 && mes <= 12;
    }

    /** ddmmyy, mmddyy ou yymmdd. */
    private boolean dataCurtaValida(int b) {
        int x = numero(b, 2);
        int y = numero(b + 2, 2);
        int z = numero(b + 4, 2);
        return diaEMesValidos(x, y) || diaEMesValidos(y, x) || diaEMesValidos(z, y) || diaEMesValidos(y, z);
    }

    /** ddmmyyyy, mmddyyyy ou yyyymmdd (e yyyyddmm). */
    private boolean dataLongaValida(int b) {
        int ano1 = numero(b + 4, 4);
        int ano2 = numero(b, 4);
        boolean anoNoFim = ano1 >= 1900 && ano1 <= 2099;
        boolean anoNoComeco = ano2 >= 1900 && ano2 <= 2099;
        int x = numero(b, 2);
        int y = numero(b + 2, 2);
        int z = numero(b + 4, 2);
        int w = numero(b + 6, 2);
        return (anoNoFim && (diaEMesValidos(x, y) || diaEMesValidos(y, x)))
            || (anoNoComeco && (diaEMesValidos(w, z) || diaEMesValidos(z, w)));
    }

    /** O fim (exclusivo) de {@code d/m/a} com o mesmo separador nas duas posições, ou -1. */
    private int dataComSeparador(int b, int ate, int digitosDoAno) {
        int p = b;
        int d1 = digitosSeguidos(p, ate, 2);
        if (d1 == 0) {
            return -1;
        }
        int dia = numero(p, d1);
        p += d1;
        if (p >= ate || !separadorDeData(original[p])) {
            return -1;
        }
        char separador = original[p++];
        int d2 = digitosSeguidos(p, ate, 2);
        if (d2 == 0) {
            return -1;
        }
        int mes = numero(p, d2);
        p += d2;
        if (p >= ate || original[p] != separador) {
            return -1;
        }
        p++;
        if (digitosSeguidos(p, ate, digitosDoAno + 1) != digitosDoAno) {
            return -1;
        }
        if (!diaEMesValidos(dia, mes) && !diaEMesValidos(mes, dia)) {
            return -1;
        }
        if (digitosDoAno == 4) {
            int ano = numero(p, 4);
            if (ano < 1900 || ano > 2099) {
                return -1;
            }
        }
        return p + digitosDoAno;
    }

    private static boolean separadorDeData(char c) {
        return c == '/' || c == '-' || c == '.';
    }

    /** Quantos dígitos há em sequência a partir de {@code de}, até {@code maximo} (0 se não houver nenhum). */
    private int digitosSeguidos(int de, int ate, int maximo) {
        int k = 0;
        while (k < maximo && de + k < ate && ehDigito(original[de + k])) {
            k++;
        }
        return k;
    }

    // ----- repetição -----

    private void repeticao(double[] custo, int[] grupos, boolean[] solto, int i, int b, int de, int ate, double base, int g) {
        int maximoDoBloco = Math.min((ate - b) / 2, 64);
        for (int tamanhoDoBloco = 1; tamanhoDoBloco <= maximoDoBloco; tamanhoDoBloco++) {
            if (b - tamanhoDoBloco >= de && blocosIguais(b - tamanhoDoBloco, b, tamanhoDoBloco)) {
                continue; // a repetição começa antes: já foi considerada a partir de lá
            }
            int vezes = 1;
            while (b + (vezes + 1) * tamanhoDoBloco <= ate && blocosIguais(b, b + vezes * tamanhoDoBloco, tamanhoDoBloco)) {
                vezes++;
            }
            if (vezes < 2 || vezes * tamanhoDoBloco < 3) {
                continue;
            }
            double bloco = melhor(b, b + tamanhoDoBloco, false).bits();
            for (int k = 2; k <= vezes; k++) {
                relaxar(custo, grupos, solto, i + k * tamanhoDoBloco, base + bloco + log2(k), g + 1, false);
            }
        }
    }

    private boolean blocosIguais(int a, int b, int tamanho) {
        return Arrays.equals(plano, a, a + tamanho, plano, b, b + tamanho);
    }

    // ----- palavras (para a regra das 4 palavras) -----

    /** Quantas palavras distintas de 3 ou mais letras há, separadas por qualquer coisa que não seja letra. */
    private int palavrasDistintas() {
        int[] inicios = new int[n];
        int[] fins = new int[n];
        int quantas = 0;
        int i = 0;
        while (i < n) {
            if (!Character.isLetter(plano[i])) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isLetter(plano[j])) {
                j++;
            }
            if (j - i >= MINIMO_DA_PALAVRA) {
                boolean repetida = false;
                for (int k = 0; k < quantas && !repetida; k++) {
                    repetida = Arrays.equals(plano, inicios[k], fins[k], plano, i, j);
                }
                if (!repetida) {
                    inicios[quantas] = i;
                    fins[quantas] = j;
                    quantas++;
                }
            }
            i = j;
        }
        return quantas;
    }

    // ----- contas -----

    private static double log2(double x) {
        return Math.log(x) / LOG2;
    }

    private static double log2DoFatorial(int m) {
        double soma = 0;
        for (int k = 2; k <= m; k++) {
            soma += log2(k);
        }
        return soma;
    }
}
