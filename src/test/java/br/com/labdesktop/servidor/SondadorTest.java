package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.teste.AgendadorFalso;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ARQUITETURA 6.7: o polling por tópicos, com relógio falso e execução síncrona (determinístico). */
class SondadorTest {

    private static final Duration DEZ = Duration.ofSeconds(10);
    private static final Duration SESSENTA = Duration.ofSeconds(60);

    /** Um tópico de brinquedo que conta chamadas e anota quando elas aconteceram. */
    private final class Brinquedo {
        final AtomicInteger chamadas = new AtomicInteger();
        final List<Instant> momentos = new ArrayList<>();
        volatile int valor = 1;
        volatile ErroApi falha;
        volatile Double temperatura;
        final Topico<Integer> topico;

        Brinquedo(String chave, boolean rodaEscondida) {
            Function<Integer, Double> temp = v -> temperatura;
            topico = Topico.de(chave, api -> {
                chamadas.incrementAndGet();
                momentos.add(relogio.agora());
                if (falha != null) {
                    throw falha;
                }
                return valor;
            }, DEZ, SESSENTA, rodaEscondida, temp);
        }
    }

    private AgendadorFalso relogio;
    private Sondador sondador;
    private ClienteApi api;
    private final List<ErroApi> errosDeConsulta = new ArrayList<>();

    @BeforeEach
    void preparar() {
        relogio = new AgendadorFalso();
        // Execução e entrega síncronas: o teste enxerga o efeito de cada avancar().
        sondador = new Sondador(relogio, Runnable::run, Runnable::run, errosDeConsulta::add);
        api = new ClienteApi("teste", 1, null, PedidoDeCodigo2fa.NUNCA);
        sondador.definirApi(api);
    }

    @AfterEach
    void fechar() {
        sondador.close();
        api.close();
    }

    private static final class Colecao<T> implements OuvinteDeTopico<T> {
        final List<T> valores = new ArrayList<>();
        final List<ErroApi> erros = new ArrayList<>();

        @Override
        public void aoAtualizar(T valor) {
            valores.add(valor);
        }

        @Override
        public void aoFalhar(ErroApi erro) {
            erros.add(erro);
        }
    }

    @Test
    void semAssinanteNadaEConsultado() {
        Brinquedo t = new Brinquedo("t", true);
        relogio.avancar(Duration.ofMinutes(10));
        assertEquals(0, t.chamadas.get());
        assertEquals(0, relogio.pendentes(), "nem timer");
    }

    @Test
    void comAssinanteConsultaDeImediatoEACadaDezSegundosComAJanelaAtiva() {
        Brinquedo t = new Brinquedo("t", false);
        Colecao<Integer> ouvinte = new Colecao<>();
        sondador.assinar(t.topico, ouvinte);

        relogio.executarVencidas();
        assertEquals(1, t.chamadas.get(), "de imediato");
        relogio.avancar(Duration.ofSeconds(35));
        assertEquals(4, t.chamadas.get(), "em 0, 10, 20 e 30 s");
        assertEquals(List.of(1), ouvinte.valores, "valor igual não é reentregue");
    }

    @Test
    void soltarAUltimaAssinaturaParaOTimer() {
        Brinquedo t = new Brinquedo("t", false);
        Inscricao a = sondador.assinar(t.topico, new Colecao<>());
        Inscricao b = sondador.assinar(t.topico, new Colecao<>());
        relogio.avancar(Duration.ofSeconds(25));
        assertEquals(3, t.chamadas.get(), "duas telas, UMA consulta");

        a.close();
        relogio.avancar(Duration.ofSeconds(20));
        assertEquals(5, t.chamadas.get(), "ainda há uma tela");

        b.close();
        b.close(); // idempotente
        int antes = t.chamadas.get();
        relogio.avancar(Duration.ofMinutes(5));
        assertEquals(antes, t.chamadas.get());
        assertEquals(0, relogio.pendentes());
    }

