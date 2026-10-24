package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.seguranca.Redator;
import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conexão DESCARTÁVEL que só faz o key exchange, captura a chave do host e aborta ANTES da autenticação
 * (ARQUITETURA 6.2). Nenhuma credencial vai a um host que o usuário ainda não confirmou: o verificador de captura
 * devolve {@code false}, o MINA encerra a sessão antes do pedido de serviço {@code ssh-userauth}, e o cliente
 * nem tem identidade para oferecer.
 *
 * <p>O resultado alimenta o {@code DialogoConfiarHost}: algoritmo, impressão {@code SHA256:...} e o comando
 * que o usuário roda no servidor para conferir. Confirmado, o pin vai para o cofre
 * ({@code AcessoAoCofre.gravarPin}).
 */
public final class SondagemDeHost {

    private static final Logger LOG = LoggerFactory.getLogger(SondagemDeHost.class);
    /** Só aparece no log do servidor; nunca é autenticado. */
    private static final String USUARIO_DA_SONDAGEM = "sondagem";

    /**
     * A chave do host, como o usuário vai conferir.
     *
     * @param algoritmo           tipo da chave ({@code ssh-ed25519}, {@code ecdsa-sha2-nistp256}, {@code ssh-rsa})
     * @param impressao           {@code SHA256:...} (o mesmo formato de {@code ssh-keygen -lf})
     * @param chave               a chave pública do host
     * @param comandoDeConferencia comando a rodar no servidor para ver a impressão
     */
    public record Resultado(String algoritmo, String impressao, PublicKey chave, String comandoDeConferencia) {

        /** O pin a gravar no cofre depois de o usuário confirmar. */
        public Pin pin() {
            return Pin.de(chave);
        }
    }

    private SondagemDeHost() { }

    /**
     * Bloqueia até obter a chave ou falhar (use fora da EDT). Não grava nada em lugar nenhum.
     *
     * @throws SondagemException se não foi possível chegar ao key exchange
     */
    public static Resultado sondar(String host, int porta, ConfigSsh cfg) throws SondagemException {
        AtomicReference<PublicKey> capturada = new AtomicReference<>();
        ServerKeyVerifier captura = new ServerKeyVerifier() {
            @Override
            public boolean verifyServerKey(ClientSession sessao, SocketAddress remoto, PublicKey chave) {
                capturada.set(chave);
                return false; // aborta aqui: nada de autenticar num host não confirmado
            }
        };
        SshClient cliente = ClienteSshFactory.criar(captura, cfg, AlgoritmosDeHost.PARA_SONDAGEM);
        cliente.start();
        Throwable causa = null;
        try (ClientSession sessao = cliente.connect(USUARIO_DA_SONDAGEM, host, porta)
            .verify(cfg.conectar()).getSession()) {
            // auth() só serve para esperar o key exchange terminar (ou ser recusado pelo verificador).
            sessao.auth().verify(cfg.autenticar());
        } catch (Exception e) {
            causa = e; // esperado: o verificador recusou de propósito
        } finally {
            cliente.stop();
        }
        PublicKey chave = capturada.get();
        if (chave == null) {
            String motivo = causa == null ? "o servidor não apresentou chave" : Redator.redigir(causa);
            LOG.info("Sondagem de {}:{} sem chave: {}", host, porta, motivo);
            throw new SondagemException("Não consegui obter a chave do servidor (" + motivo + ").", causa);
        }
        String algoritmo = KeyUtils.getKeyType(chave);
        return new Resultado(algoritmo, KeyUtils.getFingerPrint(chave), chave,
            AlgoritmosDeHost.comandoDeConferencia(algoritmo));
    }
}
