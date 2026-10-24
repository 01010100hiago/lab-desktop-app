package br.com.labdesktop.cofre;

import java.security.PublicKey;
import org.apache.sshd.common.config.keys.KeyUtils;

/**
 * Impressão digital pinada da chave do host, por algoritmo (ARQUITETURA 6.2). Fica dentro do cofre de
 * propósito: o GCM a autentica, e é ela que impede o MITM silencioso. Sem segredo (a chave PÚBLICA do host).
 *
 * @param algoritmo nome SSH do algoritmo negociado ({@code ssh-ed25519}, {@code ecdsa-sha2-nistp256}, {@code rsa-sha2-512}...)
 * @param impressao {@code SHA256:<base64>}, o mesmo formato de {@code ssh-keygen -lf}
 */
public record Pin(String algoritmo, String impressao) {

    public Pin {
        if (algoritmo == null || algoritmo.isBlank() || algoritmo.length() > 64 || algoritmo.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Algoritmo do pin inválido.");
        }
        if (impressao == null || !impressao.startsWith("SHA256:") || impressao.length() < 10 || impressao.length() > 128
            || impressao.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Impressão do pin inválida (esperado SHA256:...).");
        }
    }

    /** Pin da chave pública do host que o MINA capturou no key exchange. */
    public static Pin de(PublicKey chaveDoHost) {
        return new Pin(KeyUtils.getKeyType(chaveDoHost), KeyUtils.getFingerPrint(chaveDoHost));
    }

    /** {@code true} se a chave do host tem este algoritmo e esta impressão. */
    public boolean confere(PublicKey chaveDoHost) {
        return algoritmo.equals(KeyUtils.getKeyType(chaveDoHost))
            && impressao.equals(KeyUtils.getFingerPrint(chaveDoHost));
    }
}
