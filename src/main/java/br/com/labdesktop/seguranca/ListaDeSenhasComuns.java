package br.com.labdesktop.seguranca;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A lista de senhas e palavras comuns embarcada como recurso ({@code senhas-comuns.txt}, cerca de 94 mil entradas: as
 * 30 mil senhas mais usadas, palavras do inglês, nomes, sobrenomes e um complemento em português do Brasil; veja
 * {@code scripts/gerar-lista-de-senhas.py} para a origem). Cada entrada tem um <i>rank</i> (1 = a mais comum): é o número
 * de palpites que um atacante gasta até chegar nela, e a régua da senha-mestra o transforma em bits.
 *
 * <p>A busca é por TRECHO DE {@code char[]} ({@code a[de..ate)}), sem criar {@code String}: o trecho vem da senha-mestra
 * digitada, que nunca vira {@code String}. Por isso a lista é guardada em duas tabelas de hash próprias (dobra plana e
 * dobra leet, veja {@link Dobra}), com as chaves num único {@code char[]}.
 *
 * <p>Imutável depois de pronta; a leitura do recurso (1,3 MB) leva algumas dezenas de milissegundos e acontece uma vez,
 * na primeira vez que {@link #padrao()} é pedida. A tela chama {@link ForcaDeSenha#preaquecer()} numa thread de fundo
 * antes de o usuário digitar, para a leitura nunca cair na EDT.
 */
final class ListaDeSenhasComuns {

    static final String RECURSO = "senhas-comuns.txt";
    /** Palavras maiores que isto não entram: a busca nunca olha trechos mais longos. */
    static final int MAIOR_PALAVRA_ACEITA = 32;

    private final Tabela plana;
    private final Tabela leet;
    private final int entradas;
    private final int maiorPalavra;

    private ListaDeSenhasComuns(Tabela plana, Tabela leet, int entradas, int maiorPalavra) {
        this.plana = plana;
        this.leet = leet;
        this.entradas = entradas;
        this.maiorPalavra = maiorPalavra;
    }

    /** Carrega-sob-demanda segura entre threads (o inicializador da classe interna roda uma só vez). */
    private static final class Unica {
        static final ListaDeSenhasComuns LISTA = carregarDoRecurso();
    }

    /** A lista embarcada. */
    static ListaDeSenhasComuns padrao() {
        return Unica.LISTA;
    }

    private static ListaDeSenhasComuns carregarDoRecurso() {
        try (InputStream in = ListaDeSenhasComuns.class.getResourceAsStream(RECURSO)) {
            if (in == null) {
                throw new IllegalStateException("A lista de senhas comuns não está no jar: " + RECURSO);
            }
            return ler(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui ler a lista de senhas comuns.", e);
        }
    }

    /**
     * Lê o formato {@code palavra<TAB>rank}, uma por linha; linhas vazias e as que começam por {@code #} são ignoradas.
     * Se a mesma palavra aparecer duas vezes, vale o menor rank.
     */
    static ListaDeSenhasComuns ler(InputStream in) throws IOException {
        Map<String, Integer> planas = new HashMap<>();
        Map<String, Integer> leets = new HashMap<>();
        int maior = 0;
        try (BufferedReader leitor = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = leitor.readLine()) != null) {
                if (linha.isEmpty() || linha.charAt(0) == '#') {
                    continue;
                }
                int tab = linha.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                int rank;
                try {
                    rank = Integer.parseInt(linha.substring(tab + 1).strip());
                } catch (NumberFormatException e) {
                    continue;
                }
                String palavra = linha.substring(0, tab);
                if (rank < 1 || palavra.length() < 1 || palavra.length() > MAIOR_PALAVRA_ACEITA) {
                    continue;
                }
                char[] p = new char[palavra.length()];
                char[] l = new char[palavra.length()];
                for (int i = 0; i < p.length; i++) {
                    p[i] = Dobra.plano(palavra.charAt(i));
                    l[i] = Dobra.leet(p[i]);
                }
                planas.merge(new String(p), rank, Math::min);
                leets.merge(new String(l), rank, Math::min);
                maior = Math.max(maior, p.length);
            }
        }
        return new ListaDeSenhasComuns(Tabela.de(planas), Tabela.de(leets), planas.size(), maior);
    }

    /** O rank do trecho {@code a[de..ate)} na dobra plana (que já deve estar dobrada), ou 0 se não está na lista. */
    int rankPlano(char[] a, int de, int ate) {
        return plana.rank(a, de, ate);
    }

    /** O rank do trecho na dobra leet (que já deve estar dobrada), ou 0 se não está na lista. */
    int rankLeet(char[] a, int de, int ate) {
        return leet.rank(a, de, ate);
    }

    /** Quantas palavras distintas (na dobra plana) a lista tem. */
    int entradas() {
        return entradas;
    }

    /** O comprimento da maior palavra da lista: a busca não precisa olhar trechos maiores. */
    int maiorPalavra() {
        return maiorPalavra;
    }

    /** Tabela de hash com endereçamento aberto, chaves num único {@code char[]} e busca por trecho de {@code char[]}. */
    private static final class Tabela {
        private final char[] chaves;
        private final int[] inicios;
        private final int[] ranks;
        private final int[] ocupacao;
        private final int mascara;

        private Tabela(char[] chaves, int[] inicios, int[] ranks, int[] ocupacao) {
            this.chaves = chaves;
            this.inicios = inicios;
            this.ranks = ranks;
            this.ocupacao = ocupacao;
            this.mascara = ocupacao.length - 1;
        }

        static Tabela de(Map<String, Integer> mapa) {
            int n = mapa.size();
            int total = 0;
            for (String s : mapa.keySet()) {
                total += s.length();
            }
            char[] chaves = new char[total];
            int[] inicios = new int[n + 1];
            int[] ranks = new int[n];
            int tamanho = 1;
            while (tamanho < n * 2 + 2) {
                tamanho <<= 1;
            }
            int[] ocupacao = new int[tamanho];
            int mascara = tamanho - 1;
            int pos = 0;
            int idx = 0;
            for (Map.Entry<String, Integer> e : mapa.entrySet()) {
                String s = e.getKey();
                inicios[idx] = pos;
                s.getChars(0, s.length(), chaves, pos);
                pos += s.length();
                ranks[idx] = e.getValue();
                int i = hash(chaves, inicios[idx], pos) & mascara;
                while (ocupacao[i] != 0) {
                    i = (i + 1) & mascara;
                }
                ocupacao[i] = idx + 1;
                idx++;
            }
            inicios[n] = pos;
            return new Tabela(chaves, inicios, ranks, ocupacao);
        }

        int rank(char[] a, int de, int ate) {
            int tamanho = ate - de;
            if (tamanho < 1) {
                return 0;
            }
            int i = hash(a, de, ate) & mascara;
            while (true) {
                int s = ocupacao[i];
                if (s == 0) {
                    return 0;
                }
                int idx = s - 1;
                int ini = inicios[idx];
                int fim = inicios[idx + 1];
                if (fim - ini == tamanho && Arrays.equals(chaves, ini, fim, a, de, ate)) {
                    return ranks[idx];
                }
                i = (i + 1) & mascara;
            }
        }

        private static int hash(char[] a, int de, int ate) {
            int h = 0;
            for (int i = de; i < ate; i++) {
                h = 31 * h + a[i];
            }
            return h ^ (h >>> 16);
        }
    }
}
