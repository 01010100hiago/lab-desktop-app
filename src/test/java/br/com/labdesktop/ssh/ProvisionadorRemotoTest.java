package br.com.labdesktop.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** CONTRATO-API seção 6: o que o app roda por SSH exec, e que nada sensível vai na linha de comando. */
class ProvisionadorRemotoTest {

    private static final String BASE = "docker exec -i painel-lab node acesso.js ";
    private static final String SEGREDO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/Painel%20do%20Lab:lab?secret=" + SEGREDO + "&issuer=Painel%20do%20Lab";

    /** Fake do executor: registra o que recebeu e devolve o que o teste mandou. */
    private static final class Fake implements ProvisionadorRemoto.Executor {
        final List<String> comandos = new ArrayList<>();
        final List<String> entradas = new ArrayList<>();
        String stdout = "{\"ok\":true}\n";
        String stderr = "";
        int status = 0;
        IOException erro;

        @Override
        public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException {
            comandos.add(comando);
            entradas.add(new String(stdin, StandardCharsets.UTF_8));
            if (erro != null) {
                throw erro;
            }
            return new ResultadoExec(status, stdout.getBytes(StandardCharsets.UTF_8),
                stderr.getBytes(StandardCharsets.UTF_8), false);
        }
    }

    @Test
    void registrarTokenMandaOHashPorStdinNuncaPorArgumento() throws Exception {
        Fake fake = new Fake();
        fake.stdout = "{\"ok\":true,\"ja_existia\":false}\n";
        TokenAgente token = TokenAgente.gerar();
        Instant quando = Instant.parse("2026-10-20T13:00:00.123Z");

        boolean jaExistia = new ProvisionadorRemoto(fake).registrarToken(token.hashSha256Hex(), "mac-de-teste", quando);

        assertFalse(jaExistia);
        assertEquals(List.of(BASE + "token-registrar"), fake.comandos);
        JsonNode entrada = Json.mapper().readTree(fake.entradas.get(0));
        assertEquals(token.hashSha256Hex(), entrada.get("hash").asText());
        assertEquals("mac-de-teste", entrada.get("nome_do_dispositivo").asText());
        assertEquals("2026-10-20T13:00:00.123Z", entrada.get("criado_em").asText());
        assertTrue(fake.entradas.get(0).endsWith("\n"), "uma linha JSON terminada em quebra");
        assertFalse(fake.comandos.get(0).contains(token.hashSha256Hex()));
    }

