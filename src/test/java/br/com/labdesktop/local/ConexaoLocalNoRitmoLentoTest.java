package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.teste.AgendadorFalso;
import br.com.labdesktop.teste.CofreFalso;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Este computador" com o Docker parado ou ausente: o agente não responde, e o app insistia num teste a cada 5 s PARA SEMPRE. Depois de 12 respostas "agente fora" seguidas ele passa a insistir
 * a cada 30 s; "Tentar agora", o computador que acorda e uma resposta boa voltam ao ritmo normal. Relógio falso: nenhuma espera de verdade.
 */
class ConexaoLocalNoRitmoLentoTest {

    private static final Duration PRAZO = Duration.ofSeconds(10);

    private final AgendadorFalso relogio = new AgendadorFalso();
    private final AtomicInteger sondagens = new AtomicInteger();
    private final AtomicReference<SondaDoAgente.Resultado> resposta = new AtomicReference<>(SondaDoAgente.Resultado.AGENTE_FORA);
    private ConexaoLocal conexao;

    @BeforeEach
    void montar() {
        CofreFalso cofre = new CofreFalso("127.0.0.1", 22, List.of()).comoLocal(7070);
        conexao = new ConexaoLocal(CofreFalso.ID, cofre, ConfigLocal.padrao(), p -> {
            sondagens.incrementAndGet();
            return resposta.get();
        }, relogio, (c, in, prazo) -> {
            throw new java.io.IOException("não roda nada neste teste");
        });
    }

    @AfterEach
    void desmontar() {
        conexao.fechar().join();
    }

    /** Espera a n-ésima sondagem terminar e o app voltar a esperar (um timer pendente). */
    private void esperarASondagem(int n) {
        Espera.ate(PRAZO, () -> "sondagem " + n + " feitas=" + sondagens.get() + " estado=" + conexao.info().estado(),
            () -> sondagens.get() == n && conexao.info().estado() == EstadoConexao.SSH_OK_SEM_AGENTE && relogio.pendentes() == 1);
    }

    /** Depois da sondagem {@code n}, a próxima só vem passada a espera {@code esperado} (nem um instante antes). */
    private void aProximaVemEm(int n, Duration esperado) throws Exception {
        relogio.avancar(esperado.minusMillis(100));
        Thread.sleep(80);
        assertEquals(n, sondagens.get(), "antes de " + esperado + " nenhuma sondagem nova");
        relogio.avancar(Duration.ofMillis(100));
        esperarASondagem(n + 1);
    }

    @Test
    void ateAOnzeSeguidasCincoSegundosDepoisTrintaSegundosSemFim() throws Exception {
        conexao.iniciar();
        esperarASondagem(1);
        for (int n = 1; n < 12; n++) {
            aProximaVemEm(n, Duration.ofSeconds(5)); // as respostas 1 a 11 ainda esperam o ritmo normal
        }
        // a 12ª resposta "fora" já espera o ritmo lento
        for (int n = 12; n < 16; n++) {
            aProximaVemEm(n, Duration.ofSeconds(30));
        }
        // uma hora de relógio: no máximo 120 sondagens lentas, nunca 720 de 5 s
        int antes = sondagens.get();
        relogio.avancar(Duration.ofHours(1));
        Espera.ate(PRAZO, "as sondagens da hora", () -> sondagens.get() > antes);
        Thread.sleep(200);
        assertTrue(sondagens.get() - antes <= 121, "em uma hora, no máximo uma sondagem a cada 30 s: " + (sondagens.get() - antes));
    }

    @Test
    void tentarAgoraEORespostaBoaVoltamAoRitmoNormal() throws Exception {
        conexao.iniciar();
        esperarASondagem(1);
        for (int n = 1; n < 12; n++) {
            aProximaVemEm(n, Duration.ofSeconds(5));
        }
        aProximaVemEm(12, Duration.ofSeconds(30)); // já é lento
        int n = sondagens.get();
        conexao.tentarAgora();
        esperarASondagem(n + 1);
        aProximaVemEm(n + 1, Duration.ofSeconds(5)); // depois do Tentar agora, de novo 5 s

        // uma resposta boa também zera: o agente volta, cai, e a contagem recomeça
        resposta.set(SondaDoAgente.Resultado.OK);
        relogio.avancar(Duration.ofSeconds(5));
        Espera.ate(PRAZO, () -> "conectado " + conexao.info(), () -> conexao.info().estado() == EstadoConexao.CONECTADO);
        resposta.set(SondaDoAgente.Resultado.AGENTE_FORA);
        conexao.verificarAgora();
        int m = sondagens.get() + 1;
        esperarASondagem(m);
        aProximaVemEm(m, Duration.ofSeconds(5));
    }

    @Test
    void oComputadorQueAcordaVoltaAoRitmoNormal() throws Exception {
        conexao.iniciar();
        esperarASondagem(1);
        for (int n = 1; n < 12; n++) {
            aProximaVemEm(n, Duration.ofSeconds(5));
        }
        aProximaVemEm(12, Duration.ofSeconds(30));
        int n = sondagens.get();
        conexao.aoDormir(); // no local dormir não muda nada
        conexao.aoAcordar();
        Espera.ate(PRAZO, () -> "espera de acordar " + relogio.pendentes(), () -> relogio.pendentes() == 2); // a espera lenta e a de acordar
        relogio.avancar(ConfigLocal.padrao().esperaAposAcordar());
        Espera.ate(PRAZO, () -> "sondou depois de acordar " + sondagens.get(), () -> sondagens.get() > n && conexao.info().estado() == EstadoConexao.SSH_OK_SEM_AGENTE && relogio.pendentes() == 1);
        aProximaVemEm(sondagens.get(), Duration.ofSeconds(5));
    }

    @Test
    void aConfiguracaoTemOPadraoEAsCopiasNaoPerdem() {
        ConfigLocal p = ConfigLocal.padrao();
        assertEquals(12, p.falhasAteOIntervaloLento());
        assertEquals(Duration.ofSeconds(30), p.intervaloLento());
        assertEquals(12, ConfigLocal.rapida().falhasAteOIntervaloLento());
        assertEquals(Duration.ofSeconds(1), p.comIntervaloLento(2, Duration.ofSeconds(1)).intervaloLento());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> p.comIntervaloLento(0, Duration.ofSeconds(1)));
    }
}
