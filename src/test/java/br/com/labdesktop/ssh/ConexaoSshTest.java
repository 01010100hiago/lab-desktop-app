package br.com.labdesktop.ssh;

import br.com.labdesktop.teste.AgenteFalso;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@link ConexaoSsh} de ponta a ponta contra um {@code SshServer} embutido do MINA (chave de host gerada na
 * hora), um agente HTTP falso e, onde preciso, um proxy TCP que sabe fazer buraco negro. Tempos reduzidos por
 * {@link ConfigSsh#rapida()}.
 */
class ConexaoSshTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    @Test
    void autenticaSoComAChaveDoCofreMesmoComSshDoDonoPopuladoEEncaminhaSoParaOAgente(@TempDir Path tmp) throws Exception {
        KeyPair doHome = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        Path casa = HomeFalso.popular(tmp, doHome);
        try (CenarioDeConexao c = CenarioDeConexao.novo().sondaPorHttp()) {
            // O servidor aceita AS DUAS chaves: se a de ~/.ssh fosse oferecida, entraria por ela.
            c.ssh.aceitar(c.cofre.par().getPublic(), doHome.getPublic());
            c.ssh.permitirEncaminhamentoSomentePara(c.agente.porta());

            HomeFalso.comHome(casa, () -> {
                c.conexao.iniciar();
                Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            });

            String impressaoDoCofre = KeyUtils.getFingerPrint(c.cofre.par().getPublic());
            assertEquals(List.of(impressaoDoCofre), c.ssh.chavesOferecidas.stream().distinct().toList(),
                "o servidor só pode ter visto a chave do cofre");

            // O túnel escuta só em loopback, numa porta efêmera, e chega ao agente (que exige o Authorization).
            TunelSsh tunel = c.conexao.tunel().orElseThrow();
            assertTrue(tunel.enderecoLocal().getAddress().isLoopbackAddress());
            assertTrue(tunel.portaLocal() > 0);
            assertEquals(tunel.portaLocal(), c.info().portaLocal());
            assertEquals(200, c.getPeloTunel("/api/versao", true));
            assertEquals(401, c.getPeloTunel("/api/versao", false));
            assertEquals(List.of("127.0.0.1:" + c.agente.porta()), c.ssh.destinosPedidos.stream().distinct().toList());
            assertNull(c.info().falha());
            assertNull(c.info().semConexaoDesde());
        }
    }

    @Test
    void chaveDoSshDoDonoNuncaAutenticaOApp(@TempDir Path tmp) throws Exception {
        // O cenário do "assistente verde pelo motivo errado": o servidor só conhece a chave que está em ~/.ssh do
        // usuário. Com o cliente padrão do MINA o app conectaria SEM a chave do cofre. Aqui não pode conectar.
        KeyPair doHome = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        Path casa = HomeFalso.popular(tmp, doHome);
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.ssh.aceitar(doHome.getPublic());

            HomeFalso.comHome(casa, () -> {
                c.conexao.iniciar();
                Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.ERRO_AUTENTICACAO);
            });

            assertEquals(Falha.Tipo.CHAVE_RECUSADA, c.info().falha().tipo());
            assertEquals(0, c.ssh.autenticacoesAceitas.get());
            String impressaoDoCofre = KeyUtils.getFingerPrint(c.cofre.par().getPublic());
            assertEquals(List.of(impressaoDoCofre), c.ssh.chavesOferecidas.stream().distinct().toList(),
                "o servidor só pode ter visto a chave do cofre (recusada), nunca a de ~/.ssh");
        }
    }

    @Test
    void pinDivergenteVaiParaErroDeChaveDoHostSemRetry() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo(true, ConfigSsh.rapida(), true, null)) {
            // O pin do cofre é de OUTRA chave de host: possível MITM.
            KeyPair outra = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            c.cofre.limparPins(CofreFalso.ID);
            c.cofre.gravarPin(CofreFalso.ID, br.com.labdesktop.cofre.Pin.de(outra.getPublic()));

            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.ERRO_CHAVE_HOST);

            Falha falha = c.info().falha();
            assertEquals(Falha.Tipo.PIN_DIVERGENTE, falha.tipo());
            assertTrue(falha.mensagem().contains(KeyUtils.getFingerPrint(outra.getPublic())), falha.mensagem());
            assertTrue(falha.mensagem().contains(KeyUtils.getFingerPrint(c.ssh.chaveDoHost.getPublic())), falha.mensagem());

            // Sem retry: continua no estado, com UMA única conexão TCP, e nenhuma credencial chegou ao servidor.
            Espera.naoAcontece(Duration.ofMillis(1500), "reconexão com pin divergente",
                () -> c.estado() != EstadoConexao.ERRO_CHAVE_HOST || c.proxy.conexoesAceitas.get() > 1);
            assertEquals(1, c.proxy.conexoesAceitas.get());
            assertEquals(0, c.ssh.pedidosDeAutenticacao.get());
            assertTrue(c.ssh.chavesOferecidas.isEmpty());
            assertNull(c.info().proximaTentativaEm());
        }
    }

    @Test
    void semPinNemAbreAConexao() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo(true, ConfigSsh.rapida(), false, null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.ERRO_CHAVE_HOST);
            assertEquals(Falha.Tipo.PIN_AUSENTE, c.info().falha().tipo());
            Espera.dormir(300);
            assertEquals(0, c.proxy.conexoesAceitas.get(), "host não confirmado não pode receber conexão");

            // O usuário confia no host (grava o pin) e manda tentar: aí conecta.
            c.cofre.gravarPin(CofreFalso.ID, c.ssh.pin());
            c.conexao.tentarAgora();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
        }
    }

    @Test
    void algoritmoSemPinTambemEhRecusado() throws Exception {
        // Pin só de ed25519, mas o servidor só tem chave de host ecdsa: não há o que negociar com o pin.
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.criar("ecdsa-sha2-nistp256");
             AgenteFalso agente = new AgenteFalso()) {
            KeyPair outraEd = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            CofreFalso cofre = new CofreFalso("127.0.0.1", ssh.porta(),
                List.of(br.com.labdesktop.cofre.Pin.de(outraEd.getPublic()))).comPortaDoAgente(agente.porta());
            ssh.aceitar(cofre.par().getPublic());
            ConexaoSsh conexao = new ConexaoSsh(CofreFalso.ID, cofre, ConfigSsh.rapida(), t -> SondaDoAgente.Resultado.OK);
            try {
                conexao.iniciar();
                Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> conexao.info().estado() == EstadoConexao.ERRO_CHAVE_HOST);
                assertEquals(Falha.Tipo.PIN_DIVERGENTE, conexao.info().falha().tipo());
                assertEquals(0, ssh.pedidosDeAutenticacao.get());
            } finally {
                conexao.fechar().join();
            }
        }
    }

    @Test
    void autenticaComHostEcdsaMesmoComChaveDeUsuarioEd25519() throws Exception {
        // O algoritmo da chave do HOST (ecdsa) restringe a lista de assinaturas do cliente; a autenticação do
        // USUÁRIO (ed25519) usa lista própria e não pode ser afetada.
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.criar("ecdsa-sha2-nistp256");
             AgenteFalso agente = new AgenteFalso()) {
            CofreFalso cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
            ssh.aceitar(cofre.par().getPublic());
            ConexaoSsh conexao = new ConexaoSsh(CofreFalso.ID, cofre, ConfigSsh.rapida(), t -> SondaDoAgente.Resultado.OK);
            try {
                conexao.iniciar();
                Espera.ate(PRAZO, () -> "estado=" + conexao.info(), () -> conexao.info().estado() == EstadoConexao.CONECTADO);
            } finally {
                conexao.fechar().join();
            }
        }
    }

    @Test
    void chaveRecusadaTentaUmaVezMaisEParaSemMartelarOServidor() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo(true, ConfigSsh.rapida(), true, null)) {
            c.ssh.aceitar(); // ninguém é aceito: a chave do cofre é recusada
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.ERRO_AUTENTICACAO);
            assertEquals(Falha.Tipo.CHAVE_RECUSADA, c.info().falha().tipo());

            // Primeiro erro: agenda UMA nova tentativa (proximaTentativaEm preenchido)...
            assertTrue(c.historico.stream().anyMatch(i -> i.estado() == EstadoConexao.ERRO_AUTENTICACAO
                && i.proximaTentativaEm() != null));
            // ...que também falha; depois disso, para de vez.
            Espera.ate(PRAZO, () -> "segunda conexão; conexões=" + c.proxy.conexoesAceitas.get(),
                () -> c.proxy.conexoesAceitas.get() == 2);
            Espera.ate(PRAZO, () -> "estado=" + c.info(),
                () -> c.estado() == EstadoConexao.ERRO_AUTENTICACAO && c.info().proximaTentativaEm() == null);
            Espera.naoAcontece(Duration.ofMillis(1500), "terceira tentativa depois de chave recusada duas vezes",
                () -> c.proxy.conexoesAceitas.get() > 2);

            // "Tentar agora" é decisão do usuário: libera mais uma rodada.
            c.ssh.aceitar(c.cofre.par().getPublic());
            c.conexao.tentarAgora();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
        }
    }

    @Test
    void buracoNegroEhDetectadoPeloHeartbeatEAConexaoVoltaSozinha() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long geracaoAntes = c.info().geracao();
            int portaAntes = c.info().portaLocal();

            c.proxy.abrirBuracoNegro(); // a conexão continua "aberta", mas nada mais passa

            Espera.ate(Duration.ofSeconds(15), () -> "não percebeu a queda; estado=" + c.info(),
                () -> c.jaPassouPor(EstadoConexao.RECONECTANDO));
            InfoConexao reconectando = c.historico.stream()
                .filter(i -> i.estado() == EstadoConexao.RECONECTANDO).findFirst().orElseThrow();
            assertNotNull(reconectando.semConexaoDesde(), "a tela precisa de 'sem conexão desde HH:MM'");
            assertNotNull(reconectando.proximaTentativaEm());
            assertEquals(Falha.Tipo.REDE, reconectando.falha().tipo());

            Espera.ate(PRAZO, () -> "não voltou; estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertTrue(c.info().geracao() > geracaoAntes, "túnel novo a cada reconexão");
            assertNotNull(c.info().portaLocal());
            assertEquals(2, c.proxy.conexoesAceitas.get());
            assertTrue(portaAntes > 0);
            assertNull(c.info().semConexaoDesde());
        }
    }

    @Test
    void verificacaoAtivaPercebeSessaoMortaSemEsperarOHeartbeat() throws Exception {
        // Heartbeat de 60 s: sozinho nunca detectaria dentro do teste. Só a verificação ativa (prazo de 500 ms).
        ConfigSsh cfg = ConfigSsh.rapida().comHeartbeat(Duration.ofSeconds(60), 3);
        try (CenarioDeConexao c = CenarioDeConexao.novo(true, cfg, true, null)) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            c.proxy.abrirBuracoNegro();
            Espera.naoAcontece(Duration.ofMillis(500), "queda sem ninguém perguntar",
                () -> c.estado() != EstadoConexao.CONECTADO);

            c.conexao.verificarAgora();

            Espera.ate(Duration.ofSeconds(10), () -> "estado=" + c.info(), () -> c.jaPassouPor(EstadoConexao.RECONECTANDO));
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
        }
    }

    @Test
    void verificacaoAtivaComTudoBemNaoMudaNada() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            int sondasAntes = c.chamadasDaSonda.get();
            c.conexao.verificarAgora();
            Espera.ate(PRAZO, "a verificação testa o agente de novo", () -> c.chamadasDaSonda.get() > sondasAntes);
            assertEquals(EstadoConexao.CONECTADO, c.estado());
            assertFalse(c.jaPassouPor(EstadoConexao.RECONECTANDO), c.historico.toString());
        }
    }

    @Test
    void quedaBruscaReconectaComBackoffMostrandoTentativaEProximaEm() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);

            c.proxy.derrubarConexoes();

            Espera.ate(Duration.ofSeconds(15), () -> "estado=" + c.info(), () -> c.jaPassouPor(EstadoConexao.RECONECTANDO));
            InfoConexao r = c.historico.stream().filter(i -> i.estado() == EstadoConexao.RECONECTANDO).findFirst().orElseThrow();
            assertEquals(1, r.tentativa());
            assertTrue(r.proximaTentativaEm().isAfter(Instant.now().minusSeconds(5)));
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertEquals(0, c.info().tentativa());
        }
    }

    @Test
    void servidorForaDoArFicaReconectandoComBackoffCrescente() throws Exception {
        ConfigSsh cfg = ConfigSsh.rapida();
        try (CenarioDeConexao c = CenarioDeConexao.novo(true, cfg, true, null)) {
            c.proxy.close(); // o destino some: conexão recusada
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(),
                () -> c.historico.stream().filter(i -> i.estado() == EstadoConexao.RECONECTANDO).count() >= 3);
            List<InfoConexao> tentativas = c.historico.stream().filter(i -> i.estado() == EstadoConexao.RECONECTANDO).toList();
            assertEquals(1, tentativas.get(0).tentativa());
            assertEquals(2, tentativas.get(1).tentativa());
            assertEquals(3, tentativas.get(2).tentativa());
            assertEquals(Falha.Tipo.REDE, tentativas.get(0).falha().tipo());
            assertTrue(tentativas.get(0).falha().mensagem().contains("recusada"), tentativas.get(0).falha().mensagem());
            assertEquals(0, c.ssh.pedidosDeAutenticacao.get());
            assertNotNull(tentativas.get(0).semConexaoDesde());
        }
    }

    @Test
    void agenteForaMantemOSshDePeEVoltaParaConectado() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            c.respostaDaSonda.set(SondaDoAgente.Resultado.AGENTE_FORA);
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.SSH_OK_SEM_AGENTE);
            assertEquals(Falha.Tipo.AGENTE_FORA, c.info().falha().tipo());
            assertNotNull(c.info().portaLocal(), "o túnel segue de pé");
            // Insiste sozinha no teste do agente...
            Espera.ate(PRAZO, "a sonda insiste", () -> c.chamadasDaSonda.get() >= 3);
            // ...e o exec funciona mesmo sem agente (é como se registra o token).
            c.ssh.comandoRemoto((cmd, in, out) -> {
                out.append("ok\n");
                return 0;
            });
            assertEquals("ok\n", c.conexao.executar("true", new byte[0], Duration.ofSeconds(5)).stdout());

            c.respostaDaSonda.set(SondaDoAgente.Resultado.OK);
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertEquals(1, c.proxy.conexoesAceitas.get(), "o SSH nunca foi derrubado");
        }
    }

    @Test
    void agenteDesatualizadoFicaSemAgenteComMensagemPropriaEVoltaQuandoOPainelForPublicado() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            c.respostaDaSonda.set(SondaDoAgente.Resultado.AGENTE_DESATUALIZADO);
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.SSH_OK_SEM_AGENTE);
            assertEquals(Falha.Tipo.AGENTE_DESATUALIZADO, c.info().falha().tipo());
            assertTrue(c.info().falha().mensagem().contains("/api/versao"), c.info().falha().mensagem());
            assertNotNull(c.info().portaLocal(), "o túnel segue de pé");
            Espera.ate(PRAZO, "insiste no teste do agente", () -> c.chamadasDaSonda.get() >= 3);

            c.respostaDaSonda.set(SondaDoAgente.Resultado.OK); // o usuário publicou o painel
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertNull(c.info().falha());
            assertEquals(1, c.proxy.conexoesAceitas.get(), "o SSH nunca foi derrubado");
        }
    }

    @Test
    void tokenRecusadoEAguardando2faNaoTentamSozinhos() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            c.respostaDaSonda.set(SondaDoAgente.Resultado.TOKEN_RECUSADO);
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.TOKEN_RECUSADO);
            assertEquals(Falha.Tipo.TOKEN_RECUSADO, c.info().falha().tipo());
            int sondas = c.chamadasDaSonda.get();
            Espera.naoAcontece(Duration.ofMillis(1000), "retry do token recusado", () -> c.chamadasDaSonda.get() > sondas);

            // O usuário registra o token de novo e manda tentar: o SSH continua o mesmo.
            c.respostaDaSonda.set(SondaDoAgente.Resultado.REQUER_2FA);
            c.conexao.tentarAgora();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.AGUARDANDO_2FA);
            assertEquals(Falha.Tipo.CODIGO_2FA_PENDENTE, c.info().falha().tipo());
            int sondas2 = c.chamadasDaSonda.get();
            Espera.naoAcontece(Duration.ofMillis(1000), "retry enquanto aguarda o código", () -> c.chamadasDaSonda.get() > sondas2);

            c.respostaDaSonda.set(SondaDoAgente.Resultado.OK);
            c.conexao.tentarAgora();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertEquals(1, c.proxy.conexoesAceitas.get());
        }
    }

    @Test
    void ServidorDizTokenRecusadoOuCodigoPendenteDepoisDeConectado() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            c.conexao.informarCodigo2faPendente();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.AGUARDANDO_2FA);
            c.conexao.tentarAgora();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            c.conexao.informarTokenRecusado();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.TOKEN_RECUSADO);
        }
    }

    @Test
    void sonoSuspendeEAcordarReconectaSemEsperarHeartbeat() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            MonitorDeSono sono = new MonitorDeSono();
            sono.acompanhar(c.conexao);
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long geracaoAntes = c.info().geracao();

            sono.systemAboutToSleep(null);
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.SUSPENSO);
            assertNotNull(c.info().semConexaoDesde());
            int conexoes = c.proxy.conexoesAceitas.get();
            Espera.naoAcontece(Duration.ofMillis(800), "reconexão com o Mac dormindo", () -> c.proxy.conexoesAceitas.get() > conexoes);
            assertThrows(IOException.class, () -> c.conexao.executar("true", new byte[0], Duration.ofSeconds(1)));

            sono.systemAwoke(null);
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            assertTrue(c.info().geracao() > geracaoAntes);
            assertEquals(conexoes + 1, c.proxy.conexoesAceitas.get());
        }
    }

    @Test
    void bloquearOCofreFechaTudoENaoReconecta() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.comProxy()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            c.cofre.bloquear();
            c.conexao.bloquear();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.BLOQUEADO_COFRE);
            assertTrue(c.conexao.tunel().isEmpty());
            assertNull(c.info().portaLocal());
            assertThrows(IOException.class, () -> c.conexao.executar("true", new byte[0], Duration.ofSeconds(1)));
            int conexoes = c.proxy.conexoesAceitas.get();
            Espera.naoAcontece(Duration.ofMillis(1000), "reconexão com o cofre bloqueado", () -> c.proxy.conexoesAceitas.get() > conexoes);
            // Nem "tentar agora" reabre: o cofre está fechado.
            c.conexao.tentarAgora();
            Espera.dormir(300);
            assertEquals(EstadoConexao.BLOQUEADO_COFRE, c.estado());
        }
    }

    @Test
    void execRodaComStdinEDevolveSaida() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.ssh.comandoRemoto((cmd, stdin, out) -> {
                out.append("{\"ok\":true,\"eco\":").append(stdin.length()).append("}\n");
                return 0;
            });
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);

            ResultadoExec r = c.conexao.executar("echo oi", "123456\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10));

            assertEquals(0, r.status());
            assertEquals("{\"ok\":true,\"eco\":7}\n", r.stdout());
            assertEquals(List.of("echo oi"), c.ssh.comandos);
            assertEquals(List.of("123456\n"), c.ssh.entradas);
        }
    }

    @Test
    void execSemSessaoFalhaComMensagemClara() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            IOException e = assertThrows(IOException.class, () -> c.conexao.executar("true", new byte[0], Duration.ofSeconds(1)));
            assertTrue(e.getMessage().contains("Não há conexão SSH"), e.getMessage());
        }
    }

    @Test
    void fecharEhIdempotenteEPoeODesconectado() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            c.conexao.fechar().join();
            c.conexao.fechar().join();
            assertEquals(EstadoConexao.DESCONECTADO, c.estado());
            c.conexao.iniciar(); // depois de fechada, ignora
            Espera.dormir(200);
            assertEquals(EstadoConexao.DESCONECTADO, c.estado());
        }
    }

    @Test
    void oObservadorRecebeOEstadoAtualAoSeInscrever() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            java.util.List<InfoConexao> vistos = new java.util.concurrent.CopyOnWriteArrayList<>();
            c.conexao.observar(vistos::add);
            Espera.ate(PRAZO, "recebe o retrato inicial", () -> !vistos.isEmpty());
            assertEquals(EstadoConexao.DESCONECTADO, vistos.get(0).estado());
        }
    }

    @Test
    void reconectarJogaForaASessaoEConectaDeNovoComOQueOCofreTemAgora() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo().sondaPorHttp()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long geracaoAntes = c.info().geracao();
            int autenticacoesAntes = c.ssh.autenticacoesAceitas.get();

            c.conexao.reconectar(); // "tentar agora" não faria nada com a conexão de pé

            Espera.ate(PRAZO, () -> "reconectou, info=" + c.info(),
                () -> c.estado() == EstadoConexao.CONECTADO && c.info().geracao() > geracaoAntes);
            assertTrue(c.ssh.autenticacoesAceitas.get() > autenticacoesAntes, "abriu uma sessão SSH nova");

            // Agora o cofre perde o pin (o usuário mudou o endereço): reconectar já usa o cofre de agora e para na confirmação.
            c.cofre.limparPins(CofreFalso.ID);
            int sessoesAntes = c.ssh.sessoesAbertas.get();
            c.conexao.reconectar();
            Espera.ate(PRAZO, () -> "pin ausente, info=" + c.info(), () -> c.estado() == EstadoConexao.ERRO_CHAVE_HOST);
            assertEquals(Falha.Tipo.PIN_AUSENTE, c.info().falha().tipo());
            assertTrue(c.conexao.tunel().isEmpty(), "a sessão e o túnel antigos foram fechados");
            assertEquals(sessoesAntes, c.ssh.sessoesAbertas.get());
        }
    }

    @Test
    void reconectarNaoMexeComOCofreBloqueadoNemComOMacDormindo() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo().sondaPorHttp()) {
            c.conexao.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);

            c.conexao.aoDormir();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.SUSPENSO);
            c.conexao.reconectar();
            Espera.naoAcontece(Duration.ofMillis(600), "reconectar com o Mac dormindo", () -> c.estado() != EstadoConexao.SUSPENSO);

            c.conexao.bloquear();
            Espera.ate(PRAZO, () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.BLOQUEADO_COFRE);
            c.conexao.reconectar();
            Espera.naoAcontece(Duration.ofMillis(600), "reconectar com o cofre bloqueado", () -> c.estado() != EstadoConexao.BLOQUEADO_COFRE);
        }
    }
}
