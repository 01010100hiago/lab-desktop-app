package br.com.labdesktop.api;

import java.time.Duration;

/**
 * As rotas do agente que o app chama e o prazo de cada uma (CONTRATO-API seção 2, coluna "Timeout"). O agente
 * impõe prazos próprios por dentro; estes são o que o CLIENTE espera antes de desistir.
 *
 * <p>Os prazos longos existem por causa do que o agente faz: abrir ou reabrir sessão e Retomar fazem
 * {@code pg_dump} de até 5 min antes do {@code docker run} (6 min); iniciar, parar e reiniciar projeto rodam o
 * compose de CADA arquivo, com 10 min cada, em sequência (12 min); reiniciar container tem 120 s no agente
 * (3 min). Estourar o prazo de uma ação NÃO marca falha: o resultado é incerto e a tela consulta o estado.
 */
public enum Rota {
    VERSAO(Duration.ofSeconds(5)),
    SERVIDOR(Duration.ofSeconds(20)),
    SERVIDOR_DETALHE(Duration.ofSeconds(20)),
    LIMITES(Duration.ofSeconds(15)),
    TOKENS(Duration.ofSeconds(15)),
    TOKENS_DO_PROJETO(Duration.ofSeconds(15)),
    PROJETOS(Duration.ofSeconds(20)),
    DETALHE_DO_PROJETO(Duration.ofSeconds(20)),
    LOGS(Duration.ofSeconds(25)),
    TELA(Duration.ofSeconds(10)),
    TEXTO(Duration.ofSeconds(15)),
    NOVO_PROJETO(Duration.ofSeconds(60)),
    COMPOSE(Duration.ofMinutes(12)),
    REINICIAR_CONTAINER(Duration.ofMinutes(3)),
    ABRIR_SESSAO(Duration.ofMinutes(6)),
    FECHAR_SESSAO(Duration.ofSeconds(60)),
    REABRIR_SESSAO(Duration.ofMinutes(6)),
    TECLAS(Duration.ofSeconds(15)),
    CODIGO_DE_LOGIN(Duration.ofSeconds(30)),
    DEFINIR_LIMITE(Duration.ofSeconds(30)),
    RETOMAR(Duration.ofMinutes(6)),
    FREIO(Duration.ofSeconds(30)),
    CONFIRMAR_2FA(Duration.ofSeconds(15));

    private final Duration prazoPadrao;

    Rota(Duration prazoPadrao) {
        this.prazoPadrao = prazoPadrao;
    }

    /** O prazo do contrato. */
    public Duration prazoPadrao() {
        return prazoPadrao;
    }
}
