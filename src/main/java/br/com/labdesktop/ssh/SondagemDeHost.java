package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.transporte.DestinoSsh;
import br.com.labdesktop.transporte.FalhaDoTunelException;
import br.com.labdesktop.transporte.RotaDoSsh;
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
     * A frase para o usuário quando a sondagem não chegou à chave do servidor. Os casos comuns (porta errada, servidor
     * desligado, endereço digitado errado, fora da rede local) dizem o que aconteceu e o que conferir: o erro aparece
     * no passo da chave, mas o dado errado está no passo 1, então a frase manda para o botão Voltar. Nada de texto cru de
     * exceção aqui (esse vai para o log).
     *
     * @param causa o que a conexão lançou, ou {@code null} se o servidor respondeu sem apresentar chave
     */
    public static String traduzirFalha(String host, int porta, Throwable causa) {
        return traduzirFalha(host, porta, causa, false); // sem saber a fase, a regra da IOException comum (Windows) não vale
    }

    /** @param aoConectar {@code true} se a falha veio de abrir a conexão (ainda sem sessão); ver {@link CausaDeRede} */
    static String traduzirFalha(String host, int porta, Throwable causa, boolean aoConectar) {
        String onde = host + ":" + porta;
        CausaDeRede tipo = CausaDeRede.de(causa, aoConectar);
        return switch (tipo) {
            case RECUSOU -> "O servidor em " + onde + " recusou a conexão. Confira a porta e se o SSH está ligado nele "
                + "(botão Voltar para mudar o endereço ou a porta).";
            case TEMPO_ESGOTADO -> "Não consegui falar com " + host + " na porta " + porta + ": ele não respondeu a tempo. "
                + "Confira o endereço e a porta (botão Voltar), se o servidor está ligado e se este computador está na mesma rede "
                + "que ele.";
            case NOME_NAO_ENCONTRADO -> "Não encontrei o endereço " + host + ". Confira se ele está escrito certo "
                + "(botão Voltar).";
            case SEM_ROTA -> "Este computador não achou caminho até " + host + ". Confira o endereço (botão Voltar) e se o computador está "
                + "na mesma rede do servidor.";
            case OUTRA -> "Não consegui obter a chave do servidor em " + onde + ". Confira o endereço e a porta (botão "
                + "Voltar) e se o servidor está ligado; também pode ser que essa porta não seja a do SSH.";
        };
    }

    /**
     * A sondagem pela ROTA do servidor: direta, ou por um túnel externo (AWS Session Manager, Cloudflare) que sobe só para isto e é fechado ao terminar. A chave do
     * host que o usuário confere é a do SSH do servidor atrás do túnel, a mesma de uma conexão direta. Bloqueia: fora da EDT.
     *
     * @throws SondagemException o túnel não subiu (a frase diz por quê) ou o SSH não respondeu por ele
     */
    public static Resultado sondar(RotaDoSsh rota, ConfigSsh cfg) throws SondagemException {
        if (!rota.ehTunel()) {
            return sondar(rota.host(), rota.porta(), cfg);
        }
        DestinoSsh destino;
        try {
            destino = rota.abrir();
        } catch (FalhaDoTunelException e) {
            throw new SondagemException(e.falha().mensagem(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SondagemException("A conferência foi interrompida.", e);
        }
        try (destino) {
            try {
                return sondar(destino.host(), destino.porta(), cfg);
            } catch (SondagemException e) {
                throw new SondagemException(FrasesDoCaminho.semSshPeloTunel(rota.transporte(), destino), e.getCause());
            }
        }
    }

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
        boolean aoConectar = true;
        try (ClientSession sessao = cliente.connect(USUARIO_DA_SONDAGEM, host, porta)
            .verify(cfg.conectar()).getSession()) {
            aoConectar = false; // a conexão existe: o que falhar daqui em diante não é "recusou"
            // auth() só serve para esperar o key exchange terminar (ou ser recusado pelo verificador).
            sessao.auth().verify(cfg.autenticar());
        } catch (Exception e) {
            causa = e; // esperado: o verificador recusou de propósito
        } finally {
            cliente.stop();
        }
        PublicKey chave = capturada.get();
        if (chave == null) {
            // O texto técnico (em inglês, do Java ou do MINA) fica só no log; o usuário lê a frase traduzida.
            String motivo = causa == null ? "o servidor não apresentou chave" : Redator.redigir(causa);
            LOG.info("Sondagem de {}:{} sem chave: {}", host, porta, motivo);
            throw new SondagemException(traduzirFalha(host, porta, causa, aoConectar), causa);
        }
        String algoritmo = KeyUtils.getKeyType(chave);
        return new Resultado(algoritmo, KeyUtils.getFingerPrint(chave), chave,
            AlgoritmosDeHost.comandoDeConferencia(algoritmo));
    }
}