    @Test
    void janelaEscondidaSoRodaOTopicoQueAlimentaAFaixaDeCredito() {
        Brinquedo limites = new Brinquedo("limites", true);
        Brinquedo projetos = new Brinquedo("projetos", false);
        sondador.assinar(limites.topico, new Colecao<>());
        sondador.assinar(projetos.topico, new Colecao<>());
        relogio.avancar(Duration.ofSeconds(1));
        sondador.definirJanela(EstadoJanela.ESCONDIDA);
        int projetosAntes = projetos.chamadas.get();

        relogio.avancar(Duration.ofMinutes(5));

        assertEquals(projetosAntes, projetos.chamadas.get(), "projetos para com a janela escondida");
        assertTrue(limites.chamadas.get() >= 5, "limites segue a cada 60 s: " + limites.chamadas.get());
    }

    /** Com a janela minimizada, só /api/limites, a cada 60 s. */
    @Test
    void janelaMinimizadaContaComoEscondidaESoRodaOTopicoDeLimites() {
        Brinquedo limites = new Brinquedo("limites", true);
        Brinquedo projetos = new Brinquedo("projetos", false);
        sondador.assinar(limites.topico, new Colecao<>());
        sondador.assinar(projetos.topico, new Colecao<>());
        relogio.avancar(Duration.ofSeconds(1));
        assertTrue(projetos.chamadas.get() >= 1, "com a janela ativa os projetos são consultados");

        // O quadro minimizado continua "visível" para o Swing, mas ninguém está olhando.
        sondador.definirJanela(EstadoJanela.de(true, true, false));
        projetos.chamadas.set(0);
        limites.chamadas.set(0);
        relogio.avancar(Duration.ofMinutes(5));

        assertEquals(0, projetos.chamadas.get(), "minimizada: nada de /api/projetos");
        assertTrue(limites.chamadas.get() >= 4 && limites.chamadas.get() <= 6,
            "e /api/limites segue a cada 60 s: " + limites.chamadas.get());

        // Voltou do Dock: a última consulta de projetos é velha, então consulta já.
        sondador.definirJanela(EstadoJanela.de(true, false, true));
        relogio.executarVencidas();
        assertEquals(1, projetos.chamadas.get());
    }

    @Test
    void semFocoVaiParaSessentaSegundosESeguraSeAPreferenciaPedir() {
        Brinquedo t = new Brinquedo("t", false);
        sondador.assinar(t.topico, new Colecao<>());
        relogio.avancar(Duration.ofSeconds(1));
        sondador.definirJanela(EstadoJanela.SEM_FOCO);
        t.chamadas.set(0);
        t.momentos.clear();

        relogio.avancar(Duration.ofSeconds(185));
        assertEquals(3, t.chamadas.get(), "cadência ×6: uma a cada 60 s");

        sondador.definirCadenciaCheiaSemFoco(true);
        t.chamadas.set(0);
        relogio.avancar(Duration.ofSeconds(35));
        assertTrue(t.chamadas.get() >= 3, "com a preferência, sem foco continua a cada 10 s: " + t.chamadas.get());
    }

    @Test
    void voltarAoFocoConsultaNoPrazoDosDezSegundosDaUltima() {
        Brinquedo t = new Brinquedo("t", false);
        sondador.assinar(t.topico, new Colecao<>());
        relogio.avancar(Duration.ofSeconds(1));
        sondador.definirJanela(EstadoJanela.SEM_FOCO);
        relogio.avancar(Duration.ofSeconds(30)); // a última consulta foi há ~30 s; a próxima seria em 30 s
        int antes = t.chamadas.get();

        sondador.definirJanela(EstadoJanela.ATIVA);
        relogio.executarVencidas();

        assertEquals(antes + 1, t.chamadas.get(), "voltou ao foco com a última consulta vencida: consulta já");
    }

