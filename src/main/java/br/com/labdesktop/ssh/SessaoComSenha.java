package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.seguranca.Redator;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uma sessão SSH aberta com a SENHA do usuário, para o primeiro acesso: serve para instalar a chave pública e acaba ali. Existe
 * num cliente SEPARADO do cliente normal do app (o normal continua só publickey): cria o seu {@link SshClient} blindado e troca o único fator
 * de autenticação pelo da senha ({@link EntradaPorSenha}). Nada fica guardado: a sessão e o cliente fecham em {@link #close()}.
 *
 * <p>A ordem que protege a senha: a chave do servidor é conferida contra o {@link Pin} que o usuário confirmou ANTES de qualquer pedido de
 * autenticação (o verificador devolve falso se não bate, o MINA fecha a conexão e a senha nem é escrita). Um servidor não confirmado nunca
 * recebe a senha. A senha chega como {@code char[]} e é de quem a passou: esta classe não a guarda depois do {@link #abrir}, nem a põe em
 * log, mensagem ou {@code toString}. Quem abre zera o {@code char[]} em seguida, deu certo ou não.
 *
 * <p>Bloqueia (rede): thread de fundo, nunca a EDT.
 */
public final class SessaoComSenha implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SessaoComSenha.class);

    private final SshClient cliente;
    private final ClientSession sessao;
    private final ConfigSsh cfg;

    private SessaoComSenha(SshClient cliente, ClientSession sessao, ConfigSsh cfg) {
        this.cliente = cliente;
        this.sessao = sessao;
        this.cfg = cfg;
    }

    /**
     * Abre a sessão entrando com a senha.
     *
     * @param pin   a impressão digital do servidor que o usuário confirmou (sem ela a senha não sai daqui)
     * @param senha a senha, em {@code char[]}; lida uma vez, não copiada, não zerada aqui
     * @throws FalhaDeEntradaPorSenha senha errada, servidor sem senha, conta bloqueada, host que mudou, sem conexão (o texto é para o usuário)
     */
    public static SessaoComSenha abrir(String host, int porta, String usuario, Pin pin, char[] senha, ConfigSsh cfg)
        throws FalhaDeEntradaPorSenha {
        VerificadorDeChaveDoServidor verificador = new VerificadorDeChaveDoServidor(() -> List.of(pin));
        EntradaPorSenha.Relato relato = new EntradaPorSenha.Relato();
        SshClient cliente = ClienteSshFactory.criar(verificador, cfg, AlgoritmosDeHost.paraPins(List.of(pin)));
        cliente.setUserAuthFactories(List.of(new EntradaPorSenha.Fabrica(senha, relato)));
        cliente.start();
        ClientSession s = null;
        try {
            s = cliente.connect(usuario, host, porta).verify(cfg.conectar()).getSession();
            s.auth().verify(cfg.autenticar());
            return new SessaoComSenha(cliente, s, cfg);
        } catch (IOException | RuntimeException e) {
            fecharQuieto(cliente, s);
            if (e instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
            }
            throw traduzir(e, verificador, relato, host, porta);
        }
    }

    /** Roda UM comando FIXO nesta sessão (o {@link ExecRemoto} da sessão), com o stdin dado. */
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        return new ExecRemoto(sessao, cfg.abrirCanal()).executar(comando, stdin, prazo);
    }

    @Override
    public void close() {
        fecharQuieto(cliente, sessao);
    }

    private static void fecharQuieto(SshClient cliente, ClientSession sessao) {
        try {
            if (sessao != null) {
                sessao.close(true);
            }
        } catch (RuntimeException e) {
            LOG.debug("Falha ao fechar a sessão com senha: {}", Redator.redigir(e));
        } finally {
            cliente.stop();
        }
    }

    /** Do que o MINA lançou e do que o relato da tentativa sabe para a frase que o usuário lê. Nada daqui carrega a senha. */
    private static FalhaDeEntradaPorSenha traduzir(Throwable causa, VerificadorDeChaveDoServidor verificador, EntradaPorSenha.Relato relato,
                                                   String host, int porta) {
        LOG.info("Entrada com senha em {}:{} não deu certo (senha enviada={}, recusada={}): {}", host, porta, relato.senhaEnviada.get(),
            relato.recusada.get(), Redator.redigir(causa));
        VerificadorDeChaveDoServidor.Resultado conferencia = verificador.ultimo();
        if (conferencia == VerificadorDeChaveDoServidor.Resultado.DIVERGENTE
            || conferencia == VerificadorDeChaveDoServidor.Resultado.SEM_PIN_PARA_O_ALGORITMO) {
            return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.HOST_MUDOU,
                "A chave do servidor não é a que você confirmou. A senha NÃO foi enviada. Pode ser outro aparelho na rede ou o servidor foi "
                    + "reinstalado: confira o endereço e a porta (botão Voltar).");
        }
        if (relato.expirada.get()) {
            return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SENHA_EXPIRADA,
                "O servidor diz que a senha venceu e pede para trocá-la. Entre uma vez no servidor, troque a senha e tente de novo.");
        }
        if (relato.recusada.get()) {
            return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SENHA_INCORRETA,
                "O servidor não aceitou esta senha. Confira o usuário e digite a senha de novo.");
        }
        if (relato.senhaEnviada.get()) {
            return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.CONTA_BLOQUEADA,
                "O servidor encerrou a conexão depois da senha. A conta pode estar bloqueada por tentativas demais, ou o servidor limita "
                    + "as tentativas. Espere alguns minutos e tente de novo; se continuar, peça a quem administra o servidor.");
        }
        if (!relato.metodoIniciado.get() && semMetodoDeAutenticacao(causa)) {
            return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SERVIDOR_SEM_SENHA,
                "Este servidor não aceita entrar com senha (só com chave). Instale a chave pública nele pelas opções avançadas: o app "
                    + "mostra a chave e o comando.");
        }
        return new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_CONEXAO, SondagemDeHost.traduzirFalha(host, porta, causa), causa);
    }

    private static boolean semMetodoDeAutenticacao(Throwable causa) {
        for (Throwable t = causa; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("No more authentication methods") || m.contains("Authentication failed"))) {
                return true;
            }
        }
        return false;
    }
}
