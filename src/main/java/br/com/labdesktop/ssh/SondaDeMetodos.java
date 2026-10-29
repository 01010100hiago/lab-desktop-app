package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.transporte.DestinoSsh;
import br.com.labdesktop.transporte.FalhaDoTunelException;
import br.com.labdesktop.transporte.RotaDoSsh;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.PublicKeyAuthenticationReporter;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;

/**
 * "Quais métodos de entrada o servidor oferece agora?", lido numa conexão NOVA sem usar senha nenhuma: o cliente blindado do app (só publickey, chave do host
 * conferida contra o pin) oferece uma chave descartável que o servidor não conhece e lê, na recusa, a lista de métodos que ele ainda aceita. É a conferência de
 * verdade depois de desligar a senha: se a lista ainda traz {@code password} ou {@code keyboard-interactive}, o servidor não está só com chave. Não manda senha e
 * não grava nada.
 */
@FunctionalInterface
public interface SondaDeMetodos {

    /**
     * @return os métodos que o servidor oferece, ou {@code null} se não deu para ler (sem conexão, host que mudou, resposta inesperada)
     */
    List<String> ler() throws InterruptedException;

    /** A sonda de verdade, contra um servidor com os pins dados. */
    static SondaDeMetodos para(String host, int porta, String usuario, List<Pin> pins, ConfigSsh cfg) {
        return para(RotaDoSsh.direta(host, porta), usuario, pins, cfg);
    }

    /** Pela rota do servidor: direta, ou por um túnel externo que sobe só para esta leitura e é fechado ao terminar. Se o túnel não sobe, o resultado é {@code null} (não deu para ler). */
    static SondaDeMetodos para(RotaDoSsh rota, String usuario, List<Pin> pins, ConfigSsh cfg) {
        return () -> {
            DestinoSsh destino;
            try {
                destino = rota.abrir();
            } catch (FalhaDoTunelException e) {
                return null;
            }
            try {
                return ler(destino, usuario, pins, cfg);
            } finally {
                destino.close();
            }
        };
    }

    private static List<String> ler(DestinoSsh destino, String usuario, List<Pin> pins, ConfigSsh cfg) throws InterruptedException {
        AtomicReference<List<String>> metodos = new AtomicReference<>();
        SshClient cliente = ClienteSshFactory.criar(new VerificadorDeChaveDoServidor(() -> pins), cfg, AlgoritmosDeHost.paraPins(pins));
        cliente.setPublicKeyAuthenticationReporter(new PublicKeyAuthenticationReporter() {
            @Override
            public void signalAuthenticationFailure(ClientSession s, String servico, KeyPair chave, boolean parcial, List<String> oferecidos) {
                metodos.set(List.copyOf(oferecidos));
            }
        });
        cliente.start();
        ClientSession s = null;
        try {
            KeyPair descartavel = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            s = cliente.connect(usuario, destino.host(), destino.porta()).verify(cfg.conectar()).getSession();
            s.addPublicKeyIdentity(descartavel);
            s.auth().verify(cfg.autenticar());
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrompido.");
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // esperado: a chave descartável é recusada; o que importa é o que o servidor disse na recusa
        } finally {
            try {
                if (s != null) {
                    s.close(true);
                }
            } finally {
                cliente.stop();
            }
        }
        return metodos.get();
    }
}
