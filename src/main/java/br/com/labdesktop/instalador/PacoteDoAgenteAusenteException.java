package br.com.labdesktop.instalador;

import java.io.IOException;

/**
 * O app foi compilado sem o pacote do agente de monitoramento. A mensagem já é o texto que o usuário lê (sem jargão de build).
 */
public final class PacoteDoAgenteAusenteException extends IOException {

    /** O que a tela diz quando não há pacote do agente neste app. */
    public static final String TEXTO = "Esta versão do app foi compilada sem o agente de monitoramento, então não dá para instalá-lo daqui. "
        + "Terminal, arquivos e segurança continuam funcionando.";

    public PacoteDoAgenteAusenteException() {
        super(TEXTO);
    }
}
