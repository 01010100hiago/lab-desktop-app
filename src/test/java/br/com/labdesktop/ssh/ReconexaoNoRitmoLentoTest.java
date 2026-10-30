package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.teste.AgendadorFalso;
import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ProxyTcp;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.DestinoSsh;
import br.com.labdesktop.transporte.FalhaDoTunel;
import br.com.labdesktop.transporte.FalhaDoTunelException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

/**
 * Um servidor que não volta (instância apagada, login da AWS expirado, desligado) não pode ser martelado para sempre: depois de 12 falhas SEGUIDAS a conexão passa ao ritmo lento (uma
 * tentativa a cada 5 minutos), diz isso no retrato ({@link InfoConexao#ritmoLento()}), e só volta ao ritmo normal quando o usuário manda tentar agora, o Mac acorda, a rede muda, o cofre
 * desbloqueia, ou uma conexão boa acontece. Tudo com RELÓGIO FALSO ({@link AgendadorFalso}): nenhuma espera de verdade, e o servidor "que não volta" é um abridor de túnel que falha, sem
 * nenhuma conexão de rede. Só o caso da conexão boa usa o SSH embutido.
 */
class ReconexaoNoRitmoLentoTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final Duration SEG = Duration.ofSeconds(1);
    /** A espera de cada falha no ritmo normal, em segundos (os degraus 1, 2, 4, 8, 15, 30, 60 e o teto de 60). */
    private static final int[] DEGRAUS = {1, 2, 4, 8, 15, 30, 60, 60, 60, 60, 60};

    /** O mundo de um teste: relógio falso, um abridor que falha (ou não) e conta, e o cofre de mentira. */
    private static final class Mundo implements AutoCloseable {
        final AgendadorFalso relogio = new AgendadorFalso();
        final AtomicInteger tentativasDeAbrir = new AtomicInteger();
        final AtomicBoolean falhar = new AtomicBoolean(true);
        final AtomicInteger portaDoSsh = new AtomicInteger();
        final CofreFalso cofre;
        final ConexaoSsh conexao;
        final List<InfoConexao> historico = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ServidorSshDeTeste ssh;
        final AgenteFalso agente;
        final ProxyTcp proxy;

        Mundo(boolean comServidorDeVerdade) throws Exception {
            this(comServidorDeVerdade, ConfigSsh.padrao());
        }

        Mundo(boolean comServidorDeVerdade, ConfigSsh cfg) throws Exception {
            if (comServidorDeVerdade) {
                ssh = ServidorSshDeTeste.ed25519();
                agente = new AgenteFalso();
                proxy = new ProxyTcp(ssh.porta());
                cofre = new CofreFalso("127.0.0.1", proxy.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
                ssh.aceitar(cofre.par().getPublic());
            } else {
                ssh = null;
                agente = null;
                proxy = null;
                cofre = new CofreFalso("127.0.0.1", 1, List.of(Pin.de(KeyUtils.generateKeyPair("ssh-ed25519", 256).getPublic())));
            }
            PoliticaDeBackoff semJitter = new PoliticaDeBackoff(SEG, () -> 0.5); // fator 1,0: as esperas são EXATAMENTE os degraus
            conexao = new ConexaoSsh(CofreFalso.ID, cofre, cfg, t -> SondaDoAgente.Resultado.OK, relogio, semJitter, (transporte, host, porta, aoCair) -> {
                tentativasDeAbrir.incrementAndGet();
                if (falhar.get()) {
                    throw new FalhaDoTunelException(new FalhaDoTunel(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, "O login da AWS expirou neste computador.", "aws sso login"));
                }
                return DestinoSsh.direto(host, porta);
            });
            conexao.observar(historico::add);
        }

        InfoConexao info() {
            return conexao.info();
        }

        /** Espera a n-ésima chamada do abridor terminar (falhou) e a conexão voltar a esperar com UM timer pendente. */
        void esperarAFalha(int n) {
            Espera.ate(PRAZO, () -> "tentativa " + n + " info=" + info() + " chamadas=" + tentativasDeAbrir.get(),
                () -> tentativasDeAbrir.get() == n && info().estado() == EstadoConexao.RECONECTANDO && relogio.pendentes() == 1);
        }

        /** Anda o relógio até a próxima tentativa disparar (exatamente a espera que a conexão anunciou). */
        Duration esperaAnunciada() {
            return Duration.between(relogio.agora(), info().proximaTentativaEm());
        }

        void chegarAFalha(int n) {
            if (n > 1) {
                relogio.avancar(esperaAnunciada());
            }
            esperarAFalha(n);
        }

        @Override
        public void close() {
            try {
                conexao.fechar().join();
                if (proxy != null) {
                    proxy.close();
                }
                if (agente != null) {
                    agente.close();
                }
                if (ssh != null) {
                    ssh.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void ateAOnzeFalhasOBackoffNormalNaDozeAVezPassaAoRitmoLentoDeCincoMinutos() throws Exception {
        try (Mundo m = new Mundo(false)) {
            m.conexao.iniciar();
            for (int n = 1; n <= 11; n++) {
                m.chegarAFalha(n);
                assertFalse(m.info().ritmoLento(), "falha " + n + ": ainda no ritmo normal");
                assertEquals(Duration.ofSeconds(DEGRAUS[n - 1]), m.esperaAnunciada(), "espera depois da falha " + n);
                assertEquals(n, m.info().tentativa());
            }
            m.chegarAFalha(12);
            InfoConexao lenta = m.info();
            assertTrue(lenta.ritmoLento(), "a 12ª falha seguida passa ao ritmo lento");
            assertEquals(EstadoConexao.RECONECTANDO, lenta.estado());
            assertEquals(Duration.ofMinutes(5), m.esperaAnunciada(), "uma tentativa a cada 5 minutos (sem jitter neste teste)");
            assertNotNull(lenta.semConexaoDesde(), "desde quando está sem conexão");
            assertEquals(Falha.Tipo.TRANSPORTE_LOGIN_AWS, lenta.falha().tipo(), "a causa em palavras simples continua na faixa");
            assertEquals(12, m.tentativasDeAbrir.get());
        }
    }

    @Test
    void noRitmoLentoNaoHaTentativaEmLacoApertadoEAsEsperasTemJitter() throws Exception {
        try (Mundo m = new Mundo(false)) {
            m.conexao.iniciar();
            for (int n = 1; n <= 12; n++) {
                m.chegarAFalha(n);
            }
            int antes = m.tentativasDeAbrir.get();
            m.relogio.avancar(Duration.ofMinutes(4).plusSeconds(59));
            Thread.sleep(150);
            assertEquals(antes, m.tentativasDeAbrir.get(), "faltando 1 s para os 5 minutos, nenhuma tentativa nova");
            assertEquals(EstadoConexao.RECONECTANDO, m.info().estado());
            m.relogio.avancar(Duration.ofSeconds(1));
            m.esperarAFalha(antes + 1);
            assertTrue(m.info().ritmoLento(), "continua lento: a tentativa lenta que falha não volta ao ritmo normal");
            assertEquals(Duration.ofMinutes(5), m.esperaAnunciada());

            // uma hora inteira de relógio falso: no máximo 12 tentativas lentas (nada de martelar)
            int comecoDaHora = m.tentativasDeAbrir.get();
            for (int i = 0; i < 12; i++) {
                m.relogio.avancar(m.esperaAnunciada());
                m.esperarAFalha(comecoDaHora + i + 1);
            }
            m.relogio.avancar(Duration.ofMinutes(1)); // uma hora e um minuto depois da 12ª: nenhuma tentativa a mais além da de fechar a conta
            assertEquals(comecoDaHora + 12, m.tentativasDeAbrir.get(), "uma hora = 12 tentativas, nunca mais");

            // o jitter: com outro sorteio a espera lenta muda dentro de 20 %
            PoliticaDeBackoff p = new PoliticaDeBackoff(SEG, () -> 0.0);
            assertEquals(Duration.ofMinutes(5).multipliedBy(8).dividedBy(10), p.lenta(Duration.ofMinutes(5)));
            PoliticaDeBackoff q = new PoliticaDeBackoff(SEG, () -> 0.999999);
            assertTrue(q.lenta(Duration.ofMinutes(5)).compareTo(Duration.ofSeconds(359)) > 0 && q.lenta(Duration.ofMinutes(5)).compareTo(Duration.ofSeconds(360)) <= 0);
            assertEquals(0, p.tentativas(), "a espera lenta não gasta os degraus do ritmo normal");
        }
    }

    @Test
    void tentarAgoraNoRitmoLentoTentaNaHoraEVoltaAoBackoffNormal() throws Exception {
        try (Mundo m = new Mundo(false)) {
            m.conexao.iniciar();
            for (int n = 1; n <= 12; n++) {
                m.chegarAFalha(n);
            }
            assertTrue(m.info().ritmoLento());
            m.conexao.tentarAgora();
            m.esperarAFalha(13);
            assertFalse(m.info().ritmoLento(), "o usuário mandou tentar agora: volta ao ritmo normal");
            assertEquals(Duration.ofSeconds(1), m.esperaAnunciada(), "o backoff normal recomeça do primeiro degrau");
            // as 10 seguintes seguem os degraus de novo (1, 2, 4, 8, 15, 30, 60, 60, 60, 60) e só a 12ª seguida volta ao ritmo lento
            for (int k = 2; k <= 11; k++) {
                m.relogio.avancar(m.esperaAnunciada());
                m.esperarAFalha(12 + k);
                assertFalse(m.info().ritmoLento(), "depois do tentar agora, a falha " + k + " ainda é normal");
                assertEquals(Duration.ofSeconds(DEGRAUS[k - 1]), m.esperaAnunciada());
            }
            m.relogio.avancar(m.esperaAnunciada());
            m.esperarAFalha(24);
            assertTrue(m.info().ritmoLento());
            assertEquals(Duration.ofMinutes(5), m.esperaAnunciada());
        }
    }

    @Test
    void oMacAcordarARedeMudarOCofreDesbloquearEServidorEditadoVoltamAoRitmoNormal() throws Exception {
        for (String evento : List.of("acordar", "rede", "desbloquear", "reconectar")) {
            try (Mundo m = new Mundo(false)) {
                m.conexao.iniciar();
                for (int n = 1; n <= 12; n++) {
                    m.chegarAFalha(n);
                }
                assertTrue(m.info().ritmoLento(), evento);
                int chamadas = m.tentativasDeAbrir.get();
                switch (evento) {
                    case "acordar" -> {
                        m.conexao.aoDormir();
                        Espera.ate(PRAZO, () -> "suspenso " + m.info(), () -> m.info().estado() == EstadoConexao.SUSPENSO);
                        assertFalse(m.info().ritmoLento(), "dormindo não é 'sem conexão no ritmo lento'");
                        m.conexao.aoAcordar();
                        Espera.ate(PRAZO, () -> "espera de acordar " + m.relogio.pendentes(), () -> m.relogio.pendentes() == 1);
                        m.relogio.avancar(ConfigSsh.padrao().esperaAposAcordar());
                    }
                    case "rede" -> m.conexao.aoMudarARede();
                    case "desbloquear" -> {
                        m.conexao.bloquear();
                        Espera.ate(PRAZO, () -> "bloqueado " + m.info(), () -> m.info().estado() == EstadoConexao.BLOQUEADO_COFRE);
                        m.conexao.iniciar();
                    }
                    default -> m.conexao.reconectar();
                }
                Espera.ate(PRAZO, () -> evento + " info=" + m.info(), () -> m.tentativasDeAbrir.get() == chamadas + 1 && m.info().estado() == EstadoConexao.RECONECTANDO && m.relogio.pendentes() == 1);
                assertFalse(m.info().ritmoLento(), evento + ": ritmo normal de novo");
                assertEquals(Duration.ofSeconds(1), m.esperaAnunciada(), evento + ": o backoff recomeça do primeiro degrau");
            }
        }
    }

    @Test
    void aMudancaDeRedeSoMexeComQuemEstaEsperandoParaReconectar() throws Exception {
        try (Mundo m = new Mundo(false)) {
            // parado (nunca iniciou): nada acontece
            m.conexao.aoMudarARede();
            Thread.sleep(100);
            assertEquals(0, m.tentativasDeAbrir.get());
            assertEquals(EstadoConexao.DESCONECTADO, m.info().estado());
            // com o cofre bloqueado também não: espera o desbloqueio
            m.conexao.bloquear();
            Espera.ate(PRAZO, () -> "bloqueado", () -> m.info().estado() == EstadoConexao.BLOQUEADO_COFRE);
            m.conexao.aoMudarARede();
            Thread.sleep(100);
            assertEquals(0, m.tentativasDeAbrir.get(), "rede que muda não desbloqueia o cofre");
            assertEquals(EstadoConexao.BLOQUEADO_COFRE, m.info().estado());
        }
    }

    @Test
    void umaConexaoBoaZeraTudoEDepoisSaoDozeFalhasDeNovoAntesDoRitmoLento() throws Exception {
        try (Mundo m = new Mundo(true)) {
            m.conexao.iniciar();
            for (int n = 1; n <= 11; n++) {
                m.chegarAFalha(n);
                assertFalse(m.info().ritmoLento());
            }
            m.falhar.set(false); // a 12ª tentativa alcança o servidor
            m.relogio.avancar(m.esperaAnunciada());
            Espera.ate(PRAZO, () -> "conectado? " + m.info(), () -> m.info().estado() == EstadoConexao.CONECTADO);
            assertFalse(m.info().ritmoLento());
            assertEquals(0, m.info().tentativa());
            assertNull(m.info().semConexaoDesde());

            // a conexão cai, e o servidor some de novo: a contagem das falhas recomeçou do zero. A queda é a 1ª falha; as tentativas que falham são a 2ª em diante
            m.falhar.set(true);
            int depoisDaBoa = m.tentativasDeAbrir.get();
            m.proxy.derrubarConexoes();
            Espera.ate(PRAZO, () -> "caiu? " + m.info(), () -> m.info().estado() == EstadoConexao.RECONECTANDO && m.relogio.pendentes() == 1);
            assertFalse(m.info().ritmoLento());
            for (int k = 1; k <= 10; k++) {
                m.relogio.avancar(m.esperaAnunciada());
                m.esperarAFalha(depoisDaBoa + k);
                assertFalse(m.info().ritmoLento(), "depois da conexão boa a contagem recomeçou: a falha " + (k + 1) + " ainda é normal");
            }
            m.relogio.avancar(m.esperaAnunciada());
            m.esperarAFalha(depoisDaBoa + 11);
            assertTrue(m.info().ritmoLento(), "só a 12ª seguida depois da conexão boa leva ao ritmo lento");
        }
    }

    @Test
    void falhaDeAutenticacaoEDeChaveDoHostNuncaRepetemEChaveRecusadaTentaUmaVezSo() throws Exception {
        // a chave recusada: UMA nova tentativa depois da espera curta, e para (nada de martelar o servidor nem o fail2ban)
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519(); AgenteFalso agente = new AgenteFalso()) {
            CofreFalso cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
            // o servidor NÃO aceita a chave do cofre
            AgendadorFalso relogio = new AgendadorFalso();
            ConexaoSsh c = new ConexaoSsh(CofreFalso.ID, cofre, ConfigSsh.padrao(), t -> SondaDoAgente.Resultado.OK, relogio, new PoliticaDeBackoff(SEG, () -> 0.5));
            try {
                c.iniciar();
                Espera.ate(PRAZO, () -> "recusada " + c.info(), () -> c.info().estado() == EstadoConexao.ERRO_AUTENTICACAO && relogio.pendentes() == 1);
                int pedidos = ssh.pedidosDeAutenticacao.get();
                relogio.avancar(ConfigSsh.padrao().esperaErroAutenticacao());
                Espera.ate(PRAZO, () -> "segunda recusa " + c.info(), () -> ssh.pedidosDeAutenticacao.get() > pedidos && c.info().estado() == EstadoConexao.ERRO_AUTENTICACAO && relogio.pendentes() == 0);
                int total = ssh.pedidosDeAutenticacao.get();
                relogio.avancar(Duration.ofHours(24));
                Thread.sleep(300);
                assertEquals(total, ssh.pedidosDeAutenticacao.get(), "em um dia de relógio falso, nenhuma tentativa a mais: chave recusada não se resolve sozinha");
                assertFalse(c.info().ritmoLento());
                assertNull(c.info().proximaTentativaEm());
            } finally {
                c.fechar().join();
            }
        }
    }

    @Test
    void aConfiguracaoDoRitmoLentoTemOPadraoECadaCopiaDelaOMantem() {
        ConfigSsh p = ConfigSsh.padrao();
        assertEquals(12, p.falhasAteORitmoLento());
        assertEquals(Duration.ofMinutes(5), p.esperaNoRitmoLento());
        assertEquals(12, ConfigSsh.rapida().falhasAteORitmoLento());
        ConfigSsh outra = p.comRitmoLento(3, Duration.ofSeconds(2));
        assertEquals(3, outra.falhasAteORitmoLento());
        assertEquals(Duration.ofSeconds(2), outra.esperaNoRitmoLento());
        assertEquals(12, p.comHeartbeat(Duration.ofSeconds(1), 5).falhasAteORitmoLento(), "as outras cópias não perdem o ritmo lento");
        assertEquals(Duration.ofSeconds(2), outra.comEstavelApos(Duration.ofSeconds(9)).esperaNoRitmoLento());
        assertEquals(3, outra.comHeartbeat(Duration.ofSeconds(1), 5).falhasAteORitmoLento());
        assertFalse(InfoConexao.inicial().ritmoLento());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> p.comRitmoLento(0, Duration.ofSeconds(1)));
    }
}
