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

    private static Map<String, byte[]> arquivos() {
        Map<String, byte[]> m = new TreeMap<>();
        for (String nome : MontadorDoPacote.ARQUIVOS) {
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
        assertEquals(List.of("Dockerfile.monitor", "acesso.js", "docker-proxy.js", "guarda.js", "painel.js", "precos.json", "tokens.js", "totp.js"),
            List.copyOf(lidas.keySet()), "em ordem de nome");
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
        assertTrue(lidas.containsKey("VERSAO"));
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
