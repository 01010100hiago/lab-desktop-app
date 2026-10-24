package br.com.labdesktop.cofre;

import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM do JCE puro ({@code AES/GCM/NoPadding}), nonce de 96 bits, tag de 128 bits no fim do texto
 * cifrado (ARQUITETURA 5.4). O AAD é fornecido por quem chama (os 32 primeiros bytes do arquivo: cabeçalho
 * mais sal), então alterar qualquer parâmetro do KDF quebra a autenticação.
 *
 * <p>Limite aceito: o {@code SecretKeySpec} copia a chave e essa cópia do JCE não dá para zerar.
 */
final class CifraAesGcm {

    static final int TAMANHO_NONCE = 12;
    static final int BITS_DA_TAG = 128;
    static final int TAMANHO_TAG = BITS_DA_TAG / 8;

    private CifraAesGcm() { }

    /** Cifra {@code claro}; devolve texto cifrado com a tag de 16 bytes no fim. */
    static byte[] cifrar(byte[] chave, byte[] nonce, byte[] aad, byte[] claro) throws GeneralSecurityException {
        Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
        cifra.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(chave, "AES"), new GCMParameterSpec(BITS_DA_TAG, nonce));
        cifra.updateAAD(aad);
        return cifra.doFinal(claro);
    }

    /**
     * Decifra {@code dados[deslocamento .. deslocamento+tamanho)}.
     *
     * @throws AEADBadTagException se a tag não confere (senha errada, ou qualquer byte adulterado)
     */
    static byte[] decifrar(byte[] chave, byte[] nonce, byte[] aad, byte[] dados, int deslocamento, int tamanho)
        throws GeneralSecurityException {
        Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
        cifra.init(Cipher.DECRYPT_MODE, new SecretKeySpec(chave, "AES"), new GCMParameterSpec(BITS_DA_TAG, nonce));
        cifra.updateAAD(aad);
        return cifra.doFinal(dados, deslocamento, tamanho);
    }
}