    @Test
    void registrarTokenRepetidoEhIdempotenteEConflitoViraErroComCodigo() throws Exception {
        Fake fake = new Fake();
        fake.stdout = "{\"ok\":true,\"ja_existia\":true}\n";
        String hash = TokenAgente.gerar().hashSha256Hex();
        assertTrue(new ProvisionadorRemoto(fake).registrarToken(hash, "mac", Instant.now()));

        fake.status = 1;
        fake.stdout = "{\"ok\":false,\"codigo\":\"token_conflito\",\"erro\":\"Já existe um token com esse nome.\"}\n";
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class,
            () -> new ProvisionadorRemoto(fake).registrarToken(hash, "mac", Instant.now()));
        assertEquals(ErroProvisionamento.TOKEN_CONFLITO, e.codigo());
        assertEquals("Já existe um token com esse nome.", e.getMessage());
    }

    @Test
    void validaHashENomeAntesDeMontarQualquerComando() {
        Fake fake = new Fake();
        ProvisionadorRemoto p = new ProvisionadorRemoto(fake);
        assertThrows(IllegalArgumentException.class, () -> p.registrarToken("nao-e-hash", "mac", Instant.now()));
        assertThrows(IllegalArgumentException.class,
            () -> p.registrarToken("a".repeat(64), "Nome Com Espaço; rm -rf /", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> p.revogarToken("x y"));
        assertThrows(IllegalArgumentException.class, () -> p.totpConfirmar("123456", "dispositivo; ls"));
        assertTrue(fake.comandos.isEmpty(), "nada pode ter sido executado");
    }

    @Test
    void revogarTokenMandaONomePorStdin() throws Exception {
        Fake fake = new Fake();
        new ProvisionadorRemoto(fake).revogarToken("mac-de-teste");
        assertEquals(List.of(BASE + "token-revogar"), fake.comandos);
        assertEquals(List.of("mac-de-teste\n"), fake.entradas);
    }

    @Test
    void totpIniciarDevolveOSegredoRedigidoEZeravel() throws Exception {
        Fake fake = new Fake();
        fake.stdout = "{\"ok\":true,\"segredo\":\"" + SEGREDO + "\",\"uri\":\"" + URI + "\"}\n";

        SegredoTotp segredo = new ProvisionadorRemoto(fake).totpIniciar();

        assertEquals(List.of(BASE + "totp-iniciar"), fake.comandos);
        assertEquals(List.of(""), fake.entradas);
        assertEquals(SEGREDO, segredo.segredo());
        assertEquals("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ", segredo.segredoEmGrupos());
        assertEquals(URI, segredo.uri());
        assertFalse(segredo.toString().contains(SEGREDO));
        segredo.zerar();
        assertTrue(segredo.estaZerado());
    }

    @Test
    void totpIniciarComSegredoIlegivelEErro() {
        Fake fake = new Fake();
        fake.stdout = "{\"ok\":true,\"segredo\":\"abc\",\"uri\":\"http://x\"}\n";
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class, () -> new ProvisionadorRemoto(fake).totpIniciar());
        assertEquals(ErroProvisionamento.SEM_RESPOSTA, e.codigo());
    }

    @Test
    void totpJaAtivoVemComCodigo() {
        Fake fake = new Fake();
        fake.status = 1;
        fake.stdout = "{\"ok\":false,\"codigo\":\"totp_ja_ativo\",\"erro\":\"O 2FA já está ativo neste servidor.\"}\n";
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class, () -> new ProvisionadorRemoto(fake).totpIniciar());
        assertEquals(ErroProvisionamento.TOTP_JA_ATIVO, e.codigo());
    }

    @Test
    void totpConfirmarMandaOCodigoENomeDoDispositivoPorStdin() throws Exception {
        Fake fake = new Fake();
        fake.stdout = "{\"ok\":true,\"ativado_ate\":\"2026-11-19T12:00:00.000Z\"}\n";

        Optional<Instant> ate = new ProvisionadorRemoto(fake).totpConfirmar("123 456", "mac-de-teste");

        assertEquals(Optional.of(Instant.parse("2026-11-19T12:00:00.000Z")), ate);
        assertEquals(List.of(BASE + "totp-confirmar"), fake.comandos);
        assertEquals(List.of("123456\nmac-de-teste\n"), fake.entradas);
        assertFalse(fake.comandos.get(0).contains("123456"), "o código nunca vai na linha de comando");
    }

    @Test
    void totpConfirmarSemDispositivoMandaSoOCodigo() throws Exception {
        Fake fake = new Fake();
        fake.stdout = "{\"ok\":true,\"ativado_ate\":null}\n";
        Optional<Instant> ate = new ProvisionadorRemoto(fake).totpConfirmar("654321", null);
        assertTrue(ate.isEmpty());
        assertEquals(List.of("654321\n"), fake.entradas);
    }

    @Test
    void totpCodigoErradoViraErroComCodigoTotpIncorreto() {
        Fake fake = new Fake();
        fake.status = 1;
        fake.stdout = "{\"ok\":false,\"codigo\":\"totp_incorreto\",\"erro\":\"Código incorreto ou já usado.\"}\n";
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class,
            () -> new ProvisionadorRemoto(fake).totpConfirmar("000000", null));
        assertEquals(ErroProvisionamento.TOTP_INCORRETO, e.codigo());
        assertEquals("Código incorreto ou já usado.", e.getMessage());
    }

    @Test
    void codigoForaDoFormatoNemChegaAoServidor() {
        Fake fake = new Fake();
        ProvisionadorRemoto p = new ProvisionadorRemoto(fake);
        assertThrows(IllegalArgumentException.class, () -> p.totpConfirmar("12345", null));
        assertThrows(IllegalArgumentException.class, () -> p.totpDesativar("abcdef"));
        assertTrue(fake.comandos.isEmpty());
    }

    @Test
    void totpDesativarMandaOCodigoPorStdin() throws Exception {
        Fake fake = new Fake();
        new ProvisionadorRemoto(fake).totpDesativar("123456");
        assertEquals(List.of(BASE + "totp-desativar"), fake.comandos);
        assertEquals(List.of("123456\n"), fake.entradas);
    }

    @Test
    void ignoraRuidoAntesDaLinhaJson() throws Exception {
        Fake fake = new Fake();
        fake.stdout = "Warning: algo do docker\n\n{\"ok\":true,\"ja_existia\":true}\n";
        assertTrue(new ProvisionadorRemoto(fake).registrarToken("a".repeat(64), "mac", Instant.now()));
    }

    @Test
    void semJsonVirasemRespostaComADicaDoStderrRedigida() {
        Fake fake = new Fake();
        fake.status = 125;
        fake.stdout = "";
        fake.stderr = "Error response from daemon: No such container: painel-lab\nAuthorization: Bearer lab_" + "B".repeat(43);
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class,
            () -> new ProvisionadorRemoto(fake).revogarToken("mac"));
        assertEquals(ErroProvisionamento.SEM_RESPOSTA, e.codigo());
        assertTrue(e.getMessage().contains("No such container"), e.getMessage());
        assertFalse(e.getMessage().contains("BBBBBBBB"), "token no stderr tem de sair mascarado");
    }

    @Test
    void semConexaoSshViraSemSessao() {
        Fake fake = new Fake();
        fake.erro = new IOException("Não há conexão SSH aberta com o servidor.");
        ErroProvisionamento e = assertThrows(ErroProvisionamento.class,
            () -> new ProvisionadorRemoto(fake).totpIniciar());
        assertEquals(ErroProvisionamento.SEM_SESSAO, e.codigo());
    }

    @Test
    void ultimaLinhaJsonPegaAUltimaELinhasQuebradasNaoDerrubam() {
        assertNull(ProvisionadorRemoto.ultimaLinhaJson("sem json\n{quebrado\n"));
        JsonNode n = ProvisionadorRemoto.ultimaLinhaJson("{\"a\":1}\n{\"a\":2}\n{ruído}\n");
        assertNotNull(n);
        assertEquals(2, n.get("a").asInt());
    }

    @Test
    void pelaSessaoSshDeVerdadeOCodigoDo2faSoChegaPorStdin() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.ssh.comandoRemoto((linha, stdin, saida) -> {
                saida.append("{\"ok\":true,\"ativado_ate\":\"2026-11-19T12:00:00.000Z\"}\n");
                return 0;
            });
            c.conexao.iniciar();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);

            ProvisionadorRemoto p = new ProvisionadorRemoto(c.conexao::executar);
            Optional<Instant> ate = p.totpConfirmar("987 654", "mac-de-teste");

            assertEquals(Optional.of(Instant.parse("2026-11-19T12:00:00.000Z")), ate);
            // O servidor de teste vê o que o sshd real veria: a linha de comando e o stdin, separados.
            assertEquals(List.of(BASE + "totp-confirmar"), c.ssh.comandos);
            assertFalse(c.ssh.comandos.get(0).contains("987654"));
            assertEquals(List.of("987654\nmac-de-teste\n"), c.ssh.entradas);
        }
    }

    @Test
    void execComSaidaGigantescaEhTruncadaSemEstourarAMemoria() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.ssh.comandoRemoto((linha, stdin, saida) -> {
                saida.append("x".repeat(200_000));
                return 0;
            });
            c.conexao.iniciar();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            ResultadoExec r = c.conexao.executar("saida-grande", new byte[0], Duration.ofSeconds(10));
            assertEquals(ExecRemoto.TETO_DE_SAIDA, r.stdout().length());
            assertTrue(r.truncado());
            assertFalse(r.toString().contains("xxxx"));
        }
    }

    @Test
    void execQueNaoTerminaEstouraOPrazoSemTravarParaSempre() throws Exception {
        try (CenarioDeConexao c = CenarioDeConexao.novo()) {
            c.ssh.comandoRemoto((linha, stdin, saida) -> {
                Espera.dormir(3000);
                return 0;
            });
            c.conexao.iniciar();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.info(), () -> c.estado() == EstadoConexao.CONECTADO);
            long inicio = System.nanoTime();
            IOException e = assertThrows(IOException.class,
                () -> c.conexao.executar("demora", new byte[0], Duration.ofMillis(500)));
            assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 2500);
            assertTrue(e.getMessage().contains("não terminou"), e.getMessage());
        }
    }

    @Test
    void linhaDeComandoComControleEhRecusada() {
        assertThrows(IllegalArgumentException.class, () -> ExecRemoto.exigirLinhaSemControle("ls\nrm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> ExecRemoto.exigirLinhaSemControle(" "));
        ExecRemoto.exigirLinhaSemControle("docker exec -i painel-lab node acesso.js totp-iniciar");
    }
}
