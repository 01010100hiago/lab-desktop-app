package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Os métodos que o servidor oferece, lidos numa conexão nova sem senha nenhuma (uma chave descartável é recusada e a recusa traz a lista). */
class SondaDeMetodosTest {

    @Test
    void servidorSoComChaveOfereceSoPublickey() throws Exception {
        try (ServidorSshDeTeste s = ServidorSshDeTeste.ed25519()) {
            List<String> metodos = SondaDeMetodos.para("127.0.0.1", s.porta(), "u", List.of(s.pin()), ConfigSsh.rapida()).ler();
            assertEquals(List.of("publickey"), metodos.stream().filter(m -> !m.equals("keyboard-interactive")).toList());
            assertTrue(s.senhasVistas.isEmpty(), "nenhuma senha foi enviada");
        }
    }

    @Test
    void servidorComSenhaOfereceAPasswordETambemNaoViuSenhaNenhuma() throws Exception {
        try (ServidorSshDeTeste s = ServidorSshDeTeste.ed25519()) {
            s.aceitarSenha("u", "x");
            List<String> metodos = SondaDeMetodos.para("127.0.0.1", s.porta(), "u", List.of(s.pin()), ConfigSsh.rapida()).ler();
            assertTrue(metodos.contains("password"), metodos.toString());
            assertTrue(s.senhasVistas.isEmpty(), "só olha o que é oferecido: não manda senha");
        }
    }

    @Test
    void hostQueNaoBateComOPinNaoDaListaENaoEnviaNada() throws Exception {
        try (ServidorSshDeTeste s = ServidorSshDeTeste.ed25519(); ServidorSshDeTeste outro = ServidorSshDeTeste.ed25519()) {
            assertNull(SondaDeMetodos.para("127.0.0.1", s.porta(), "u", List.of(outro.pin()), ConfigSsh.rapida()).ler());
            assertFalse(s.pedidosDeAutenticacao.get() > 0, "nem o pedido de autenticação saiu para um host não confirmado");
        }
    }
}
