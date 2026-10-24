package br.com.labdesktop.cofre;

/**
 * Parâmetros do Argon2id gravados no cabeçalho do cofre (ARQUITETURA 5.2 e 5.4). Sem segredo, por isso
 * {@code record}.
 *
 * <p>Os limites abaixo são os que a LEITURA aceita: um arquivo malicioso ou corrompido não pode pedir 4 GiB
 * de memória e provocar {@code OutOfMemoryError}, nem pedir custo tão baixo que facilite força bruta (o
 * cabeçalho entra no AAD do GCM, então baixar o custo à mão também falha a autenticação).
 *
 * @param memoriaKib  memória em KiB (19.456 a 1.048.576)
 * @param iteracoes   passes (2 a 10)
 * @param paralelismo lanes (1 a 8). O BouncyCastle as executa em série, então {@code p} não encarece para quem defende.
 */
public record ParametrosKdf(int memoriaKib, int iteracoes, int paralelismo) {

    public static final int MEMORIA_MIN_KIB = 19_456;
    public static final int MEMORIA_MAX_KIB = 1_048_576;
    public static final int ITERACOES_MIN = 2;
    public static final int ITERACOES_MAX = 10;
    public static final int PARALELISMO_MIN = 1;
    public static final int PARALELISMO_MAX = 8;

    /** Parâmetros de produção: 256 MiB, t=6, p=1 (cerca de 1 s num computador de referência, medido). */
    public static final ParametrosKdf PADRAO = new ParametrosKdf(262_144, 6, 1);

    /**
     * O mais barato que o formato aceita (19 MiB, t=2, p=1). Existe para os testes não gastarem um segundo por
     * abertura; a tela nunca cria cofre com isto.
     */
    public static final ParametrosKdf MINIMO = new ParametrosKdf(MEMORIA_MIN_KIB, ITERACOES_MIN, PARALELISMO_MIN);

    public ParametrosKdf {
        if (!dentroDaFaixa(memoriaKib, iteracoes, paralelismo)) {
            throw new IllegalArgumentException("Parâmetros do KDF fora da faixa aceita: m=" + memoriaKib
                + " KiB, t=" + iteracoes + ", p=" + paralelismo);
        }
    }

    /** Usa {@code long} para a leitura do arquivo poder conferir u32 sem estourar o {@code int}. */
    public static boolean dentroDaFaixa(long memoriaKib, long iteracoes, long paralelismo) {
        return memoriaKib >= MEMORIA_MIN_KIB && memoriaKib <= MEMORIA_MAX_KIB
            && iteracoes >= ITERACOES_MIN && iteracoes <= ITERACOES_MAX
            && paralelismo >= PARALELISMO_MIN && paralelismo <= PARALELISMO_MAX;
    }

    /**
     * {@code true} se estes parâmetros custam menos que {@code alvo} em memória ou iterações (paralelismo não
     * conta: não muda o custo para quem defende). É o gatilho do reforço no desbloqueio (ARQUITETURA 5.2).
     */
    public boolean maisFracoQue(ParametrosKdf alvo) {
        return memoriaKib < alvo.memoriaKib || iteracoes < alvo.iteracoes;
    }
}
