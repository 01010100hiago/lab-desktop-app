package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.teste.FerramentasFalsasDeTunel;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import br.com.labdesktop.transporte.RotaDoSsh;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

/** A sondagem, a prova da chave, a entrada com senha e a sonda de métodos pela ROTA do servidor: pelo túnel externo, com a falha dita em palavras simples. */
class CaminhosPeloTunelTest {

    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", null, 22);
    private static final String USUARIO = "ubuntu";
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);

    private static boolean vivo(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private RotaDoSsh rota(LancadorDeFerramentaFalsa lancador, Transporte t) {
        return new RotaDoSsh(t, "", 0, FerramentasFalsasDeTunel.abridor(lancador, registro));
    }

    private void tunelFechado(LancadorDeFerramentaFalsa lancador) throws InterruptedException {
        for (var l : lancador.lancados) {
            for (int i = 0; i < 100 && vivo(l.processo().pid()); i++) {
                Thread.sleep(50);
            }
            assertFalse(vivo(l.processo().pid()));
        }
        assertEquals(0, registro.quantos());
    }

    @Test
    void sondagemPelaRotaPegaAChaveDoHostDoSshAtrasDoTunelENaoAutentica() throws Exception {
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519()) {
            LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(ssh.porta(), Map.of());
            SondagemDeHost.Resultado r = SondagemDeHost.sondar(rota(lancador, SSM), ConfigSsh.rapida());
            assertEquals(ssh.pin(), r.pin());
            assertEquals(0, ssh.pedidosDeAutenticacao.get());
            assertEquals(1, lancador.lancados.size());
            tunelFechado(lancador);
        }
    }

    @Test
    void sondagemQuandoOTunelSobeEOSshNaoRespondeDizOQueConferir() throws Exception {
        // o túnel sobe e leva a uma porta onde não há SSH
        java.net.ServerSocket semSsh = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        int portaSemSsh = semSsh.getLocalPort();
        semSsh.close(); // ninguém escuta
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(portaSemSsh, Map.of());
        SondagemException e = assertThrows(SondagemException.class, () -> SondagemDeHost.sondar(rota(lancador, SSM), ConfigSsh.rapida()));
        assertTrue(e.getMessage().startsWith("O túnel do modo AWS Session Manager (SSM) abriu, mas o SSH do servidor não respondeu por ele."), e.getMessage());
        assertTrue(e.getMessage().contains("porta SSH da instância"));
        assertFalse(e.getMessage().contains("127.0.0.1"), "nada de porta local aleatória na frase do usuário");
        tunelFechado(lancador);
    }

    @Test
    void sondagemSemAFerramentaDizOPassoDeInstalacaoEFalhaSemLancarNada() throws Exception {
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(1, Map.of());
        RotaDoSsh r = new RotaDoSsh(Transporte.cloudflared("ssh.example.com"), "", 0, FerramentasFalsasDeTunel.abridor(lancador, f -> Optional.empty(), registro));
        SondagemException e = assertThrows(SondagemException.class, () -> SondagemDeHost.sondar(r, ConfigSsh.rapida()));
        assertTrue(e.getMessage().contains("O app não instala nada"));
        assertTrue(lancador.lancados.isEmpty());
    }

    @Test
    void provaDaChavePeloTunelPassaERecusaHostDiferente() throws Exception {
        KeyPair chave = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519(chave.getPublic())) {
            ssh.comandoRemoto((linha, in, out) -> {
                if (linha.equals(ProvaPorChave.COMANDO_DA_PROVA)) {
                    out.append("lab-desktop-prova\n");
                }
                return 0;
            });
            LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(ssh.porta(), Map.of());
            new ProvaPorChave(rota(lancador, SSM), USUARIO, chave, List.of(ssh.pin()), ConfigSsh.rapida()).provar();
            tunelFechado(lancador);

            // outro host key: recusa e nenhuma credencial vai
            Pin deOutra = Pin.de(KeyUtils.generateKeyPair("ssh-ed25519", 256).getPublic());
            FalhaDeEntradaPorSenha f = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> new ProvaPorChave(rota(lancador, SSM), USUARIO, chave, List.of(deOutra), ConfigSsh.rapida()).provar());
            assertEquals(FalhaDeEntradaPorSenha.Motivo.HOST_MUDOU, f.motivo());
            tunelFechado(lancador);
        }
    }

    @Test
    void provaDaChaveComOTunelQueNaoSobeDizAMensagemDoTunel() throws Exception {
        KeyPair chave = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(1,
            Map.of("LABTEST_SAIR_LOGO_TEXTO", "An error occurred (ExpiredToken) when calling the StartSession operation"));
        FalhaDeEntradaPorSenha f = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> new ProvaPorChave(rota(lancador, SSM), USUARIO, chave, List.of(), ConfigSsh.rapida()).provar());
        assertEquals(FalhaDeEntradaPorSenha.Motivo.SEM_CONEXAO, f.motivo());
        assertEquals("Seu login da AWS expirou. No terminal rode: aws sso login e tente de novo.", f.getMessage());
    }

    @Test
    void sondaDeMetodosPeloTunelLeOsMetodosEFechaOTunelESeOTunelNaoSobeNaoDaParaLer() throws Exception {
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519()) {
            ssh.aceitarSenha(USUARIO, "x");
            LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(ssh.porta(), Map.of());
            List<String> metodos = SondaDeMetodos.para(rota(lancador, SSM), USUARIO, List.of(ssh.pin()), ConfigSsh.rapida()).ler();
            assertTrue(metodos.contains("password") && metodos.contains("publickey"), String.valueOf(metodos));
            tunelFechado(lancador);
        }
        LancadorDeFerramentaFalsa quebrado = new LancadorDeFerramentaFalsa(1, Map.of("LABTEST_SAIR_LOGO_TEXTO", "boom"));
        assertNull(SondaDeMetodos.para(rota(quebrado, SSM), USUARIO, List.of(), ConfigSsh.rapida()).ler());
    }

    @Test
    void umaExcecaoDepoisDeAbrirOTunelNaoDeixaOProgramaDoTunelAberto() throws Exception {
        // o destino abre e, ANTES de a sessão existir, algo lança RuntimeException (aqui: um pin nulo na lista): o túnel tem de fechar
        java.util.concurrent.atomic.AtomicInteger fechamentos = new java.util.concurrent.atomic.AtomicInteger();
        br.com.labdesktop.transporte.DestinoSsh destino = new br.com.labdesktop.transporte.DestinoSsh() {
            @Override
            public String host() {
                return "127.0.0.1";
            }

            @Override
            public int porta() {
                return 1;
            }

            @Override
            public void close() {
                fechamentos.incrementAndGet();
            }
        };
        RotaDoSsh rota = new RotaDoSsh(Transporte.cloudflared("ssh.example.com"), "", 0, (t, h, p, aoCair) -> destino);
        List<Pin> comNulo = java.util.Arrays.asList((Pin) null);
        assertThrows(FalhaDeEntradaPorSenha.class, () -> SessaoComSenha.abrir(rota, USUARIO, comNulo, "x".toCharArray(), ConfigSsh.rapida()));
        assertEquals(1, fechamentos.get(), "o túnel fechou");
    }

    @Test
    void sessaoComSenhaPeloTunelFechaOTunelJuntoComAsessao() throws Exception {
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519()) {
            ssh.aceitarSenha(USUARIO, "senha-x");
            ssh.comandoRemoto((linha, in, out) -> {
                out.append("ola\n");
                return 0;
            });
            LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(ssh.porta(), Map.of());
            long pid;
            try (SessaoComSenha s = SessaoComSenha.abrir(rota(lancador, SSM), USUARIO, List.of(ssh.pin()), "senha-x".toCharArray(), ConfigSsh.rapida())) {
                pid = lancador.lancados.get(0).processo().pid();
                assertTrue(vivo(pid), "o túnel fica aberto enquanto a sessão vive");
                assertEquals("ola\n", s.executar("echo ola", new byte[0], java.time.Duration.ofSeconds(5)).stdout());
            }
            tunelFechado(lancador);
            // senha errada: falha e o túnel também não fica
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> SessaoComSenha.abrir(rota(lancador, SSM), USUARIO, List.of(ssh.pin()), "errada".toCharArray(), ConfigSsh.rapida()));
            assertEquals(FalhaDeEntradaPorSenha.Motivo.SENHA_INCORRETA, e.motivo());
            tunelFechado(lancador);
        }
    }
}
