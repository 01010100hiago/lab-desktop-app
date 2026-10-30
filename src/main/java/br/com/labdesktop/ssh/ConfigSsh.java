package br.com.labdesktop.ssh;

import java.time.Duration;

/**
 * Tempos e limites da camada SSH (ARQUITETURA 6.1 e 6.3). Os valores de {@link #padrao()} são os do doc; os
 * testes usam {@link #rapida()} para exercitar queda, backoff e reconexão em segundos em vez de minutos.
 *
 * @param conectar               prazo do connect TCP + versão SSH
 * @param autenticar             prazo do key exchange + userauth (inclui o verificador de chave do host)
 * @param abrirCanal             prazo para abrir canal exec ou forward
 * @param heartbeat              intervalo do keepalive do MINA ({@code keepalive@openssh.com} com resposta)
 * @param heartbeatSemRespostaMax quantos keepalives sem resposta derrubam a sessão (10 s x 3 = cerca de 40 s)
 * @param verificacaoAtiva       prazo da pergunta avulsa de {@code verificarAgora()} (5 s)
 * @param esperaAposAcordar      folga para a rede voltar depois de o Mac acordar (2 s)
 * @param esperaErroAutenticacao quanto esperar antes da UMA nova tentativa após chave recusada (5 s)
 * @param intervaloSemAgente     de quanto em quanto tempo insistir em {@code GET /api/versao} com o SSH de pé
 * @param estavelApos            tempo conectado que zera o backoff (60 s)
 * @param escalaBackoff          unidade dos degraus 1, 2, 4, 8, 15, 30, 60 do backoff (1 s)
 * @param falhasAteORitmoLento   quantas falhas SEGUIDAS (sem nenhuma conexão boa no meio) levam ao ritmo lento (12: cerca de 7 minutos no teto de 60 s)
 * @param esperaNoRitmoLento     de quanto em quanto tempo tenta de novo no ritmo lento (5 min, com jitter de 20 %)
 */
public record ConfigSsh(
    Duration conectar,
    Duration autenticar,
    Duration abrirCanal,
    Duration heartbeat,
    int heartbeatSemRespostaMax,
    Duration verificacaoAtiva,
    Duration esperaAposAcordar,
    Duration esperaErroAutenticacao,
    Duration intervaloSemAgente,
    Duration estavelApos,
    Duration escalaBackoff,
    int falhasAteORitmoLento,
    Duration esperaNoRitmoLento) {

    /** Quantas falhas seguidas levam ao ritmo lento. */
    public static final int FALHAS_ATE_O_RITMO_LENTO = 12;
    /** A espera do ritmo lento. */
    public static final Duration ESPERA_NO_RITMO_LENTO = Duration.ofMinutes(5);

    public ConfigSsh {
        if (heartbeatSemRespostaMax < 1) {
            throw new IllegalArgumentException("heartbeatSemRespostaMax < 1");
        }
        if (falhasAteORitmoLento < 1) {
            throw new IllegalArgumentException("falhasAteORitmoLento < 1");
        }
    }

    /** Sem o ritmo lento por parâmetro: vale o padrão (12 falhas seguidas, depois uma tentativa a cada 5 minutos). */
    public ConfigSsh(Duration conectar, Duration autenticar, Duration abrirCanal, Duration heartbeat, int heartbeatSemRespostaMax, Duration verificacaoAtiva, Duration esperaAposAcordar,
                     Duration esperaErroAutenticacao, Duration intervaloSemAgente, Duration estavelApos, Duration escalaBackoff) {
        this(conectar, autenticar, abrirCanal, heartbeat, heartbeatSemRespostaMax, verificacaoAtiva, esperaAposAcordar, esperaErroAutenticacao, intervaloSemAgente, estavelApos, escalaBackoff,
            FALHAS_ATE_O_RITMO_LENTO, ESPERA_NO_RITMO_LENTO);
    }

    /** Os valores de produção. */
    public static ConfigSsh padrao() {
        return new ConfigSsh(
            Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(10),
            Duration.ofSeconds(10), 3, Duration.ofSeconds(5),
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(60), Duration.ofSeconds(1));
    }

    /** Tudo na casa das centenas de milissegundos, para teste de integração. */
    public static ConfigSsh rapida() {
        return new ConfigSsh(
            Duration.ofSeconds(5), Duration.ofSeconds(8), Duration.ofSeconds(5),
            Duration.ofMillis(300), 2, Duration.ofMillis(800),
            Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(200),
            Duration.ofSeconds(60), Duration.ofMillis(40));
    }

    public ConfigSsh comHeartbeat(Duration intervalo, int semRespostaMax) {
        return new ConfigSsh(conectar, autenticar, abrirCanal, intervalo, semRespostaMax, verificacaoAtiva,
            esperaAposAcordar, esperaErroAutenticacao, intervaloSemAgente, estavelApos, escalaBackoff, falhasAteORitmoLento, esperaNoRitmoLento);
    }

    /** Muda quando começa o ritmo lento e de quanto em quanto tempo ele tenta (os testes e o roteiro de prova usam valores pequenos). */
    public ConfigSsh comRitmoLento(int falhasSeguidas, Duration espera) {
        return new ConfigSsh(conectar, autenticar, abrirCanal, heartbeat, heartbeatSemRespostaMax, verificacaoAtiva,
            esperaAposAcordar, esperaErroAutenticacao, intervaloSemAgente, estavelApos, escalaBackoff, falhasSeguidas, espera);
    }

    public ConfigSsh comEstavelApos(Duration novo) {
        return new ConfigSsh(conectar, autenticar, abrirCanal, heartbeat, heartbeatSemRespostaMax, verificacaoAtiva,
            esperaAposAcordar, esperaErroAutenticacao, intervaloSemAgente, novo, escalaBackoff, falhasAteORitmoLento, esperaNoRitmoLento);
    }
}
