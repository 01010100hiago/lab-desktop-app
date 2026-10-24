package br.com.labdesktop.api;

import java.util.Optional;

/**
 * O callback do 2FA (ARQUITETURA 10.1): o {@code ClienteApi} chama isto quando o agente responde 401
 * o pedido de código, e quem implementa (a {@code Aplicacao}) abre o {@code DialogoCodigo2fa} na EDT e
 * devolve o que o usuário digitou. Fica em {@code api} para o domínio não conhecer Swing.
 *
 * <p>É BLOQUEANTE e chamado numa thread virtual do {@code ClienteApi}, NUNCA na EDT: quem implementa despacha o
 * diálogo por {@code Fundo.naEdt} e espera o resultado num {@code CompletableFuture}.
 *
 * <p>O código digitado nunca vai para log, cofre ou preferências.
 */
@FunctionalInterface
public interface PedidoDeCodigo2fa {

    /**
     * Pede os seis dígitos do autenticador.
     *
     * @param servidor nome do servidor, para o título do diálogo
     * @param aviso    {@code null} no primeiro pedido; depois, o motivo do novo pedido ("Código incorreto ou já usado.")
     * @return o texto digitado (pode ter o espaço do meio: "123 456"), ou vazio se o usuário cancelou
     */
    Optional<String> pedir(String servidor, String aviso);

    /**
     * Variante para quando o agente bloqueou novas tentativas por um tempo (bloqueio temporário): o
     * diálogo mostra "Aguarde N s" e mantém o botão desabilitado até acabar. Por padrão cai no pedido comum.
     */
    default Optional<String> pedir(String servidor, String aviso, int aguardeSegundos) {
        return pedir(servidor, aviso);
    }

    /** Um pedido que sempre cancela: vale quando não há quem pergunte (testes, uso sem interface). */
    PedidoDeCodigo2fa NUNCA = (servidor, aviso) -> Optional.empty();
}
