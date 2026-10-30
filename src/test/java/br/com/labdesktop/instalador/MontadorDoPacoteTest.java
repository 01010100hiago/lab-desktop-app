package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A montagem do pacote do agente: o tar.gz é o mesmo em qualquer máquina e só leva o que deve levar; git de verdade, num repositório de mentira. */
class MontadorDoPacoteTest {

    @TempDir
    Path tmp;

    /** Os arquivos de um agente de mentira (os nomes do agente público de hoje; o que vale na prática é o campo "files" do package.json dele). */
    private static final List<String> NOMES = List.of("Dockerfile.monitor", "acesso.js", "docker-proxy.js", "historico.js", "metricas.js", "painel.js",
        "privilegios.js", "totp.js");

    private static Map<String, byte[]> arquivos() {
        Map<String, byte[]> m = new TreeMap<>();
        for (String nome : NOMES) {
            m.put(nome, ("conteúdo de " + nome + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void tarGzEDeterministicoEOrdenadoComDataEDonoZerados() throws Exception {
        byte[] a = MontadorDoPacote.tarGz(arquivos());
        Map<String, byte[]> embaralhado = new java.util.LinkedHashMap<>();
        List<String> nomes = new java.util.ArrayList<>(arquivos().keySet());
        java.util.Collections.reverse(nomes);
        for (String n : nomes) {
            embaralhado.put(n, arquivos().get(n));
        }
        byte[] b = MontadorDoPacote.tarGz(embaralhado);
        assertArrayEquals(a, b, "a ordem de entrada não muda o resultado");

        Map<String, LeitorDeTarParaTeste.Entrada> lidas = LeitorDeTarParaTeste.ler(a);
        assertEquals(NOMES, List.copyOf(lidas.keySet()), "em ordem de nome");
        for (var e : lidas.values()) {
            assertEquals(0, e.data(), e.nome());
            assertEquals(0, e.uid());
            assertEquals(0, e.gid());
            assertEquals(0644, e.modo());
            assertArrayEquals(arquivos().get(e.nome()), e.conteudo(), e.nome());
        }
    }

    @Test
    void oTarGzTemOCabecalhoGzipSemDataENoFimOsBlocosDeZero() throws Exception {
        byte[] gz = MontadorDoPacote.tarGz(arquivos());
        assertEquals((byte) 0x1f, gz[0]);
        assertEquals((byte) 0x8b, gz[1]);
        for (int i = 4; i < 8; i++) {
            assertEquals(0, gz[i], "a data do gzip é zero: o pacote não muda de um dia para o outro");
        }
    }

    @Test
    void nomeDeArquivoComBarraOuPontoPontoNaoEntra() {
        for (String ruim : new String[] {"a/b", "../x", "", "x".repeat(100)}) {
            Map<String, byte[]> m = new TreeMap<>();
            m.put(ruim, new byte[] {1});
            assertThrows(IllegalArgumentException.class, () -> MontadorDoPacote.tarGz(m), ruim);
        }
    }

    @Test
    void deAcrescentaOArquivoVersaoEFixaOShaEAVersao() throws Exception {
        PacoteDoAgente p = MontadorDoPacote.de(arquivos(), "0.1.0-abc1234", "abc1234def");
        assertEquals("0.1.0-abc1234", p.versao());
        assertEquals(PacoteDoAgente.sha256De(p.conteudo()), p.sha256());
        var lidas = LeitorDeTarParaTeste.ler(p.conteudo());
        assertEquals("0.1.0-abc1234\n", new String(lidas.get("VERSAO").conteudo(), StandardCharsets.UTF_8));
        p.conferir();
    }

    @Test
    void montarLeDoCommitFixadoEnaoDaPastaDeTrabalho() throws Exception {
        Path repo = tmp.resolve("repo-do-agente");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        git(repo, "config", "commit.gpgsign", "false");
        for (var e : arquivos().entrySet()) {
            Files.write(repo.resolve(e.getKey()), e.getValue());
        }
        Files.writeString(repo.resolve("VERSAO"), "9.9.9\n");
        Files.writeString(repo.resolve("package.json"), pacoteJson(NOMES, "VERSAO"));
        Files.writeString(repo.resolve("README.md"), "fora de files: não vai para o pacote\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "um");
        String commit = git(repo, "rev-parse", "HEAD").strip();
        // a pasta de trabalho muda DEPOIS do commit: o pacote não pode ver isso
        Files.writeString(repo.resolve("painel.js"), "ALTERADO DEPOIS DO COMMIT\n");
        Files.writeString(repo.resolve("extra.js"), "não é do pacote\n");

        Path destino = tmp.resolve("agente");
        Files.createDirectories(destino);
        Files.writeString(destino.resolve("agente-velho.tar.gz"), "lixo");
        PacoteDoAgente p = MontadorDoPacote.montar(repo, commit, "0.1.0", destino);

        assertEquals("0.1.0-" + commit.substring(0, 7), p.versao());
        assertEquals(commit, p.commit());
        var lidas = LeitorDeTarParaTeste.ler(p.conteudo());
        assertEquals("conteúdo de painel.js\n", new String(lidas.get("painel.js").conteudo(), StandardCharsets.UTF_8));
        assertTrue(!lidas.containsKey("extra.js"));
        assertTrue(!lidas.containsKey("README.md") && !lidas.containsKey("package.json"), "só o que o campo files lista");
        assertEquals("0.1.0-" + commit.substring(0, 7) + "\n", new String(lidas.get("VERSAO").conteudo(), StandardCharsets.UTF_8),
            "o VERSAO do pacote é escrito pelo montador, não o do repositório (9.9.9)");
        assertEquals(NOMES.size() + 1, lidas.size());
        assertTrue(!Files.exists(destino.resolve("agente-velho.tar.gz")), "o pacote velho sai: só há um");
        JsonNode d = Json.mapper().readTree(Files.readString(destino.resolve("agente.json")));
        assertEquals(p.versao(), d.path("versao").asText());
        assertEquals(p.sha256(), d.path("sha256").asText());
        assertEquals(PacoteDoAgente.sha256De(Files.readAllBytes(destino.resolve(d.path("arquivo").asText()))), p.sha256());

        // de novo, do mesmo commit: o mesmo conteúdo (o tar sem o gzip é idêntico entre máquinas; aqui, na mesma, o gzip também)
        PacoteDoAgente de_novo = MontadorDoPacote.montar(repo, commit, "0.1.0", destino);
        assertEquals(p.sha256(), de_novo.sha256());
    }

    @Test
    void aListaVemDoPackageJsonDoAgenteEUmaListaRuimNaoMontaNada() throws Exception {
        Path repo = tmp.resolve("agente");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        git(repo, "config", "commit.gpgsign", "false");
        for (var e : arquivos().entrySet()) {
            Files.write(repo.resolve(e.getKey()), e.getValue());
        }
        Files.writeString(repo.resolve("package.json"), pacoteJson(NOMES, "VERSAO"));
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "ok");
        String bom = git(repo, "rev-parse", "HEAD").strip();
        assertEquals(NOMES, MontadorDoPacote.arquivosDoCommit(repo, bom), "VERSAO sai da lista: o montador a escreve");

        String[][] ruins = {
            {"{\"name\":\"x\"}", "sem files"},
            {"{\"files\":[]}", "files vazio"},
            {"{\"files\":\"painel.js\"}", "files que não é lista"},
            {"não é json", "JSON quebrado"},
            {pacoteJson(List.of("Dockerfile.monitor", "../fora.js", "painel.js")), "sobe de pasta"},
            {pacoteJson(List.of("Dockerfile.monitor", "pasta/x.js", "painel.js")), "pasta"},
            {pacoteJson(List.of("Dockerfile.monitor", "-x", "painel.js")), "começa com hífen"},
            {pacoteJson(List.of("Dockerfile.monitor", ".env", "painel.js")), "arquivo escondido"},
            {pacoteJson(List.of("Dockerfile.monitor", "x".repeat(100), "painel.js")), "nome grande demais"},
            {pacoteJson(List.of("painel.js")), "sem o Dockerfile"},
            {pacoteJson(List.of("Dockerfile.monitor")), "sem o painel.js"},
        };
        for (String[] r : ruins) {
            Files.writeString(repo.resolve("package.json"), r[0]);
            git(repo, "commit", "-q", "-am", r[1]);
            String commit = git(repo, "rev-parse", "HEAD").strip();
            assertThrows(java.io.IOException.class, () -> MontadorDoPacote.arquivosDoCommit(repo, commit), r[1]);
            assertThrows(java.io.IOException.class, () -> MontadorDoPacote.montar(repo, commit, "0.1.0", tmp.resolve("saida")), r[1]);
        }
        assertTrue(!Files.exists(tmp.resolve("saida")), "uma lista ruim não grava nada");
    }

    private static String pacoteJson(List<String> files, String... mais) {
        List<String> todos = new java.util.ArrayList<>(files);
        todos.addAll(List.of(mais));
        return "{\"name\":\"agente\",\"files\":[" + todos.stream().map(f -> "\"" + f + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}\n";
    }

    @Test
    void commitQueNaoEOshaCompletoOuNaoExisteERecusado() {
        Path repo = tmp.resolve("x");
        assertThrows(IllegalArgumentException.class, () -> MontadorDoPacote.montar(repo, "main", "0.1.0", tmp.resolve("d")));
        assertThrows(IllegalArgumentException.class, () -> MontadorDoPacote.montar(repo, "abc1234", "0.1.0", tmp.resolve("d")));
        assertThrows(java.io.IOException.class, () -> MontadorDoPacote.montar(repo, "0".repeat(40), "0.1.0", tmp.resolve("d")));
    }

    @Test
    void pacoteComShaErradoOuSemBytesEhRecusado() {
        assertThrows(IllegalArgumentException.class, () -> new PacoteDoAgente("1.0", "abc1234", "xyz", new byte[] {1}));
        PacoteDoAgente ruim = new PacoteDoAgente("1.0", "abc1234", "0".repeat(64), new byte[] {1});
        assertThrows(java.io.IOException.class, ruim::conferir);
    }

    private static String git(Path pasta, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", pasta.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + saida);
        }
        return saida;
    }
}
