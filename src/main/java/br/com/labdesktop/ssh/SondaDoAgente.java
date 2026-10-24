package br.com.labdesktop.ssh;

/**
 * O teste de saúde do agente que a {@link ConexaoSsh} pede depois de o SSH e o túnel subirem: um
 * {@code GET /api/versao}. Quem a implementa mora em {@code servidor} (que conhece o {@code ClienteApi}): o
 * pacote {@code ssh} não depende de {@code api}.
 *
 * <p>É chamada numa thread virtual, nunca no ator da conexão; pode bloquear (inclusive esperando o usuário digitar
 * o código do 2FA).
 */
@FunctionalInterface
public interface SondaDoAgente {

    /** Veredito da sonda. */
    enum Resultado {
        /** 200: o agente respondeu e o token vale. */
        OK,
        /** Sem resposta utilizável (erro de rede, 5xx, resposta ilegível): o agente está fora ou reiniciando. */
        AGENTE_FORA,
        /**
         * 404 em {@code GET /api/versao}: o agente responde, mas é de antes do app nativo (falta o deploy do painel
         * com o acesso do app). Fica em {@code SSH_OK_SEM_AGENTE}, como "agente fora", mas com a mensagem
         * certa: sem ela o usuário procuraria um agente parado que está de pé.
         */
        AGENTE_DESATUALIZADO,
        /** 401 sem {@code codigo}: token errado ou revogado. */
        TOKEN_RECUSADO,
        /** 401 o pedido de código e o usuário cancelou (ou errou até desistir): falta o código do autenticador. */
        REQUER_2FA
    }

    Resultado sondar(TunelSsh tunel) throws InterruptedException;
}
