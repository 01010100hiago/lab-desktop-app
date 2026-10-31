package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.transporte.RotaDoSsh;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * A frase de uma falha pela rota direta, por valor conhecido e sem depender do sistema de quem roda: o Windows entrega a recusa de conexão como uma {@code IOException} COMUM, com a
 * frase do próprio Windows (em qualquer idioma), e só a FASE da falha (abrir a conexão ou depois) separa "recusou" de "não consegui obter a chave".
 */
class FrasesDoCaminhoTest {

    private static final RotaDoSsh ROTA = RotaDoSsh.direta("192.0.2.50", 2222);

    @Test
    void aRecusaDoWindowsAoConectarVirasoRecusouNoPassoDaSenhaTambem() {
        IOException comoNoWindows = new IOException("The remote computer refused the network connection");
        String frase = FrasesDoCaminho.traduzir(ROTA, null, comoNoWindows, true);
        assertTrue(frase.contains("192.0.2.50:2222") && frase.contains("recusou a conexão") && frase.contains("Voltar"), frase);
        String emOutroIdioma = FrasesDoCaminho.traduzir(ROTA, null, new IOException("O computador remoto recusou a conexão de rede"), true);
        assertTrue(emOutroIdioma.contains("recusou a conexão") && emOutroIdioma.contains("Confira a porta"), emOutroIdioma);
    }

    @Test
    void semSaberAFaseOuDepoisDeAbrirAIoExceptionComumNaoViraRecusa() {
        IOException comum = new IOException("The remote computer refused the network connection");
        String semFase = FrasesDoCaminho.traduzir(ROTA, null, comum);
        String depoisDeAbrir = FrasesDoCaminho.traduzir(ROTA, null, comum, false);
        for (String frase : new String[] {semFase, depoisDeAbrir}) {
            assertTrue(frase.contains("Não consegui obter a chave do servidor em 192.0.2.50:2222"), frase);
        }
    }

    @Test
    void aRecusaComTipoValeEmQualquerFaseEEmQualquerSistema() {
        String frase = FrasesDoCaminho.traduzir(ROTA, null, new java.net.ConnectException("Connection refused"), false);
        assertTrue(frase.contains("recusou a conexão"), frase);
    }
}
