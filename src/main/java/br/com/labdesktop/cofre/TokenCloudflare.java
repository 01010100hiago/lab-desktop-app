package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.Segredo;
import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.Zeragem;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * O token de API da Cloudflare do usuário (criado por ele no painel da Cloudflare). É segredo FORTE: com ele se mexe no DNS da conta. Vive no cofre
 * cifrado, nunca em log, arquivo, URL, mensagem de erro ou {@code toString}, e nunca vai ao servidor.
 *
 * <p>O texto fica num {@code byte[]} que o cofre zera ao bloquear (ou ao trocar o token). NÃO é {@code record} e o {@code toString} é redigido. Quem
 * digita o token entrega um {@code char[]} (do {@code JPasswordField}) a {@link #deChars(char[])} e o zera logo depois; quem o usa numa chamada
 * pede {@link #copia()}, usa e zera a cópia. O valor só vira {@code String} no cabeçalho {@code Authorization} de cada chamada
 * ({@link #cabecalhoAuthorization()}), o mesmo limite aceito para o token do agente (ARQUITETURA 5.5).
 *
 * <p>Um {@code TokenCloudflare} devolvido pelo cofre PERTENCE ao cofre: não chame {@code zerar()} nele (use {@link #copia()}).
 */
public final class TokenCloudflare implements Segredo {

    /** O formato de um token da Cloudflare: letras, números, hífen e sublinhado (hoje são 40 caracteres; os novos têm prefixo). */
    public static final int TAMANHO_MINIMO = 20;
    public static final int TAMANHO_MAXIMO = 160;

    private final byte[] texto;
    private volatile boolean zerado;

    private TokenCloudflare(byte[] texto) {
        this.texto = texto;
    }

    /** O erro de um token que não tem a forma de um token da Cloudflare (a frase não repete o que foi colado). */
    public static final class FormatoInvalidoException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        FormatoInvalidoException(String mensagem) {
            super(mensagem);
        }
    }

    /**
     * Valida o que o usuário colou e o copia para bytes. Tira espaços e quebras de linha das PONTAS (é comum copiar com uma quebra no fim), mas um
     * espaço no meio recusa. NÃO zera o {@code char[]} recebido: quem o digitou o zera.
     *
     * @throws FormatoInvalidoException vazio, curto, longo ou com caractere que um token não tem
     */
    public static TokenCloudflare deChars(char[] digitado) {
        if (digitado == null) {
            throw new FormatoInvalidoException("Cole o token da Cloudflare.");
        }
        int ini = 0;
        int fim = digitado.length;
        while (ini < fim && Character.isWhitespace(digitado[ini])) {
            ini++;
        }
        while (fim > ini && Character.isWhitespace(digitado[fim - 1])) {
            fim--;
        }
        int n = fim - ini;
        if (n == 0) {
            throw new FormatoInvalidoException("Cole o token da Cloudflare.");
        }
        if (n < TAMANHO_MINIMO || n > TAMANHO_MAXIMO) {
            throw new FormatoInvalidoException("Isto não parece um token da Cloudflare (ele tem letras, números, hífen e sublinhado, sem espaços). "
                + "Confira se copiou o token inteiro.");
        }
        byte[] bytes = new byte[n];
        try {
            for (int i = 0; i < n; i++) {
                char c = digitado[ini + i];
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
                if (!ok) {
                    throw new FormatoInvalidoException("Isto não parece um token da Cloudflare (ele tem só letras, números, hífen e sublinhado, sem espaços). "
                        + "Confira se copiou o token inteiro.");
                }
                bytes[i] = (byte) c;
            }
        } catch (RuntimeException e) {
            Zeragem.zerar(bytes);
            throw e;
        }
        return new TokenCloudflare(bytes);
    }

    /** Copia os bytes do texto já validado (para testes e para quem recebe de outro cofre). Não zera o array recebido. */
    public static TokenCloudflare deBytes(byte[] bytes) {
        char[] chars = new char[bytes.length];
        try {
            for (int i = 0; i < bytes.length; i++) {
                chars[i] = (char) (bytes[i] & 0xFF);
            }
            return deChars(chars);
        } finally {
            Zeragem.zerar(chars);
        }
    }

    /** Adota o array (sem copiar): vem do JSON do cofre; quem chama o entrega e não o usa mais. */
    static TokenCloudflare adotar(byte[] texto) {
        TokenCloudflare t = deBytes(texto);
        // o deBytes copiou e validou; o array do JSON é zerado aqui e o objeto passa a ter a própria cópia
        Zeragem.zerar(texto);
        return t;
    }

    /** O valor do cabeçalho HTTP de autorização. Cria uma {@code String} (não zerável): use só ao montar a chamada. */
    public synchronized String cabecalhoAuthorization() {
        conferirVivo();
        return "Bearer " + new String(texto, StandardCharsets.US_ASCII);
    }

    /** O texto do token (para um filtro que o tire de mensagens); uma {@code String} nova a cada chamada. */
    public synchronized String valorParaFiltro() {
        conferirVivo();
        return new String(texto, StandardCharsets.US_ASCII);
    }

    /** Cópia independente (zerar uma não zera a outra). */
    public synchronized TokenCloudflare copia() {
        conferirVivo();
        return new TokenCloudflare(texto.clone());
    }

    /** O array interno, para o cofre serializar. Não vaze. */
    synchronized byte[] bytesInternos() {
        conferirVivo();
        return texto;
    }

    /** Quantos caracteres tem (sem revelar nada mais). */
    public synchronized int tamanho() {
        return texto.length;
    }

    private void conferirVivo() {
        if (zerado) {
            throw new SegredoZeradoException("O token da Cloudflare já foi zerado.");
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
        return "TokenCloudflare[redigido]";
    }
}
