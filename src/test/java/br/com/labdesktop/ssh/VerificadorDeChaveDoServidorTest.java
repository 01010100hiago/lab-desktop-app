package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.io.IOException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

/** ARQUITETURA 6.2: o verificador SÓ compara com o pin; divergente ou sem pin é recusa, sem exceção e sem diálogo. */
class VerificadorDeChaveDoServidorTest {

    @Test
    void aceitaQuandoOPinConfere() throws Exception {
        KeyPair host = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        var v = new VerificadorDeChaveDoServidor(() -> List.of(Pin.de(host.getPublic())));
        assertTrue(v.verifyServerKey(null, null, host.getPublic()));
        assertEquals(VerificadorDeChaveDoServidor.Resultado.OK, v.ultimo());
        assertNull(v.ultimaDivergencia());
    }

    @Test
    void recusaChaveDiferenteDaPinadaEGuardaAsDuasImpressoes() throws Exception {
        KeyPair verdadeira = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        KeyPair impostora = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        var v = new VerificadorDeChaveDoServidor(() -> List.of(Pin.de(verdadeira.getPublic())));
        assertFalse(v.verifyServerKey(null, null, impostora.getPublic()));
        assertEquals(VerificadorDeChaveDoServidor.Resultado.DIVERGENTE, v.ultimo());
        var d = v.ultimaDivergencia();
        assertEquals(KeyUtils.getFingerPrint(verdadeira.getPublic()), d.esperada());
        assertEquals(KeyUtils.getFingerPrint(impostora.getPublic()), d.recebida());
    }

    @Test
    void recusaAlgoritmoSemPin() throws Exception {
        KeyPair ed = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        KeyPair ec = KeyUtils.generateKeyPair("ecdsa-sha2-nistp256", 256);
        var v = new VerificadorDeChaveDoServidor(() -> List.of(Pin.de(ed.getPublic())));
        assertFalse(v.verifyServerKey(null, null, ec.getPublic()));
        assertEquals(VerificadorDeChaveDoServidor.Resultado.SEM_PIN_PARA_O_ALGORITMO, v.ultimo());
        assertNull(v.ultimaDivergencia().esperada());
    }

    @Test
    void semNenhumPinRecusaTudo() throws Exception {
        KeyPair ed = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        var v = new VerificadorDeChaveDoServidor(List::of);
        assertFalse(v.verifyServerKey(null, null, ed.getPublic()));
    }

    @Test
    void lePinsDeNovoACadaVerificacao() throws Exception {
        KeyPair host = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        AtomicReference<List<Pin>> pins = new AtomicReference<>(List.of());
        var v = new VerificadorDeChaveDoServidor(pins::get);
        assertFalse(v.verifyServerKey(null, null, host.getPublic()));
        pins.set(List.of(Pin.de(host.getPublic()))); // o usuário confiou no host (gravou o pin) entre duas tentativas
        assertTrue(v.verifyServerKey(null, null, host.getPublic()));
        v.reiniciar();
        assertEquals(VerificadorDeChaveDoServidor.Resultado.NAO_VERIFICADO, v.ultimo());
        assertNotNull(AlgoritmosDeHost.PARA_SONDAGEM);
    }

    @Test
    void algoritmosOferecidosSaoSoOsPinados() throws Exception {
        KeyPair rsa = KeyUtils.generateKeyPair("ssh-rsa", 2048);
        KeyPair ed = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        // RSA entra como rsa-sha2-*: o ssh-rsa de SHA-1 nunca é oferecido.
        assertEquals(List.of("rsa-sha2-512", "rsa-sha2-256", "ssh-ed25519"),
            AlgoritmosDeHost.paraPins(List.of(Pin.de(rsa.getPublic()), Pin.de(ed.getPublic()))));
    }

    @Test
    void oVereditoFicaNaSessaoQuePerguntouEDuasSessoesNaoSeMisturam() throws Exception {
        // O SshClient é um só por servidor: uma tentativa velha ainda em andamento não pode sujar a leitura da
        // nova. Duas sessões no MESMO cliente e no MESMO verificador, com o pin mudando entre elas.
        try (ServidorSshDeTeste servidor = ServidorSshDeTeste.ed25519()) {
            KeyPair outra = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            AtomicReference<List<Pin>> pins = new AtomicReference<>(List.of(servidor.pin()));
            var verificador = new VerificadorDeChaveDoServidor(pins::get);
            SshClient cliente = ClienteSshFactory.criar(verificador, ConfigSsh.rapida(), List.of("ssh-ed25519"));
            cliente.start();
            try {
                Duration prazo = Duration.ofSeconds(15);
                ClientSession primeira = cliente.connect("u", "127.0.0.1", servidor.porta()).verify(prazo).getSession();
                // Sem identidade, a autenticação falha de qualquer jeito; o que interessa é o veredito do host,
                // que sai ANTES (o key exchange termina e o verificador responde antes do userauth).
                assertThrows(IOException.class, () -> primeira.auth().verify(prazo));

                pins.set(List.of(Pin.de(outra.getPublic()))); // o usuário "redefiniu" o pin entre as tentativas
                ClientSession segunda = cliente.connect("u", "127.0.0.1", servidor.porta()).verify(prazo).getSession();
                assertThrows(IOException.class, () -> segunda.auth().verify(prazo));

                // O veredito sai no key exchange; com a máquina carregada o auth() pode devolver o erro antes de o
                // veredito chegar na sessão, então a leitura espera por ele em vez de supor a ordem.
                var daPrimeira = esperarVeredito(primeira, prazo);
                var daSegunda = esperarVeredito(segunda, prazo);
                assertEquals(VerificadorDeChaveDoServidor.Resultado.OK, daPrimeira.resultado());
                assertNull(daPrimeira.divergencia());
                assertEquals(VerificadorDeChaveDoServidor.Resultado.DIVERGENTE, daSegunda.resultado());
                assertEquals(KeyUtils.getFingerPrint(outra.getPublic()), daSegunda.divergencia().esperada());
                assertEquals(KeyUtils.getFingerPrint(servidor.chaveDoHost.getPublic()), daSegunda.divergencia().recebida());
                // O "último de todos" é o da segunda; o da primeira continua intacto na sessão dela.
                assertEquals(VerificadorDeChaveDoServidor.Resultado.DIVERGENTE, verificador.ultimo());
            } finally {
                cliente.stop();
            }
        }
    }

    private static VerificadorDeChaveDoServidor.Veredito esperarVeredito(ClientSession sessao, Duration prazo)
        throws InterruptedException {
        long limite = System.nanoTime() + prazo.toNanos();
        while (System.nanoTime() < limite) {
            var veredito = sessao.getAttribute(VerificadorDeChaveDoServidor.VEREDITO_DA_SESSAO);
            if (veredito != null) {
                return veredito;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("a sessão não recebeu nenhum veredito do verificador em " + prazo);
    }
}
