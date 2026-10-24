package br.com.labdesktop.ssh;

import br.com.labdesktop.teste.ServidorSshDeTeste;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void hostForaDoArViraErroDeSondagemEmPortuguesQueDizOQueConferir() throws Exception {
        int portaFechada;
        try (ServerSocket s = new ServerSocket(0)) {
            portaFechada = s.getLocalPort();
        }
        SondagemException e = assertThrows(SondagemException.class,
            () -> SondagemDeHost.sondar("127.0.0.1", portaFechada, ConfigSsh.rapida().comHeartbeat(Duration.ofSeconds(1), 2)));

        String frase = e.getMessage();
        assertTrue(frase.startsWith("O servidor em 127.0.0.1:" + portaFechada + " recusou a conexão."), frase);
        assertTrue(frase.contains("botão Voltar"), "diz onde está o dado a corrigir: " + frase);
        assertFalse(frase.toLowerCase().contains("connection refused") || frase.contains("Exception"),
            "o texto técnico em inglês fica só no log: " + frase);
        assertNotNull(e.getCause(), "a causa técnica continua na exceção, para o log");
    }

    @Test
    void asFalhasComunsDeRedeViramFrasesEmPortuguesComOQueFazer() {
        String recusou = SondagemDeHost.traduzirFalha("192.0.2.50", 2222, new java.net.ConnectException("Connection refused"));
        assertTrue(recusou.contains("192.0.2.50:2222") && recusou.contains("recusou a conexão") && recusou.contains("Voltar"), recusou);

        // O MINA embrulha o erro de rede: a causa de verdade está uns degraus abaixo.
        Throwable embrulhado = new org.apache.sshd.common.SshException("falhou", new java.io.IOException("x",
            new java.net.ConnectException("Connection refused")));
        assertTrue(SondagemDeHost.traduzirFalha("h", 22, embrulhado).contains("recusou a conexão"));

        String tempo = SondagemDeHost.traduzirFalha("192.0.2.50", 2222,
            new org.apache.sshd.common.SshException("Failed to get operation result within specified timeout: 10000 ms"));
        assertTrue(tempo.contains("Não consegui falar com 192.0.2.50 na porta 2222") && tempo.contains("não respondeu a tempo")
            && tempo.contains("Voltar") && tempo.contains("mesma rede"), tempo);
        assertTrue(SondagemDeHost.traduzirFalha("h", 22, new java.net.SocketTimeoutException("Read timed out")).contains("a tempo"));
        assertTrue(SondagemDeHost.traduzirFalha("h", 22, new java.net.ConnectException("Connect timed out")).contains("a tempo"));

        String nome = SondagemDeHost.traduzirFalha("lab.caseiro", 22, new java.net.UnknownHostException("lab.caseiro"));
        assertTrue(nome.startsWith("Não encontrei o endereço lab.caseiro.") && nome.contains("Voltar"), nome);
        assertTrue(SondagemDeHost.traduzirFalha("h", 22, new java.nio.channels.UnresolvedAddressException()).contains("Não encontrei"));

        String rota = SondagemDeHost.traduzirFalha("10.9.9.9", 22, new java.net.NoRouteToHostException("No route to host"));
        assertTrue(rota.contains("não achou caminho até 10.9.9.9") && rota.contains("mesma rede"), rota);
        assertTrue(SondagemDeHost.traduzirFalha("h", 22, new java.io.IOException("Network is unreachable")).contains("não achou caminho"));
    }

    @Test
    void oQueNaoSeReconheceTemUmaFraseGenericaQueMandaConferirEnderecoEPorta() {
        for (Throwable causa : new Throwable[] {null, new IllegalStateException("kex falhou"), new java.io.IOException("Connection reset")}) {
            String frase = SondagemDeHost.traduzirFalha("192.0.2.5", 2222, causa);
            assertTrue(frase.startsWith("Não consegui obter a chave do servidor em 192.0.2.5:2222."), frase);
            assertTrue(frase.contains("Voltar") && frase.contains("não seja a do SSH"), frase);
            assertFalse(frase.contains("kex") || frase.contains("reset"), "nada de texto técnico: " + frase);
        }
    }

    @Test
    void comandosDeConferenciaPorAlgoritmo() {
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub", AlgoritmosDeHost.comandoDeConferencia("ssh-rsa"));
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub",
            AlgoritmosDeHost.comandoDeConferencia("ecdsa-sha2-nistp384"));
    }
}
