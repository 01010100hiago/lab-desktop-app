package br.com.labdesktop.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O log de requisições do app (modo debug), que serve para PROVAR a carga no servidor: com a tela
 * visível, uma consulta de {@code /api/projetos} a cada cerca de 10 s; com a janela minimizada, só {@code /api/limites}
 * a cada 60 s. Cada chamada ao agente vira uma linha em {@code ~/Library/Logs/LabDesktop/lab-desktop.log}:
 *
 * <pre>[lab] GET /api/projetos -> 200 em 54 ms</pre>
 *
 * <p>Só método, rota (sem a query), status e tempo, nunca cabeçalho, token, corpo ou código de 2FA. Fica DESLIGADO por
 * padrão (nível {@code info}); liga com {@code -Dlabdesktop.debug=true} (no {@code ./gradlew run}:
 * {@code -Plabdesktop.debug=true}) ou com {@code LABDESKTOP_DEBUG=1} no ambiente. Usa um logger próprio
 * ({@value #NOME_DO_LOGGER}), então ligar isto não enche o log com o debug do resto do app.
 */
public final class LogDeRequisicoes {

    /** O nome do logger das requisições. */
    public static final String NOME_DO_LOGGER = "br.com.labdesktop.api.Requisicoes";
    /** A propriedade de sistema que liga o log. */
    public static final String PROPRIEDADE = "labdesktop.debug";
    /** A variável de ambiente que liga o log (qualquer valor diferente de vazio, {@code 0} e {@code false}). */
    public static final String VARIAVEL = "LABDESKTOP_DEBUG";

    private static final String PROPRIEDADE_DO_NIVEL = "org.slf4j.simpleLogger.log." + NOME_DO_LOGGER;

    private LogDeRequisicoes() { }

    /** {@code true} se o usuário pediu o log (propriedade ou variável de ambiente). */
    public static boolean solicitado() {
        return solicitado(System.getProperty(PROPRIEDADE), System.getenv(VARIAVEL));
    }

    static boolean solicitado(String propriedade, String variavel) {
        return ligado(propriedade) || ligado(variavel);
    }

    private static boolean ligado(String valor) {
        if (valor == null) {
            return false;
        }
        String v = valor.strip();
        return !v.isEmpty() && !v.equals("0") && !v.equalsIgnoreCase("false");
    }

    /**
     * Põe o logger das requisições em {@code debug} se o usuário pediu. Tem de rodar ANTES de o logger ser criado (o
     * {@code slf4j-simple} lê o nível uma vez, na criação): {@code ConfiguracaoDeLog.configurar} chama isto na partida.
     *
     * @return {@code true} se ligou
     */
    public static boolean ligarSeSolicitado() {
        if (!solicitado()) {
            return false;
        }
        System.setProperty(PROPRIEDADE_DO_NIVEL, "debug");
        return true;
    }

    /** Escreve a linha no logger das requisições (nível debug; não faz nada se estiver desligado). */
    public static void registrar(String linha) {
        Logger log = LoggerFactory.getLogger(NOME_DO_LOGGER);
        if (log.isDebugEnabled()) {
            log.debug(linha);
        }
    }

    /**
     * A linha de uma chamada. A rota entra SEM a query ({@code ?container=...}) e o nome do servidor sem caracteres de
     * controle (uma quebra de linha no nome não pode falsificar uma linha de log). Não há token nem código aqui: o
     * {@code util} não conhece o {@code Redator}, e a linha só tem o que o app mesmo montou.
     *
     * @param desfecho o status HTTP ("200") ou o que impediu a resposta ("sem resposta (HttpTimeoutException)")
     */
    public static String linha(String servidor, String metodo, String caminho, String desfecho, long milissegundos) {
        int q = caminho.indexOf('?');
        String rota = q >= 0 ? caminho.substring(0, q) : caminho;
        return "[" + semControles(servidor) + "] " + metodo + " " + semControles(rota) + " -> " + desfecho + " em "
            + milissegundos + " ms";
    }

    private static String semControles(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        return sb.toString();
    }
}
