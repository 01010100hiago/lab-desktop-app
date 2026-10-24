package br.com.labdesktop.ssh;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * Fábrica do {@link SshClient} BLINDADO do app (ARQUITETURA 6.1).
 *
 * <p>O cliente padrão do MINA é "esperto" demais para um app que guarda a chave no cofre:
 * <ul>
 *   <li>oferece {@code ~/.ssh/id_ed25519} junto com a chave do cofre (o assistente "ficaria verde" pelo motivo
 *       errado) e lê {@code ~/.ssh/config} (que pode mudar porta, usuário e chave). Aqui:
 *       {@code EMPTY_KEYS_PROVIDER} e {@code HostConfigEntryResolver.EMPTY};</li>
 *   <li>tenta senha, keyboard-interactive e agent. Aqui: SÓ publickey, e só com assinatura Ed25519;</li>
 *   <li>negocia algoritmos legados. Aqui: KEX curve25519 e ECDH, cifras AES-GCM, ChaCha20-Poly1305 e AES-CTR,
 *       MAC HMAC-SHA2 ETM, e chave de host sem SHA-1.</li>
 * </ul>
 * O cliente devolvido NÃO está iniciado ({@code start()} é de quem o usa) e a chave do host é verificada pelo
 * {@code ServerKeyVerifier} recebido, nunca pelo {@code ~/.ssh/known_hosts}.
 */
public final class ClienteSshFactory {

    static final String PEDIDO_DE_KEEPALIVE = "keepalive@openssh.com";

    private static final List<String> CIFRAS = List.of(
        "aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "chacha20-poly1305@openssh.com",
        "aes256-ctr", "aes192-ctr", "aes128-ctr");
    private static final List<String> MACS = List.of(
        "hmac-sha2-512-etm@openssh.com", "hmac-sha2-256-etm@openssh.com");

    private ClienteSshFactory() { }

    /**
     * Cria o cliente (não iniciado).
     *
     * @param verificador     decide se a chave do host é aceita (pin do cofre, ou captura na sondagem)
     * @param cfg             tempos e heartbeat
     * @param algoritmosDeHost nomes de assinatura de chave de host a oferecer (veja {@link AlgoritmosDeHost})
     */
    public static SshClient criar(ServerKeyVerifier verificador, ConfigSsh cfg, List<String> algoritmosDeHost) {
        SshClient cliente = SshClient.setUpDefaultClient();

        // Sem estas duas linhas o cliente padrão oferece ~/.ssh/id_* e lê ~/.ssh/config.
        cliente.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        cliente.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        cliente.setServerKeyVerifier(verificador);

        // Só publickey, e só Ed25519 (é o único tipo de chave que o cofre cria e importa). A lista é PRÓPRIA do
        // fator de autenticação: a lista de assinaturas do cliente é restrita ao algoritmo do host pinado e
        // não pode ser reaproveitada para assinar o userauth.
        cliente.setUserAuthFactories(List.of(new UserAuthPublicKeyFactory(List.of(BuiltinSignatures.ed25519))));

        cliente.setKeyExchangeFactories(trocasDeChave());
        cliente.setCipherFactoriesNames(CIFRAS);
        cliente.setMacFactoriesNames(MACS);
        cliente.setSignatureFactoriesNames(algoritmosDeHost);

        // Heartbeat do MINA: a cada 10 s um keepalive COM resposta; 3 sem resposta derrubam a sessão
        // (cerca de 40 s). Com 15 s a queda foi detectada em 58 s (medido).
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(cliente, cfg.heartbeat());
        CoreModuleProperties.HEARTBEAT_REQUEST.set(cliente, PEDIDO_DE_KEEPALIVE);
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(cliente, cfg.heartbeatSemRespostaMax());
        CoreModuleProperties.AUTH_TIMEOUT.set(cliente, cfg.autenticar());
        CoreModuleProperties.CHANNEL_OPEN_TIMEOUT.set(cliente, cfg.abrirCanal());
        // O padrão (10 min) derrubaria uma sessão quieta; quem mantém a sessão viva é o heartbeat.
        CoreModuleProperties.IDLE_TIMEOUT.set(cliente, Duration.ofDays(30));
        return cliente;
    }

    private static List<KeyExchangeFactory> trocasDeChave() {
        return Stream.of(
                BuiltinDHFactories.curve25519, BuiltinDHFactories.curve25519_libssh,
                BuiltinDHFactories.ecdhp256, BuiltinDHFactories.ecdhp384, BuiltinDHFactories.ecdhp521)
            .filter(BuiltinDHFactories::isSupported)
            .map(ClientBuilder.DH2KEX)
            .toList();
    }

    /**
     * Todos os nomes de algoritmo que o cliente ofereceria ao servidor (KEX, cifras, MACs e assinaturas de
     * chave de host). É o que o teste de "nenhum algoritmo fraco" examina.
     */
    public static List<String> algoritmosEfetivos(SshClient cliente) {
        List<String> nomes = new ArrayList<>();
        cliente.getKeyExchangeFactories().stream().map(NamedResource::getName).forEach(nomes::add);
        nomes.addAll(cliente.getCipherFactoriesNames());
        nomes.addAll(cliente.getMacFactoriesNames());
        nomes.addAll(cliente.getSignatureFactoriesNames());
        return nomes;
    }
}
