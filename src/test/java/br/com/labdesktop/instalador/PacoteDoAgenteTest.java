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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * O pacote do agente que vai DENTRO do app (resources/agente): confere que é íntegro, que tem o que o Dockerfile precisa, que o agente empacotado é o
 * de só leitura (as rotas, o loopback, o token obrigatório, nada que execute programa) e, quando há uma cópia do repositório do agente ao lado
 * ({@code ../lab-desktop-agente}), que é o conteúdo do commit fixado. É o "build normal só CONFERE": quem monta é a tarefa {@code empacotarAgente}.
 */
class PacoteDoAgenteTest {

    /** O que o agente público de hoje põe no pacote (campo {@code files} do package.json dele, sem o VERSAO, que o montador escreve). */
    private static final List<String> ESPERADOS = List.of("Dockerfile.monitor", "VERSAO", "acesso.js", "docker-proxy.js", "historico.js", "metricas.js",
        "painel.js", "privilegios.js", "totp.js");

    /** As rotas que o app chama num agente de monitoramento (ClienteApi): versão, métricas, detalhe, histórico e a confirmação do 2FA. */
    private static final Set<String> ROTAS_DO_APP = Set.of("GET /api/versao", "GET /api/servidor", "GET /api/servidor/detalhe",
        "GET /api/servidor/historico", "POST /api/acesso/2fa");