    @Test
    void soAvisaAsTelasQuandoOValorMuda() {
        Brinquedo t = new Brinquedo("t", false);
        Colecao<Integer> ouvinte = new Colecao<>();
        sondador.assinar(t.topico, ouvinte);
        relogio.avancar(Duration.ofSeconds(25));
        assertEquals(List.of(1), ouvinte.valores);

        t.valor = 2;
        relogio.avancar(Duration.ofSeconds(10));
        t.valor = 2;
        relogio.avancar(Duration.ofSeconds(10));
        assertEquals(List.of(1, 2), ouvinte.valores);
        assertEquals(Optional.of(2), sondador.ultimo(t.topico));
    }

    @Test
    void quemAssinaDepoisRecebeOUltimoValorNaHora() {
        Brinquedo t = new Brinquedo("t", false);
        sondador.assinar(t.topico, new Colecao<>());
        relogio.executarVencidas();
        t.valor = 7;
        relogio.avancar(Duration.ofSeconds(10));

        Colecao<Integer> tardio = new Colecao<>();
        sondador.assinar(t.topico, tardio);

        assertEquals(List.of(7), tardio.valores, "recebe o último valor conhecido logo na inscrição");
    }

    @Test
    void naoSobrepoeExecucoesEPedidoDeAtualizarRodaUmaVezMaisDepois() {
        List<Runnable> fila = new ArrayList<>();
        Sondador manual = new Sondador(relogio, fila::add, Runnable::run, e -> { });
        manual.definirApi(api);
        AtomicInteger chamadas = new AtomicInteger();
        Topico<Integer> t = Topico.de("lento", a -> chamadas.incrementAndGet(), DEZ, SESSENTA, false, null);
        manual.assinar(t, new Colecao<>());

        relogio.executarVencidas();                 // dispara; a consulta fica na fila do executor (ainda "rodando")
        assertEquals(1, fila.size());
        relogio.avancar(Duration.ofSeconds(3));     // o tempo passa enquanto ela não termina
        assertEquals(1, fila.size(), "não começou outra por cima");

        manual.atualizarAgora(t);                   // pedido enquanto roda: só marca
        manual.atualizarAgora(t);
        relogio.executarVencidas();                 // se o pedido virasse um disparo, a segunda consulta começaria já
        assertEquals(1, fila.size(), "o pedido durante a consulta não pode abrir outra por cima");
        assertEquals(0, chamadas.get(), "nenhuma consulta terminou ainda");

        fila.remove(0).run();                       // a primeira termina (3 s depois de começar: o intervalo NÃO venceu)
        relogio.executarVencidas();
        assertEquals(1, fila.size(), "exatamente UMA a mais, pedida durante a consulta, sem esperar os 10 s");
        fila.remove(0).run();
        relogio.executarVencidas();
        assertTrue(fila.isEmpty());
        assertEquals(2, chamadas.get());

        // Sem novo pedido, a próxima só vem quando o intervalo vence.
        relogio.avancar(Duration.ofSeconds(9));
        assertTrue(fila.isEmpty());
        relogio.avancar(Duration.ofSeconds(2));
        assertEquals(1, fila.size());
        manual.close();
    }

    @Test
    void semPedidoDeAtualizarOTiqueQueVenceComUmaConsultaEmCursoEPuladoEORitmoVolta() {
        List<Runnable> fila = new ArrayList<>();
        Sondador manual = new Sondador(relogio, fila::add, Runnable::run, e -> { });
        manual.definirApi(api);
        AtomicInteger chamadas = new AtomicInteger();
        Topico<Integer> t = Topico.de("lento", a -> chamadas.incrementAndGet(), DEZ, SESSENTA, false, null);
        manual.assinar(t, new Colecao<>());

        relogio.executarVencidas();                 // consulta 1 começa e emperra
        relogio.avancar(Duration.ofSeconds(35));    // passam 3 intervalos
        assertEquals(1, fila.size(), "um tique durante a consulta não abre outra");

        fila.remove(0).run();                       // termina muito depois do prazo: o intervalo já venceu
        relogio.executarVencidas();
        assertEquals(1, fila.size(), "venceu enquanto rodava: a próxima vem logo, UMA só");
        fila.remove(0).run();
        relogio.executarVencidas();
        assertTrue(fila.isEmpty());
        assertEquals(2, chamadas.get());
        manual.close();
    }

