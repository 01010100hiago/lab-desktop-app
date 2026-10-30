package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.teste.LancadorFalso;
import br.com.labdesktop.teste.PortasFalsas;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * O túnel externo com um lançador e portas de MENTIRA: sobe, cai, demora, não sobe, solta lixo na saída; o programa é encerrado ao fechar; a corrida da porta; o PATH do
 * processo filho do aws; e o registro do gancho de desligamento.
 */
class TunelDeTransporteTest {

    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22);
    private static final Transporte CLOUD = Transporte.cloudflared("ssh.example.com");

    private final PortasFalsas portas = new PortasFalsas(40001, 40002, 40003, 40004);
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);

    private TunelDeTransporte.Dependencias deps(LancadorFalso lancador, LocalizadorDeFerramenta loc, ConfigDoTunel cfg, SistemaOperacional so, String path) {
        return new TunelDeTransporte.Dependencias(lancador, loc, portas, registro, cfg, so, path, so == SistemaOperacional.WINDOWS ? ";" : ":");
    }

    private TunelDeTransporte.Dependencias deps(LancadorFalso lancador) {
        return deps(lancador, tudoInstalado(), ConfigDoTunel.rapida(), SistemaOperacional.MAC, "/usr/bin:/bin");
    }

    private static LocalizadorDeFerramenta tudoInstalado() {
        return f -> Optional.of(switch (f) {
            case AWS -> "/opt/homebrew/bin/aws";
            case PLUGIN_SSM -> "/usr/local/sessionmanagerplugin/bin/session-manager-plugin";
            case CLOUDFLARED -> "/opt/homebrew/bin/cloudflared";
        });
    }

    // ----- sobe -----

    @Test
    void sobeEscutaSoEm127001ComOsArgumentosExatosEEntregaAPorta() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        try (TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { })) {
            assertEquals("127.0.0.1", t.host());
            assertEquals(40001, t.porta());
            assertTrue(t.vivo());
            assertEquals(1, lancador.lancamentos.size());
            assertEquals(List.of("/opt/homebrew/bin/cloudflared", "access", "tcp", "--hostname", "ssh.example.com", "--url", "127.0.0.1:40001"), lancador.lancamentos.get(0).argumentos);
            assertEquals(1, registro.quantos());
            assertTrue(t.falhaDoTunel().isEmpty());
        }
        assertEquals(0, registro.quantos());
    }

    @Test
    void ssmSobeComOsArgumentosExatosEAPastaDoPluginNoPathDoFilho() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        try (TunelDeTransporte t = TunelDeTransporte.abrir(SSM, deps(lancador), f -> { })) {
            assertEquals(List.of("/opt/homebrew/bin/aws", "ssm", "start-session", "--target", "i-0123456789abcdef0", "--document-name", "AWS-StartPortForwardingSession",
                "--parameters", "portNumber=22,localPortNumber=40001", "--region", "us-east-1", "--profile", "meu-perfil"), lancador.lancamentos.get(0).argumentos);
            Map<String, String> ambiente = lancador.lancamentos.get(0).ambiente;
            assertEquals(Map.of("PATH", "/usr/local/sessionmanagerplugin/bin:/usr/bin:/bin"), ambiente, "só o PATH, com a pasta do plugin na frente e o resto do PATH depois");
        }
    }

    @Test
    void noWindowsOPathDoFilhoUsaPontoEVirgula() throws Exception {
        LocalizadorDeFerramenta win = f -> Optional.of(switch (f) {
            case AWS -> "C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe";
            case PLUGIN_SSM -> "C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe";
            case CLOUDFLARED -> "C:\\Program Files\\cloudflared\\cloudflared.exe";
        });
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        try (TunelDeTransporte t = TunelDeTransporte.abrir(SSM, deps(lancador, win, ConfigDoTunel.rapida(), SistemaOperacional.WINDOWS, "C:\\Windows;C:\\Windows\\System32"), f -> { })) {
            assertEquals("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe", lancador.lancamentos.get(0).argumentos.get(0));
            assertEquals("C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin;C:\\Windows;C:\\Windows\\System32", lancador.lancamentos.get(0).ambiente.get("PATH"));
        }
    }

    @Test
    void cloudflareNaoMexeNoAmbiente() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        try (TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { })) {
            assertEquals(Map.of(), lancador.lancamentos.get(0).ambiente);
        }
    }

    @Test
    void semPathNenhumOFilhoRecebeSoAPastaDoPlugin() {
        assertEquals("/p", TunelDeTransporte.pathComAPasta("/p", null, ":"));
        assertEquals("/p", TunelDeTransporte.pathComAPasta("/p", " ", ":"));
        assertEquals("/p:/a", TunelDeTransporte.pathComAPasta("/p", "/a", ":"));
        assertEquals("/a", TunelDeTransporte.pathComAPasta("", "/a", ":"));
    }

    // ----- fechar -----

    @Test
    void fecharEncerraOProgramaLiberaAPortaERetiraDoRegistro() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { });
        LancadorFalso.ProcessoFalso p = lancador.lancamentos.get(0).processo;
        assertTrue(portas.escutaAlguem(40001));
        t.close();
        assertFalse(p.vivo(), "o programa foi encerrado");
        assertEquals(1, p.pedidosDeEncerrar.get());
        assertFalse(portas.escutaAlguem(40001), "a porta foi liberada");
        assertEquals(0, registro.quantos());
        assertFalse(t.vivo());
        t.close(); // idempotente
        assertEquals(1, p.pedidosDeEncerrar.get(), "fechar de novo não encerra de novo");
        assertTrue(t.falhaDoTunel().isEmpty(), "fechado de propósito não é falha");
    }

    @Test
    void fecharDePropositoNuncaChamaOAvisoDeQueda() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        List<FalhaDoTunel> quedas = new CopyOnWriteArrayList<>();
        TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), quedas::add);
        t.close();
        assertTrue(quedas.isEmpty());
    }

    // ----- cai sozinho -----

    @Test
    void seProgramaTerminarSozinhoDepoisDeProntoOAvisoDeQuedaRecebeACausaUmaVez() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        List<FalhaDoTunel> quedas = new CopyOnWriteArrayList<>();
        TunelDeTransporte t = TunelDeTransporte.abrir(SSM, deps(lancador), quedas::add);
        LancadorFalso.Lancamento l = lancador.lancamentos.get(0);
        l.processo.escrever("An error occurred (ExpiredToken) when calling the StartSession operation");
        l.cair(255);
        assertEquals(1, quedas.size());
        assertEquals(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, quedas.get(0).tipo());
        assertEquals(Optional.of(quedas.get(0)), t.falhaDoTunel());
        assertFalse(t.vivo());
        assertFalse(portas.escutaAlguem(40001));
        t.close();
        assertEquals(1, quedas.size(), "fechar depois da queda não avisa de novo");
        assertEquals(0, registro.quantos());
    }

    @Test
    void falhaDoTunelConfereNaHoraSemDependerDoAvisoQuePodeAindaNaoTerChegado() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { });
        assertTrue(t.falhaDoTunel().isEmpty());
        lancador.lancamentos.get(0).processo.escrever("ERR boom");
        lancador.lancamentos.get(0).cair(9);
        FalhaDoTunel f = t.falhaDoTunel().orElseThrow();
        assertTrue(f.mensagem().contains("boom"), f.mensagem());
        t.close();
    }

    @Test
    void sessaoPerdidaComOSocketFechadoUmInstanteAntesDoProcessoMorrerEsperaACausaEAchaOLoginDaAws() throws Exception {
        // a corrida real: o sistema fecha os sockets do túnel (e a sessão SSH cai) ANTES de o processo ser dado como morto e de a saída ser lida
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        TunelDeTransporte t = TunelDeTransporte.abrir(SSM, deps(lancador), f -> { });
        LancadorFalso.Lancamento l = lancador.lancamentos.get(0);
        assertTrue(t.vivo()); // o socket já fechou e a sessão SSH caiu, mas o processo ainda consta como vivo
        assertTrue(t.falhaDoTunel().isEmpty(), "na hora ainda não há causa: é a janela da corrida");
        Thread morre = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            l.processo.escrever("An error occurred (ExpiredToken) when calling the StartSession operation");
            l.cair(255);
        });
        long inicio = System.nanoTime();
        FalhaDoTunel f = t.falhaDoTunelAposPerderASessao().orElseThrow();
        assertEquals(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, f.tipo(), "a causa chegou durante a espera curta, e não 'conexão caiu'");
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 1_400, "espera só o que precisa");
        morre.join();
        t.close();
    }

    @Test
    void sessaoPerdidaComOTunelDeVoltaDePeEsperaSoOPrazoCurtoESemCausaDeTunel() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { });
        long inicio = System.nanoTime();
        assertTrue(t.falhaDoTunelAposPerderASessao().isEmpty(), "o túnel segue de pé: a sessão caiu por outro motivo, sem causa de túnel");
        long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
        assertTrue(ms >= 250 && ms < 3_000, "espera o prazo curto da configuração (300 ms) e não mais: " + ms);
        assertTrue(t.vivo(), "e não encerra nada");
        t.close();
    }

    @Test
    void sessaoPerdidaComACausaJaConhecidaNaoEsperaNada() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        TunelDeTransporte t = TunelDeTransporte.abrir(SSM, deps(lancador), f -> { });
        lancador.lancamentos.get(0).processo.escrever("An error occurred (ExpiredToken) when calling the StartSession operation");
        lancador.lancamentos.get(0).cair(255);
        long inicio = System.nanoTime();
        assertEquals(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, t.falhaDoTunelAposPerderASessao().orElseThrow().tipo());
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 250, "o processo já morreu: responde na hora");
        t.close();
    }

    // ----- não sobe -----

    @Test
    void ferramentaAusenteNaoLancaNadaEDizOPassoDeInstalacaoDoSistema() {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        LocalizadorDeFerramenta semAws = f -> f == FerramentaExterna.AWS ? Optional.empty() : tudoInstalado().localizar(f);
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class,
            () -> TunelDeTransporte.abrir(SSM, deps(lancador, semAws, ConfigDoTunel.rapida(), SistemaOperacional.MAC, "/usr/bin"), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.FERRAMENTA_AUSENTE, e.falha().tipo());
        assertTrue(e.falha().mensagem().contains("aws"));
        assertEquals("brew install awscli", e.falha().textoCopiavel());
        assertTrue(lancador.lancamentos.isEmpty(), "nada foi lançado");
        assertEquals(0, registro.quantos());
        assertTrue(portas.reservadas.isEmpty(), "nem porta foi reservada");

        FalhaDoTunelException semPlugin = assertThrows(FalhaDoTunelException.class,
            () -> TunelDeTransporte.abrir(SSM, deps(lancador, f -> f == FerramentaExterna.PLUGIN_SSM ? Optional.empty() : tudoInstalado().localizar(f), ConfigDoTunel.rapida(),
                SistemaOperacional.WINDOWS, "C:\\Windows"), f -> { }));
        assertTrue(semPlugin.falha().mensagem().contains("session-manager-plugin"));
        assertTrue(semPlugin.falha().textoCopiavel().contains("instalador oficial da AWS"));
        assertTrue(lancador.lancamentos.isEmpty());

        FalhaDoTunelException semCloud = assertThrows(FalhaDoTunelException.class,
            () -> TunelDeTransporte.abrir(CLOUD, deps(lancador, f -> Optional.empty(), ConfigDoTunel.rapida(), SistemaOperacional.LINUX, "/usr/bin"), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.FERRAMENTA_AUSENTE, semCloud.falha().tipo());
        assertTrue(semCloud.falha().mensagem().contains("O app não instala nada"));
    }

    @Test
    void programaQueSaiLogoComLoginExpiradoDaAMensagemEOProcessoNaoFicaAberto() {
        LancadorFalso lancador = new LancadorFalso(portas, l -> {
            l.processo.escrever("The SSO session associated with this profile has expired or is otherwise invalid.");
            l.cair(255);
        });
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(SSM, deps(lancador), f -> { }));
        assertEquals("Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo.", e.falha().mensagem());
        assertEquals(0, registro.quantos());
        assertEquals(0, lancador.vivos());
        assertEquals(1, lancador.lancamentos.size(), "um login expirado não é motivo para tentar outra porta");
    }

    @Test
    void instanciaSemSsmELoginDaCloudflareSaemComAMensagemCerta() {
        LancadorFalso semSsm = new LancadorFalso(portas, l -> {
            l.processo.escrever("An error occurred (TargetNotConnected) when calling the StartSession operation: i-0abc is not connected.");
            l.cair(254);
        });
        assertEquals(FalhaDoTunel.Tipo.INSTANCIA_SEM_SSM, assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(SSM, deps(semSsm), f -> { })).falha().tipo());

        LancadorFalso pedeLogin = new LancadorFalso(portas, l -> {
            l.processo.escrever("Please open the following URL and log in:\nhttps://dash.cloudflare.com/argotunnel?aud=x\n");
            // continua vivo e escutando, como o cloudflared; a URL vem quando o prazo estoura
        });
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class,
            () -> TunelDeTransporte.abrir(CLOUD, deps(pedeLogin, tudoInstalado(), ConfigDoTunel.rapida().comPrazo(Duration.ofMillis(150)), SistemaOperacional.MAC, ""), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.CLOUDFLARE_PEDE_LOGIN, e.falha().tipo());
        assertEquals("https://dash.cloudflare.com/argotunnel?aud=x", e.falha().textoCopiavel());
        assertEquals(0, pedeLogin.vivos(), "o programa foi encerrado ao estourar o prazo");
    }

    @Test
    void naoLancarOProgramaViraNaoIniciou() {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        lancador.falharAoLancar(new IOException("Cannot run program \"aws\": error=13, Permission denied"));
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(SSM, deps(lancador), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.NAO_INICIOU, e.falha().tipo());
        assertTrue(e.falha().mensagem().contains("Permission denied"));
        assertEquals(0, registro.quantos());
    }

    @Test
    void semPortaLivreNaoIniciaESemLancar() {
        PortasFalsas semPortas = new PortasFalsas();
        LancadorFalso lancador = LancadorFalso.queSobe(semPortas);
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(CLOUD,
            new TunelDeTransporte.Dependencias(lancador, tudoInstalado(), semPortas, registro, ConfigDoTunel.rapida(), SistemaOperacional.MAC, "", ":"), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.NAO_INICIOU, e.falha().tipo());
        assertTrue(lancador.lancamentos.isEmpty());
    }

    // ----- demora -----

    @Test
    void demoraAteSubirMasSobeDentroDoPrazo() throws Exception {
        LancadorFalso lancador = new LancadorFalso(portas, l -> new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                return;
            }
            l.subir();
        }).start());
        try (TunelDeTransporte t = TunelDeTransporte.abrir(SSM, deps(lancador), f -> { })) {
            assertTrue(t.vivo());
            assertEquals(40001, t.porta());
        }
    }

    @Test
    void estouraOPrazoSeNuncaSobeEOProgramaEEncerrado() {
        LancadorFalso lancador = new LancadorFalso(portas, l -> l.processo.escrever("Starting session with SessionId: abc"));
        long inicio = System.nanoTime();
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(SSM,
            deps(lancador, tudoInstalado(), ConfigDoTunel.rapida().comPrazo(Duration.ofMillis(200)), SistemaOperacional.MAC, ""), f -> { }));
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() >= 190, "esperou o prazo");
        assertEquals(FalhaDoTunel.Tipo.PRAZO_ESTOURADO, e.falha().tipo());
        assertTrue(e.falha().mensagem().contains("Starting session with SessionId"));
        assertEquals(0, lancador.vivos());
        assertEquals(0, registro.quantos());
        assertEquals(1, lancador.lancamentos.size());
    }

    @Test
    void sairEsperandoEInterrompidoEncerraOProgramaERelancaAInterrupcao() throws Exception {
        LancadorFalso lancador = new LancadorFalso(portas, l -> { });
        AtomicReference<Throwable> erro = new AtomicReference<>();
        Thread th = new Thread(() -> {
            try {
                TunelDeTransporte.abrir(SSM, deps(lancador, tudoInstalado(), ConfigDoTunel.rapida().comPrazo(Duration.ofSeconds(20)), SistemaOperacional.MAC, ""), f -> { });
            } catch (Throwable e) {
                erro.set(e);
            }
        });
        th.start();
        while (lancador.lancamentos.isEmpty()) {
            Thread.sleep(5);
        }
        th.interrupt();
        th.join(5_000);
        assertTrue(erro.get() instanceof InterruptedException, String.valueOf(erro.get()));
        assertEquals(0, lancador.vivos());
        assertEquals(0, registro.quantos());
    }

    // ----- lixo na saída -----

    @Test
    void lixoNaSaidaNuncaVirarComandoNemHtmlNemSegredo() {
        String token = "lab_" + "Z".repeat(43);
        LancadorFalso lancador = new LancadorFalso(portas, l -> {
            l.processo.escrever("<html><script>alert(1)</script></html>\u001b[2J\u0000\u0007\r\n$(rm -rf /) `id` ; " + token + " Authorization: Bearer segredo12345678");
            l.cair(1);
        });
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { }));
        String m = e.falha().mensagem();
        assertFalse(m.contains(token));
        assertFalse(m.contains("segredo12345678"));
        assertFalse(m.chars().anyMatch(Character::isISOControl));
        assertEquals(1, lancador.lancamentos.size(), "o texto da saída nunca é executado: um único lançamento");
    }

    // ----- a corrida da porta -----

    @Test
    void portaTomadaPorOutroProgramaTentaOutraPorta() throws Exception {
        LancadorFalso lancador = new LancadorFalso(portas, l -> {
            if (l.portaPedida() == 40001) {
                l.processo.escrever("listen tcp 127.0.0.1:40001: bind: address already in use");
                l.cair(1);
            } else {
                l.subir();
            }
        });
        try (TunelDeTransporte t = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { })) {
            assertEquals(40002, t.porta(), "usou a segunda porta");
            assertEquals(2, lancador.lancamentos.size());
            assertEquals(List.of(40001, 40002), portas.reservadas);
            assertNotEquals(lancador.lancamentos.get(0).portaPedida(), lancador.lancamentos.get(1).portaPedida());
            assertEquals(1, registro.quantos(), "o primeiro (morto) já saiu do registro");
        }
    }

    @Test
    void portaSempreTomadaDesistedepoisDasTentativasESemOrfao() {
        LancadorFalso lancador = new LancadorFalso(portas, l -> {
            l.processo.escrever("bind: address already in use");
            l.cair(1);
        });
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.PORTA_OCUPADA, e.falha().tipo());
        assertEquals(3, lancador.lancamentos.size(), "tentou 3 portas e parou");
        assertEquals(0, lancador.vivos());
        assertEquals(0, registro.quantos());
    }

    @Test
    void portaTomadaPorOutroProgramaQueContinuaEscutandoNaoDaOkSeONossoMorreu() {
        // alguém escuta na porta, mas o NOSSO programa não está mais de pé: não vale como "pronto"
        LancadorFalso lancador = new LancadorFalso(portas, l -> {
            portas.escutar(l.portaPedida());
            l.processo.escrever("ERR some failure");
            l.cair(2);
            portas.escutar(l.portaPedida()); // o outro programa segue escutando
        });
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.SAIU, e.falha().tipo());
    }

    @Test
    void oProgramaMorreNoInstanteEmQueAPortaApareceEscutandoEstaNaoValeComoPronto() {
        // o programa estava de pé na última conferência e morre antes de a porta ser dada como pronta, enquanto outro programa passa a escutar nela
        LancadorFalso lancador = new LancadorFalso(portas, l -> { });
        PortasLocais aPortaMuda = new PortasLocais() {
            @Override
            public int reservar() throws IOException {
                return portas.reservar();
            }

            @Override
            public boolean alguemEscuta(int porta) {
                lancador.lancamentos.get(0).processo.escrever("ERR morri de repente");
                lancador.lancamentos.get(0).cair(4);
                return true; // alguém escuta (o estranho que tomou a porta), mas o NOSSO programa já não está de pé
            }
        };
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(CLOUD,
            new TunelDeTransporte.Dependencias(lancador, tudoInstalado(), aPortaMuda, registro, ConfigDoTunel.rapida(), SistemaOperacional.MAC, "", ":"), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.SAIU, e.falha().tipo());
        assertTrue(e.falha().mensagem().contains("morri de repente"), e.falha().mensagem());
        assertEquals(0, registro.quantos());
    }

    @Test
    void oCodigoDeSaidaQueChegaUmInstanteDepoisNaoViraMenosUm() {
        // o processo já não está de pé, mas o código de saída só chega 300 ms depois (o leitor da saída ainda esvazia o cano): a frase leva o código de verdade
        java.util.concurrent.CompletableFuture<Integer> saida = new java.util.concurrent.CompletableFuture<>();
        ProcessoExterno quaseMorto = new ProcessoExterno() {
            @Override
            public boolean vivo() {
                return false;
            }

            @Override
            public java.util.concurrent.CompletableFuture<Integer> aoSair() {
                return saida;
            }

            @Override
            public String saida() {
                return "ERR algo deu errado";
            }

            @Override
            public void encerrar() { }
        };
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            saida.complete(9);
        }).start();
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> TunelDeTransporte.abrir(CLOUD,
            new TunelDeTransporte.Dependencias((argumentos, ambienteExtra) -> quaseMorto, tudoInstalado(), portas, registro, ConfigDoTunel.rapida(), SistemaOperacional.MAC, "", ":"), f -> { }));
        assertEquals(FalhaDoTunel.Tipo.SAIU, e.falha().tipo());
        assertTrue(e.falha().mensagem().contains("(código 9)"), e.falha().mensagem());
    }

    // ----- o registro e o gancho -----

    @Test
    void oRegistroEncerraTodosOsTuneisAbertosComoFazOGanchoDeDesligamento() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        TunelDeTransporte a = TunelDeTransporte.abrir(CLOUD, deps(lancador), f -> { });
        TunelDeTransporte b = TunelDeTransporte.abrir(SSM, deps(lancador), f -> { });
        assertEquals(2, registro.quantos());
        assertEquals(2, lancador.vivos());
        registro.encerrarTodos();
        assertEquals(0, lancador.vivos(), "nenhum processo órfão");
        assertEquals(0, registro.quantos());
        assertFalse(a.vivo());
        assertFalse(b.vivo());
        assertFalse(portas.escutaAlguem(40001));
        assertFalse(portas.escutaAlguem(40002));
    }

    @Test
    void oRegistroDeProcessoTemUmSoEOGanchoSoSeInstalaUmaVez() {
        assertTrue(RegistroDeTuneis.doProcesso() == RegistroDeTuneis.doProcesso());
    }

    // ----- direto e local -----

    @Test
    void oAbridorNaoSobeNadaNoDiretoERecusaOLocal() throws Exception {
        LancadorFalso lancador = LancadorFalso.queSobe(portas);
        AbridorDeTuneis abridor = new AbridorDeTuneis(lancador, tudoInstalado(), portas, registro, ConfigDoTunel.rapida(),
            br.com.labdesktop.plataforma.AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin")));
        try (DestinoSsh d = abridor.abrir(Transporte.DIRETO, "192.0.2.5", 2222)) {
            assertEquals("192.0.2.5", d.host());
            assertEquals(2222, d.porta());
            assertTrue(d.falhaDoTunel().isEmpty());
        }
        assertTrue(lancador.lancamentos.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> abridor.abrir(Transporte.LOCAL, "localhost", 0));
        try (DestinoSsh d = abridor.abrir(CLOUD, "ignorado", 99)) {
            assertEquals("127.0.0.1", d.host());
            assertEquals(40001, d.porta());
        }
        assertEquals(0, lancador.vivos());
    }

    @Test
    void oAbridorSoDiretoRecusaOsModosSemPortaAberta() throws Exception {
        assertThrows(FalhaDoTunelException.class, () -> AbridorDeDestino.SO_DIRETO.abrir(SSM, "x", 1));
        try (DestinoSsh d = AbridorDeDestino.SO_DIRETO.abrir(Transporte.DIRETO, "h", 22)) {
            assertEquals("h", d.host());
        }
    }
}
