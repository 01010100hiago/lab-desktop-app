package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.projetos.ModelosDeContainer.Parametros;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Adicionar container": o que mostra (só leitura), o que grava e, principalmente, o que NÃO grava: compose que o Docker recusa, compose do usuário, arquivo
 * que mudou depois da diferença. Sistema de arquivos de uma pasta temporária e um roteiro no lugar do {@code docker compose config}.
 */
class EscritorDeComposeTest {

    @TempDir
    Path tmp;

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    private AmbienteFalso a;
    private Path projeto;

    private void montar(String compose) throws IOException {
        a = new AmbienteFalso(tmp);
        projeto = a.raiz.resolve("demo");
        Files.createDirectories(projeto);
        if (compose != null) {
            Files.writeString(projeto.resolve("docker-compose.yml"), compose, StandardCharsets.UTF_8);
        }
    }

    private EscritorDeCompose escritor() {
        return new EscritorDeCompose(a.local(), RELOGIO, new SecureRandom());
    }

    private static BlocoDeServico bloco(Modelo m) {
        return ModelosDeContainer.gerar(m, Parametros.padraoDe(m));
    }

    private static List<String> nomes(Path pasta) throws IOException {
        try (Stream<Path> s = Files.list(pasta)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private String lerCompose() throws IOException {
        return Files.readString(projeto.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------
    // ver
    // ---------------------------------------------------------------------------------------------

    @Test
    void prepararSoLeENaoMudaNada() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        List<String> antes = nomes(projeto);
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.POSTGRES));
        assertTrue(p.gravavel());
        assertEquals(ComposeDoProjeto.Situacao.ESQUELETO, p.situacao());
        assertTrue(p.diferenca().contains("- services: {}\n+ services:\n+   postgres:\n"), p.diferenca());
        assertTrue(p.diferenca().contains("+ volumes:\n+   postgres_dados:\n"), p.diferenca());
        assertEquals(GabaritoDoProjeto.composeVazio("demo"), lerCompose());
        assertEquals(antes, nomes(projeto));
        assertEquals(List.of(), a.comandos(), "nem o Docker foi chamado ainda");
        assertEquals(0, a.abertos.get());
    }

    @Test
    void composeComplexoSoMostraOBlocoParaCopiar() throws Exception {
        String original = "services:\n  web:\n    image: nginx\nvolumes:\n  d:\n";
        montar(original);
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.REDIS));
        assertFalse(p.gravavel());
        assertEquals(ComposeDoProjeto.Situacao.COMPLEXO, p.situacao());
        assertNull(p.novo());
        assertEquals("", p.diferenca());
        assertTrue(p.textoParaCopiar().contains("# 1) Em \"services:\""));
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(p));
        assertEquals(FalhaDoProjeto.Tipo.RECUSADO, f.tipo());
        assertEquals(original, lerCompose(), "o compose do usuário nunca é reescrito");
        assertEquals(List.of("docker-compose.yml"), nomes(projeto));
        assertEquals(List.of(), a.comandos());
    }

    @Test
    void semComposeOArquivoSeriaCriadoDoEsqueleto() throws Exception {
        montar(null);
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.REDIS));
        assertEquals(ComposeDoProjeto.Situacao.AUSENTE, p.situacao());
        assertTrue(p.gravavel());
        assertTrue(p.diferenca().startsWith("+ name: \"demo\"\n"), p.diferenca());
        assertEquals(List.of(), nomes(projeto));
    }

    @Test
    void projetoQueNaoExisteMaisEUmaFrase() throws Exception {
        montar(null);
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().preparar("fantasma", bloco(Modelo.REDIS)));
        assertTrue(f.mensagem().contains("não existe mais"));
        assertThrows(FalhaDoProjeto.class, () -> escritor().preparar("../x", bloco(Modelo.REDIS)));
    }

    // ---------------------------------------------------------------------------------------------
    // gravar
    // ---------------------------------------------------------------------------------------------

    @Test
    void gravaRedisConferindoAntesEGuardandoUmaCopiaDatada() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        montar(original);
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.REDIS));
        EscritorDeCompose.Gravado g = escritor().gravar(p);
        assertEquals("docker-compose.yml.20261028-153000.bak", g.copia());
        assertEquals(original, Files.readString(projeto.resolve(g.copia())), "a cópia é o compose de antes");
        assertEquals(p.novo(), lerCompose());
        assertEquals(List.of(), g.variaveisNovas());
        // a conferência recebeu o resultado pela ENTRADA, na pasta do projeto, e só depois o arquivo foi trocado
        assertEquals(1, a.chamadas.size());
        assertEquals(List.of("docker", "compose", "--project-directory", projeto.toString(), "-f", "-", "config", "-q"), a.chamadas.get(0).argumentos());
        assertEquals(p.novo(), a.chamadas.get(0).entrada());
        assertEquals(List.of("docker-compose.yml", "docker-compose.yml.20261028-153000.bak"), nomes(projeto));
        assertEquals(0, a.abertos.get());
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void postgresGeraSenhaNoEnvENuncaNoCompose() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        Files.writeString(projeto.resolve(".env.example"), "# nomes\n");
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.POSTGRES));
        EscritorDeCompose.Gravado g = escritor().gravar(p);
        assertEquals(List.of("POSTGRES_PASSWORD"), g.variaveisNovas());
        String env = Files.readString(projeto.resolve(".env"));
        assertTrue(env.matches("POSTGRES_PASSWORD=[A-Za-z0-9]{24}\n"), env);
        String senha = env.substring("POSTGRES_PASSWORD=".length()).strip();
        assertFalse(lerCompose().contains(senha), "a senha nunca vai para o compose");
        for (AmbienteFalso.Chamada c : a.chamadas) {
            assertFalse(c.entrada().contains(senha), "nem para a conferência");
            assertFalse(String.join(" ", c.argumentos()).contains(senha));
            assertFalse(c.ambiente().containsValue(senha));
        }
        assertEquals("conferencia", a.chamadas.get(0).ambiente().get("POSTGRES_PASSWORD"), "a conferência só precisa da variável definida");
        assertEquals("# nomes\nPOSTGRES_PASSWORD=\n", Files.readString(projeto.resolve(".env.example")), "só o NOME vai para o .env.example");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(projeto.resolve(".env"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms, ".env só do usuário");
    }

    @Test
    void chaveQueJaExisteNoEnvNuncaETrocada() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        Files.writeString(projeto.resolve(".env"), "OUTRA=1\nPOSTGRES_PASSWORD=minha-senha\n");
        EscritorDeCompose.Gravado g = escritor().gravar(escritor().preparar("demo", bloco(Modelo.POSTGRES)));
        assertEquals(List.of(), g.variaveisNovas());
        assertEquals("OUTRA=1\nPOSTGRES_PASSWORD=minha-senha\n", Files.readString(projeto.resolve(".env")));
    }

    @Test
    void dockerRecusandoOComposeNadaEGravado() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        montar(original);
        a.roteiro = c -> AmbienteFalso.erro(1, "services.postgres.ports contains an invalid type\nOutra linha");
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.POSTGRES));
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(p));
        assertEquals(FalhaDoProjeto.Tipo.RECUSADO, f.tipo());
        assertTrue(f.mensagem().contains("invalid type"), f.mensagem());
        assertTrue(f.mensagem().contains("Nada foi gravado"));
        assertEquals(original, lerCompose());
        assertEquals(List.of("docker-compose.yml"), nomes(projeto), "nem cópia, nem .env");
    }

    @Test
    void semDockerNaoGravaPorqueNaoDaParaConferir() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        montar(original);
        a.roteiro = c -> {
            throw new ProgramaNaoEncontradoException("docker");
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS))));
        assertTrue(f.mensagem().contains("Nada foi gravado"));
        assertEquals(original, lerCompose());
        assertEquals(List.of("docker-compose.yml"), nomes(projeto));
    }

    @Test
    void conexaoQueCaiNaConferenciaTambemNaoGrava() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        montar(original);
        a.roteiro = c -> {
            throw new IOException("conexão caiu");
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS))));
        assertTrue(f.mensagem().contains("Nada foi gravado"));
        assertEquals(original, lerCompose());
    }

    @Test
    void arquivoQueMudouDepoisDaDiferencaNaoEGravado() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.REDIS));
        String outro = "services:\n  web:\n    image: nginx\n";
        Files.writeString(projeto.resolve("docker-compose.yml"), outro); // o usuário editou enquanto olhava
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(p));
        assertTrue(f.mensagem().contains("mudou"), f.mensagem());
        assertEquals(outro, lerCompose());
        assertEquals(List.of("docker-compose.yml"), nomes(projeto));
        assertEquals(List.of(), a.comandos());
    }

    @Test
    void semArquivoCriaOCompose() throws Exception {
        montar(null);
        EscritorDeCompose.Gravado g = escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertNull(g.copia(), "não havia o que copiar");
        assertTrue(lerCompose().startsWith("name: \"demo\"\n"));
        assertTrue(lerCompose().contains("services:\n  redis:\n"));
        assertEquals(List.of("docker-compose.yml"), nomes(projeto));
    }

    @Test
    void duasCopiasNoMesmoSegundoNaoSeSobrescrevem() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        Files.writeString(projeto.resolve("docker-compose.yml.20261028-153000.bak"), "copia antiga");
        EscritorDeCompose.Gravado g = escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertEquals("docker-compose.yml.20261028-153000.bak-2", g.copia());
        assertEquals("copia antiga", Files.readString(projeto.resolve("docker-compose.yml.20261028-153000.bak")));
    }

    @Test
    void falhaAoGravarOEnvNaoDeixaOComposeMudado() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        montar(original);
        a.falharNaGravacao = 2; // 1 = a cópia, 2 = o .env
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(escritor().preparar("demo", bloco(Modelo.POSTGRES))));
        assertEquals(FalhaDoProjeto.Tipo.SEM_PERMISSAO, f.tipo());
        assertEquals(original, lerCompose(), "o compose com a variável obrigatória não foi gravado sem a variável");
        assertFalse(Files.exists(projeto.resolve(".env")));
    }

    @Test
    void oCaminhoDoComandoVaiComoOSistemaOEntende() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        EscritorDeCompose e = new EscritorDeCompose(a.localComConversao(p -> "C:" + p.replace('/', '\\')), RELOGIO, new SecureRandom());
        e.gravar(e.preparar("demo", bloco(Modelo.REDIS)));
        assertEquals("C:" + projeto.toString().replace('/', '\\'), a.chamadas.get(0).argumentos().get(3));
    }

    @Test
    void porSshACaminhoComEspacoRecusaSemGravar() throws Exception {
        AmbienteFalso comEspaco = new AmbienteFalso(tmp.resolve("com espaco"));
        Path pasta = comEspaco.raiz.resolve("demo");
        Files.createDirectories(pasta);
        String original = GabaritoDoProjeto.composeVazio("demo");
        Files.writeString(pasta.resolve("docker-compose.yml"), original);
        EscritorDeCompose e = new EscritorDeCompose(comEspaco.remoto(), RELOGIO, new SecureRandom());
        EscritorDeCompose.Previa p = e.preparar("demo", bloco(Modelo.REDIS));
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> e.gravar(p));
        assertTrue(f.mensagem().contains("Copiar o bloco"), f.mensagem());
        assertEquals(original, Files.readString(pasta.resolve("docker-compose.yml")));
        assertEquals(List.of(), comEspaco.comandos(), "nada saiu para o servidor");
    }

    @Test
    void arquivoGrandeDemaisNaoEMexido() throws Exception {
        montar("# " + "x".repeat(EscritorDeCompose.LIMITE_DO_COMPOSE + 10) + "\nservices: {}\n");
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertTrue(f.mensagem().contains("grande demais"));
    }

    // ---------------------------------------------------------------------------------------------
    // casos de borda do compose
    // ---------------------------------------------------------------------------------------------

    @Test
    void composeEmLatin1NaoEDecodificadoNemGravadoSoMostraOBloco() throws Exception {
        montar(null);
        byte[] latin1 = "name: demo\n# comentário com acento: ação\nservices: {}\n".getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
        Files.write(projeto.resolve("docker-compose.yml"), latin1);
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.REDIS));
        assertFalse(p.gravavel(), "decodificar corromperia os acentos (U+FFFD): o app não escreve neste arquivo");
        assertEquals(ComposeDoProjeto.Situacao.COMPLEXO, p.situacao());
        assertNull(p.original());
        assertTrue(p.textoParaCopiar().contains("redis"));
        assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(p));
        assertTrue(java.util.Arrays.equals(latin1, Files.readAllBytes(projeto.resolve("docker-compose.yml"))), "o arquivo não mudou nem um byte");
    }

    @Test
    void aCopiaDatadaSaiDosBytesLidosFielmenteInclusiveComAcentoEmUtf8() throws Exception {
        String original = "# serviço de ação\nname: demo\nservices: {}\n";
        montar(original);
        EscritorDeCompose.Gravado g = escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertTrue(java.util.Arrays.equals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(projeto.resolve(g.copia()))), "a cópia é fiel ao que havia");
    }

    @Test
    void seOArquivoMudaEnquantoODockerConfereNadaEGravadoENemACopiaNemOEnv() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        montar(original);
        EscritorDeCompose.Previa p = escritor().preparar("demo", bloco(Modelo.POSTGRES));
        a.roteiro = c -> {
            // a conferência demora até um minuto: neste meio-tempo outro cliente edita o arquivo
            Files.writeString(projeto.resolve("docker-compose.yml"), original + "# o usuário mexeu agora\n");
            return AmbienteFalso.ok("");
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> escritor().gravar(p));
        assertTrue(f.mensagem().contains("mudou"), f.mensagem());
        assertEquals(original + "# o usuário mexeu agora\n", Files.readString(projeto.resolve("docker-compose.yml")), "o que o outro escreveu ficou intacto");
        assertEquals(List.of("docker-compose.yml"), nomes(projeto), "nem cópia datada nem .env");
    }

    @Test
    void aCopiaDatadaFicaForaDoGitQuandoOGitignoreEDoEsqueletoDoApp() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        Files.writeString(projeto.resolve(".gitignore"), GabaritoDoProjeto.GITIGNORE);
        escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertTrue(Files.readString(projeto.resolve(".gitignore")).endsWith(".minio/\ndocker-compose.yml.*.bak\n"), Files.readString(projeto.resolve(".gitignore")));
    }

    @Test
    void comGitignoreDoDonoOAppNaoReescreveSoAvisa() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        Files.writeString(projeto.resolve(".gitignore"), "node_modules/\n");
        EscritorDeCompose.Gravado g = escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertEquals("node_modules/\n", Files.readString(projeto.resolve(".gitignore")));
        assertTrue(g.avisos().stream().anyMatch(x -> x.contains("docker-compose.yml.*.bak") && x.contains(".gitignore")), g.avisos().toString());
    }

    @Test
    void comGitignoreQueJaIgnoraBakNaoAvisaNada() throws Exception {
        montar(GabaritoDoProjeto.composeVazio("demo"));
        Files.writeString(projeto.resolve(".gitignore"), "*.bak\n");
        EscritorDeCompose.Gravado g = escritor().gravar(escritor().preparar("demo", bloco(Modelo.REDIS)));
        assertEquals(List.of(), g.avisos());
    }
}
