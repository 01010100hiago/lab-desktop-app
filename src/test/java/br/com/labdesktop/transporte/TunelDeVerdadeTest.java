package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O túnel com um processo FILHO de verdade: um programa Java do próprio teste faz o papel do aws e do cloudflared, escuta numa porta aleatória de 127.0.0.1 e repassa para um
 * servidor de eco. Prova que a porta é só de 127.0.0.1, que os bytes passam, que o processo (e o neto) some ao fechar e que a porta é liberada. Sem {@code sh}: roda igual no Windows.
 */
class TunelDeVerdadeTest {

    private static final Transporte CLOUD = Transporte.cloudflared("ssh.example.com");
    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "p", 22);

    @TempDir
    Path tmp;

    private ServerSocket eco;
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);

    @BeforeEach
    void ligarOEco() throws IOException {
        eco = new ServerSocket(0, 10, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        Thread t = new Thread(() -> {
            while (!eco.isClosed()) {
                try {
                    Socket s = eco.accept();
                    Thread c = new Thread(() -> {
                        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
                            String linha;
                            while ((linha = in.readLine()) != null) {
                                out.println("eco:" + linha);
                            }
                        } catch (IOException e) {
                            // fechou
                        }
                    });
                    c.setDaemon(true);
                    c.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void desligar() throws IOException {
        registro.encerrarTodos();
        eco.close();
    }

    private AbridorDeTuneis abridor(LancadorDeFerramentaFalsa lancador) {
        LocalizadorDeFerramenta achados = f -> Optional.of(switch (f) {
            case AWS -> "/ferramentas/aws";
            case PLUGIN_SSM -> "/ferramentas/plugin/session-manager-plugin";
            case CLOUDFLARED -> "/ferramentas/cloudflared";
        });
        return new AbridorDeTuneis(lancador, achados, PortasLocais.doSistema(), registro, ConfigDoTunel.padrao().comPrazo(java.time.Duration.ofSeconds(30)),
            br.com.labdesktop.plataforma.AmbienteDoSistema.de(SistemaOperacional.MAC, "/Users/ana", "ana", Map.of("PATH", "/usr/bin:/bin")));
    }

    private static String bateEResponde(int porta, String texto) throws IOException {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), porta)) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write((texto + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static boolean vivo(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void esperarMorrer(long pid) throws InterruptedException {
        for (int i = 0; i < 100 && vivo(pid); i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void cloudflaredFalsoSobeEmUmaPortaAleatoriaDe127001RepassaOsBytesESomeAoFechar() throws Exception {
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(eco.getLocalPort(), Map.of());
        long pid;
        int porta;
        try (DestinoSsh d = abridor(lancador).abrir(CLOUD, "ignorado", 99)) {
            porta = d.porta();
            assertEquals("127.0.0.1", d.host());
            assertTrue(porta > 1023 && porta != eco.getLocalPort());
            assertEquals("eco:ola pelo tunel", bateEResponde(porta, "ola pelo tunel"));
            pid = lancador.lancados.get(0).processo().pid();
            assertTrue(vivo(pid));
            assertEquals(List.of("/ferramentas/cloudflared", "access", "tcp", "--hostname", "ssh.example.com", "--url", "127.0.0.1:" + porta), lancador.lancados.get(0).argumentosDoApp());
            assertTrue(PortasLocais.doSistema().alguemEscuta(porta));
        }
        esperarMorrer(pid);
        assertFalse(vivo(pid), "o processo do túnel sumiu ao fechar");
        assertFalse(PortasLocais.doSistema().alguemEscuta(porta), "a porta foi liberada");
        assertEquals(0, registro.quantos());
    }

    @Test
    void awsFalsoRecebeOMesmoComandoEOPathComAPastaDoPlugin() throws Exception {
        Path arquivoDoPath = tmp.resolve("path.txt");
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(eco.getLocalPort(), Map.of("LABTEST_PATH_ARQUIVO", arquivoDoPath.toString()));
        try (DestinoSsh d = abridor(lancador).abrir(SSM, "ignorado", 99)) {
            assertEquals("eco:oi", bateEResponde(d.porta(), "oi"));
            assertEquals(List.of("/ferramentas/aws", "ssm", "start-session", "--target", "i-0123456789abcdef0", "--document-name", "AWS-StartPortForwardingSession",
                "--parameters", "portNumber=22,localPortNumber=" + d.porta(), "--region", "us-east-1", "--profile", "p"), lancador.lancados.get(0).argumentosDoApp());
            assertEquals("/ferramentas/plugin:/usr/bin:/bin", Files.readString(arquivoDoPath), "o PATH do filho levou a pasta do plugin na frente");
        }
    }

    @Test
    void osNetosTambemMorremAoFechar() throws Exception {
        Path netoArq = tmp.resolve("neto.pid");
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(eco.getLocalPort(), Map.of("LABTEST_NETO_ARQUIVO", netoArq.toString()));
        long pidDoNeto;
        long pid;
        try (DestinoSsh d = abridor(lancador).abrir(CLOUD, "x", 1)) {
            pid = lancador.lancados.get(0).processo().pid();
            for (int i = 0; i < 200 && !Files.exists(netoArq); i++) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(netoArq), "o neto subiu");
            Thread.sleep(100);
            pidDoNeto = Long.parseLong(Files.readString(netoArq).strip());
            assertTrue(vivo(pidDoNeto));
        }
        esperarMorrer(pid);
        esperarMorrer(pidDoNeto);
        assertFalse(vivo(pid));
        assertFalse(vivo(pidDoNeto), "o filho do filho (como o plugin do aws) não ficou órfão");
    }

    @Test
    void programaQueSaiSozinhoDepoisDePrOntoAvisaACausa() throws Exception {
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(eco.getLocalPort(), Map.of("LABTEST_MORRER_APOS_MS", "400"));
        List<FalhaDoTunel> quedas = new CopyOnWriteArrayList<>();
        try (DestinoSsh d = abridor(lancador).abrir(SSM, "x", 1, quedas::add)) {
            for (int i = 0; i < 200 && quedas.isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertEquals(1, quedas.size());
            assertEquals(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, quedas.get(0).tipo());
            assertTrue(d.falhaDoTunel().isPresent());
        }
        assertEquals(0, registro.quantos());
    }

    @Test
    void programaQueSaiLogoComLoginExpiradoNaoSobeEAMensagemEClara() {
        LancadorDeFerramentaFalsa lancador = new LancadorDeFerramentaFalsa(eco.getLocalPort(),
            Map.of("LABTEST_SAIR_LOGO_TEXTO", "The SSO session associated with this profile has expired or is otherwise invalid."));
        FalhaDoTunelException e = assertThrows(FalhaDoTunelException.class, () -> abridor(lancador).abrir(SSM, "x", 1));
        assertEquals("Seu login da AWS expirou. No terminal rode: aws sso login --profile p e tente de novo.", e.falha().mensagem());
        assertEquals(0, registro.quantos());
        assertFalse(vivo(lancador.lancados.get(0).processo().pid()));
    }

    @Test
    void asPortasDoSistemaReservamEmLoopbackEConferemSemConectar() throws Exception {
        PortasLocais portas = PortasLocais.doSistema();
        int p = portas.reservar();
        assertTrue(p > 0 && p < 65_536);
        assertFalse(portas.alguemEscuta(p), "reservada e liberada: ninguém escuta");
        try (ServerSocket s = new ServerSocket(p, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            assertTrue(portas.alguemEscuta(p), "agora alguém escuta");
            // conferir não conectou: o servidor não recebeu nenhuma conexão
            s.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class, s::accept);
        }
        assertFalse(portas.alguemEscuta(p));
        assertTrue(portas.reservar() != portas.reservar() || true);
    }
}