    @Test
    void tresFalhasSeguidasDobramOIntervaloAteSessentaSegundos() {
        Brinquedo t = new Brinquedo("t", false);
        t.falha = new ErroRede("sem rede", null);
        Colecao<Integer> ouvinte = new Colecao<>();
        sondador.assinar(t.topico, ouvinte);

        relogio.avancar(Duration.ofMinutes(10));

        List<Long> intervalos = new ArrayList<>();
        for (int i = 1; i < t.momentos.size(); i++) {
            intervalos.add(Duration.between(t.momentos.get(i - 1), t.momentos.get(i)).toSeconds());
        }
        // Falhas 1 e 2 mantêm 10 s; da 3ª em diante dobra: 20, 40, 60, 60...
        assertEquals(List.of(10L, 10L, 20L, 40L, 60L, 60L), intervalos.subList(0, 6), intervalos.toString());
        assertEquals(t.chamadas.get(), ouvinte.erros.size(), "cada falha chega à tela");
        assertEquals(t.chamadas.get(), errosDeConsulta.size());
    }

    @Test
    void muitasTentativasDoAgenteFazOTopicoEsperarOQueEleDisseSemMartelar() throws Exception {
        Brinquedo t = new Brinquedo("t", false);
        t.falha = new ErroApi.ServidorErro(429, br.com.labdesktop.util.Json.mapper()
            .readTree("{\"codigo\":\"muitas_tentativas\",\"aguarde_s\":45}"), "Muitas tentativas. Aguarde 45 s.");
        Colecao<Integer> ouvinte = new Colecao<>();
        sondador.assinar(t.topico, ouvinte);

        relogio.avancar(Duration.ofSeconds(100));

        // a primeira consulta em 0 s, a seguinte só depois dos 45 s pedidos (e não aos 10 s da cadência): duas em 100 s, não onze
        assertEquals(List.of(0L, 45L), t.momentos.stream().map(m -> Duration.between(t.momentos.get(0), m).toSeconds()).toList().subList(0, 2));
        assertEquals(3, t.chamadas.get(), "0 s, 45 s e 90 s");
        assertEquals(3, ouvinte.erros.size(), "cada resposta chega à tela, que mostra a frase de espera");

        // passado o bloqueio, o sucesso volta à cadência normal de 10 s
        t.falha = null;
        relogio.avancar(Duration.ofSeconds(60));
        t.momentos.clear();
        relogio.avancar(Duration.ofSeconds(25));
        assertEquals(List.of(10L), List.of(Duration.between(t.momentos.get(0), t.momentos.get(1)).toSeconds()));
    }

    @Test
    void umPedidoDeEsperaAbsurdoTemTetoDeCincoMinutos() throws Exception {
        Brinquedo t = new Brinquedo("t", false);
        t.falha = new ErroApi.ServidorErro(429, br.com.labdesktop.util.Json.mapper()
            .readTree("{\"codigo\":\"muitas_tentativas\",\"aguarde_s\":86400}"), "x");
        sondador.assinar(t.topico, new Colecao<>());
        relogio.avancar(Duration.ofMinutes(6));
        assertEquals(2, t.chamadas.get(), "0 s e 300 s");
    }

    @Test
    void umSucessoZeraAContagemDeFalhas() {
        Brinquedo t = new Brinquedo("t", false);
        t.falha = new ErroRede("sem rede", null);
        sondador.assinar(t.topico, new Colecao<>());
        relogio.avancar(Duration.ofSeconds(45)); // 0, 10, 20 (3 falhas), próxima em 20 s

        t.falha = null;
        relogio.avancar(Duration.ofSeconds(30));  // sucesso
        t.momentos.clear();
        relogio.avancar(Duration.ofSeconds(31));

        assertEquals(List.of(Duration.ofSeconds(10).getSeconds()),
            List.of(Duration.between(t.momentos.get(0), t.momentos.get(1)).toSeconds()), "voltou a 10 s");
    }

