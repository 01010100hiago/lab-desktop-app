package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.Zeragem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;

/**
 * Gera pares Ed25519 para o cofre (ARQUITETURA 5.8).
 *
 * <p>Usa {@code KeyUtils.generateKeyPair("ssh-ed25519", 256)} do MINA e NÃO o {@code KeyPairGenerator} do JDK:
 * o MINA 2.20.0 não decodifica a chave EdDSA do JDK e só suporta Ed25519 com o BouncyCastle. A privada sai
 * serializada no formato {@code BEGIN OPENSSH PRIVATE KEY} sem passphrase, direto para memória.
 */
public final class GeradorDeChaves {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private GeradorDeChaves() { }

    /**
     * Gera uma chave nova.
     *
     * @param nome     nome mostrado ao usuário ("lab")
     * @param nomeDoMac usado no comentário da pública: {@code lab-desktop@<nomeDoMac>}
     */
    public static ChaveSsh gerar(String nome, String nomeDoMac) throws CofreException {
        String nomeLimpo = TextoSeguro.limparLinha(nome);
        if (nomeLimpo.isEmpty() || nomeLimpo.length() > 64) {
            throw new IllegalArgumentException("O nome da chave deve ter de 1 a 64 caracteres.");
        }
        try {
            KeyPair par = KeyUtils.generateKeyPair(ChaveSsh.ALGORITMO_ED25519, 256);
            return empacotar(novoId(), nomeLimpo, par, comentarioDo(nomeDoMac), Instant.now());
        } catch (GeneralSecurityException | IOException e) {
            throw new CofreException("Não consegui gerar a chave Ed25519.", e);
        }
    }

    /** Serializa o par no formato do cofre. Compartilhado com o {@link ImportadorDeChaves}. */
    static ChaveSsh empacotar(String id, String nome, KeyPair par, String comentario, Instant criadaEm)
        throws IOException, GeneralSecurityException {
        SaidaZeravel saida = new SaidaZeravel();
        try {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(par, comentario, null, saida);
            byte[] pem = saida.copiaExata();
            String publica = PublicKeyEntry.toString(par.getPublic()) + " " + comentario;
            String impressao = KeyUtils.getFingerPrint(par.getPublic());
            return new ChaveSsh(id, nome, ChaveSsh.ALGORITMO_ED25519, publica, impressao, criadaEm, pem);
        } finally {
            saida.zerar();
        }
    }

    static String comentarioDo(String nomeDoMac) {
        String mac = nomeDoMac == null || nomeDoMac.isBlank() ? "mac" : TextoSeguro.limparLinha(nomeDoMac);
        // O comentário vira texto de linha de authorized_keys: sem espaço nem controle.
        return "lab-desktop@" + mac.replaceAll("\\s+", "-");
    }

    /** Id novo de chave: {@code k} mais 8 hexadecimais. */
    static String novoId() {
        byte[] b = new byte[4];
        ALEATORIO.nextBytes(b);
        return "k" + HexFormat.of().formatHex(b);
    }

    /** O buffer interno do {@code ByteArrayOutputStream} é acessível aqui para poder ser zerado. */
    static final class SaidaZeravel extends ByteArrayOutputStream {
        byte[] copiaExata() {
            return Arrays.copyOf(buf, count);
        }

        void zerar() {
            Zeragem.zerar(buf);
            count = 0;
        }
    }
}
