package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.ssh.ComandoDemorouException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Importar: git clone, pasta do computador e adotar uma pasta do servidor, com o sistema de arquivos de uma pasta temporária e um roteiro no lugar do git e do Docker. */
class ImportadorDeProjetoTest {

    @TempDir
    Path tmp;

    private static final UrlDeGit URL = UrlDeGit.conferir("https://github.com/usuario/repo.git").url();

    private static void escrever(Path p, String texto) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, texto, StandardCharsets.UTF_8);
    }

    private static List<String> nomes(Path pasta) throws IOException {
        try (Stream<Path> s = Files.list(pasta)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Um roteiro de {@code git clone} que cria a pasta de destino (o último argumento) com os arquivos dados. */
    private static AmbienteFalso.Roteiro clonando(String... arquivosEConteudos) {
        return c -> {
            if (c.argumentos().get(0).equals("git")) {
                Path destino = Path.of(c.argumentos().get(c.argumentos().size() - 1));
                for (int i = 0; i < arquivosEConteudos.length; i += 2) {
                    escrever(destino.resolve(arquivosEConteudos[i]), arquivosEConteudos[i + 1]);
                }
                Files.createDirectories(destino);
            }
            return AmbienteFalso.ok("");
        };
    }

    private static List<List<String>> soGit(AmbienteFalso a) {
        return a.comandos().stream().filter(c -> c.get(0).equals("git")).toList();
    }

    // ---------------------------------------------------------------------------------------------
    // git clone
    // ---------------------------------------------------------------------------------------------

    @Test
    void cloneRodaGitComEndereçoConferidoEOAmbienteSemPergunta() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = clonando("package.json", "{}", "src/index.js", "x");
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM);
        assertEquals("repo", r.nome());
        assertEquals(List.of(List.of("git", "clone", "--", "https://github.com/usuario/repo.git", a.raiz.resolve("repo").toString())), soGit(a));
        AmbienteFalso.Chamada git = a.chamadas.get(0);
        assertEquals("0", git.ambiente().get("GIT_TERMINAL_PROMPT"));
        assertEquals("https:ssh", git.ambiente().get("GIT_ALLOW_PROTOCOL"));
        assertEquals("", git.entrada());
    }

    @Test
    void cloneSemComposeGanhaOEsqueletoEARede() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = clonando("package.json", "{}");
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM);
        assertTrue(r.composeCriado());
        assertEquals(GabaritoDoProjeto.composeVazio("repo"), Files.readString(a.raiz.resolve("repo/docker-compose.yml")));
        assertEquals(List.of("{}"), List.of(Files.readString(a.raiz.resolve("repo/package.json"))), "nada do repositório foi mexido");
        assertEquals(List.of("docker", "network", "create", "--label", "com.docker.compose.network=default", "--label", "com.docker.compose.project=repo",
            "repo_default"), a.comandos().get(1));
        assertTrue(r.avisos().get(0).contains("não tinha docker-compose.yml"));
        assertEquals(0, a.abertos.get());
    }

    @Test
    void cloneComComposeNaoToca() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        String meu = "services:\n  web:\n    image: nginx\n";
        a.roteiro = clonando("docker-compose.yml", meu, "README.md", "x");
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM);
        assertFalse(r.composeCriado());
        assertEquals(meu, Files.readString(a.raiz.resolve("repo/docker-compose.yml")));
        assertEquals(List.of("README.md", "docker-compose.yml"), nomes(a.raiz.resolve("repo")));
    }

    @Test
    void composeEmSubpastaOuComNomeDeOverrideTambemConta() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = clonando("infra/docker-compose.yml", "services: {}\n");
        assertFalse(new ImportadorDeProjeto(a.remoto()).clonar(URL, "um", ImportadorDeProjeto.Observador.NENHUM).composeCriado());
        a.roteiro = clonando("docker-compose.lab.yml", "services: {}\n");
        assertFalse(new ImportadorDeProjeto(a.remoto()).clonar(URL, "dois", ImportadorDeProjeto.Observador.NENHUM).composeCriado());
        assertFalse(Files.exists(a.raiz.resolve("um/docker-compose.yml")));
    }

    @Test
    void composeYamlModernoSoGeraAviso() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = clonando("compose.yaml", "services: {}\n");
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM);
        assertFalse(r.composeCriado());
        assertFalse(Files.exists(a.raiz.resolve("repo/docker-compose.yml")));
        assertTrue(r.avisos().stream().anyMatch(x -> x.contains("compose.yaml")), r.avisos().toString());
    }

    @Test
    void cloneQueFalhaExplicaEApagaOQueFicou() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            if (c.argumentos().get(0).equals("git")) {
                Files.createDirectories(Path.of(c.argumentos().get(c.argumentos().size() - 1)));
                return AmbienteFalso.erro(128, "fatal: could not read Username for 'https://github.com': terminal prompts disabled");
            }
            return AmbienteFalso.ok("");
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM));
        assertEquals(FalhaDoProjeto.Tipo.RECUSADO, f.tipo());
        assertTrue(f.mensagem().contains("privado"), f.mensagem());
        assertFalse(Files.exists(a.raiz.resolve("repo")), "a pasta incompleta foi apagada");
        assertEquals(1, a.comandos().size(), "não chegou a criar rede nem compose");
    }

    @Test
    void cloneQueDemoraDemaisParaEApagaOQueFicou() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            Files.createDirectories(Path.of(c.argumentos().get(c.argumentos().size() - 1)));
            throw new ComandoDemorouException(600);
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM));
        assertTrue(f.mensagem().contains("demorou mais de 10 minutos"), f.mensagem());
        assertFalse(Files.exists(a.raiz.resolve("repo")));
    }

    @Test
    void semGitNoComputadorDizOQueFazer() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            throw new br.com.labdesktop.local.ProgramaNaoEncontradoException("git");
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.local()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM));
        assertTrue(f.mensagem().contains("O git não está instalado neste computador"), f.mensagem());
        assertTrue(f.mensagem().contains("Preparar este computador"), f.mensagem());
        assertFalse(Files.exists(a.raiz.resolve("repo")));
    }

    @Test
    void conexaoQueCaiNoCloneEUmaFraseSemDetalheTecnico() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            throw new IOException("Connection reset by peer 10.0.0.5");
        };
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM));
        assertTrue(f.mensagem().contains("a conexão caiu"), f.mensagem());
        assertFalse(f.mensagem().contains("10.0.0.5"));
    }

    @Test
    void cloneInterrompidoApagaEPropagaAInterrupcao() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            Files.createDirectories(Path.of(c.argumentos().get(c.argumentos().size() - 1)));
            throw new InterruptedException("cancelado");
        };
        assertThrows(InterruptedException.class, () -> new ImportadorDeProjeto(a.remoto()).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM));
        assertFalse(Files.exists(a.raiz.resolve("repo")));
    }

    @Test
    void cloneNaoSobrescreveENaoAceitaNomeRuim() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        escrever(a.raiz.resolve("repo/meu.txt"), "do usuário");
        a.roteiro = clonando("x", "y");
        for (String ruim : List.of("repo", "Repo", "../x", "", "painel", "abc-")) {
            assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).clonar(URL, ruim, ImportadorDeProjeto.Observador.NENHUM), ruim);
        }
        assertEquals(List.of(), a.comandos(), "nenhum comando foi rodado");
        assertEquals("do usuário", Files.readString(a.raiz.resolve("repo/meu.txt")));
    }

    @Test
    void cloneNoLocalUsaOCaminhoDoSistema() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            if (c.argumentos().get(0).equals("git")) {
                Files.createDirectories(a.raiz.resolve("repo")); // o "git" criou a pasta de verdade
            }
            return AmbienteFalso.ok("");
        };
        new ImportadorDeProjeto(a.localComConversao(p -> "SIS:" + p)).clonar(URL, "repo", ImportadorDeProjeto.Observador.NENHUM);
        assertEquals("SIS:" + a.raiz.resolve("repo"), a.comandos().get(0).get(4));
    }

    // ---------------------------------------------------------------------------------------------
    // pasta do computador
    // ---------------------------------------------------------------------------------------------

    private Path pastaDoDono() throws IOException {
        Path p = tmp.resolve("Meu Codigo");
        escrever(p.resolve("src/index.js"), "codigo");
        escrever(p.resolve("package.json"), "{}");
        escrever(p.resolve("node_modules/x/y.js"), "pesado");
        escrever(p.resolve("src/node_modules/z.js"), "pesado");
        escrever(p.resolve(".git/HEAD"), "ref: refs/heads/main");
        escrever(p.resolve(".env"), "SENHA=1");
        escrever(p.resolve(".env.local"), "SENHA=2");
        escrever(p.resolve(".env.example"), "SENHA=");
        escrever(p.resolve(".DS_Store"), "lixo");
        escrever(p.resolve("docs/LEIA.md"), "oi");
        return p;
    }

    @Test
    void pastaPadraoIgnoraNodeModulesGitEEnv() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).importarPasta(pastaDoDono(), "meu-codigo", FiltroDeEnvio.PADRAO,
            ImportadorDeProjeto.Observador.NENHUM, new Cancelamento());
        assertEquals("meu-codigo", r.nome());
        Path destino = a.raiz.resolve("meu-codigo");
        assertEquals(List.of(".env.example", "docker-compose.yml", "docs", "package.json", "src"), nomes(destino));
        assertEquals("codigo", Files.readString(destino.resolve("src/index.js")));
        assertEquals(List.of("index.js"), nomes(destino.resolve("src")), "node_modules fundo também fica de fora");
        assertTrue(r.composeCriado());
        assertEquals(List.of("meu-codigo"), nomes(a.raiz), "a pasta provisória não ficou");
        assertEquals(0, a.abertos.get());
    }

    @Test
    void pastaComGitEEnvMarcadosLevaOsDois() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        new ImportadorDeProjeto(a.remoto()).importarPasta(pastaDoDono(), "meu-codigo", new FiltroDeEnvio(true, true), ImportadorDeProjeto.Observador.NENHUM,
            new Cancelamento());
        Path destino = a.raiz.resolve("meu-codigo");
        assertTrue(Files.exists(destino.resolve(".git/HEAD")));
        assertTrue(Files.exists(destino.resolve(".env")));
        assertTrue(Files.exists(destino.resolve(".env.local")));
        assertFalse(Files.exists(destino.resolve("node_modules")), "node_modules nunca vai");
        assertFalse(Files.exists(destino.resolve(".DS_Store")));
    }

    @Test
    void pastaComComposeNaoGanhaOutro() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        Path p = pastaDoDono();
        escrever(p.resolve("docker-compose.yml"), "services:\n  a:\n    image: x\n");
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).importarPasta(p, "meu-codigo", FiltroDeEnvio.PADRAO,
            ImportadorDeProjeto.Observador.NENHUM, new Cancelamento());
        assertFalse(r.composeCriado());
        assertEquals("services:\n  a:\n    image: x\n", Files.readString(a.raiz.resolve("meu-codigo/docker-compose.yml")));
    }

    @Test
    void pastaNaoSobrescreveEDestinoQueExisteERecusado() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        escrever(a.raiz.resolve("meu-codigo/meu.txt"), "do usuário");
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).importarPasta(pastaDoDono(), "meu-codigo",
            FiltroDeEnvio.PADRAO, ImportadorDeProjeto.Observador.NENHUM, new Cancelamento()));
        assertEquals("Já existe um projeto com esse nome.", f.mensagem());
        assertEquals(List.of("meu.txt"), nomes(a.raiz.resolve("meu-codigo")));
        assertEquals(List.of("meu-codigo"), nomes(a.raiz));
    }

    @Test
    void pastaQueNaoEPastaERecusada() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        Path arquivo = tmp.resolve("solto.txt");
        escrever(arquivo, "x");
        assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).importarPasta(arquivo, "x", FiltroDeEnvio.PADRAO,
            ImportadorDeProjeto.Observador.NENHUM, new Cancelamento()));
        assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).importarPasta(tmp.resolve("nao-existe"), "x", FiltroDeEnvio.PADRAO,
            ImportadorDeProjeto.Observador.NENHUM, new Cancelamento()));
    }

    @Test
    void cancelarApagaOQueJaFoiEnviadoENaoCriaProjeto() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        Path p = tmp.resolve("grande");
        for (int i = 0; i < 40; i++) {
            escrever(p.resolve("pasta/arq" + i + ".bin"), "x".repeat(2000));
        }
        Cancelamento cancelamento = new Cancelamento();
        cancelamento.cancelar(); // o usuário clicou em Cancelar antes de começar a andar
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).importarPasta(p, "grande", FiltroDeEnvio.PADRAO,
            ImportadorDeProjeto.Observador.NENHUM, cancelamento));
        assertEquals(FalhaDoProjeto.Tipo.CANCELADO, f.tipo());
        assertEquals(List.of(), nomes(a.raiz), "nem pasta provisória nem projeto");
        assertEquals(0, a.abertos.get());
    }

    @Test
    void sobraDeUmEnvioInterrompidoEPassadaPorCima() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        escrever(a.raiz.resolve(".meu-codigo.enviando/velho.txt"), "sobra");
        new ImportadorDeProjeto(a.remoto()).importarPasta(pastaDoDono(), "meu-codigo", FiltroDeEnvio.PADRAO, ImportadorDeProjeto.Observador.NENHUM,
            new Cancelamento());
        assertEquals(List.of("meu-codigo"), nomes(a.raiz));
        assertFalse(Files.exists(a.raiz.resolve("meu-codigo/velho.txt")));
    }

    @Test
    void pastaNoLocalTambemVaiPeloMesmoCaminho() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.local()).importarPasta(pastaDoDono(), "meu-codigo", FiltroDeEnvio.PADRAO,
            ImportadorDeProjeto.Observador.NENHUM, new Cancelamento());
        assertTrue(Files.exists(a.raiz.resolve("meu-codigo/src/index.js")));
        assertTrue(r.composeCriado());
    }

    @Test
    void oAndamentoChegaAoObservador() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        List<String> frases = new ArrayList<>();
        new ImportadorDeProjeto(a.remoto()).importarPasta(pastaDoDono(), "meu-codigo", FiltroDeEnvio.PADRAO, (f, x) -> frases.add(f), new Cancelamento());
        assertTrue(frases.stream().anyMatch(f -> f.startsWith("Copiando")), frases.toString());
        assertTrue(frases.contains("Criando a rede do projeto…"));
    }

    // ---------------------------------------------------------------------------------------------
    // adotar uma pasta que já está no servidor
    // ---------------------------------------------------------------------------------------------

    private AmbienteFalso comMvECp() throws IOException {
        AmbienteFalso a = new AmbienteFalso(tmp);
        a.roteiro = c -> {
            List<String> v = c.argumentos();
            if (v.get(0).equals("mv")) {
                Files.move(Path.of(v.get(3)), Path.of(v.get(4)));
            } else if (v.get(0).equals("cp")) {
                Path de = Path.of(v.get(3));
                Path para = Path.of(v.get(4));
                try (Stream<Path> s = Files.walk(de)) {
                    for (Path x : s.toList()) {
                        Path alvo = para.resolve(de.relativize(x).toString());
                        if (Files.isDirectory(x)) {
                            Files.createDirectories(alvo);
                        } else {
                            Files.copy(x, alvo);
                        }
                    }
                }
            }
            return AmbienteFalso.ok("");
        };
        return a;
    }

    @Test
    void candidatasSaoAsPastasDaCasaSemOcultasSemArquivosESemARaiz() throws Exception {
        AmbienteFalso a = comMvECp();
        escrever(a.casa.resolve("zeta/a.txt"), "x");
        escrever(a.casa.resolve("Alfa/a.txt"), "x");
        escrever(a.casa.resolve(".oculta/a.txt"), "x");
        escrever(a.casa.resolve("solto.txt"), "x");
        Files.createDirectories(a.raiz.resolve("ja-projeto"));
        List<ImportadorDeProjeto.Candidata> c = new ImportadorDeProjeto(a.remoto()).candidatasParaAdotar();
        assertEquals(List.of("Alfa", "zeta"), c.stream().map(ImportadorDeProjeto.Candidata::nome).toList(), "LabLocal contém a raiz dos projetos: não vira projeto?");
    }

    @Test
    void adotarMoverTrazAPastaEOComandoEExato() throws Exception {
        AmbienteFalso a = comMvECp();
        escrever(a.casa.resolve("meuapp/src/a.js"), "x");
        ImportadorDeProjeto.Resultado r = new ImportadorDeProjeto(a.remoto()).adotar(a.casa.resolve("meuapp").toString(), "meu-app", ImportadorDeProjeto.Modo.MOVER,
            ImportadorDeProjeto.Observador.NENHUM);
        assertEquals(List.of("mv", "-n", "--", a.casa.resolve("meuapp").toString(), a.raiz.resolve("meu-app").toString()), a.comandos().get(0));
        assertFalse(Files.exists(a.casa.resolve("meuapp")), "a pasta saiu do lugar de antes");
        assertTrue(Files.exists(a.raiz.resolve("meu-app/src/a.js")));
        assertTrue(r.composeCriado());
    }

    @Test
    void adotarCopiarDeixaAOriginal() throws Exception {
        AmbienteFalso a = comMvECp();
        escrever(a.casa.resolve("meuapp/src/a.js"), "x");
        new ImportadorDeProjeto(a.remoto()).adotar(a.casa.resolve("meuapp").toString(), "meu-app", ImportadorDeProjeto.Modo.COPIAR, ImportadorDeProjeto.Observador.NENHUM);
        assertEquals(List.of("cp", "-a", "--", a.casa.resolve("meuapp").toString(), a.raiz.resolve("meu-app").toString()), a.comandos().get(0));
        assertTrue(Files.exists(a.casa.resolve("meuapp/src/a.js")));
        assertTrue(Files.exists(a.raiz.resolve("meu-app/src/a.js")));
    }

    @Test
    void adotarSemPermissaoMostraOComandoExato() throws Exception {
        AmbienteFalso a = comMvECp();
        escrever(a.casa.resolve("meuapp/a.js"), "x");
        a.roteiro = c -> AmbienteFalso.erro(1, "mv: cannot move: Permission denied");
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).adotar(a.casa.resolve("meuapp").toString(), "meu-app",
            ImportadorDeProjeto.Modo.MOVER, ImportadorDeProjeto.Observador.NENHUM));
        assertEquals(FalhaDoProjeto.Tipo.SEM_PERMISSAO, f.tipo());
        assertEquals("mv -n -- " + a.casa.resolve("meuapp") + " " + a.raiz.resolve("meu-app"), f.comandoParaCopiar());
        assertTrue(Files.exists(a.casa.resolve("meuapp/a.js")), "nada mudou");
    }

    @Test
    void adotarPastaComEspacoNaoPassaPelaLinhaRemotaMasDaOComando() throws Exception {
        AmbienteFalso a = comMvECp();
        escrever(a.casa.resolve("meu app/a.js"), "x");
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).adotar(a.casa.resolve("meu app").toString(), "meu-app",
            ImportadorDeProjeto.Modo.MOVER, ImportadorDeProjeto.Observador.NENHUM));
        assertEquals(FalhaDoProjeto.Tipo.RECUSADO, f.tipo());
        assertEquals("mv -n -- '" + a.casa.resolve("meu app") + "' " + a.raiz.resolve("meu-app"), f.comandoParaCopiar());
        assertEquals(List.of(), a.comandos(), "nada foi enviado ao servidor");
        assertTrue(Files.exists(a.casa.resolve("meu app/a.js")));
    }

    @Test
    void adotarRecusaOQueNaoPodeVirarProjeto() throws Exception {
        AmbienteFalso a = comMvECp();
        Files.createDirectories(a.raiz.resolve("ja"));
        escrever(a.casa.resolve("arq.txt"), "x");
        Files.createSymbolicLink(a.casa.resolve("atalho"), a.casa.resolve("LabLocal"));
        Files.createDirectories(a.casa.resolve("meuapp"));
        List<String> ruins = List.of("/", a.casa.toString(), a.casa.getParent().toString(), a.casa.resolve("LabLocal").toString(), a.raiz.toString(),
            a.raiz.resolve("ja").toString(), a.casa.resolve("arq.txt").toString(), a.casa.resolve("atalho").toString(), a.casa.resolve("nao-existe").toString());
        for (String ruim : ruins) {
            assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).adotar(ruim, "novo", ImportadorDeProjeto.Modo.MOVER,
                ImportadorDeProjeto.Observador.NENHUM), ruim);
        }
        assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).adotar("relativo/x", "novo", ImportadorDeProjeto.Modo.MOVER,
            ImportadorDeProjeto.Observador.NENHUM));
        assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.remoto()).adotar(a.casa.resolve("meuapp").toString(), "ja", ImportadorDeProjeto.Modo.MOVER,
            ImportadorDeProjeto.Observador.NENHUM), "destino existe");
        assertEquals(List.of(), a.comandos(), "nenhum comando saiu");
    }

    @Test
    void adotarNoLocalNaoExiste() throws Exception {
        AmbienteFalso a = comMvECp();
        Files.createDirectories(a.casa.resolve("meuapp"));
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> new ImportadorDeProjeto(a.local()).adotar(a.casa.resolve("meuapp").toString(), "novo",
            ImportadorDeProjeto.Modo.MOVER, ImportadorDeProjeto.Observador.NENHUM));
        assertTrue(f.mensagem().contains("Pasta do meu computador"));
        assertEquals(List.of(), a.comandos());
    }
}
