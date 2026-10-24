package br.com.labdesktop.ssh;

import br.com.labdesktop.teste.ServidorSshDeTeste;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import java.net.ServerSocket;
import java.time.Duration;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

/** ARQUITETURA 6.2: a sondagem captura a chave do host e termina ANTES de qualquer autenticação. */
class SondagemDeHostTest {

    @Test
    void capturaAChaveEAbortaSemNenhumaTentativaDeAutenticacao() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            SondagemDeHost.Resultado r = SondagemDeHost.sondar("127.0.0.1", servidor.porta(), ConfigSsh.rapida());

            assertEquals("ssh-ed25519", r.algoritmo());
            assertEquals(KeyUtils.getFingerPrint(servidor.chaveDoHost.getPublic()), r.impressao());
            assertEquals(servidor.pin(), r.pin());
            assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", r.comandoDeConferencia());

            // Zero tentativas: o cliente nem pediu o serviço de autenticação; nenhuma chave foi oferecida.
            Espera.dormir(300); // dá tempo para um pedido tardio, se existisse, aparecer
            assertEquals(0, servidor.pedidosDeAutenticacao.get(), "a sondagem chegou a pedir autenticação");
            assertTrue(servidor.chavesOferecidas.isEmpty());
            assertEquals(0, servidor.autenticacoesAceitas.get());
        }
    }

    @Test
    void mostraOComandoDeConferenciaDoAlgoritmoNegociado() throws Exception {
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.criar("ecdsa-sha2-nistp256")) {
            SondagemDeHost.Resultado r = SondagemDeHost.sondar("127.0.0.1", servidor.porta(), ConfigSsh.rapida());
            assertEquals("ecdsa-sha2-nistp256", r.algoritmo());
            assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub", r.comandoDeConferencia());
            assertEquals(0, servidor.pedidosDeAutenticacao.get());
        }
    }

    @Test
    void hostForaDoArViraErroDeSondagem() throws Exception {
        int portaFechada;
        try (ServerSocket s = new ServerSocket(0)) {
            portaFechada = s.getLocalPort();
        }
        assertThrows(SondagemException.class,
            () -> SondagemDeHost.sondar("127.0.0.1", portaFechada, ConfigSsh.rapida().comHeartbeat(Duration.ofSeconds(1), 2)));
    }

    @Test
    void comandosDeConferenciaPorAlgoritmo() {
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub", AlgoritmosDeHost.comandoDeConferencia("ssh-rsa"));
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub",
            AlgoritmosDeHost.comandoDeConferencia("ecdsa-sha2-nistp384"));
    }
}
