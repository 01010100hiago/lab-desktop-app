package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.seguranca.Redator;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A prova de que a chave entra: uma conexão NOVA (cliente novo, sessão nova) só com a chave, que roda {@link #COMANDO_DA_PROVA} e confere a resposta.
 * Usa o cliente blindado do app (só publickey, chave do servidor conferida contra o pin), então nada de {@code ~/.ssh}, de senha ou de agent
 * entra na prova. Não escreve nada no servidor.
 */
public final class ProvaPorChave implements ProvaDeChave {

    private static final Logger LOG = LoggerFactory.getLogger(ProvaPorChave.class);

    /** O comando inocente da prova: só ecoa. Igual em qualquer shell de login. */
    public static final String COMANDO_DA_PROVA = "echo lab-desktop-prova";
    private static final String RESPOSTA_ESPERADA = "lab-desktop-prova";

    private final String host;
    private final int porta;
    private final String usuario;
    private final KeyPair chave;
    private final List<Pin> pins;
    private final ConfigSsh cfg;

    /**
     * @param chave o par da chave do cofre (o chamador o obtém do cofre, só para esta prova)
     * @param pins  as impressões digitais confirmadas do servidor
     */
    public ProvaPorChave(String host, int porta, String usuario, KeyPair chave, List<Pin> pins, ConfigSsh cfg) {
        this.host = host;
        this.porta = porta;
        this.usuario = usuario;
        this.chave = chave;
        this.pins = List.copyOf(pins);
        this.cfg = cfg;
    }

    @Override
    public void provar() throws FalhaDeEntradaPorSenha, InterruptedException {
        VerificadorDeChaveDoServidor verificador = new VerificadorDeChaveDoServidor(() -> pins);
        SshClient cliente = ClienteSshFactory.criar(verificador, cfg, AlgoritmosDeHost.paraPins(pins));
        cliente.start();
        ClientSession s = null;
        try {
            s = cliente.connect(usuario, host, porta).verify(cfg.conectar()).getSession();
            s.addPublicKeyIdentity(chave);
            s.auth().verify(cfg.autenticar());
            ResultadoExec r = new ExecRemoto(s, cfg.abrirCanal()).executar(COMANDO_DA_PROVA, new byte[0], Duration.ofSeconds(15));
            if (r.status() != 0 || !r.stdout().strip().equals(RESPOSTA_ESPERADA)) {
                throw new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE,
                    "A chave entrou, mas o servidor não deixou rodar o comando de teste. Nada mais foi alterado.");
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrompido durante a prova da chave.");
        } catch (IOException | RuntimeException e) {
            throw traduzir(e, verificador);
        } finally {
            try {
                if (s != null) {
                    s.close(true);
                }
            } catch (RuntimeException e) {
                LOG.debug("Falha ao fechar a sessão da prova: {}", Redator.redigir(e));
            } finally {
                cliente.stop();
            }
        }
    }

    private FalhaDeEntradaPorSenha traduzir(Throwable causa, VerificadorDeChaveDoServidor verificador) {
        LOG.info("Prova da chave em {}:{} não passou: {}", host, porta, Redator.redigir(causa));
        VerificadorDeChaveDoServidor.Resultado conferencia = verificador.ultimo();
        if (conferencia == VerificadorDeChaveDoServidor.Resultado.DIVERGENTE
            || conferencia == VerificadorDeChaveDoServidor.Resultado.SEM_PIN_PARA_O_ALGORITMO) {
            return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.HOST_MUDOU,
                "A chave do servidor mudou durante o teste. Nada foi enviado. Confira o endereço e a porta.");
        }
        for (Throwable t = causa; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("No more authentication methods") || m.contains("Authentication failed")
                || m.contains("authentication failed"))) {
                return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE,
                    "O servidor não aceitou a chave. Confira se ele permite login por chave e se a pasta .ssh e o arquivo "
                        + "authorized_keys do usuário têm as permissões certas. Nada mais foi alterado.");
            }
        }
        return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_CONEXAO, SondagemDeHost.traduzirFalha(host, porta, causa), causa);
    }
}
