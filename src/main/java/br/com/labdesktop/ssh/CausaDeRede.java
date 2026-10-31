package br.com.labdesktop.ssh;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Por que uma conexão de rede não chegou ao servidor, em categorias que a tela sabe explicar. Quem decide é o TIPO da exceção (e das causas encadeadas: o MINA embrulha o erro de rede em
 * {@code SshException} e {@code IOException}), nunca o texto da mensagem: o texto é do sistema e do idioma ("Connection refused" no Linux e no Mac, "The remote computer refused the
 * network connection" no Windows em inglês, outra frase em português ou espanhol).
 *
 * <p>No Windows o Java entrega uma recusa de conexão como um {@link java.io.IOException} COMUM (nem {@link ConnectException}): sem tipo para olhar. Por isso, ainda na fase de CONECTAR
 * (antes de existir sessão), uma {@code IOException} comum sem nenhuma outra causa conhecida vale "recusou": uma falha imediata de conexão que não foi tempo esgotado, nome desconhecido
 * nem falta de rota. Depois de a sessão existir, essa regra não vale.
 *
 * <p>A única leitura de texto que sobra separa, dentro de uma {@link ConnectException}, o "tempo esgotado" da "recusa" (os dois têm o mesmo tipo em alguns sistemas) e reconhece "rede
 * inalcançável" onde o sistema não dá tipo próprio; se o texto vier em outro idioma, cai na categoria mais comum (recusou), sem quebrar nada.
 */
public enum CausaDeRede {
    RECUSOU, TEMPO_ESGOTADO, NOME_NAO_ENCONTRADO, SEM_ROTA, OUTRA;

    private static final int PROFUNDIDADE_MAXIMA = 10;

    /**
     * @param erro        o que a conexão lançou (com as causas)
     * @param aoConectar  {@code true} se a falha veio de abrir a conexão (ainda sem sessão); {@code false} depois disso
     */
    public static CausaDeRede de(Throwable erro, boolean aoConectar) {
        CausaDeRede pelaCadeia = peloTipoNaCadeia(erro);
        if (pelaCadeia != OUTRA) {
            return pelaCadeia;
        }
        CausaDeRede peloTexto = peloTextoNaCadeia(erro);
        if (peloTexto != OUTRA) {
            return peloTexto;
        }
        if (aoConectar && temIoExceptionComum(erro)) {
            return RECUSOU;
        }
        return OUTRA;
    }

    private static CausaDeRede peloTipoNaCadeia(Throwable erro) {
        int fundo = 0;
        for (Throwable t = erro; t != null && fundo < PROFUNDIDADE_MAXIMA; t = t.getCause() == t ? null : t.getCause(), fundo++) {
            if (t instanceof ConnectException) {
                String texto = minusculas(t);
                return texto.contains("timed out") || texto.contains("timeout") ? TEMPO_ESGOTADO : RECUSOU;
            }
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return NOME_NAO_ENCONTRADO;
            }
            if (t instanceof NoRouteToHostException || t instanceof PortUnreachableException) {
                return SEM_ROTA;
            }
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException || t instanceof InterruptedByTimeoutException) {
                return TEMPO_ESGOTADO;
            }
        }
        return OUTRA;
    }

    private static CausaDeRede peloTextoNaCadeia(Throwable erro) {
        int fundo = 0;
        for (Throwable t = erro; t != null && fundo < PROFUNDIDADE_MAXIMA; t = t.getCause() == t ? null : t.getCause(), fundo++) {
            String texto = minusculas(t);
            if (texto.contains("connection refused")) {
                return RECUSOU;
            }
            if (texto.contains("no route to host") || texto.contains("network is unreachable") || texto.contains("host is down") || texto.contains("network is down")) {
                return SEM_ROTA;
            }
            if (texto.contains("timed out") || texto.contains("specified timeout") || texto.contains("timeout")) {
                return TEMPO_ESGOTADO;
            }
            if (texto.contains("unknownhost") || texto.contains("unresolved") || texto.contains("name or service not known") || texto.contains("nodename nor servname")) {
                return NOME_NAO_ENCONTRADO;
            }
        }
        return OUTRA;
    }

    /** Alguma exceção da cadeia é exatamente {@code java.io.IOException} (não uma subclasse como {@code SshException}, {@code EOFException} ou {@code SocketException}). */
    private static boolean temIoExceptionComum(Throwable erro) {
        int fundo = 0;
        for (Throwable t = erro; t != null && fundo < PROFUNDIDADE_MAXIMA; t = t.getCause() == t ? null : t.getCause(), fundo++) {
            if (t.getClass() == java.io.IOException.class) {
                return true;
            }
        }
        return false;
    }

    private static String minusculas(Throwable t) {
        return String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
    }
}
