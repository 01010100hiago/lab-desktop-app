package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.Segredo;
import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.Zeragem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.util.security.SecurityUtils;

/**
 * Par de chaves SSH Ed25519 de um servidor, guardado no cofre (ARQUITETURA 5.3 e 5.8). A parte privada é o
 * texto {@code BEGIN OPENSSH PRIVATE KEY} (sem passphrase: o cofre inteiro já é cifrado) num {@code byte[]}
 * que o cofre zera ao bloquear.
 *
 * <p>NÃO é {@code record} e o {@code toString} só mostra o que é público. Uma {@code ChaveSsh} devolvida
 * pelo cofre PERTENCE ao cofre: não chame {@code zerar()} nem {@code close()} nela.
 *
 * <p><b>Entrega ao MINA</b>: {@link #paraKeyPair()} reconstrói o {@link KeyPair} em memória a partir do texto,
 * sem arquivo temporário, sem ssh-agent e sem {@code -i}. Quem recebe passa a {@code ClientSession.addPublicKeyIdentity}
 * e solta a referência logo depois da autenticação.
 */
public final class ChaveSsh implements Segredo {

    public static final String ALGORITMO_ED25519 = "ssh-ed25519";

    private final String id;
    private final String nome;
    private final String algoritmo;
    private final String publica;
    private final String impressao;
    private final Instant criadaEm;
    private final InfoChave.Origem origem;
    private final byte[] privadaOpenssh;
    private volatile boolean zerada;

    ChaveSsh(String id, String nome, String algoritmo, String publica, String impressao, Instant criadaEm,
             InfoChave.Origem origem, byte[] privadaOpenssh) {
        this.id = id;
        this.nome = nome;
        this.algoritmo = algoritmo;
        this.publica = publica;
        this.impressao = impressao;
        this.criadaEm = criadaEm;
        this.origem = origem == null ? InfoChave.Origem.DESCONHECIDA : origem;
        this.privadaOpenssh = privadaOpenssh;
    }

    public String id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    public String algoritmo() {
        return algoritmo;
    }

    /** Linha da chave pública: {@code ssh-ed25519 AAAA... lab-desktop@mac}. */
    public String publica() {
        return publica;
    }

    /** {@code SHA256:...}, o formato de {@code ssh-keygen -lf}. */
    public String impressao() {
        return impressao;
    }

    public Instant criadaEm() {
        return criadaEm;
    }

    /** Quem criou a chave (o app ou o usuário, por arquivo). */
    public InfoChave.Origem origem() {
        return origem;
    }

    /** A parte pública, sem segredo, para telas. */
    public InfoChave info() {
        return new InfoChave(id, nome, algoritmo, publica, impressao, criadaEm, origem);
    }

    /**
     * Linha pronta para colar em {@code ~/.ssh/authorized_keys} do servidor (ARQUITETURA 5.8): sem
     * encaminhamento de agente e de X11, e o forward limitado ao agente no loopback, para a chave não servir de
     * pivô para a LAN. Não leva {@code command=} porque o terminal usa {@code exec} com PTY.
     */
    public String linhaDeAuthorizedKeys(int portaAgente) {
        return "no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:" + portaAgente + "\" " + publica;
    }

    /**
     * Reconstrói o {@link KeyPair} para o MINA. Cada chamada cria um objeto novo.
     *
     * @throws SegredoZeradoException se o cofre já foi bloqueado
     * @throws CofreException         se o texto guardado não for uma chave OpenSSH legível
     */
    public synchronized KeyPair paraKeyPair() throws CofreException {
        conferirViva();
        try {
            Iterable<KeyPair> pares = SecurityUtils.loadKeyPairIdentities(
                null, NamedResource.ofName(id), new ByteArrayInputStream(privadaOpenssh), null);
            Iterator<KeyPair> it = pares == null ? null : pares.iterator();
            if (it == null || !it.hasNext()) {
                throw new CofreException("A chave privada guardada não pôde ser lida.");
            }
            KeyPair par = it.next();
            if (it.hasNext()) {
                throw new CofreException("O cofre guarda mais de uma chave num só registro.");
            }
            return par;
        } catch (IOException | GeneralSecurityException e) {
            throw new CofreException("A chave privada guardada não pôde ser lida.", e);
        }
    }

    /** O array interno, para o cofre serializar. Não vaze. */
    synchronized byte[] privadaInterna() {
        conferirViva();
        return privadaOpenssh;
    }

    private void conferirViva() {
        if (zerada) {
            throw new SegredoZeradoException("A chave SSH já foi zerada (cofre bloqueado).");
        }
    }

    @Override
    public synchronized void zerar() {
        zerada = true;
        Arrays.fill(privadaOpenssh, (byte) 0);
    }

    @Override
    public synchronized boolean estaZerado() {
        return Zeragem.todoZero(privadaOpenssh);
    }

    @Override
    public String toString() {
        return "ChaveSsh[id=" + id + ", nome=" + nome + ", impressao=" + impressao + ", privada=redigida]";
    }
}
