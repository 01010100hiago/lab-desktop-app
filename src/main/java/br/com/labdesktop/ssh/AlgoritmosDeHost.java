package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Quais algoritmos de chave de host o cliente aceita negociar (ARQUITETURA 6.2).
 *
 * <p>O pin é por algoritmo, e o MINA pode negociar um algoritmo diferente do que o {@code known_hosts} do Mac
 * tem para o lab. Por isso a sessão real pede SÓ o algoritmo pinado: o servidor não tem como oferecer outra
 * chave de host. A lista nunca inclui {@code ssh-rsa} (assinatura com SHA-1) nem {@code ssh-dss}.
 */
public final class AlgoritmosDeHost {

    /** Ordem de preferência da sondagem: moderno primeiro. É o que o usuário vai ver e conferir. */
    public static final List<String> PARA_SONDAGEM = List.of(
        "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
        "rsa-sha2-512", "rsa-sha2-256");

    private AlgoritmosDeHost() { }

    /**
     * Nomes de assinatura a oferecer para os pins dados. Chave RSA aparece como {@code ssh-rsa} no pin (é o
     * tipo da chave), mas só se negocia com {@code rsa-sha2-*}.
     */
    public static List<String> paraPins(List<Pin> pins) {
        Set<String> nomes = new LinkedHashSet<>();
        for (Pin p : pins) {
            nomes.addAll(assinaturasDoTipo(p.algoritmo()));
        }
        return new ArrayList<>(nomes);
    }

    static List<String> assinaturasDoTipo(String tipoDaChave) {
        return switch (tipoDaChave) {
            case "ssh-rsa" -> List.of("rsa-sha2-512", "rsa-sha2-256");
            default -> List.of(tipoDaChave);
        };
    }

    /**
     * O comando que o usuário roda NO SERVIDOR para conferir a impressão que a sondagem mostrou
     * (ARQUITETURA 6.2).
     */
    public static String comandoDeConferencia(String tipoDaChave) {
        String arquivo = switch (tipoDaChave) {
            case "ssh-ed25519" -> "ssh_host_ed25519_key.pub";
            case "ssh-rsa" -> "ssh_host_rsa_key.pub";
            default -> tipoDaChave.startsWith("ecdsa-") ? "ssh_host_ecdsa_key.pub" : "ssh_host_*_key.pub";
        };
        return "ssh-keygen -lf /etc/ssh/" + arquivo;
    }
}
