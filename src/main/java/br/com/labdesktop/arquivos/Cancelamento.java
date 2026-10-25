package br.com.labdesktop.arquivos;

import java.util.concurrent.atomic.AtomicBoolean;

/** Um pedido de cancelamento que a thread que transfere confere entre um bloco e outro. */
public final class Cancelamento {

    /** Nunca cancela (leituras curtas). */
    public static final Cancelamento NUNCA = new Cancelamento();

    private final AtomicBoolean cancelado = new AtomicBoolean();

    public void cancelar() {
        cancelado.set(true);
    }

    public boolean cancelado() {
        return cancelado.get();
    }

    /** @throws ErroSftp {@code CANCELADO} se já foi pedido */
    public void conferir() throws ErroSftp {
        if (cancelado.get()) {
            throw ErroSftp.cancelado();
        }
    }
}
