package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prova a ENTREGA da chave ao MINA (ARQUITETURA 5.8): o {@link KeyPair} que sai do cofre, em memória, autentica
 * num {@code SshServer} embutido via {@code ClientSession.addPublicKeyIdentity}, sem arquivo, sem ssh-agent e
 * sem tocar {@code ~/.ssh}. O cliente aqui já nasce blindado do jeito que a ARQUITETURA 6.1 pede (sem
 * identidades nem {@code ~/.ssh/config}), então o teste nunca lê nada da pasta do usuário.
 */
class EntregaAoMinaTest {

    private static final Duration PRAZO = Duration.ofSeconds(15);

    private static SshServer servidorQueAceitaSo(PublicKey aceita, List<String> oferecidas) throws Exception {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(GeradorDeChaves.gerar("host", "teste").paraKeyPair()));
        sshd.setPublickeyAuthenticator((usuario, chave, sessao) -> {
            oferecidas.add(KeyUtils.getFingerPrint(chave));
            return KeyUtils.compareKeys(chave, aceita);
        });
        sshd.start();
        return sshd;
    }

    private static SshClient clienteBlindado() {
        SshClient cliente = SshClient.setUpDefaultClient();
        // Sem estas duas linhas o cliente padrão oferece ~/.ssh/id_ed25519 e lê ~/.ssh/config (ARQUITETURA 6.1).
        cliente.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        cliente.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        cliente.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
        cliente.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE); // teste; o app usa o pin do cofre
        return cliente;
    }

    @Test
    void keyPairDoCofreAutenticaENadaMaisEOferecido(@TempDir Path tmp) throws Exception {
        var caminhos = Fixtures.caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(caminhos);
        List<String> oferecidas = new CopyOnWriteArrayList<>();
        try (CofreDeChaves cofre = Fixtures.abrir(caminhos)) {
            KeyPair doCofre = cofre.keyPairDoServidor(cfg.id());
            SshServer sshd = servidorQueAceitaSo(doCofre.getPublic(), oferecidas);
            SshClient cliente = clienteBlindado();
            cliente.start();
            try (ClientSession sessao = cliente.connect(cfg.usuario(), "127.0.0.1", sshd.getPort())
                .verify(PRAZO).getSession()) {
                sessao.addPublicKeyIdentity(cofre.keyPairDoServidor(cfg.id()));
                sessao.auth().verify(PRAZO);
                assertTrue(sessao.isAuthenticated());
            } finally {
                cliente.stop();
                sshd.stop(true);
            }
            // O servidor só viu A chave do cofre: nenhuma identidade de ~/.ssh foi oferecida.
            assertEquals(List.of(cofre.chave(cfg.chaveId()).orElseThrow().impressao()), oferecidas.stream().distinct().toList());
        }
    }

    @Test
    void outraChaveEhRecusadaEOCofreNaoEnganaOServidor(@TempDir Path tmp) throws Exception {
        var caminhos = Fixtures.caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(caminhos);
        PublicKey outra = GeradorDeChaves.gerar("outra", "mac").paraKeyPair().getPublic();
        List<String> oferecidas = new CopyOnWriteArrayList<>();
        try (CofreDeChaves cofre = Fixtures.abrir(caminhos)) {
            SshServer sshd = servidorQueAceitaSo(outra, oferecidas);
            SshClient cliente = clienteBlindado();
            cliente.start();
            try (ClientSession sessao = cliente.connect(cfg.usuario(), "127.0.0.1", sshd.getPort())
                .verify(PRAZO).getSession()) {
                sessao.addPublicKeyIdentity(cofre.keyPairDoServidor(cfg.id()));
                assertThrows(IOException.class, () -> sessao.auth().verify(PRAZO));
                assertFalse(sessao.isAuthenticated());
            } finally {
                cliente.stop();
                sshd.stop(true);
            }
        }
    }
}
