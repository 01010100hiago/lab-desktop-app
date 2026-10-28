package br.com.labdesktop.teste;

import br.com.labdesktop.instalador.PacoteDoAgente;

/**
 * Diz se os testes estão usando o pacote do agente de VERDADE (montado por {@code ./gradlew empacotarAgente} em
 * {@code src/main/resources/agente}) ou o SINTÉTICO de teste ({@code src/test/resources/agente-teste}, que não é o agente). Os testes que
 * conferem o conteúdo do agente real se ignoram, com o motivo certo, quando o pacote é o sintético.
 */
public final class PacoteDeTeste {

    private PacoteDeTeste() { }

    /** {@code true} se o jar de produção (src/main/resources) leva o descritor do agente. */
    public static boolean temPacoteDeVerdade() {
        return PacoteDoAgente.class.getResource("/agente/agente.json") != null;
    }

    /** {@code true} quando NÃO há pacote de verdade: os testes que precisam do agente real devem se ignorar. */
    public static boolean ehSintetico() {
        return !temPacoteDeVerdade();
    }
}
