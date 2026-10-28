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
     * A frase para o usuário quando a sondagem não chegou à chave do servidor. Os casos comuns (porta errada, servidor
     * desligado, endereço digitado errado, fora da rede local) dizem o que aconteceu e o que conferir: o erro aparece
     * no passo da chave, mas o dado errado está no passo 1, então a frase manda para o botão Voltar. Nada de texto cru de
     * exceção aqui (esse vai para o log).
     *
     * @param causa o que a conexão lançou, ou {@code null} se o servidor respondeu sem apresentar chave
     */
    public static String traduzirFalha(String host, int porta, Throwable causa) {
        String onde = host + ":" + porta;
        Tipo tipo = classificar(causa);
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
            case OUTRO -> "Não consegui obter a chave do servidor em " + onde + ". Confira o endereço e a porta (botão "
                + "Voltar) e se o servidor está ligado; também pode ser que essa porta não seja a do SSH.";
        };
    }

    private enum Tipo { RECUSOU, TEMPO_ESGOTADO, NOME_NAO_ENCONTRADO, SEM_ROTA, OUTRO }

    /** Percorre a cadeia de causas: o MINA embrulha o erro de rede em {@code SshException} e {@code IOException}. */
    private static Tipo classificar(Throwable causa) {
        Throwable atual = causa;
        for (int fundo = 0; atual != null && fundo < 10; fundo++, atual = atual.getCause() == atual ? null : atual.getCause()) {
            if (atual instanceof java.net.ConnectException) {
                String texto = minusculas(atual);
                return texto.contains("timed out") || texto.contains("timeout") ? Tipo.TEMPO_ESGOTADO : Tipo.RECUSOU;
            }
            if (atual instanceof java.net.UnknownHostException || atual instanceof java.nio.channels.UnresolvedAddressException) {
                return Tipo.NOME_NAO_ENCONTRADO;
            }
            if (atual instanceof java.net.NoRouteToHostException) {
                return Tipo.SEM_ROTA;
            }
            if (atual instanceof java.net.SocketTimeoutException || atual instanceof java.util.concurrent.TimeoutException) {
                return Tipo.TEMPO_ESGOTADO;
            }
            String texto = minusculas(atual);
            if (texto.contains("connection refused")) {
                return Tipo.RECUSOU;
            }
            if (texto.contains("no route to host") || texto.contains("network is unreachable") || texto.contains("host is down")
                || texto.contains("network is down")) {
                return Tipo.SEM_ROTA;
            }
            if (texto.contains("timed out") || texto.contains("specified timeout") || texto.contains("timeout")) {
                return Tipo.TEMPO_ESGOTADO;
            }
            if (texto.contains("unknownhost") || texto.contains("unresolved") || texto.contains("name or service not known")
                || texto.contains("nodename nor servname")) {
                return Tipo.NOME_NAO_ENCONTRADO;
            }
        }
        return Tipo.OUTRO;
    }

    private static String minusculas(Throwable t) {
        return String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
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
            // O texto técnico (em inglês, do Java ou do MINA) fica só no log; o usuário lê a frase traduzida.
            String motivo = causa == null ? "o servidor não apresentou chave" : Redator.redigir(causa);
            LOG.info("Sondagem de {}:{} sem chave: {}", host, porta, motivo);
            throw new SondagemException(traduzirFalha(host, porta, causa), causa);
        }
        String algoritmo = KeyUtils.getKeyType(chave);
        return new Resultado(algoritmo, KeyUtils.getFingerPrint(chave), chave,
            AlgoritmosDeHost.comandoDeConferencia(algoritmo));
    }
}