    private static String texto(Map<String, LeitorDeTarParaTeste.Entrada> a, String nome) {
        return new String(a.get(nome).conteudo(), StandardCharsets.UTF_8);
    }

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
    void oAppLevaOPacoteDoAgenteDeVerdade() {
        assertTrue(PacoteDoAgente.disponivel(), "o jar leva agente/agente.json e o tar.gz dele");
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
    void oPacoteTemExatamenteOsArquivosDoAgenteEAVersaoDentroEOGradlePropertiesFixaOMesmoCommit() throws Exception {
        PacoteDoAgente p = PacoteDoAgente.doClasspath();
        Map<String, LeitorDeTarParaTeste.Entrada> a = LeitorDeTarParaTeste.ler(p.conteudo());
        assertEquals(ESPERADOS, List.copyOf(new java.util.TreeSet<>(a.keySet())),
            "só estes arquivos: sem .git, node_modules, testes, documentação nem segredos");
        for (String nome : MontadorDoPacote.OBRIGATORIOS) {
            assertTrue(a.containsKey(nome), "falta " + nome);
        }
        assertEquals(p.versao() + "\n", texto(a, "VERSAO"));
        for (var e : a.values()) {
            assertEquals(0, e.data(), "pacote reproduzível: " + e.nome());
            assertEquals(0, e.uid());
        }
        String propriedades = Files.readString(Path.of("gradle.properties"));
        assertTrue(propriedades.contains("agenteCommit=" + p.commit() + "\n"), "gradle.properties fixa o commit que está no pacote (rode empacotarAgente com -PagenteCommit)");
    }

    @Test
    void oDockerfileCopiaTudoQueOAgenteCarregaESemDependenciasNpm() throws Exception {
        Map<String, LeitorDeTarParaTeste.Entrada> a = LeitorDeTarParaTeste.ler(PacoteDoAgente.doClasspath().conteudo());
        String dockerfile = texto(a, "Dockerfile.monitor");
        assertTrue(dockerfile.contains("FROM node:"), dockerfile);
        assertTrue(dockerfile.contains("PAINEL_PERFIL=monitor"));
        assertTrue(dockerfile.contains("COPY VERSAO"));
        assertFalse(dockerfile.lines().anyMatch(l -> l.strip().matches("^(RUN|COPY|ADD) .*(npm|package\\.json).*") && !l.contains("rm -rf")), "sem dependências npm");
        // cada arquivo .js do pacote entra na imagem (um que faltasse faria o agente cair com "Cannot find module" só na máquina do usuário)
        Set<String> copiados = new java.util.HashSet<>();
        for (String linha : dockerfile.split("\\R")) {
            if (linha.strip().startsWith("COPY ")) {
                String[] partes = linha.strip().split("\\s+");
                copiados.addAll(List.of(partes).subList(1, partes.length - 1));
            }
        }
        for (String nome : a.keySet()) {
            if (nome.endsWith(".js")) {
                assertTrue(copiados.contains(nome), "o Dockerfile não copia " + nome + ": " + copiados);
            }
        }
        // e todo require('./x.js') aponta para um arquivo do pacote
        java.util.regex.Pattern requer = java.util.regex.Pattern.compile("require\\('\\./([A-Za-z0-9_.-]+)'\\)");
        for (var e : a.entrySet()) {
            if (e.getKey().endsWith(".js")) {
                java.util.regex.Matcher m = requer.matcher(texto(a, e.getKey()));
                while (m.find()) {
                    assertTrue(a.containsKey(m.group(1)), e.getKey() + " carrega " + m.group(1) + ", que não está no pacote");
                }
            }
        }
    }

    @Test
    void oAgenteEmpacotadoSoLeTemSoAsRotasDoAppEExigeToken() throws Exception {
        Map<String, LeitorDeTarParaTeste.Entrada> a = LeitorDeTarParaTeste.ler(PacoteDoAgente.doClasspath().conteudo());
        String painel = texto(a, "painel.js");
        // a lista de rotas, lida do próprio fonte: tem de ser EXATAMENTE as que o app chama (e nenhuma de escrita fora do 2FA)
        Set<String> rotas = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("req\\.method === '(GET|POST)' && caminho === '([^']+)'").matcher(painel);
        while (m.find()) {
            rotas.add(m.group(1) + " " + m.group(2));
        }
        assertEquals(new java.util.TreeSet<>(ROTAS_DO_APP), rotas, "as rotas do agente empacotado: o que o app chama, e nada mais");
        assertFalse(painel.contains("/api/containers") || painel.contains("/api/projetos") || painel.contains("/terminal"), "o agente de leitura não tem rotas de containers, projetos nem terminal");
        assertTrue(painel.contains("claude: false, projetos: false, monitor: true, guia_ia: false, historico: true"), "recursos que o app lê em /api/versao");
        assertTrue(painel.contains("PAINEL_PERFIL") && painel.contains("PAINEL_PERFIL inválido"), "valor desconhecido de perfil não sobe");
        // o token é sempre exigido: PAINEL_EXIGE_TOKEN=0 não sobe (o app manda =1 e o agente ignora)
        assertTrue(painel.contains("O token é obrigatório e não pode ser desligado"), "não existe modo sem token");
        // o padrão (e o do servidor remoto) é o loopback; só PAINEL_ESCUTA=todas, do container de Este computador (porta publicada só em 127.0.0.1), muda isso
        assertTrue(painel.contains("enderecoDeEscuta: escuta === 'todas' ? '0.0.0.0' : '127.0.0.1'"), "o padrão escuta só no loopback");
        assertTrue(painel.contains("servidor.listen(configuracao.porta, configuracao.enderecoDeEscuta"));
        assertTrue(painel.contains("texto('PAINEL_ESCUTA', 'loopback')"), "sem a variável, loopback");
        assertTrue(painel.contains("PAINEL_ESCUTA inválido"), "valor desconhecido não sobe");
        // o resto da cerca: nada que execute programa nem avalie texto, em arquivo nenhum do pacote
        for (var e : a.entrySet()) {
            if (e.getKey().endsWith(".js")) {
                String js = texto(a, e.getKey());
                assertFalse(js.contains("child_process"), e.getKey() + " não pode executar programa");
                assertFalse(js.matches("(?s).*\\beval\\s*\\(.*") || js.contains("new Function("), e.getKey() + " não pode avaliar texto");
            }
        }
    }

    @Test
    void comORepositorioDoAgenteAoLadoOPacoteEOConteudoDoCommitFixado() throws Exception {
        Path agente = Path.of("..", "lab-desktop-agente");
        assumeTrue(Files.isDirectory(agente.resolve(".git")), "sem ../lab-desktop-agente (a CI não o tem): o resto da conferência já valeu");
        PacoteDoAgente p = PacoteDoAgente.doClasspath();
        Process existe = new ProcessBuilder("git", "-C", agente.toString(), "cat-file", "-e", p.commit() + "^{commit}").start();
        assumeTrue(existe.waitFor() == 0, "o commit fixado não está neste clone do agente");
        Map<String, LeitorDeTarParaTeste.Entrada> embutido = LeitorDeTarParaTeste.ler(p.conteudo());
        List<String> doCommit = MontadorDoPacote.arquivosDoCommit(agente, p.commit());
        assertEquals(doCommit.size() + 1, embutido.size(), "o pacote tem os arquivos do campo files do commit, mais o VERSAO");
        for (String nome : doCommit) {
            Process show = new ProcessBuilder("git", "-C", agente.toString(), "show", p.commit() + ":" + nome).start();
            byte[] doGit = show.getInputStream().readAllBytes();
            assertEquals(0, show.waitFor());
            assertEquals(PacoteDoAgente.sha256De(doGit), PacoteDoAgente.sha256De(embutido.get(nome).conteudo()), nome + " difere do commit fixado");
        }
    }
}
