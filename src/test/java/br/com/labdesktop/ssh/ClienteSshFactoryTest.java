package br.com.labdesktop.ssh;

import br.com.labdesktop.teste.ServidorSshDeTeste;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.ConfigFileHostEntryResolver;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ARQUITETURA 6.1: o cliente SSH do app é blindado. Os dois testes mais importantes aqui provam o que o doc
 * descobriu na prática: o cliente padrão do MINA oferece {@code ~/.ssh/id_ed25519} (então o assistente "ficaria
 * verde" pelo motivo errado) e a fábrica do app não.
 */
class ClienteSshFactoryTest {

    private static final Duration PRAZO = Duration.ofSeconds(15);
    private static final Pattern FRACO = Pattern.compile(
        "(?i)(sha1|cbc|3des|arcfour|blowfish|group1-|group14-sha1|md5|^none$|^ssh-rsa$|ssh-dss|^hmac-sha1)");

    @Test
    void listaEfetivaNaoTemAlgoritmoFraco() {
        SshClient cliente = ClienteSshFactory.criar(AcceptAllServerKeyVerifier.INSTANCE, ConfigSsh.padrao(),
            AlgoritmosDeHost.PARA_SONDAGEM);
        List<String> nomes = ClienteSshFactory.algoritmosEfetivos(cliente);
        assertFalse(nomes.isEmpty());
        List<String> fracos = nomes.stream().filter(n -> FRACO.matcher(n).find()).toList();
        assertTrue(fracos.isEmpty(), "algoritmos fracos oferecidos: " + fracos);
        // E o que deve estar lá está.
        assertTrue(nomes.contains("curve25519-sha256"), nomes.toString());
        assertTrue(nomes.contains("aes256-gcm@openssh.com"), nomes.toString());
        assertTrue(nomes.contains("hmac-sha2-256-etm@openssh.com"), nomes.toString());
        assertTrue(nomes.contains("ssh-ed25519"), nomes.toString());
    }

    @Test
    void soPublickeyEHeartbeatDe10sPor3() {
        SshClient cliente = ClienteSshFactory.criar(AcceptAllServerKeyVerifier.INSTANCE, ConfigSsh.padrao(),
            List.of("ssh-ed25519"));
        assertEquals(List.of("publickey"), cliente.getUserAuthFactoriesNames());
        assertEquals(Duration.ofSeconds(10), org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(cliente));
        assertEquals(3, org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(cliente));
        assertEquals("keepalive@openssh.com",
            org.apache.sshd.core.CoreModuleProperties.HEARTBEAT_REQUEST.getRequired(cliente));
    }

    @Test
    void oClienteNascePorConstrucaoSemIdentidadesSemConfigSemAgentENemSenha() {
        // O teste de comportamento com HOME falso não pega tudo: o resolvedor padrão de ~/.ssh/config do MINA é um
        // singleton que fixa o caminho do HOME verdadeiro na primeira vez que alguém o usa. Então a blindagem
        // também é conferida pela ESTRUTURA do cliente: se alguém tirar uma destas linhas da fábrica, cai aqui.
        AcceptAllServerKeyVerifier verificador = AcceptAllServerKeyVerifier.INSTANCE;
        SshClient cliente = ClienteSshFactory.criar(verificador, ConfigSsh.padrao(), List.of("ssh-ed25519"));
        assertSame(HostConfigEntryResolver.EMPTY, cliente.getHostConfigEntryResolver(),
            "o app não pode ler ~/.ssh/config (mudaria porta, usuário e chave)");
        assertSame(KeyIdentityProvider.EMPTY_KEYS_PROVIDER, cliente.getKeyIdentityProvider(),
            "o app não pode oferecer ~/.ssh/id_*");
        assertSame(verificador, cliente.getServerKeyVerifier(), "a chave do host é do verificador, nunca do known_hosts");
        assertEquals(1, cliente.getUserAuthFactories().size());
        assertEquals("publickey", cliente.getUserAuthFactories().get(0).getName());
        assertEquals(List.of("ssh-ed25519"), cliente.getSignatureFactoriesNames());
    }

    @Test
    void clientePadraoOfereceChaveDoSshDoDonoEOClienteDoAppNao(@TempDir Path tmp) throws Exception {
        KeyPair doCofre = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        KeyPair doHome = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        Path casa = HomeFalso.popular(tmp, doHome);
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519(doHome.getPublic())) {
            // 1) CONTROLE: sem blindagem, o MINA autentica com a chave de ~/.ssh sem que ninguém peça.
            HomeFalso.comHome(casa, () -> {
                SshClient padrao = SshClient.setUpDefaultClient();
                // O resolvedor padrão é um singleton que fixa o caminho do config na primeira vez que alguém
                // o usa (com o HOME verdadeiro). O controle aponta explicitamente para o config FALSO, para o
                // teste nunca abrir o ~/.ssh/config do usuário.
                padrao.setHostConfigEntryResolver(new ConfigFileHostEntryResolver(casa.resolve(".ssh").resolve("config")));
                padrao.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
                padrao.start();
                try (ClientSession s = padrao.connect("labuser", "127.0.0.1", servidor.porta()).verify(PRAZO).getSession()) {
                    s.auth().verify(PRAZO);
                    assertTrue(s.isAuthenticated(),
                        "o controle falhou: o cliente padrão deveria usar ~/.ssh (o teste não prova nada)");
                } finally {
                    padrao.stop();
                }
            });
            assertEquals(List.of(KeyUtils.getFingerPrint(doHome.getPublic())),
                servidor.chavesOferecidas.stream().distinct().toList());
            servidor.chavesOferecidas.clear();

            // 2) O cliente do app, com o MESMO ~/.ssh populado, não autentica por ele...
            HomeFalso.comHome(casa, () -> {
                SshClient app = ClienteSshFactory.criar(AcceptAllServerKeyVerifier.INSTANCE, ConfigSsh.padrao(),
                    List.of("ssh-ed25519"));
                app.start();
                try (ClientSession s = app.connect("labuser", "127.0.0.1", servidor.porta()).verify(PRAZO).getSession()) {
                    assertThrows(IOException.class, () -> s.auth().verify(PRAZO));
                    assertFalse(s.isAuthenticated());
                } finally {
                    app.stop();
                }
            });
            assertTrue(servidor.chavesOferecidas.isEmpty(),
                "o cliente do app ofereceu chave de ~/.ssh: " + servidor.chavesOferecidas);

            // 3) ...e com a chave do cofre, oferece SÓ ela (mesmo com o servidor aceitando as duas).
            servidor.aceitar(doCofre.getPublic());
            HomeFalso.comHome(casa, () -> {
                SshClient app = ClienteSshFactory.criar(AcceptAllServerKeyVerifier.INSTANCE, ConfigSsh.padrao(),
                    List.of("ssh-ed25519"));
                app.start();
                try (ClientSession s = app.connect("labuser", "127.0.0.1", servidor.porta()).verify(PRAZO).getSession()) {
                    s.addPublicKeyIdentity(doCofre);
                    s.auth().verify(PRAZO);
                    assertTrue(s.isAuthenticated());
                } finally {
                    app.stop();
                }
            });
            assertEquals(List.of(KeyUtils.getFingerPrint(doCofre.getPublic())),
                servidor.chavesOferecidas.stream().distinct().toList());
        }
    }

    @Test
    void naoLeOConfigDoSsh(@TempDir Path tmp) throws Exception {
        KeyPair doCofre = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        KeyPair doHome = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        Path casa = HomeFalso.popular(tmp, doHome);
        // O config do usuário manda para a porta 2: se o app o lesse, a conexão falharia.
        Files.writeString(casa.resolve(".ssh/config"), "Host *\n  Port 2\n  User intruso\n  IdentityFile ~/.ssh/id_ed25519\n");
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519(doCofre.getPublic())) {
            HomeFalso.comHome(casa, () -> {
                SshClient app = ClienteSshFactory.criar(AcceptAllServerKeyVerifier.INSTANCE, ConfigSsh.padrao(),
                    List.of("ssh-ed25519"));
                app.start();
                try (ClientSession s = app.connect("labuser", "127.0.0.1", servidor.porta()).verify(PRAZO).getSession()) {
                    assertEquals(servidor.porta(), ((java.net.InetSocketAddress) s.getRemoteAddress()).getPort());
                    s.addPublicKeyIdentity(doCofre);
                    s.auth().verify(PRAZO);
                    assertTrue(s.isAuthenticated());
                    assertEquals("labuser", s.getUsername());
                } finally {
                    app.stop();
                }
            });
        }
    }
}
