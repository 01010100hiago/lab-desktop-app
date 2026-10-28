package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.Zeragem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.client.auth.AbstractUserAuth;
import org.apache.sshd.client.auth.AbstractUserAuthFactory;
import org.apache.sshd.client.auth.UserAuth;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.auth.UserAuthMethodFactory;
import org.apache.sshd.common.util.buffer.Buffer;

/**
 * A autenticação por SENHA do MINA, escrita aqui para a senha nunca virar {@code String}: o autenticador pronto do MINA
 * ({@code UserAuthPassword}) só trabalha com {@code String}, que o Java não deixa zerar. Este manda o {@code char[]} que o usuário digitou,
 * codificado em bytes, direto no pacote de autenticação, e zera tudo o que criou (os bytes e o buffer do pacote).
 *
 * <p>Vale para UMA tentativa por conexão: se o servidor recusa, a conexão acaba (quem quiser tentar de novo abre outra, com a senha
 * redigitada). A senha pertence a quem a passou ({@link SessaoComSenha}): aqui só se lê. Não há {@code toString} com conteúdo, nem log.
 * Este fator existe SÓ no cliente da {@link SessaoComSenha}; o cliente normal do app ({@link ClienteSshFactory#criar}) continua só
 * publickey.
 */
final class EntradaPorSenha {

    static final String NOME = "password";
    /** O código 60 do protocolo, na autenticação por senha: "a senha venceu, escolha outra". */
    private static final int SSH_MSG_USERAUTH_PASSWD_CHANGEREQ = 60;

    private EntradaPorSenha() { }

    /** O que se soube da tentativa (sem segredo); lido por quem abriu a conexão para dizer ao usuário o que houve. */
    static final class Relato {
        /** O servidor aceitou começar a conversa de senha (o método estava na lista dele). */
        final AtomicBoolean metodoIniciado = new AtomicBoolean();
        /** O pacote com a senha foi escrito. */
        final AtomicBoolean senhaEnviada = new AtomicBoolean();
        /** O servidor respondeu "falhou" à senha. */
        final AtomicBoolean recusada = new AtomicBoolean();
        /** O servidor pediu para trocar a senha. */
        final AtomicBoolean expirada = new AtomicBoolean();
    }

    /** O fator de autenticação para o cliente: um por conexão, com a senha e o relato dela. */
    static final class Fabrica extends AbstractUserAuthFactory {
        private final char[] senha;
        private final Relato relato;

        Fabrica(char[] senha, Relato relato) {
            super(NOME);
            this.senha = senha;
            this.relato = relato;
        }

        @Override
        public UserAuth createUserAuth(ClientSession sessao) throws IOException {
            return new Autenticacao(senha, relato);
        }
    }

    private static final class Autenticacao extends AbstractUserAuth {
        private final char[] senha;
        private final Relato relato;
        private boolean tentou;

        Autenticacao(char[] senha, Relato relato) {
            super(NOME);
            this.senha = senha;
            this.relato = relato;
        }

        @Override
        public void init(ClientSession sessao, String servico) throws Exception {
            super.init(sessao, servico);
            relato.metodoIniciado.set(true);
        }

        @Override
        protected boolean sendAuthDataRequest(ClientSession sessao, String servico) throws Exception {
            if (tentou) {
                return false; // uma tentativa por conexão
            }
            tentou = true;
            if (!UserAuthMethodFactory.isSecureAuthenticationTransport(sessao)) {
                throw new IOException("A conexão não está cifrada: a senha não foi enviada.");
            }
            Buffer pacote = sessao.createBuffer(SshConstants.SSH_MSG_USERAUTH_REQUEST);
            pacote.putString(sessao.getUsername());
            pacote.putString(servico);
            pacote.putString(NOME);
            pacote.putBoolean(false);
            byte[] bytes = emBytes(senha);
            try {
                pacote.putBytes(bytes);
            } finally {
                Zeragem.zerar(bytes);
            }
            relato.senhaEnviada.set(true);
            sessao.writePacket(pacote).addListener(f -> pacote.clear(true));
            return true;
        }

        @Override
        protected boolean processAuthDataRequest(ClientSession sessao, String servico, Buffer resposta) throws Exception {
            int comando = resposta.getUByte();
            if (comando == SSH_MSG_USERAUTH_PASSWD_CHANGEREQ) {
                relato.expirada.set(true);
                return false; // não trocamos senha de ninguém
            }
            throw new IllegalStateException("Resposta inesperada à senha: " + SshConstants.getCommandMessageName(comando));
        }

        @Override
        public void signalAuthMethodFailure(ClientSession sessao, String servico, boolean parcial, List<String> metodos, Buffer resposta)
            throws Exception {
            relato.recusada.set(true);
        }

        @Override
        public String toString() {
            return "EntradaPorSenha[redigida]";
        }
    }

    /** UTF-8 do {@code char[]}, sem passar por {@code String}; os temporários do codificador são zerados aqui. */
    static byte[] emBytes(char[] senha) {
        ByteBuffer codificado = StandardCharsets.UTF_8.encode(CharBuffer.wrap(senha));
        byte[] saida = new byte[codificado.remaining()];
        codificado.get(saida);
        if (codificado.hasArray()) {
            Zeragem.zerar(codificado.array());
        }
        return saida;
    }
}
