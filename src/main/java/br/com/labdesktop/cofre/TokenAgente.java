package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.Segredo;
import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.Zeragem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token do cliente nativo para o agente (CONTRATO-API seção 6): texto aleatório em base64url (32
 * bytes aleatórios). Gerado pelo app e guardado no cofre cifrado.
 *
 * <p>O texto fica num {@code byte[]} que o cofre zera ao bloquear. NÃO é {@code record} e o {@code toString}
 * é redigido. O valor sai daqui em duas formas, de propósito: o cabeçalho {@code Authorization} (uma
 * {@code String} que o {@code ClienteApi} guarda enquanto a geração do túnel viver, limite aceito em
 * ARQUITETURA 5.5) e o hash em hexadecimal (o que vai por stdin para o agente).
 *
 * <p>Um {@code TokenAgente} devolvido pelo cofre PERTENCE ao cofre: não chame {@code zerar()} nem
 * {@code close()} nele (use {@link #copia()} se precisar de um seu).
 */
public final class TokenAgente implements Segredo {

    public static final String PREFIXO = "lab_";
    /** 4 do prefixo mais 43 do base64url de 32 bytes. */
    public static final int TAMANHO = 47;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final byte[] texto;
    private volatile boolean zerado;

    private TokenAgente(byte[] texto) {
        this.texto = texto;
    }

    /** Token novo: 32 bytes do {@code SecureRandom}. */
    public static TokenAgente gerar() {
        byte[] aleatorio = new byte[32];
        ALEATORIO.nextBytes(aleatorio);
        byte[] b64 = Base64.getUrlEncoder().withoutPadding().encode(aleatorio);
        byte[] texto = new byte[TAMANHO];
        System.arraycopy(PREFIXO.getBytes(StandardCharsets.US_ASCII), 0, texto, 0, PREFIXO.length());
        System.arraycopy(b64, 0, texto, PREFIXO.length(), b64.length);
        Zeragem.zerar(aleatorio);
        Zeragem.zerar(b64);
        return new TokenAgente(texto);
    }

    /** Copia e valida o texto do token. Não zera o array recebido. */
    public static TokenAgente deBytes(byte[] texto) {
        validar(texto);
        return new TokenAgente(texto.clone());
    }

    /** Adota o array (sem copiar): quem chama o entrega e não o usa mais. Vem do JSON do cofre. */
    static TokenAgente adotar(byte[] texto) {
        validar(texto);
        return new TokenAgente(texto);
    }

    private static void validar(byte[] t) {
        if (t == null || t.length != TAMANHO) {
            throw new IllegalArgumentException("Token do agente com tamanho inválido.");
        }
        for (int i = 0; i < t.length; i++) {
            char c = (char) (t[i] & 0xFF);
            boolean ok = i < PREFIXO.length()
                ? c == PREFIXO.charAt(i)
                : (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("Token do agente com formato inválido.");
            }
        }
    }

    /** O valor do cabeçalho HTTP de identificação. Cria uma {@code String} (não zerável). */
    public synchronized String cabecalhoAuthorization() {
        conferirVivo();
        return "Bearer " + new String(texto, StandardCharsets.US_ASCII);
    }

    /** Hash do texto inteiro (bytes ASCII) em hexadecimal minúsculo, 64 caracteres. */
    public synchronized String hashSha256Hex() {
        conferirVivo();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    /** Cópia independente (zerar uma não zera a outra). */
    public synchronized TokenAgente copia() {
        conferirVivo();
        return new TokenAgente(texto.clone());
    }

    /** O array interno, para o cofre serializar. Não vaze. */
    synchronized byte[] bytesInternos() {
        conferirVivo();
        return texto;
    }

    private void conferirVivo() {
        if (zerado) {
            throw new SegredoZeradoException("O token do agente já foi zerado.");
        }
    }

    @Override
    public synchronized void zerar() {
        zerado = true;
        Arrays.fill(texto, (byte) 0);
    }

    @Override
    public synchronized boolean estaZerado() {
        return Zeragem.todoZero(texto);
    }

    @Override
    public String toString() {
        return "TokenAgente[redigido]";
    }
}
