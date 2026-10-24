package br.com.labdesktop.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lê uma resposta por fluxo com TETO de tamanho (ARQUITETURA 6.5): um agente hostil ou com defeito não pode
 * provocar {@code OutOfMemoryError} mandando um corpo gigante. JSON da API: 2 MiB; logs: 8 MiB (o
 * {@code maxBuffer} do agente).
 */
public final class LeituraLimitada {

    /** Teto de uma resposta JSON do agente. */
    public static final int TETO_JSON = 2 * 1024 * 1024;
    /** Teto de uma resposta de texto (logs e tela): o {@code maxBuffer} do agente. */
    public static final int TETO_TEXTO = 8 * 1024 * 1024;

    /** O corpo passou do teto. */
    public static final class AcimaDoTeto extends IOException {
        private static final long serialVersionUID = 1L;

        public AcimaDoTeto(int teto) {
            super("A resposta passou do limite de " + teto + " bytes.");
        }
    }

    private LeituraLimitada() { }

    /**
     * Lê até o fim do fluxo.
     *
     * @throws AcimaDoTeto se houver mais de {@code teto} bytes (não lê o resto)
     * @throws IOException em falha de leitura (inclusive o fluxo fechado pelo vigia de prazo)
     */
    public static byte[] ler(InputStream entrada, int teto) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream(Math.min(8192, teto));
        byte[] bloco = new byte[8192];
        int total = 0;
        int n;
        while ((n = entrada.read(bloco)) != -1) {
            total += n;
            if (total > teto) {
                throw new AcimaDoTeto(teto);
            }
            saida.write(bloco, 0, n);
        }
        return saida.toByteArray();
    }
}