    @Test
    void guardaDeCalorDobraOsIntervalosAcimaDe85EVoltaAbaixoDe80() {
        Brinquedo t = new Brinquedo("t", false);
        t.temperatura = 86.0;
        sondador.assinar(t.topico, new Colecao<>());
        relogio.executarVencidas();
        assertTrue(sondador.quente());

        t.momentos.clear();
        relogio.avancar(Duration.ofSeconds(65));
        assertEquals(20L, Duration.between(t.momentos.get(0), t.momentos.get(1)).toSeconds(), "10 s dobrou para 20 s");

        t.temperatura = 82.0; // entre 80 e 85: histerese, continua quente
        relogio.avancar(Duration.ofSeconds(30));
        assertTrue(sondador.quente());

        t.temperatura = 79.0;
        relogio.avancar(Duration.ofSeconds(30));
        assertFalse(sondador.quente());
        t.momentos.clear();
        relogio.avancar(Duration.ofSeconds(35));
        assertEquals(10L, Duration.between(t.momentos.get(0), t.momentos.get(1)).toSeconds());
    }

    @Test
    void semApiNadaRodaEComApiNovaConsultaDeImediato() {
        Brinquedo t = new Brinquedo("t", false);
        sondador.assinar(t.topico, new Colecao<>());
        relogio.executarVencidas();
        sondador.definirApi(null); // conexão caiu
        int antes = t.chamadas.get();
        relogio.avancar(Duration.ofMinutes(2));
        assertEquals(antes, t.chamadas.get());
        assertEquals(0, relogio.pendentes());

        try (ClienteApi nova = new ClienteApi("teste", 2, null, PedidoDeCodigo2fa.NUNCA)) {
            sondador.definirApi(nova);
            relogio.executarVencidas();
            assertEquals(antes + 1, t.chamadas.get());
        }
    }

    @Test
    void resultadoDeUmaGeracaoAntigaDoTunelEDescartado() {
        List<Runnable> fila = new ArrayList<>();
        Sondador manual = new Sondador(relogio, fila::add, Runnable::run, e -> { });
        manual.definirApi(api);
        Colecao<Integer> ouvinte = new Colecao<>();
        Topico<Integer> t = Topico.de("t", a -> 99, DEZ, SESSENTA, false, null);
        manual.assinar(t, ouvinte);
        relogio.executarVencidas();                 // a consulta fica na fila, com a API antiga
        assertEquals(1, fila.size());

        manual.definirApi(null);                    // reconectou: a API antiga morreu...
        fila.remove(0).run();                       // ...e a consulta velha termina agora
        assertTrue(ouvinte.valores.isEmpty(), "valor de túnel antigo não vai para a tela");
        manual.close();
    }

    @Test
    void macDormindoNaoConsultaEAcordarConsultaJa() {
        Brinquedo t = new Brinquedo("t", true);
        sondador.assinar(t.topico, new Colecao<>());
        relogio.executarVencidas();
        int antes = t.chamadas.get();

        sondador.definirSuspenso(true);
        relogio.avancar(Duration.ofHours(1));
        assertEquals(antes, t.chamadas.get());

        sondador.definirSuspenso(false);
        relogio.executarVencidas();
        assertEquals(antes + 1, t.chamadas.get());
    }

    @Test
    void erroDeRedeEDeAutorizacaoChegamAoDonoDoSondador() {
        Brinquedo t = new Brinquedo("t", false);
        t.falha = new NaoAutorizado(null, "Confirme o código do autenticador.");
        sondador.assinar(t.topico, new Colecao<>());
        relogio.executarVencidas();
        assertEquals(1, errosDeConsulta.size());
        assertTrue(errosDeConsulta.get(0) instanceof NaoAutorizado);
    }

