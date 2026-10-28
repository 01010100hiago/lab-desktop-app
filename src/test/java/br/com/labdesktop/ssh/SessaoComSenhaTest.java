package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha.Motivo;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.junit.jupiter.api.Test;

/**
 * A entrada por SENHA, no cliente SEPARADO do app (o normal continua só publickey): entra, recusa, diz o motivo em português e,
 * principalmente, NUNCA manda a senha a um host que o usuário não confirmou.
 */
class SessaoComSenhaTest {

    private static final String USUARIO = "labuser";
    private static final String SENHA = "uma-senha-so-de-teste-9Qx";

    private static char[] senha(String s) {
        return s.toCharArray();
    }

    @Test
    void entraComASenhaRodaUmComandoEFecha() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            servidor.aceitarSenha(USUARIO, SENHA);
            servidor.comandoRemoto((linha, in, out) -> {
                out.append("ola\n");
                return 0;
            });
            char[] digitada = senha(SENHA);
            try (SessaoComSenha s = SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, servidor.pin(), digitada, ConfigSsh.rapida())) {
                ResultadoExec r = s.executar("echo ola", new byte[0], Duration.ofSeconds(5));
                assertEquals(0, r.status());
                assertEquals("ola\n", r.stdout());
            }
            assertEquals(java.util.List.of(SENHA), servidor.senhasVistas, "a senha chegou ao servidor uma vez, depois da conferência da chave dele");
            assertEquals(0, servidor.autenticacoesAceitas.get(), "o contador do servidor é só o da chave: a entrada foi por senha");
            assertTrue(servidor.chavesOferecidas.isEmpty(), "nenhuma chave foi oferecida: o cliente da senha só fala senha");
        }
    }

    @Test
    void senhaErradaDizSenhaIncorretaEOServidorViuSoUmaTentativa() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            servidor.aceitarSenha(USUARIO, SENHA);
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, servidor.pin(), senha("outra-senha"), ConfigSsh.rapida()));
            assertEquals(Motivo.SENHA_INCORRETA, e.motivo());
            assertTrue(e.getMessage().contains("não aceitou esta senha"), e.getMessage());
            assertFalse(e.getMessage().contains("outra-senha"), "a mensagem nunca leva a senha");
            assertEquals(java.util.List.of("outra-senha"), servidor.senhasVistas, "uma única tentativa por conexão");
        }
    }

    @Test
    void servidorSemLoginPorSenhaDizQueNaoAceitaSenha() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            // nenhum autenticador de senha: o servidor só aceita chave
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, servidor.pin(), senha(SENHA), ConfigSsh.rapida()));
            assertEquals(Motivo.SERVIDOR_SEM_SENHA, e.motivo());
            assertTrue(e.getMessage().contains("não aceita entrar com senha"), e.getMessage());
            assertTrue(servidor.senhasVistas.isEmpty(), "se o servidor não oferece senha, o app não manda nenhuma");
        }
    }

    @Test
    void servidorQueLargaAConexaoDepoisDaSenhaViraContaBloqueada() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            servidor.largarAoReceberSenha();
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, servidor.pin(), senha(SENHA), ConfigSsh.rapida()));
            assertEquals(Motivo.CONTA_BLOQUEADA, e.motivo());
            assertTrue(e.getMessage().contains("conta pode estar bloqueada"), e.getMessage());
        }
    }

    @Test
    void senhaVencidaPedeParaTrocarNoServidor() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            servidor.pedirTrocaDeSenha();
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, servidor.pin(), senha(SENHA), ConfigSsh.rapida()));
            assertEquals(Motivo.SENHA_EXPIRADA, e.motivo());
            assertTrue(e.getMessage().contains("senha venceu"), e.getMessage());
        }
    }

    @Test
    void hostNaoConfirmadoNuncaRecebeASenhaNemOPedidoDeAutenticacao() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519(); ServidorSshDeTeste outro = ServidorSshDeTeste.ed25519()) {
            servidor.aceitarSenha(USUARIO, SENHA);
            Pin deOutroServidor = outro.pin(); // o usuário confirmou a impressão de OUTRA máquina: esta é um impostor
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, deOutroServidor, senha(SENHA), ConfigSsh.rapida()));
            assertEquals(Motivo.HOST_MUDOU, e.motivo());
            assertTrue(e.getMessage().contains("A senha NÃO foi enviada"), e.getMessage());
            Espera.dormir(300); // tempo para um pedido tardio, se existisse, aparecer
            assertEquals(0, servidor.pedidosDeAutenticacao.get(), "nem o serviço de autenticação foi pedido a um host não confirmado");
            assertTrue(servidor.senhasVistas.isEmpty(), "o servidor falso NUNCA viu a senha");
            assertEquals(0, servidor.autenticacoesAceitas.get());
        }
    }

    @Test
    void semConexaoDizOQueConferirSemTextoDeExcecao() throws Exception {
        int portaFechada;
        try (ServerSocket s = new ServerSocket(0)) {
            portaFechada = s.getLocalPort();
        }
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", portaFechada, USUARIO, servidor.pin(), senha(SENHA), ConfigSsh.rapida()));
            assertEquals(Motivo.SEM_CONEXAO, e.motivo());
            assertTrue(e.getMessage().contains("recusou a conexão"), e.getMessage());
            assertFalse(e.getMessage().contains("Exception"), e.getMessage());
        }
    }

    @Test
    void aSenhaNaoVaiParaLogNemParaMensagemNemParaToStringEOCharArrayContinuaDeQuemChamou() throws Exception {
        PrintStream erroOriginal = System.err;
        ByteArrayOutputStream capturado = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturado, true, StandardCharsets.UTF_8));
        String mensagens;
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            servidor.aceitarSenha(USUARIO, "a-senha-certa");
            char[] digitada = senha("senha-DISTINTA-de-teste-7Zp");
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir("127.0.0.1", servidor.porta(), USUARIO, servidor.pin(), digitada, ConfigSsh.rapida()));
            mensagens = e.getMessage() + " " + e + " " + java.util.Arrays.toString(e.getSuppressed());
            // quem chamou é o responsável pelo char[]: a sessão não o apagou nem o guardou (a zeragem é de quem o criou)
            assertEquals("senha-DISTINTA-de-teste-7Zp", new String(digitada));
        } finally {
            System.setErr(erroOriginal);
        }
        String log = capturado.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("senha-DISTINTA-de-teste-7Zp"), "o log não pode ter a senha");
        assertFalse(mensagens.contains("senha-DISTINTA-de-teste-7Zp"));
    }

    @Test
    void oClienteNormalDoAppContinuaSoComPublickeyEOdaSenhaNaoVazaParaEle() {
        SshClient normal = ClienteSshFactory.criar((s, a, k) -> true, ConfigSsh.rapida(), AlgoritmosDeHost.PARA_SONDAGEM);
        assertEquals(1, normal.getUserAuthFactories().size());
        assertTrue(normal.getUserAuthFactories().get(0) instanceof UserAuthPublicKeyFactory, "o cliente normal só tem publickey");
        assertEquals("password", EntradaPorSenha.NOME);
    }

    @Test
    void asSenhasComAcentoViajamEmUtf8() {
        byte[] bytes = EntradaPorSenha.emBytes("sénha-ção".toCharArray());
        assertEquals("sénha-ção", new String(bytes, StandardCharsets.UTF_8));
    }
}
