package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.RespostaInvalida;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Agendador.Cancelavel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O polling de UM servidor, por tópicos com assinatura por tela (ARQUITETURA 6.7). Reproduz o {@code UI.aCada}
 * do front: só consulta o que alguma tela visível assinou. Uma aba esquecida custava cerca de 20 % de um núcleo
 * no lab, que já anda perto de 87 °C; cada {@code /api/projetos} faz um {@code docker exec} por sessão aberta.
 *
 * <h2>Regras</h2>
 * <ul>
 *   <li><b>Assinatura</b>: sem ouvinte, o tópico não roda. Vários ouvintes do mesmo tópico compartilham UMA
 *       consulta. Quem assina recebe logo o último valor conhecido.</li>
 *   <li><b>Cadência</b> por {@link EstadoJanela} (veja {@link Topico}): ativa, ×6 sem foco, só {@code LIMITES}
 *       com a janela escondida. Mac dormindo ({@link #definirSuspenso}): nada.</li>
 *   <li><b>Sem sobreposição</b>: se a consulta anterior não terminou, o tique é pulado. Um pedido de
 *       {@link #atualizarAgora} durante uma consulta roda UMA vez a mais, logo depois.</li>
 *   <li><b>Dedupe</b>: só avisa as telas se o valor mudou (os DTOs são records com igualdade por valor).</li>
 *   <li><b>Falhas</b>: três falhas seguidas dobram o intervalo, e a cada falha seguinte dobra de novo, até 60 s.</li>
 *   <li><b>Calor</b>: com {@code servidor.cpu.temperatura} acima de 85 °C os intervalos dobram, até cair
 *       abaixo de 80 °C (histerese, para não oscilar).</li>
 *   <li><b>Sem API</b> ({@link #definirApi} com {@code null}, quando a conexão não está {@code CONECTADO}):
 *       nenhuma consulta e nenhum timer. Com uma API nova, tudo o que está assinado consulta de imediato.</li>
 * </ul>
 *
 * <p>O trabalho (rede) roda no executor de trabalho (threads virtuais); a entrega às telas, no despachante de
 * entrega (a EDT). O relógio e os timers são do {@link Agendador}, então os testes usam tempo falso.
 */
public final class Sondador implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Sondador.class);

    private static final int FALHAS_PARA_DOBRAR = 3;
    private static final Duration TETO_DE_FALHAS = Duration.ofSeconds(60);
    private static final double CALOR_LIGA = 85.0;
    private static final double CALOR_DESLIGA = 80.0;

    private final Agendador agendador;
    private final Executor trabalho;
    private final Executor entrega;
    private final Consumer<ErroApi> aoErro;
    private final Map<String, Canal<?>> canais = new ConcurrentHashMap<>();

    // Estado global, protegido por "this".
    private ClienteApi api;
    private EstadoJanela janela = EstadoJanela.ATIVA;
    private boolean cadenciaCheiaSemFoco;
    private boolean suspenso;
    private boolean quente;
    private boolean fechado;

    /** Um tópico em andamento: ouvintes, último valor, contagem de falhas e o timer. */
    private static final class Canal<T> {
        final Topico<T> topico;
        final List<OuvinteDeTopico<T>> ouvintes = new CopyOnWriteArrayList<>();
        T ultimo;
        boolean temUltimo;
        int falhasSeguidas;
        boolean rodando;
        boolean pendente;
        Cancelavel proximo;
        Instant ultimaExecucao;

        Canal(Topico<T> topico) {
            this.topico = topico;
        }
    }

    /**
     * @param agendador relógio e timers (o do Fundo em produção; falso nos testes)
     * @param trabalho  onde roda a consulta (threads virtuais em produção; inline nos testes)
     * @param entrega   para onde vão os resultados (a EDT em produção, {@code Fundo::naEdt})
     * @param aoErro    recebe TODO erro de consulta, para o usuário do Sondador decidir (erro de rede pede à
     *                  conexão para verificar; 401 muda o estado da conexão)
     */
    public Sondador(Agendador agendador, Executor trabalho, Executor entrega, Consumer<ErroApi> aoErro) {
        this.agendador = agendador;
        this.trabalho = trabalho;
        this.entrega = entrega;
        this.aoErro = aoErro;
    }

    // =====================================================================================================
    // Configuração
    // =====================================================================================================

    /** A API da geração atual do túnel; {@code null} quando não há conexão (para tudo). */
    public synchronized void definirApi(ClienteApi nova) {
        if (nova == this.api) {
            return; // a mesma geração: nada a refazer (a conexão publica mudanças que não são de túnel)
        }
        this.api = nova;
        for (Canal<?> c : canais.values()) {
            c.falhasSeguidas = 0;
            reprogramar(c, true);
        }
    }

    /** O estado da janela (veja {@link EstadoJanela}). */
    public synchronized void definirJanela(EstadoJanela nova) {
        if (this.janela == nova) {
            return;
        }
        this.janela = nova;
        canais.values().forEach(c -> reprogramar(c, false));
    }

    /** Preferência "manter cadência cheia com a janela visível sem foco" (útil para o Monitor numa segunda tela). */
    public synchronized void definirCadenciaCheiaSemFoco(boolean ligada) {
        this.cadenciaCheiaSemFoco = ligada;
        canais.values().forEach(c -> reprogramar(c, false));
    }

    /** Mac dormindo: nada roda. Ao acordar, tudo o que está assinado consulta de imediato. */
    public synchronized void definirSuspenso(boolean dormindo) {
        if (this.suspenso == dormindo) {
            return;
        }
        this.suspenso = dormindo;
        canais.values().forEach(c -> reprogramar(c, !dormindo));
    }

    // =====================================================================================================
    // Assinatura
    // =====================================================================================================

    /**
     * Assina um tópico. A inscrição devolvida solta a assinatura; quando o último ouvinte sai, o timer do tópico
     * para. O ouvinte recebe o último valor conhecido (se houver) logo em seguida, pelo despachante de entrega.
     */
    @SuppressWarnings("unchecked")
    public <T> Inscricao assinar(Topico<T> topico, OuvinteDeTopico<T> ouvinte) {
        Objects.requireNonNull(ouvinte, "ouvinte");
        Canal<T> canal;
        T anterior = null;
        boolean temAnterior;
        synchronized (this) {
            if (fechado) {
                return Inscricao.vazia();
            }
            canal = (Canal<T>) canais.computeIfAbsent(topico.chave(), k -> new Canal<>(topico));
            canal.ouvintes.add(ouvinte);
            temAnterior = canal.temUltimo;
            anterior = canal.ultimo;
            // Quem acabou de aparecer quer o dado AGORA, não daqui a 10 s.
            reprogramar(canal, canal.ouvintes.size() == 1);
        }
        if (temAnterior) {
            T valor = anterior;
            entrega.execute(() -> avisar(ouvinte, valor));
        }
        Canal<T> soltar = canal;
        return () -> soltarOuvinte(soltar, ouvinte);
    }

    private <T> void soltarOuvinte(Canal<T> canal, OuvinteDeTopico<T> ouvinte) {
        synchronized (this) {
            if (!canal.ouvintes.remove(ouvinte)) {
                return;
            }
            if (canal.ouvintes.isEmpty()) {
                cancelar(canal);
                canal.pendente = false;
            }
        }
    }

    /** O último valor conhecido do tópico, se algum ouvinte já o recebeu. */
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> ultimo(Topico<T> topico) {
        Canal<T> c = (Canal<T>) canais.get(topico.chave());
        return c == null || !c.temUltimo ? Optional.empty() : Optional.ofNullable(c.ultimo);
    }

    /**
     * Pede consulta imediata dos tópicos dados (os que têm ouvinte). Se algum está consultando, roda UMA vez a
     * mais logo depois. É o que {@code AcoesDoServidor} usa ao terminar uma ação.
     */
    public synchronized void atualizarAgora(Topico<?>... topicos) {
        for (Topico<?> t : topicos) {
            Canal<?> c = canais.get(t.chave());
            if (c != null) {
                reprogramar(c, true);
            }
        }
    }

    /** Pede consulta imediata de tudo o que está assinado. */
    public synchronized void atualizarTudoAgora() {
        canais.values().forEach(c -> reprogramar(c, true));
    }

    @Override
    public synchronized void close() {
        fechado = true;
        canais.values().forEach(this::cancelar);
        canais.clear();
    }

    // =====================================================================================================
    // Agendamento (sempre sob "this")
    // =====================================================================================================

    private boolean deveRodar(Canal<?> c) {
        return !fechado && !suspenso && api != null && !c.ouvintes.isEmpty()
            && c.topico.intervalo(janela, cadenciaCheiaSemFoco).isPresent();
    }

    private void cancelar(Canal<?> c) {
        if (c.proximo != null) {
            c.proximo.cancelar();
            c.proximo = null;
        }
    }

    /** (Re)programa o próximo disparo do tópico; {@code imediato} = o quanto antes, sem esperar o intervalo. */
    private void reprogramar(Canal<?> c, boolean imediato) {
        cancelar(c);
        if (!deveRodar(c)) {
            return;
        }
        if (c.rodando) {
            // Não sobrepõe: se pediram agora, roda uma vez a mais quando a atual terminar.
            if (imediato) {
                c.pendente = true;
            }
            return;
        }
        Duration intervalo = intervaloEfetivo(c);
        Duration atraso;
        if (imediato || c.ultimaExecucao == null) {
            atraso = Duration.ZERO;
        } else {
            Duration decorrido = Duration.between(c.ultimaExecucao, agendador.agora());
            atraso = intervalo.minus(decorrido);
            if (atraso.isNegative()) {
                atraso = Duration.ZERO;
            }
        }
        c.proximo = agendador.agendar(atraso, () -> disparar(c));
    }

    /** O intervalo base, dobrado pelas falhas seguidas e pelo calor. */
    private Duration intervaloEfetivo(Canal<?> c) {
        Duration base = c.topico.intervalo(janela, cadenciaCheiaSemFoco).orElse(TETO_DE_FALHAS);
        Duration d = base;
        if (c.falhasSeguidas >= FALHAS_PARA_DOBRAR) {
            Duration teto = base.compareTo(TETO_DE_FALHAS) > 0 ? base : TETO_DE_FALHAS;
            int dobras = Math.min(c.falhasSeguidas - FALHAS_PARA_DOBRAR + 1, 16);
            Duration dobrado = base.multipliedBy(1L << dobras);
            d = dobrado.compareTo(teto) > 0 ? teto : dobrado;
        }
        return quente ? d.multipliedBy(2) : d;
    }

    private <T> void disparar(Canal<T> c) {
        ClienteApi minhaApi;
        synchronized (this) {
            c.proximo = null;
            if (!deveRodar(c)) {
                return;
            }
            if (c.rodando) {
                c.pendente = true;
                return;
            }
            c.rodando = true;
            c.ultimaExecucao = agendador.agora();
            minhaApi = api;
        }
        trabalho.execute(() -> executar(c, minhaApi));
    }

    private <T> void executar(Canal<T> c, ClienteApi minhaApi) {
        T valor = null;
        ErroApi erro = null;
        try {
            valor = c.topico.consulta().consultar(minhaApi);
        } catch (ErroApi e) {
            erro = e;
        } catch (RuntimeException e) {
            LOG.warn("Consulta {} falhou por erro inesperado: {}", c.topico, Redator.redigir(e));
            erro = new RespostaInvalida(0, "Erro inesperado ao consultar o servidor.", e);
        }
        concluir(c, minhaApi, valor, erro);
    }

    private <T> void concluir(Canal<T> c, ClienteApi minhaApi, T valor, ErroApi erro) {
        boolean mudou = false;
        List<OuvinteDeTopico<T>> destinatarios;
        synchronized (this) {
            c.rodando = false;
            if (fechado || minhaApi != api) {
                // Resultado de uma geração de túnel que já acabou: descarta e consulta de novo na atual.
                reprogramar(c, true);
                return;
            }
            destinatarios = List.copyOf(c.ouvintes);
            if (erro == null) {
                c.falhasSeguidas = 0;
                mudou = !c.temUltimo || !Objects.equals(c.ultimo, valor);
                if (mudou) {
                    c.ultimo = valor;
                    c.temUltimo = true;
                }
                atualizarCalor(c.topico.temperaturaDe(valor));
            } else {
                c.falhasSeguidas++;
            }
            boolean imediato = c.pendente;
            c.pendente = false;
            reprogramar(c, imediato);
        }
        if (erro != null) {
            ErroApi e = erro;
            entrega.execute(() -> destinatarios.forEach(o -> avisarFalha(o, e)));
            try {
                aoErro.accept(e);
            } catch (RuntimeException ex) {
                LOG.warn("O tratador de erro do Sondador falhou: {}", Redator.redigir(ex));
            }
        } else if (mudou) {
            T v = valor;
            entrega.execute(() -> destinatarios.forEach(o -> avisar(o, v)));
        }
    }

    /** Guarda de calor com histerese: liga acima de 85 °C, desliga abaixo de 80 °C. */
    private void atualizarCalor(Double temperatura) {
        if (temperatura == null) {
            return;
        }
        boolean antes = quente;
        if (temperatura > CALOR_LIGA) {
            quente = true;
        } else if (temperatura < CALOR_DESLIGA) {
            quente = false;
        }
        if (antes != quente) {
            LOG.info("Guarda de calor {} ({} °C).", quente ? "ligada" : "desligada", temperatura);
            canais.values().forEach(x -> {
                if (!x.rodando) {
                    reprogramar(x, false);
                }
            });
        }
    }

    private static <T> void avisar(OuvinteDeTopico<T> o, T valor) {
        try {
            o.aoAtualizar(valor);
        } catch (RuntimeException e) {
            LOG.warn("Ouvinte de tópico falhou: {}", e.toString(), e);
        }
    }

    private static <T> void avisarFalha(OuvinteDeTopico<T> o, ErroApi erro) {
        try {
            o.aoFalhar(erro);
        } catch (RuntimeException e) {
            LOG.warn("Ouvinte de tópico falhou ao receber erro: {}", e.toString(), e);
        }
    }

    /** Se a guarda de calor está ligada (para a tela e os testes). */
    public synchronized boolean quente() {
        return quente;
    }
}
