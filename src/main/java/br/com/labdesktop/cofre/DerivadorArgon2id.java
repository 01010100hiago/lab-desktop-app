package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.Zeragem;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id v1.3 da API leve do BouncyCastle ({@code Argon2BytesGenerator}, sem registrar provider). Saída de
 * 32 bytes, a chave do AES-256 (ARQUITETURA 5.4).
 *
 * <p>Custa cerca de 1 s e 256 MiB de heap com os parâmetros de produção: NUNCA na EDT. O chamador roda isto
 * numa thread própria ({@code cofre-kdf}); ver {@link CofreDeChaves#emThreadDoKdf}.
 */
final class DerivadorArgon2id {

    static final int TAMANHO_CHAVE = 32;

    private DerivadorArgon2id() { }

    /**
     * Deriva a chave de 32 bytes. A senha em {@code char[]} vira bytes UTF-8 EXATAMENTE como digitada (sem
     * normalização Unicode, para não criar uma {@code String} da senha); a cópia em bytes é zerada aqui. O
     * {@code char[]} do chamador NÃO é zerado: é dele (o {@code JPasswordField} entrega e o diálogo zera).
     */
    static byte[] derivar(char[] senha, byte[] sal, ParametrosKdf parametros) {
        byte[] senhaBytes = senhaParaBytes(senha);
        try {
            Argon2Parameters.Builder construtor = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(parametros.iteracoes())
                .withMemoryAsKB(parametros.memoriaKib())
                .withParallelism(parametros.paralelismo())
                .withSalt(sal);
            Argon2BytesGenerator gerador = new Argon2BytesGenerator();
            gerador.init(construtor.build());
            byte[] chave = new byte[TAMANHO_CHAVE];
            gerador.generateBytes(senhaBytes, chave);
            return chave;
        } finally {
            Zeragem.zerar(senhaBytes);
        }
    }

    /** UTF-8 sem normalização. O buffer intermediário do encoder é zerado. */
    static byte[] senhaParaBytes(char[] senha) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(senha));
        try {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } finally {
            if (buffer.hasArray()) {
                Zeragem.zerar(buffer.array());
            }
        }
    }
}
