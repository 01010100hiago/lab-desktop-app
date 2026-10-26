package br.com.labdesktop.ssh;

import java.io.IOException;

/**
 * O servidor SSH recusou abrir mais um canal de sessão ({@code administratively prohibited}): o {@code MaxSessions} dele (10 no
 * OpenSSH padrão) acabou. Reconectar não resolve; a tela precisa dizer isso com todas as letras.
 */
public final class LimiteDeSessoesException extends IOException {

    private static final long serialVersionUID = 1L;

    public LimiteDeSessoesException(Throwable causa) {
        super("O servidor não aceita mais terminais abertos ao mesmo tempo (limite de sessões do SSH). "
            + "Feche um terminal ou uma aba de arquivos e tente de novo.", causa);
    }
}
