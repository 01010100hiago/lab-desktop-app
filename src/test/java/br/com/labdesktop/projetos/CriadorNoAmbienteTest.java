package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Criar projeto no computador local, de ponta a ponta com o sistema de arquivos de uma pasta temporária e um roteiro no lugar do Docker. */
class CriadorNoAmbienteTest {

    @TempDir
    Path tmp;

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    private AmbienteFalso amb() {
        return new AmbienteFalso(tmp);
    }

    private static String ler(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void criaAPastaComOsMesmosArquivosDoAgenteEARede() throws Exception {
        AmbienteFalso a = amb();
        CriadorDeProjeto.Resultado r = new CriadorNoAmbiente(a.local(), RELOGIO).criar("meu-app", null);
        assertEquals("meu-app", r.nome());
        assertEquals(List.of(), r.avisos());
        Path pasta = a.raiz.resolve("meu-app");
        for (Map.Entry<String, String> e : GabaritoDoProjeto.arquivos("meu-app", LocalDate.of(2026, 10, 28)).entrySet()) {
            assertEquals(e.getValue(), ler(pasta.resolve(e.getKey())), e.getKey());
        }
        try (var s = Files.list(pasta)) {
            assertEquals(5, s.count());
        }
        assertFalse(Files.exists(pasta.resolve("CLAUDE.md")));
        assertEquals(List.of(List.of("docker", "network", "create", "--label", "com.docker.compose.network=default", "--label",
            "com.docker.compose.project=meu-app", "meu-app_default")), a.comandos());
        assertEquals(0, a.abertos.get(), "nenhum canal de arquivos ficou aberto");
    }

    @Test
    void guiaIaNoLocalNuncaEscreveClaudeMd() throws Exception {
        AmbienteFalso a = amb();
        new CriadorNoAmbiente(a.local(), RELOGIO).criar("com-guia", true);
        assertFalse(Files.exists(a.raiz.resolve("com-guia").resolve("CLAUDE.md")), "o Claude não existe no laboratório local");
    }

    @Test
    void aPastaDosProjetosQueNaoExisteEraCriadaSemTocarEmNada() throws Exception {
        AmbienteFalso a = amb();
        assertFalse(Files.exists(a.raiz));
        new CriadorNoAmbiente(a.local(), RELOGIO).criar("x", null);
        assertTrue(Files.isDirectory(a.raiz.resolve("x")));
    }

    @Test
    void nomeInvalidoReservadoEExistenteSaoRecusadosSemCriarNada() throws Exception {
        AmbienteFalso a = amb();
        CriadorNoAmbiente c = new CriadorNoAmbiente(a.local(), RELOGIO);
        for (String ruim : List.of("Meu", "a b", "../x", "", "a".repeat(50), "abc-")) {
            FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> c.criar(ruim, null), ruim);
            assertEquals(FalhaDoProjeto.Tipo.RECUSADO, f.tipo());
            assertFalse(f.mensagem().isEmpty());
        }
        for (String reservado : List.of("infra", "painel", "lab")) {
            FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> c.criar(reservado, null));
            assertEquals("Esse nome é reservado pela infraestrutura do lab.", f.mensagem());
        }
        c.criar("existe", null);
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> c.criar("existe", null));
        assertEquals("Já existe um projeto com esse nome.", f.mensagem());
        assertEquals(1, a.comandos().size(), "só a rede do primeiro; nada foi rodado nos recusados");
        try (var s = Files.list(a.raiz)) {
            assertEquals(List.of("existe"), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void oQueJaExisteNuncaETocado() throws Exception {
        AmbienteFalso a = amb();
        Files.createDirectories(a.raiz.resolve("meu"));
        Files.writeString(a.raiz.resolve("meu").resolve("dados.txt"), "do usuário");
        assertThrows(FalhaDoProjeto.class, () -> new CriadorNoAmbiente(a.local(), RELOGIO).criar("meu", null));
        assertEquals("do usuário", ler(a.raiz.resolve("meu").resolve("dados.txt")));
        try (var s = Files.list(a.raiz.resolve("meu"))) {
            assertEquals(1, s.count());
        }
    }

    @Test
    void dockerForaDoArNaoImpedeCriarEViraAviso() throws Exception {
        AmbienteFalso a = amb();
        a.roteiro = c -> {
            throw new ProgramaNaoEncontradoException("docker");
        };
        CriadorDeProjeto.Resultado r = new CriadorNoAmbiente(a.local(), RELOGIO).criar("meu-app", null);
        assertTrue(Files.isDirectory(a.raiz.resolve("meu-app")));
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("meu-app_default"));
        assertTrue(r.avisos().get(0).contains("cria na primeira vez"));
    }

    @Test
    void dockerRecusandoARedeViraAvisoMasRedeQueJaExisteNao() throws Exception {
        AmbienteFalso a = amb();
        a.roteiro = c -> AmbienteFalso.erro(1, "Error response from daemon: network with name meu-app_default already exists");
        assertEquals(List.of(), new CriadorNoAmbiente(a.local(), RELOGIO).criar("meu-app", null).avisos());
        a.roteiro = c -> AmbienteFalso.erro(1, "Cannot connect to the Docker daemon");
        assertEquals(1, new CriadorNoAmbiente(a.local(), RELOGIO).criar("outro", null).avisos().size());
    }

    @Test
    void falhaNoMeioDaGravacaoApagaSoAPastaCriada() throws Exception {
        AmbienteFalso a = amb();
        a.falharNaGravacao = 3;
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new CriadorNoAmbiente(a.local(), RELOGIO).criar("meu-app", null));
        assertEquals(FalhaDoProjeto.Tipo.SEM_PERMISSAO, f.tipo());
        assertFalse(Files.exists(a.raiz.resolve("meu-app")), "nada de projeto pela metade");
        assertTrue(Files.isDirectory(a.raiz), "a raiz dos projetos fica");
        assertEquals(List.of(), a.comandos(), "nem a rede foi pedida");
        assertEquals(0, a.abertos.get());
    }

    @Test
    void existeRelaOSistemaDeArquivos() throws Exception {
        AmbienteFalso a = amb();
        CriadorNoAmbiente c = new CriadorNoAmbiente(a.local(), RELOGIO);
        assertEquals(Optional.of(false), c.existe("meu-app"));
        c.criar("meu-app", null);
        assertEquals(Optional.of(true), c.existe("meu-app"));
        assertEquals(Optional.of(false), c.existe("../x"));
    }

    @Test
    void odiaDoClockEAdoGabarito() throws Exception {
        AmbienteFalso a = amb();
        new CriadorNoAmbiente(a.local(), RELOGIO).criar("meu-app", null);
        assertTrue(ler(a.raiz.resolve("meu-app").resolve("STATUS.md")).contains("Criado em 2026-10-28."));
    }
}
