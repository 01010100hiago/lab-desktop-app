package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * O pacote do agente que vai DENTRO do app (resources/agente): confere que é íntegro, que tem o que o Dockerfile precisa e, quando há uma
 * cópia do repositório do agente (pasta indicada em LABDESKTOP_AGENTE_DIR), que é o conteúdo do commit fixado. É o "build normal só CONFERE": quem monta é a tarefa
 * {@code empacotarAgente}.
 */
class PacoteDoAgenteTest {

    @Test
    void semOPacoteOErroEOTextoQueOUsuarioLe() {
        String anterior = PacoteDoAgente.trocarDescritorParaTeste("/agente-que-nao-existe/agente.json");
        try {
            assertFalse(PacoteDoAgente.disponivel());
            PacoteDoAgenteAusenteException e = org.junit.jupiter.api.Assertions.assertThrows(PacoteDoAgenteAusenteException.class, PacoteDoAgente::doClasspath);
            assertEquals(PacoteDoAgenteAusenteException.TEXTO, e.getMessage());
            assertTrue(e.getMessage().startsWith("Esta versão do app foi compilada sem o agente de monitoramento"), e.getMessage());
            assertTrue(e.getMessage().contains("Terminal, arquivos e segurança continuam funcionando."));
            assertFalse(e.getMessage().contains("gradlew"), "sem jargão de build");
            assertEquals(PacoteDoAgenteAusenteException.TEXTO, br.com.labdesktop.ui.MensagensDeErro.de(e), "a tela mostra a frase pura, sem 'Algo deu errado'");
        } finally {
            PacoteDoAgente.trocarDescritorParaTeste(anterior);
        }
    }

    @Test
    void oPacoteDeTesteEOSinteticoSoQuandoNaoHaPacoteDeVerdade() {
        assertEquals(!br.com.labdesktop.teste.PacoteDeTeste.temPacoteDeVerdade(), br.com.labdesktop.teste.PacoteDeTeste.ehSintetico());
        assertTrue(PacoteDoAgente.disponivel(), "com ou sem o de verdade, há um pacote no classpath de teste");
    }

    @Test
    void oPacoteDoJarEIntegroEDescritoPelaVersaoEPeloCommit() throws Exception {
        assumeTrue(PacoteDoAgente.disponivel(), "sem pacote do agente no classpath (nem o sintético de teste)");
        PacoteDoAgente p = PacoteDoAgente.doClasspath();
        assertNotNull(p);
        assertTrue(p.versao().matches("^[0-9]+\\.[0-9]+\\.[0-9]+-[0-9a-f]{7}$"), p.versao());
        assertTrue(p.commit().matches("^[0-9a-f]{40}$"), "o commit é o hash inteiro, nunca um nome de branch: " + p.commit());
        assertTrue(p.versao().endsWith(p.commit().substring(0, 7)));
        assertEquals(PacoteDoAgente.sha256De(p.conteudo()), p.sha256());
        p.conferir();
        assertEquals("agente-" + p.versao() + ".tar.gz", p.nomeDoArquivo());
    }

    @Test
    void oPacoteTemOQueOAgenteMonitorPrecisaEAVersaoDentro() throws Exception {
        assumeFalse(br.com.labdesktop.teste.PacoteDeTeste.ehSintetico(), "o app não leva o pacote do agente de verdade (src/main/resources/agente): este teste confere o agente real; rode ./gradlew empacotarAgente");
        PacoteDoAgente p = PacoteDoAgente.doClasspath();
        Map<String, LeitorDeTarParaTeste.Entrada> a = LeitorDeTarParaTeste.ler(p.conteudo());
        for (String nome : MontadorDoPacote.ARQUIVOS) {
            assertTrue(a.containsKey(nome), "falta " + nome);
        }
        assertEquals(p.versao() + "\n", new String(a.get("VERSAO").conteudo(), StandardCharsets.UTF_8));
        String dockerfile = new String(a.get("Dockerfile.monitor").conteudo(), StandardCharsets.UTF_8);
        assertTrue(dockerfile.contains("FROM node:"), dockerfile);
        assertTrue(dockerfile.contains("PAINEL_PERFIL=monitor"));
        assertTrue(dockerfile.contains("COPY VERSAO"));
        assertFalse(dockerfile.lines().anyMatch(l -> l.strip().matches("^(RUN|COPY|ADD) .*(npm|package\\.json).*")), "sem dependências npm");
        String painel = new String(a.get("painel.js").conteudo(), StandardCharsets.UTF_8);
        assertTrue(painel.contains("PAINEL_PERFIL"), "o painel do pacote entende o perfil");
        assertTrue(painel.contains("rotaPermitidaNoMonitor"));
        assertTrue(painel.contains("servidor.listen(PORT, '127.0.0.1'"), "escuta só no loopback");
        for (var e : a.values()) {
            assertEquals(0, e.data(), "pacote reproduzível: " + e.nome());
        }
    }

    @Test
    void comORepositorioAoLadoOPacoteEOConteudoDoCommitFixado() throws Exception {
        assumeFalse(br.com.labdesktop.teste.PacoteDeTeste.ehSintetico(), "o app não leva o pacote do agente de verdade (src/main/resources/agente): este teste confere o agente real; rode ./gradlew empacotarAgente");
        String pasta = System.getenv("LABDESKTOP_AGENTE_DIR");
        assumeTrue(pasta != null && Files.isDirectory(Path.of(pasta).resolve(".git")), "sem LABDESKTOP_AGENTE_DIR apontando para o repositório do agente (a CI não o tem): o resto da conferência já valeu");
        Path painel = Path.of(pasta);
        PacoteDoAgente p = PacoteDoAgente.doClasspath();
        Process existe = new ProcessBuilder("git", "-C", painel.toString(), "cat-file", "-e", p.commit() + "^{commit}").start();
        assumeTrue(existe.waitFor() == 0, "o commit fixado não está neste clone do agente");
        Map<String, LeitorDeTarParaTeste.Entrada> embutido = LeitorDeTarParaTeste.ler(p.conteudo());
        for (String nome : MontadorDoPacote.ARQUIVOS) {
            Process show = new ProcessBuilder("git", "-C", painel.toString(), "show", p.commit() + ":" + nome).start();
            byte[] doGit = show.getInputStream().readAllBytes();
            assertEquals(0, show.waitFor());
            assertEquals(PacoteDoAgente.sha256De(doGit), PacoteDoAgente.sha256De(embutido.get(nome).conteudo()), nome + " difere do commit fixado");
        }
    }
}
