package br.com.labdesktop.terminal;

import br.com.labdesktop.seguranca.TextoSeguro;

/**
 * O título que o programa remoto manda por OSC ({@code ESC ] 0 ; título BEL}) é texto do servidor, não confiável: antes de virar nome de
 * aba perde controles e caracteres bidi, não pode começar como HTML e é cortado (ARQUITETURA 7.4).
 */
public final class TituloDeAba {

    public static final int MAXIMO = 32;

    private TituloDeAba() { }

    /** O título limpo e curto, ou {@code padrao} se não sobrou nada legível. */
    public static String limpar(String titulo, String padrao) {
        String limpo = TextoSeguro.limparLinha(titulo);
        if (limpo.isBlank()) {
            return padrao;
        }
        return TextoSeguro.truncar(limpo, MAXIMO);
    }
}
