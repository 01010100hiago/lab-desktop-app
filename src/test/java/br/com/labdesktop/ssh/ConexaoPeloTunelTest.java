package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.AbridorDeTuneis;
import br.com.labdesktop.transporte.ConfigDoTunel;
import br.com.labdesktop.transporte.FerramentaExterna;
import br.com.labdesktop.transporte.LocalizadorDeFerramenta;
import br.com.labdesktop.transporte.PortasLocais;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conexão SEM PORTA ABERTA de ponta a ponta: o SSH embutido do repo, um túnel externo de verdade (um processo Java do teste que faz o papel do aws e do cloudflared, rodado
 * pelo mesmo lançador de produção), a {@link ConexaoSsh} do app e a sonda do agente falso pelo túnel do agente. O endereço guardado no cofre NUNCA resolve: se a conexão chega, é
 * porque passou pelo túnel. Nada de AWS, Cloudflare, rede de fora nem servidor real.
 */
class ConexaoPeloTunelTest {

    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final Transporte CLOUD = Transporte.cloudflared("ssh.example.com");
    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22);

    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);

    private AbridorDeTuneis abridor(br.com.labdesktop.transporte.LancadorDeProcesso lancador, LocalizadorDeFerramenta localizador) {
        return new AbridorDeTuneis(lancador, localizador, PortasLocais.doSistema(), registro, ConfigDoTunel.padrao().comPrazo(Duration.ofSeconds(25)),
            AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin:/bin")));
    }

    /** Como {@link #abridor}, mas com folga para o programa do túnel ser dado como morto: a espera acaba assim que ele sai, então num runner lento a causa não se perde por 1 s a menos. */
    private AbridorDeTuneis abridorComFolgaParaACausa(br.com.labdesktop.transporte.LancadorDeProcesso lancador, LocalizadorDeFerramenta localizador) {
        return new AbridorDeTuneis(lancador, localizador, PortasLocais.doSistema(), registro,
            ConfigDoTunel.padrao().comPrazo(Duration.ofSeconds(25)).comEsperaPelaCausa(Duration.ofSeconds(15)),
            AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin:/bin")));
    }

    private static LocalizadorDeFerramenta tudoInstalado() {
        return f -> Optional.of(switch (f) {
            case AWS -> "/ferramentas/aws";
            case PLUGIN_SSM -> "/ferramentas/plugin/session-manager-plugin";
            case CLOUDFLARED -> "/ferramentas/cloudflared";
        });
    }

    private static boolean vivo(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void esperarMorrer(long pid) throws InterruptedException {
        for (int i = 0; i < 100 && vivo(pid); i++) {
            Thread.sleep(50);
        }
    }

    // ----- ponta a ponta -----

    @Test
    void cloudflareDePontaAPontaSshEmbutidoMaisTunelExternoMaisSondaDoAgente() throws Exception {
        PoliticaDeBackoff semEsperaLonga = new PoliticaDeBackoff(Duration.ofMillis(40), Math::random);
        // o lançador só conhece a porta do SSHD depois de o cenário existir: cria o cenário com um abridor que a lê depois
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), semEsperaLonga).sondaPorHttp()) {
            portaDoSsh.set(c.ssh.porta());
            c.ssh.permitirEncaminhamentoSomentePara(c.agente.porta());
            assertEquals(ConfigServidorHost.PLACEHOLDER, c.cofre.servidor(CofreFalso.ID).orElseThrow().host(), "o endereço guardado nunca resolve");

            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info() + " falha=" + c.info().falha(), () -> c.estado() == EstadoConexao.CONECTADO);

            // o SSH chegou ao SSHD embutido POR DENTRO do túnel externo, e a sonda do agente (porta 7000 do servidor) passou pelo SSH
            assertEquals(1, lancador.real().lancados.size());
            var lancado = lancador.real().lancados.get(0);
            assertEquals("/ferramentas/cloudflared", lancado.argumentosDoApp().get(0));
            assertEquals(200, c.getPeloTunel("/api/versao", true), "o agente respondeu pelo túnel do SSH, dentro do transporte");
            assertEquals(401, c.getPeloTunel("/api/versao", false));
            assertTrue(c.chamadasDaSonda.get() >= 1);
            assertEquals(List.of("127.0.0.1:" + c.agente.porta()), c.ssh.destinosPedidos.stream().distinct().toList());
            assertTrue(c.ssh.autenticacoesAceitas.get() >= 1, "autenticou");
            assertEquals(1, c.ssh.chavesOferecidas.stream().distinct().count(), "só com a chave do cofre");
            assertNotNull(c.conexao.tunel().orElse(null));
            assertEquals(1, registro.quantos());

            long pid = lancado.processo().pid();
            assertTrue(vivo(pid));
            c.conexao.fechar().join();
            assertFalse(vivo(pid) && waitDead(pid), "o programa do túnel some assim que fechar() volta");
            assertEquals(0, registro.quantos());
        }
    }

    private static boolean waitDead(long pid) throws InterruptedException {
        esperarMorrer(pid);
        return vivo(pid);
    }

    @Test
    void ssmDePontaAPontaComOMesmoCaminho() throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null).sondaPorHttp()) {
            portaDoSsh.set(c.ssh.porta());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info() + " falha=" + c.info().falha(), () -> c.estado() == EstadoConexao.CONECTADO);
            var lancado = lancador.real().lancados.get(0);
            assertEquals("/ferramentas/aws", lancado.argumentosDoApp().get(0));
            assertTrue(lancado.argumentosDoApp().contains("AWS-StartPortForwardingSession"));
            assertEquals("/ferramentas/plugin:/usr/bin:/bin", lancado.ambienteDoApp().get("PATH"));
            assertEquals(200, c.getPeloTunel("/api/versao", true));
        }
    }

    // ----- pins -----

    @Test
    void oPinFicaPresoAoServidorEPortasAleatoriasNaoInvalidamNemCriamConfiancaNova() throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            portaDoSsh.set(c.ssh.porta());
            List<Pin> pinsAntes = c.cofre.servidor(CofreFalso.ID).orElseThrow().pins();
            assertEquals(1, pinsAntes.size());

            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            int primeiraPorta = portaDoTunel(lancador, 0);
            c.conexao.reconectar();
            Espera.ate(PRAZO, () -> "lançamentos=" + lancador.real().lancados.size(), () -> lancador.real().lancados.size() >= 2);
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);

            assertEquals(2, lancador.real().lancados.size(), "cada conexão sobe o seu túnel");
            int segundaPorta = portaDoTunel(lancador, 1);
            assertTrue(primeiraPorta > 0 && segundaPorta > 0);
            assertEquals(pinsAntes, c.cofre.servidor(CofreFalso.ID).orElseThrow().pins(), "o pin é o mesmo, qualquer que seja a porta local");
            assertTrue(c.cofre.pinsGravados.isEmpty(), "nenhuma confiança nova foi gravada");
            esperarMorrer(lancador.real().lancados.get(0).processo().pid());
            assertFalse(vivo(lancador.real().lancados.get(0).processo().pid()), "o primeiro túnel foi encerrado ao reconectar");
        }
    }

    @Test
    void trocarOModoMantemOPinEConectaSemPerguntarDeNovo() throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            portaDoSsh.set(c.ssh.porta());
            Pin pin = c.cofre.servidor(CofreFalso.ID).orElseThrow().pins().get(0);
            // o servidor era "Cloudflare"; o usuário o troca por SSM (como o Editar faz): o pin continua
            c.cofre.alterarServidor(CofreFalso.ID, atual -> atual.editar(atual.nome(), SSM, "x", 0, atual.usuario(), atual.portaAgente()));
            assertEquals(List.of(pin), c.cofre.servidor(CofreFalso.ID).orElseThrow().pins());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info() + " falha=" + c.info().falha(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertEquals("/ferramentas/aws", lancador.real().lancados.get(0).argumentosDoApp().get(0), "agora é o aws que sobe");
            assertTrue(c.cofre.pinsGravados.isEmpty());
        }
    }

    @Test
    void umaChaveDeHostDiferentePeloTunelContinuaRecusadaSemMandarNenhumaCredencial() throws Exception {
        try (ServidorSshDeTeste outroServidor = ServidorSshDeTeste.ed25519()) {
            AtomicInteger portaDoSsh = new AtomicInteger(outroServidor.porta()); // o túnel leva a OUTRA máquina
            LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
            try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
                outroServidor.aceitar(c.cofre.par().getPublic());
                c.conexao.iniciar();
                Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.ERRO_CHAVE_HOST);
                assertEquals(Falha.Tipo.PIN_DIVERGENTE, c.info().falha().tipo());
                assertEquals(0, outroServidor.pedidosDeAutenticacao.get(), "nenhuma credencial chegou à outra máquina");
                assertTrue(outroServidor.chavesOferecidas.isEmpty());
                assertEquals(0, c.ssh.pedidosDeAutenticacao.get());
                Espera.naoAcontece(Duration.ofMillis(800), "reconexão com pin divergente", () -> lancador.real().lancados.size() > 1);
                long pid = lancador.real().lancados.get(0).processo().pid();
                esperarMorrer(pid);
                assertFalse(vivo(pid), "o túnel foi encerrado mesmo na recusa");
            }
        }
    }

    // ----- erros claros -----

    @Test
    void semAFerramentaSoEsseServidorMostraOAvisoComOPassoDeInstalacaoESemLacoApertado() throws Exception {
        AtomicInteger consultas = new AtomicInteger();
        LocalizadorDeFerramenta semAws = f -> {
            consultas.incrementAndGet();
            return f == FerramentaExterna.AWS ? Optional.empty() : tudoInstalado().localizar(f);
        };
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(new AtomicInteger(1), Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridor(lancador, semAws), ConfigSsh.rapida(), null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.RECONECTANDO && c.info().falha() != null);
            Falha f = c.info().falha();
            assertEquals(Falha.Tipo.TRANSPORTE_FERRAMENTA_AUSENTE, f.tipo());
            assertTrue(f.ehDoTransporte());
            assertTrue(f.mensagem().contains("aws"));
            assertEquals("brew install awscli", f.textoCopiavel());
            Thread.sleep(1_500);
            // o mesmo backoff do resto do app (40 ms * 1, 2, 4, 8, 15...): umas poucas tentativas, nunca centenas
            assertTrue(consultas.get() <= 12, "tentativas demais (laço apertado): " + consultas.get());
            assertTrue(lancador.real().lancados.isEmpty(), "nada foi lançado");
            assertEquals(0, c.ssh.pedidosDeAutenticacao.get());
        }
    }

    @Test
    void programaQueSaiLogoComLoginDaAwsExpiradoDizAFraseCertaEOutrosModosNaoSao() throws Exception {
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(new AtomicInteger(1),
            Map.of("LABTEST_SAIR_LOGO_TEXTO", "The SSO session associated with this profile has expired or is otherwise invalid."));
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.RECONECTANDO && c.info().falha() != null);
            assertEquals(Falha.Tipo.TRANSPORTE_LOGIN_AWS, c.info().falha().tipo());
            assertEquals("Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo.", c.info().falha().mensagem());
            assertEquals("aws sso login --profile meu-perfil", c.info().falha().textoCopiavel());
        }
    }

    @Test
    void instanciaSemSsmELoginDaCloudflareTambemChegamAoEstado() throws Exception {
        LancadorDeFerramentaFalsaAdiado semSsm = new LancadorDeFerramentaFalsaAdiado(new AtomicInteger(1),
            Map.of("LABTEST_SAIR_LOGO_TEXTO", "An error occurred (TargetNotConnected) when calling the StartSession operation: i-0abc is not connected."));
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridor(semSsm, tudoInstalado()), ConfigSsh.rapida(), null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.RECONECTANDO && c.info().falha() != null);
            assertEquals(Falha.Tipo.TRANSPORTE_INSTANCIA_SEM_SSM, c.info().falha().tipo());
        }
        LancadorDeFerramentaFalsaAdiado pedeLogin = new LancadorDeFerramentaFalsaAdiado(new AtomicInteger(1),
            Map.of("LABTEST_SAIR_LOGO_TEXTO", "Please open the following URL and log in:\nhttps://dash.cloudflare.com/argotunnel?aud=x"));
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(pedeLogin, tudoInstalado()), ConfigSsh.rapida(), null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.RECONECTANDO && c.info().falha() != null);
            assertEquals(Falha.Tipo.TRANSPORTE_LOGIN_CLOUDFLARE, c.info().falha().tipo());
            assertEquals("https://dash.cloudflare.com/argotunnel?aud=x", c.info().falha().textoCopiavel());
        }
    }

    @Test
    void cloudflaredQueAbrePortaEPedeLoginNaPrimeiraConexaoMostraAUrlEmVezDoErroGenerico() throws Exception {
        // como o cloudflared de verdade: escuta de imediato (o app dá o túnel por pronto), só escreve o pedido de login quando alguém conecta, e CONTINUA vivo
        AtomicInteger portaDoSsh = new AtomicInteger(1);
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of("LABTEST_SEGURAR_CONEXOES", "1",
            "LABTEST_TEXTO_NA_PRIMEIRA_CONEXAO", "Please open the following URL and log in with your Cloudflare account:\nhttps://dash.cloudflare.com/argotunnel?aud=x&callback=y\n"));
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info() + " falha=" + c.info().falha(),
                () -> c.estado() == EstadoConexao.RECONECTANDO && c.info().falha() != null && c.info().falha().tipo() == Falha.Tipo.TRANSPORTE_LOGIN_CLOUDFLARE);
            Falha f = c.info().falha();
            assertEquals("https://dash.cloudflare.com/argotunnel?aud=x&callback=y", f.textoCopiavel());
            assertFalse(f.mensagem().contains("https://"), "o endereço só vai como texto copiável");
            assertTrue(f.mensagem().contains("navegador"), f.mensagem());
            assertTrue(lancador.real().lancados.size() >= 1);
            assertEquals(0, c.ssh.pedidosDeAutenticacao.get(), "o servidor nunca chegou a ser falado");
        }
    }

    @Test
    void bloquearOCofreFechaOTunelExternoENaoReconecta() throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            portaDoSsh.set(c.ssh.porta());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long pid = lancador.real().lancados.get(0).processo().pid();
            c.conexao.bloquear();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.BLOQUEADO_COFRE);
            esperarMorrer(pid);
            assertFalse(vivo(pid), "bloquear o cofre encerra o programa do túnel");
            assertEquals(0, registro.quantos());
            Espera.naoAcontece(Duration.ofMillis(600), "reconectar com o cofre bloqueado", () -> lancador.real().lancados.size() > 1);
        }
    }

    @Test
    void oRepousoDoComputadorFechaOTunelEAoAcordarSobeUmNovo() throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            portaDoSsh.set(c.ssh.porta());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long pid = lancador.real().lancados.get(0).processo().pid();
            c.conexao.aoDormir();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.SUSPENSO);
            esperarMorrer(pid);
            assertFalse(vivo(pid), "o túnel do computador que dormiu é encerrado: a sessão dele já morreu");
            assertEquals(0, registro.quantos());
            c.conexao.aoAcordar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO && lancador.real().lancados.size() == 2);
        }
    }

    @Test
    void seOProgramaDoTunelMorrerComAConexaoDePeAConexaoCaiComAMensagemClaraEVoltaPeloBackoff(@TempDir Path tmp) throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        // o programa do túnel só morre quando o teste manda (depois de a conexão estar de pé): nada de tempo fixo, que num runner lento vencia antes do CONECTADO
        Path gatilho = tmp.resolve("morrer-agora");
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of("LABTEST_MORRER_QUANDO_EXISTIR", gatilho.toString()));
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridorComFolgaParaACausa(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            portaDoSsh.set(c.ssh.porta());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            Files.createFile(gatilho);
            // lê o retrato do RECONECTANDO no histórico da conexão, e não o estado de agora: num runner lento a janela do RECONECTANDO pode passar entre a espera e a leitura
            Espera.ate(PRAZO, () -> "histórico=" + c.historico, () -> c.historico.stream().anyMatch(i -> i.estado() == EstadoConexao.RECONECTANDO));
            InfoConexao reconectando = c.historico.stream().filter(i -> i.estado() == EstadoConexao.RECONECTANDO).findFirst().orElseThrow();
            assertEquals(Falha.Tipo.TRANSPORTE_LOGIN_AWS, reconectando.falha().tipo(), "a causa do túnel, e não 'conexão caiu'");
            assertTrue(reconectando.tentativa() >= 1);
            assertNotNull(reconectando.proximaTentativaEm(), "a reconexão foi agendada pelo backoff, não em laço apertado");
            // e volta a conectar sozinha (um túnel novo, outra porta)
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO && lancador.real().lancados.size() >= 2);
        }
    }

    @Test
    void seOTunelMorreAindaConectandoACausaChegaDepoisDoSocketEAindaAssimEDita() throws Exception {
        // A corrida de verdade, sem depender de tempo: o socket do túnel fecha (o SSH falha) ANTES de o programa ser dado como morto, então no instante da falha o destino ainda não sabe a
        // causa; ela só aparece na espera de falhaDoTunelAposPerderASessao(). Antes, a tela dizia "conexão caiu" (REDE) e a causa se perdia.
        try (java.net.ServerSocket quemFecha = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread aceitador = Thread.ofVirtual().start(() -> {
                while (!quemFecha.isClosed()) {
                    try {
                        quemFecha.accept().close();
                    } catch (java.io.IOException e) {
                        return;
                    }
                }
            });
            var causa = new br.com.labdesktop.transporte.FalhaDoTunel(br.com.labdesktop.transporte.FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, "Seu login da AWS expirou.", null);
            var consultas = new AtomicInteger();
            br.com.labdesktop.transporte.AbridorDeDestino abridorQueDemoraACausa = (transporte, host, porta, aoCair) -> new br.com.labdesktop.transporte.DestinoSsh() {
                @Override
                public String host() {
                    return "127.0.0.1";
                }

                @Override
                public int porta() {
                    return quemFecha.getLocalPort();
                }

                @Override
                public Optional<br.com.labdesktop.transporte.FalhaDoTunel> falhaDoTunelAposPerderASessao() {
                    consultas.incrementAndGet();
                    return Optional.of(causa); // depois da espera, o programa já foi dado como morto e a saída foi lida
                }

                @Override
                public void close() { }
            };
            try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(SSM, abridorQueDemoraACausa, ConfigSsh.rapida(), null)) {
                c.conexao.iniciar();
                Espera.ate(PRAZO, () -> "histórico=" + c.historico, () -> c.historico.stream().anyMatch(i -> i.estado() == EstadoConexao.RECONECTANDO && i.falha() != null));
                InfoConexao reconectando = c.historico.stream().filter(i -> i.estado() == EstadoConexao.RECONECTANDO && i.falha() != null).findFirst().orElseThrow();
                assertEquals(Falha.Tipo.TRANSPORTE_LOGIN_AWS, reconectando.falha().tipo(), "a causa do túnel, e não 'conexão caiu'");
                assertTrue(consultas.get() >= 1, "esperou a causa do túnel antes de dar a falha por rede");
            } finally {
                quemFecha.close();
                aceitador.join(2_000);
            }
        }
    }

    // ----- sem órfão -----

    @Test
    void editarRemoverEFecharNaoDeixamProcessoOrfaoNemPortaPresa() throws Exception {
        AtomicInteger portaDoSsh = new AtomicInteger();
        LancadorDeFerramentaFalsaAdiado lancador = new LancadorDeFerramentaFalsaAdiado(portaDoSsh, Map.of());
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(lancador, tudoInstalado()), ConfigSsh.rapida(), null)) {
            portaDoSsh.set(c.ssh.porta());
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long pid1 = lancador.real().lancados.get(0).processo().pid();
            int porta1 = portaDoTunel(lancador, 0);

            // "editar": reconectar com o que o cofre tem agora (o túnel velho sai, o novo sobe)
            c.conexao.reconectar();
            Espera.ate(PRAZO, () -> "lançamentos=" + lancador.real().lancados.size(), () -> lancador.real().lancados.size() == 2);
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            esperarMorrer(pid1);
            assertFalse(vivo(pid1));
            assertFalse(PortasLocais.doSistema().alguemEscuta(porta1), "a porta do primeiro túnel foi liberada");

            // "remover" e "fechar o app": fechar()
            long pid2 = lancador.real().lancados.get(1).processo().pid();
            c.conexao.fechar().join();
            assertFalse(vivo(pid2), "fechar() só volta depois de o programa do túnel ter sido encerrado");
            assertEquals(0, registro.quantos());
            assertTrue(lancador.real().lancados.stream().noneMatch(l -> vivo(l.processo().pid())));
        }
    }

    @Test
    void fecharComOTunelAindaSubindoEncerraOProgramaSemEsperarOPrazo() throws Exception {
        // a ferramenta falsa fica de pé e NUNCA escuta (o túnel "demora"): fechar() no meio da espera não pode esperar os 25 s do prazo nem deixar o processo vivo
        LancadorDeFerramentaFalsaAdiado travado = new LancadorDeFerramentaFalsaAdiado(new AtomicInteger(1), Map.of("LABTEST_NAO_ESCUTAR", "1"));
        try (CenarioDeConexao c = CenarioDeConexao.pelaTunel(CLOUD, abridor(travado, tudoInstalado()), ConfigSsh.rapida(), null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "lançamentos", () -> !travado.real().lancados.isEmpty());
            Thread.sleep(300);
            long inicio = System.nanoTime();
            c.conexao.fechar().join();
            assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toSeconds() < 10, "não esperou o prazo do túnel");
            for (var l : travado.real().lancados) {
                esperarMorrer(l.processo().pid());
                assertFalse(vivo(l.processo().pid()), "o programa que ainda subia foi encerrado");
            }
            assertEquals(0, registro.quantos());
        }
    }

    private static int portaDoTunel(LancadorDeFerramentaFalsaAdiado lancador, int indice) {
        String url = lancador.real().lancados.get(indice).argumentosDoApp().stream().filter(a -> a.startsWith("127.0.0.1:") || a.contains("localPortNumber=")).findFirst().orElseThrow();
        return Integer.parseInt(url.substring(url.lastIndexOf(url.contains("localPortNumber=") ? '=' : ':') + 1));
    }

    /** A constante do endereço de enfeite, vista do teste. */
    private static final class ConfigServidorHost {
        static final String PLACEHOLDER = br.com.labdesktop.cofre.ConfigServidor.HOST_DO_TRANSPORTE;
    }

    /** O lançador de ferramenta falsa que só descobre a porta do SSHD depois de o cenário existir. */
    static class LancadorDeFerramentaFalsaAdiado implements br.com.labdesktop.transporte.LancadorDeProcesso {
        private final AtomicInteger portaDoSsh;
        private final Map<String, String> variaveis;
        private volatile LancadorDeFerramentaFalsa real;

        LancadorDeFerramentaFalsaAdiado(AtomicInteger portaDoSsh, Map<String, String> variaveis) {
            this.portaDoSsh = portaDoSsh;
            this.variaveis = variaveis;
        }

        synchronized LancadorDeFerramentaFalsa real() {
            if (real == null) {
                real = new LancadorDeFerramentaFalsa(portaDoSsh.get(), variaveis);
            }
            return real;
        }

        @Override
        public br.com.labdesktop.transporte.ProcessoExterno lancar(List<String> argumentos, Map<String, String> ambienteExtra) throws java.io.IOException {
            return real().lancar(argumentos, ambienteExtra);
        }
    }
}