    @Test
    void ouvinteQueLancaNaoMataOPolling() {
        Brinquedo t = new Brinquedo("t", false);
        AtomicInteger bons = new AtomicInteger();
        sondador.assinar(t.topico, valor -> {
            throw new IllegalStateException("tela quebrada");
        });
        sondador.assinar(t.topico, valor -> bons.incrementAndGet());
        relogio.executarVencidas();
        t.valor = 5;
        relogio.avancar(Duration.ofSeconds(10));
        assertEquals(2, bons.get());
    }

    @Test
    void atualizarAgoraSoAfetaTopicosAssinados() {
        Brinquedo a = new Brinquedo("a", false);
        Brinquedo b = new Brinquedo("b", false);
        sondador.assinar(a.topico, new Colecao<>());
        relogio.executarVencidas();

        sondador.atualizarAgora(a.topico, b.topico);
        relogio.executarVencidas();

        assertEquals(2, a.chamadas.get());
        assertEquals(0, b.chamadas.get(), "b não tem ouvinte");
    }

    @Test
    void tabelaDeCadenciasDosTopicosDoContrato() {
        assertEquals(Optional.of(SESSENTA), Topico.LIMITES.intervalo(EstadoJanela.ESCONDIDA, false));
        assertEquals(Optional.of(SESSENTA), Topico.LIMITES.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(DEZ), Topico.PROJETOS.intervalo(EstadoJanela.ATIVA, false));
        assertEquals(Optional.of(SESSENTA), Topico.PROJETOS.intervalo(EstadoJanela.SEM_FOCO, false));
        assertEquals(Optional.of(DEZ), Topico.PROJETOS.intervalo(EstadoJanela.SEM_FOCO, true));
        assertEquals(Optional.empty(), Topico.PROJETOS.intervalo(EstadoJanela.ESCONDIDA, true));
        assertEquals(Optional.empty(), Topico.SERVIDOR.intervalo(EstadoJanela.ESCONDIDA, false));
        assertEquals(Optional.of(DEZ), Topico.detalhe("vendas").intervalo(EstadoJanela.ATIVA, false));
        assertEquals("detalhe:vendas", Topico.detalhe("vendas").chave());
        assertEquals("detalhe:lab", Topico.detalhe("lab").chave());
        assertNull(Topico.PROJETOS.temperaturaDe(null));
    }

    @Test
    void valorIgualNaoReentregaMasConfirmaALeituraParaOLidoAsNaoFicarVelho() {
        Brinquedo t = new Brinquedo("confirma", false);
        List<String> eventos = new ArrayList<>();
        sondador.assinar(t.topico, new OuvinteDeTopico<Integer>() {
            @Override
            public void aoAtualizar(Integer valor) {
                eventos.add("atualizou " + valor);
            }

            @Override
            public void aoConfirmar() {
                eventos.add("confirmou");
            }
        });
        relogio.executarVencidas();
        relogio.avancar(Duration.ofSeconds(20));
        assertEquals(List.of("atualizou 1", "confirmou", "confirmou"), eventos, "a 1ª leitura muda; as seguintes só confirmam");
        t.valor = 2;
        relogio.avancar(Duration.ofSeconds(10));
        assertEquals("atualizou 2", eventos.get(eventos.size() - 1), "mudou: avisa o valor novo, não confirma");
    }

    @Test
    void falhaNaoConfirmaNada() {
        Brinquedo t = new Brinquedo("falha-sem-confirmar", false);
        t.falha = new ErroRede("caiu", null);
        List<String> eventos = new ArrayList<>();
        sondador.assinar(t.topico, new OuvinteDeTopico<Integer>() {
            @Override
            public void aoConfirmar() {
                eventos.add("confirmou");
            }

            @Override
            public void aoAtualizar(Integer valor) {
                eventos.add("atualizou");
            }
        });
        relogio.executarVencidas();
        relogio.avancar(Duration.ofSeconds(10));
        assertTrue(eventos.isEmpty(), "só consulta que deu certo confirma: " + eventos);
    }
}
