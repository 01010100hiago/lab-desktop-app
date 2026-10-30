package br.com.labdesktop.git;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Guarda por pouco tempo (60 s) o que a procura de repositórios em subpastas achou numa pasta de projeto, porque ela roda a cada leitura da Visão geral e da aba Git, e num projeto sem
 * {@code .git} na raiz (o caso dela) pode demorar. Um por projeto e servidor (quem o guarda é o {@code ServidorLab}). Só guarda procura que DEU CERTO: falha nunca fica guardada. O botão Atualizar
 * e a criação de um repositório mandam {@link #esquecer()}. A criação de repositório NÃO usa este cache: confere sempre de verdade.
 */
public final class CacheDeBusca {

    public static final Duration VALIDADE = Duration.ofSeconds(60);

    private final Supplier<Instant> relogio;
    private final Duration validade;
    private String raiz;
    private RepositoriosAbaixo.Achados valor;
    private Instant quando;

    public CacheDeBusca() {
        this(Instant::now, VALIDADE);
    }

    /** Com o relógio e a validade trocáveis (os testes usam um relógio falso). */
    public CacheDeBusca(Supplier<Instant> relogio, Duration validade) {
        this.relogio = relogio;
        this.validade = validade;
    }

    /** O que a última procura achou, se ainda vale; senão procura de verdade (e guarda, se der certo). Bloqueia: quem chega junto espera a mesma procura e aproveita o resultado. */
    public synchronized RepositoriosAbaixo.Achados obter(String pasta, RepositoriosAbaixo.Buscador real) throws ErroDoGit, InterruptedException {
        Instant agora = relogio.get();
        if (valor != null && pasta.equals(raiz) && Duration.between(quando, agora).compareTo(validade) < 0) {
            return valor;
        }
        RepositoriosAbaixo.Achados achados = real.buscar(pasta);
        raiz = pasta;
        valor = achados;
        quando = agora;
        return achados;
    }

    /** Joga fora o que está guardado: a próxima procura é de verdade. */
    public synchronized void esquecer() {
        valor = null;
        raiz = null;
        quando = null;
    }
}
