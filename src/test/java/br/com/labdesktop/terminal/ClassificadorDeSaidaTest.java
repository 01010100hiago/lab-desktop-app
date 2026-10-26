package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.terminal.ClassificadorDeSaida.Veredito;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** As saídas do Docker e do tmux citadas em ARQUITETURA 7.3, e o que cada uma faz com o laço de reanexação. */
class ClassificadorDeSaidaTest {

    private static final Duration RAPIDO = Duration.ofSeconds(1);
    private static final Duration LENTO = Duration.ofSeconds(11);

    private static byte[] b(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Error response from daemon: No such container: lab-claude-orbita\r\n",
        "Error response from daemon: Container 5a0f1c2d3e4f is not running\r\n",
        "no sessions\r\n",
        "can't find session: work\r\n",
        "no server running on /tmp/tmux-0/default\r\n",
        "ERROR RESPONSE FROM DAEMON: NO SUCH CONTAINER: X\r\n"})
    void canalQueFechaRapidoComSessaoAusenteNaoEntraEmLaco(String saida) {
        assertEquals(Veredito.SESSAO_FECHADA, ClassificadorDeSaida.classificar(RAPIDO, 1, b(saida), false));
        assertEquals(Veredito.SESSAO_FECHADA, ClassificadorDeSaida.classificar(RAPIDO, 126, b(saida), false), "o status não muda o veredito");
        assertEquals(Veredito.SESSAO_FECHADA, ClassificadorDeSaida.classificar(RAPIDO, null, b(saida), false));
    }

    @Test
    void aMesmaSaidaDepoisDeDezSegundosEQuedaNormal() {
        // depois de 10 s o texto pode ser a tela do próprio Claude (uma conversa sobre "no sessions"): reanexa
        assertEquals(Veredito.QUEDA,
            ClassificadorDeSaida.classificar(LENTO, 1, b("Error response from daemon: No such container: x"), false));
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(Duration.ofSeconds(10), 1, b("no sessions"), false),
            "10 s exatos já não são 'menos de 10 s'");
        assertEquals(Veredito.SESSAO_FECHADA,
            ClassificadorDeSaida.classificar(Duration.ofMillis(9_999), 1, b("no sessions"), false));
    }

    @Test
    void statusZeroSignificaQueOBashDoTmuxSaiuSemLaco() {
        assertEquals(Veredito.TERMINOU, ClassificadorDeSaida.classificar(LENTO, 0, b("[exited]\r\n"), false));
        assertEquals(Veredito.TERMINOU, ClassificadorDeSaida.classificar(RAPIDO, 0, b(""), false));
        // mas "sessão ausente" tem prioridade: docker exec com sessão que sumiu também pode sair com 0 em alguns casos
        assertEquals(Veredito.SESSAO_FECHADA, ClassificadorDeSaida.classificar(RAPIDO, 0, b("can't find session: work"), false));
    }

    @Test
    void quedaSemStatusOuComStatusDeErroReanexa() {
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(LENTO, null, b("tela do claude..."), false));
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(LENTO, 255, b(""), false));
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(RAPIDO, 137, b("Killed"), false), "container morto: tenta anexar de novo");
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(RAPIDO, null, null, false));
    }

    @Test
    void noShellDoServidorQualquerStatusDeVerdadeEOFimDaConversa() {
        assertEquals(Veredito.TERMINOU, ClassificadorDeSaida.classificar(LENTO, 0, b("logout\r\n"), true));
        assertEquals(Veredito.TERMINOU, ClassificadorDeSaida.classificar(LENTO, 130, b(""), true));
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(LENTO, null, b(""), true), "sem status: a conexão caiu");
        // o shell nunca é julgado pelo texto: um "no sessions" impresso por um comando não é uma sessão do Claude ausente
        assertEquals(Veredito.TERMINOU, ClassificadorDeSaida.classificar(RAPIDO, 0, b("tmux ls\r\nno sessions\r\n"), true));
        assertEquals(Veredito.QUEDA, ClassificadorDeSaida.classificar(RAPIDO, null, b("no sessions"), true));
    }

    @Test
    void soOsUltimosDoisKilobytesContam() {
        String lixo = "x".repeat(ClassificadorDeSaida.BYTES_EXAMINADOS + 500);
        assertFalse(ClassificadorDeSaida.indicaSessaoAusente(b("no sessions\n" + lixo)), "o sinal ficou fora da janela de 2 KB");
        assertTrue(ClassificadorDeSaida.indicaSessaoAusente(b(lixo + "\nno sessions")));
        assertFalse(ClassificadorDeSaida.indicaSessaoAusente(b("")));
        assertFalse(ClassificadorDeSaida.indicaSessaoAusente(null));
    }

    @Test
    void textoQualquerNaoEConfundidoComSessaoAusente() {
        assertFalse(ClassificadorDeSaida.indicaSessaoAusente(b("Connection to host closed.\r\n")));
        assertFalse(ClassificadorDeSaida.indicaSessaoAusente(b("Cannot connect to the Docker daemon at unix:///var/run/docker.sock\r\n")),
            "docker fora do ar é queda: reanexa em vez de dizer que a sessão fechou");
    }
}
