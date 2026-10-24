package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.Segredo;
import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.Zeragem;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * O segredo do 2FA recém-gerado pelo agente (CONTRATO-API seção 6): o texto base32 e a URI {@code otpauth://} que
 * o diálogo "Configurar 2FA" desenha como QR. Existe na tela só enquanto o diálogo está aberto; ao fechar, quem
 * o criou chama {@link #zerar()} (ARQUITETURA 10).
 *
 * <p>NÃO é {@code record} e o {@code toString} é redigido (ARQUITETURA 4.3). Limite aceito (5.5): quem pede o
 * texto ({@link #segredoEmGrupos()}, {@link #uri()}) recebe uma {@code String}, que não se zera; o QR vira um
 * {@code BufferedImage}, que também não. A mitigação é o tempo de vida curto.
 */
public final class SegredoTotp implements Segredo {

    private static final Pattern BASE32 = Pattern.compile("^[A-Z2-7]{16,64}$");
    private static final String PREFIXO_URI = "otpauth://totp/";

    private final byte[] segredo;
    private final byte[] uri;
    private volatile boolean zerado;

    private SegredoTotp(byte[] segredo, byte[] uri) {
        this.segredo = segredo;
        this.uri = uri;
    }

    /**
     * Valida e guarda. Lança {@link IllegalArgumentException} se o formato não é o do agente (base32 de 16 a 64
     * caracteres; URI {@code otpauth://totp/...}).
     */
    public static SegredoTotp de(String base32, String uriOtpauth) {
        if (base32 == null || !BASE32.matcher(base32).matches()) {
            throw new IllegalArgumentException("Segredo do 2FA em formato inválido.");
        }
        if (uriOtpauth == null || !uriOtpauth.startsWith(PREFIXO_URI) || uriOtpauth.length() > 512
            || uriOtpauth.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new IllegalArgumentException("URI do 2FA em formato inválido.");
        }
        return new SegredoTotp(base32.getBytes(StandardCharsets.US_ASCII), uriOtpauth.getBytes(StandardCharsets.UTF_8));
    }

    /** O segredo base32 corrido (32 caracteres no agente). */
    public synchronized String segredo() {
        conferirVivo();
        return new String(segredo, StandardCharsets.US_ASCII);
    }

    /** O segredo em grupos de 4 separados por espaço, para quem digita à mão no autenticador. */
    public synchronized String segredoEmGrupos() {
        conferirVivo();
        StringBuilder sb = new StringBuilder(segredo.length + segredo.length / 4);
        for (int i = 0; i < segredo.length; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append((char) segredo[i]);
        }
        return sb.toString();
    }

    /** A URI {@code otpauth://totp/Painel%20do%20Lab:<servidor>?secret=...&issuer=Painel%20do%20Lab} que vira QR. */
    public synchronized String uri() {
        conferirVivo();
        return new String(uri, StandardCharsets.UTF_8);
    }

    private void conferirVivo() {
        if (zerado) {
            throw new SegredoZeradoException("O segredo do 2FA já foi zerado.");
        }
    }

    @Override
    public synchronized void zerar() {
        zerado = true;
        Zeragem.zerar(segredo);
        Zeragem.zerar(uri);
    }

    @Override
    public synchronized boolean estaZerado() {
        return Zeragem.todoZero(segredo) && Zeragem.todoZero(uri);
    }

    @Override
    public String toString() {
        return "SegredoTotp[redigido]";
    }
}
