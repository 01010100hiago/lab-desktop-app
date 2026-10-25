package br.com.labdesktop.servidor;

import br.com.labdesktop.api.modelo.TelaClaude;

/**
 * O que a aba "Sessão do Claude" recebe do tópico da tela: a tela analisada, ou {@code null} quando o agente diz que a sessão
 * não está aberta (404, fluxo normal). Existe para a "sessão fechada" ser um VALOR do tópico e não uma falha.
 */
public record VisaoDaTela(TelaClaude tela) {

    /** A sessão não está aberta no servidor. */
    public static VisaoDaTela fechada() {
        return new VisaoDaTela(null);
    }

    public boolean estaFechada() {
        return tela == null;
    }
}
