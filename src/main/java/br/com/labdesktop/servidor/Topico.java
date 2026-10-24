package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.modelo.Detalhe;
import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Um assunto que uma tela pode assinar (ARQUITETURA 6.7): o que consultar e com que cadência. A tabela:
 *
 * <pre>
 * Tópico          ativa   sem foco   escondida ou minimizada
 * LIMITES         60 s    60 s       60 s   (alimenta faixa de crédito, ícone, notificações)
 * PROJETOS        10 s    60 s       parado
 * SERVIDOR        10 s    60 s       parado
 * DETALHE(p)      10 s    60 s       parado
 * </pre>
 *
 * Os tópicos de consumo, tela do Claude e logs (fase 2 e 3) entram aqui da mesma forma, por acréscimo.
 *
 * @param <T> o DTO imutável que o tópico entrega
 */
public final class Topico<T> {

    /** A consulta de um tópico. Bloqueia (rede); roda numa thread virtual. */
    @FunctionalInterface
    public interface Consulta<T> {
        T consultar(ClienteApi api) throws ErroApi;
    }

    private static final Duration DEZ_SEGUNDOS = Duration.ofSeconds(10);
    private static final Duration SESSENTA_SEGUNDOS = Duration.ofSeconds(60);

    /** Limites do plano, freio e pausados. SEMPRE ligado: nem a janela escondida o para. */
    public static final Topico<LimitesConta> LIMITES = new Topico<>("limites", ClienteApi::limites,
        SESSENTA_SEGUNDOS, SESSENTA_SEGUNDOS, true, null);

    /** Lista de projetos (com o resumo do servidor) mais a sessão raiz. Custa um {@code docker exec} por sessão aberta. */
    public static final Topico<VisaoProjetos> PROJETOS = new Topico<>("projetos", Topico::consultarProjetos,
        DEZ_SEGUNDOS, SESSENTA_SEGUNDOS, false, v -> v.lista() == null || v.lista().servidor() == null
            ? null : v.lista().servidor().temperatura());

    /** Métricas do servidor com histórico de 10 min e processos. */
    public static final Topico<InfoServidor> SERVIDOR = new Topico<>("servidor", ClienteApi::servidor,
        DEZ_SEGUNDOS, SESSENTA_SEGUNDOS, false, InfoServidor::temperatura);

    /**
     * Um tópico sob medida (a cadência e a consulta são do chamador). Existe para os tópicos das próximas etapas
     * (consumo, tela do Claude, logs) e para os testes do {@link Sondador}.
     */
    static <T> Topico<T> de(String chave, Consulta<T> consulta, Duration ativa, Duration semFoco,
                            boolean rodaComJanelaEscondida, Function<T, Double> temperatura) {
        return new Topico<>(chave, consulta, ativa, semFoco, rodaComJanelaEscondida, temperatura);
    }

    /** O detalhe de um projeto (ou da sessão raiz, {@code lab}). */
    public static Topico<Detalhe> detalhe(String projeto) {
        String p = ValidadorDeNomes.exigirProjeto(projeto);
        return new Topico<>("detalhe:" + p, api -> api.detalhe(p), DEZ_SEGUNDOS, SESSENTA_SEGUNDOS, false, null);
    }

    private final String chave;
    private final Consulta<T> consulta;
    private final Duration cadenciaAtiva;
    private final Duration cadenciaSemFoco;
    private final boolean rodaComJanelaEscondida;
    private final Function<T, Double> temperatura;

    private Topico(String chave, Consulta<T> consulta, Duration cadenciaAtiva, Duration cadenciaSemFoco,
                   boolean rodaComJanelaEscondida, Function<T, Double> temperatura) {
        this.chave = chave;
        this.consulta = consulta;
        this.cadenciaAtiva = cadenciaAtiva;
        this.cadenciaSemFoco = cadenciaSemFoco;
        this.rodaComJanelaEscondida = rodaComJanelaEscondida;
        this.temperatura = temperatura;
    }

    /** Identifica o tópico: duas assinaturas com a mesma chave compartilham a mesma consulta. */
    public String chave() {
        return chave;
    }

    Consulta<T> consulta() {
        return consulta;
    }

    /** A temperatura do servidor que este valor traz (para a guarda de calor), ou {@code null}. */
    Double temperaturaDe(T valor) {
        return temperatura == null || valor == null ? null : temperatura.apply(valor);
    }

    /**
     * De quanto em quanto tempo consultar nesta situação, ou vazio se o tópico não roda.
     *
     * @param cadenciaCheiaSemFoco preferência "manter cadência cheia com a janela visível sem foco" (Monitor numa segunda tela)
     */
    public Optional<Duration> intervalo(EstadoJanela janela, boolean cadenciaCheiaSemFoco) {
        return switch (janela) {
            case ATIVA -> Optional.of(cadenciaAtiva);
            case SEM_FOCO -> Optional.of(cadenciaCheiaSemFoco ? cadenciaAtiva : cadenciaSemFoco);
            case ESCONDIDA -> rodaComJanelaEscondida ? Optional.of(cadenciaSemFoco) : Optional.empty();
        };
    }

    @Override
    public String toString() {
        return "Topico[" + chave + "]";
    }

    /**
     * A tela Projetos: lista e raiz juntas, como o front. A falha da raiz NÃO derruba a lista; só os erros que
     * pedem ação (rede e autorização) sobem.
     */
    private static VisaoProjetos consultarProjetos(ClienteApi api) throws ErroApi {
        ListaProjetos lista = api.projetos();
        DetalheRaiz raiz = null;
        try {
            raiz = api.sessaoRaiz();
        } catch (ErroRede | NaoAutorizado e) {
            throw e;
        } catch (ErroApi e) {
            // sem a raiz a tela mostra a lista do mesmo jeito
        }
        return new VisaoProjetos(lista, raiz);
    }
}
