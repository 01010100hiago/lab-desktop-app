package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.security.KeyPair;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

/** A prova da chave procura a resposta em UMA linha: o que o shell do usuário imprime por conta própria (um .bashrc) não a derruba. */
class ProvaPorChaveTest {

    @Test
    void ruidoAntesEDepoisDaRespostaNaoDerrubaAProva() throws Exception {
        KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        try (ServidorSshDeTeste s = ServidorSshDeTeste.ed25519(par.getPublic())) {
            s.comandoRemoto((linha, in, out) -> {
                out.append("Bem-vindo!\nÚltimo login: ontem\nlab-desktop-prova\ntchau\n");
                return 0;
            });
            assertDoesNotThrow(() -> new ProvaPorChave("127.0.0.1", s.porta(), "u", par, List.of(s.pin()), ConfigSsh.rapida()).provar());
        }
    }

    @Test
    void semAEcoDaRespostaOuComCodigoDeErroFalha() throws Exception {
        KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        try (ServidorSshDeTeste s = ServidorSshDeTeste.ed25519(par.getPublic())) {
            s.comandoRemoto((linha, in, out) -> {
                out.append("so ruido\n");
                return 0;
            });
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> new ProvaPorChave("127.0.0.1", s.porta(), "u", par, List.of(s.pin()), ConfigSsh.rapida()).provar());
            assertEquals(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, e.motivo());
        }
    }
}
