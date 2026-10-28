package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Os projetos de "Este computador": a pasta e o Docker lidos com as regras do agente do lab, e as ações pelo compose. Sem Docker de verdade. */
class ProjetosLocaisTest {

    @TempDir
    Path tmp;

    private final List<List<String>> comandos = new CopyOnWriteArrayList<>();
    private volatile String saidaDoPs = "";
    private volatile int statusDoPs;
    private volatile int statusDaAcao;
    private volatile String erroDaAcao = "";

    private final ExecutorDeProcesso executor = new ExecutorDeProcesso() {
        @Override
        public ResultadoExec executar(List<String> a, byte[] stdin, Duration prazo) {
            comandos.add(List.copyOf(a));
            if (a.equals(ComandosLocais.lerContainersDosProjetos())) {
                return new ResultadoExec(statusDoPs, saidaDoPs.getBytes(StandardCharsets.UTF_8), new byte[0], false);
            }
            return new ResultadoExec(statusDaAcao, ("ok " + a.get(a.size() - 1)).getBytes(StandardCharsets.UTF_8), erroDaAcao.getBytes(StandardCharsets.UTF_8), false);
        }
    };

    private ProjetosLocais projetos() {
        return new ProjetosLocais(() -> tmp, executor);
    }

    private void escrever(String relativo, String texto) throws IOException {
        Path p = tmp.resolve(relativo);
        Files.createDirectories(p.getParent());
        Files.writeString(p, texto, StandardCharsets.UTF_8);
    }

    private static String linha(String nome, String estado, String projetoDoCompose, String pasta) {
        return nome + "|" + estado + "|Up 3 hours|127.0.0.1:5440->5432/tcp, :::5440->5432/tcp|postgres:17|org.x=a|b,com.docker.compose.project=" + projetoDoCompose
            + ",com.docker.compose.project.working_dir=" + pasta + ",outro=1\n";
    }

    @Test
    void listaSoPastasComNomeValidoENuncaArquivosOuAtalhos() throws Exception {
        escrever("zeta/README.md", "# Zeta\n");
        escrever("alfa/docker-compose.yml", "services: {}\n");
        escrever(".oculta/x", "x");
        escrever("Maiuscula/x", "x");
        escrever("com espaco/x", "x");
        escrever("solto.txt", "x");
        Files.createSymbolicLink(tmp.resolve("atalho"), tmp.resolve("alfa"));
        List<Projeto> lista = projetos().lista().projetos();
        assertEquals(List.of("alfa", "zeta"), lista.stream().map(Projeto::nome).toList());
    }

    @Test
    void pastaQueNaoExisteEListaVazia() throws Exception {
        ProjetosLocais p = new ProjetosLocais(() -> tmp.resolve("nao-existe"), executor);
        assertEquals(List.of(), p.lista().projetos());
        assertEquals(List.of(), comandos, "sem pastas nem se pergunta ao Docker");
    }

    @Test
    void containersContamPeloRotuloDoComposeEPelaPastaDeTrabalho() throws Exception {
        escrever("meu-app/docker-compose.yml", "name: meu-app\nservices: {}\n");
        escrever("outro/docker-compose.yml", "services: {}\n");
        saidaDoPs = linha("meu-app-web-1", "running", "meu-app", tmp.resolve("meu-app").toString())
            + linha("meu-app-db-1", "exited", "meu-app", tmp.resolve("meu-app") + "/") // barra no fim não atrapalha
            + linha("alheio-1", "running", "meu-app", "/outra/pasta/meu-app")             // mesmo nome de compose, outra pasta: não é deste projeto
            + linha("outro-web-1", "running", "outro", tmp.resolve("outro").toString())
            + linha("estranho-1", "running", "nao-conhecido", tmp.resolve("meu-app").toString()) // nome que o compose do projeto não declara
            + "linha quebrada sem barras\n";
        List<Projeto> lista = projetos().lista().projetos();
        Projeto meu = lista.stream().filter(p -> p.nome().equals("meu-app")).findFirst().orElseThrow();
        assertEquals(List.of("meu-app-web-1", "meu-app-db-1"), meu.containers().stream().map(c -> c.nome()).toList());
        assertTrue(meu.rodando());
        assertEquals(EstadoProjeto.PARCIAL, meu.estado());
        assertEquals("127.0.0.1:5440->5432/tcp, :::5440->5432/tcp", meu.containers().get(0).portas(), "as portas com vírgula chegam inteiras");
        Projeto outro = lista.stream().filter(p -> p.nome().equals("outro")).findFirst().orElseThrow();
        assertEquals(EstadoProjeto.NO_AR, outro.estado());
        assertEquals(List.of(ComandosLocais.lerContainersDosProjetos()), comandos, "UMA leitura do Docker para a lista toda, e só leitura");
    }

    @Test
    void semComposeEOEstadoSemCompose_eNuncaSubiuQuandoNaoHaContainer() throws Exception {
        escrever("so-codigo/index.js", "x");
        escrever("novo/docker-compose.yml", "services: {}\n");
        List<Projeto> l = projetos().lista().projetos();
        assertEquals(EstadoProjeto.SEM_COMPOSE, l.get(1).estado()); // so-codigo
        assertEquals(EstadoProjeto.NUNCA_SUBIU, l.get(0).estado()); // novo
    }

    @Test
    void dockerParadoNaoDerrubaAListaSoNaoTemContainers() throws Exception {
        escrever("meu-app/docker-compose.yml", "services: {}\n");
        statusDoPs = 1;
        saidaDoPs = "";
        List<Projeto> l = projetos().lista().projetos();
        assertEquals(1, l.size());
        assertEquals(List.of(), l.get(0).containers());
    }

    @Test
    void composeDeOutroProjetoOuDaInfraEhIgnoradoComoNoAgente() throws Exception {
        escrever("alfa/docker-compose.yml", "name: infra\nservices: {}\n");
        escrever("beta/docker-compose.yml", "name: alfa\nservices: {}\n");
        escrever("gama/docker-compose.yml", "name: lab\nservices: {}\n");
        List<Projeto> l = projetos().lista().projetos();
        for (Projeto p : l) {
            assertEquals(List.of(), p.composes(), p.nome() + ": o name do compose é de outro projeto ou da infraestrutura");
        }
    }

    @Test
    void osArquivosComposeSeguemARegraDoAgente() throws Exception {
        escrever("p/docker-compose.yml", "services: {}\n");
        escrever("p/docker-compose.lab.yaml", "services: {}\n");
        escrever("p/compose.yaml", "services: {}\n");        // o agente não reconhece este nome
        escrever("p/servicos/docker-compose.yml", "services: {}\n");
        escrever("p/node_modules/x/docker-compose.yml", "services: {}\n");
        escrever("p/.oculta/docker-compose.yml", "services: {}\n");
        Projeto p = projetos().lista().projetos().get(0);
        assertEquals(List.of("docker-compose.lab.yaml", "docker-compose.yml", "servicos/docker-compose.yml"), p.composes());
    }

    @Test
    void descricaoGitEMexidoVemDasPastas() throws Exception {
        escrever("p/README.md", "# Título\n\n![logo](x.png)\n| a | b |\nUma **descrição** de uma linha.\nOutra.\n");
        escrever("p/.git/HEAD", "ref: refs/heads/principal\n");
        escrever("q/.git/HEAD", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2\n");
        escrever("r/x", "x");
        List<Projeto> l = projetos().lista().projetos();
        assertEquals("Uma descrição de uma linha.", l.get(0).descricao());
        assertEquals("principal", l.get(0).git().branch());
        assertEquals("(sem branch)", l.get(1).git().branch());
        assertNull(l.get(2).git());
        assertTrue(l.get(0).mexido() != null && l.get(0).mexido() > 0);
        assertEquals("", l.get(2).descricao());
    }

    @Test
    void detalheTrazReadmeStatusArquivosEComposes() throws Exception {
        escrever("meu-app/README.md", "# x\n" + "a".repeat(7000));
        escrever("meu-app/STATUS.md", "Pronto\n");
        escrever("meu-app/docker-compose.yml", "name: meu-app\nservices: {}\n");
        escrever("meu-app/src/index.js", "x");
        saidaDoPs = linha("meu-app-web-1", "running", "meu-app", tmp.resolve("meu-app").toString());
        DetalheProjeto d = projetos().detalhe("meu-app");
        assertEquals("meu-app", d.nome());
        assertEquals(6000, d.readme().length(), "o teto do agente: 6000 caracteres");
        assertEquals("Pronto\n", d.status());
        assertEquals(List.of(new DetalheProjeto.Compose("docker-compose.yml", "meu-app")), d.composes());
        assertEquals(1, d.containers().size());
        assertNull(d.sessao());
        assertEquals(List.of(), d.backups());
        assertTrue(d.arquivos().stream().anyMatch(a -> a.nome().equals("src") && a.tipo().equals("pasta")));
        assertTrue(d.arquivos().stream().anyMatch(a -> a.nome().equals("STATUS.md") && a.tipo().equals("arquivo")));
    }

    @Test
    void detalheDeProjetoQueNaoExisteOuNomeRuimEUmaFrase() {
        assertThrows(ProjetosLocais.ProjetoNaoExisteException.class, () -> projetos().detalhe("fantasma"));
        assertThrows(ProjetosLocais.ProjetoNaoExisteException.class, () -> projetos().detalhe("../x"));
        assertEquals(List.of(), comandos);
    }

    // ---------------------------------------------------------------------------------------------
    // as ações
    // ---------------------------------------------------------------------------------------------

    @Test
    void iniciarRodaComposeUpEmCadaArquivoComOPExplicito() throws Exception {
        escrever("meu-app/docker-compose.yml", "name: meu-app\nservices: {}\n");
        escrever("meu-app/docker-compose.lab.yml", "name: meu-app\nservices: {}\n");
        String saida = projetos().iniciar("meu-app");
        assertEquals(List.of(
            List.of("docker", "compose", "-p", "meu-app", "-f", tmp.resolve("meu-app/docker-compose.lab.yml").toString(), "up", "-d"),
            List.of("docker", "compose", "-p", "meu-app", "-f", tmp.resolve("meu-app/docker-compose.yml").toString(), "up", "-d")), comandos);
        assertTrue(saida.contains("[docker-compose.lab.yml]"), saida);
    }

    @Test
    void pararNuncaLevaMenosVEReiniciarUsaRestart() throws Exception {
        escrever("meu-app/docker-compose.yml", "services: {}\n");
        projetos().parar("meu-app");
        projetos().reiniciar("meu-app");
        List<String> parar = comandos.get(0);
        assertEquals("down", parar.get(parar.size() - 1));
        assertFalse(parar.contains("-v"), "volume nenhum some por aqui");
        assertEquals("restart", comandos.get(1).get(comandos.get(1).size() - 1));
    }

    @Test
    void falhaDoDockerParaEMostraOTexto() throws Exception {
        escrever("meu-app/docker-compose.yml", "services: {}\n");
        escrever("meu-app/docker-compose.z.yml", "services: {}\n");
        statusDaAcao = 1;
        erroDaAcao = "Cannot connect to the Docker daemon";
        IOException e = assertThrows(IOException.class, () -> projetos().iniciar("meu-app"));
        assertTrue(e.getMessage().contains("Cannot connect"), e.getMessage());
        assertEquals(1, comandos.size(), "parou no primeiro que falhou");
    }

    @Test
    void semComposeOuProjetoInexistenteNaoRodaNada() throws Exception {
        escrever("so-codigo/x", "x");
        assertThrows(IOException.class, () -> projetos().iniciar("so-codigo"));
        assertThrows(ProjetosLocais.ProjetoNaoExisteException.class, () -> projetos().iniciar("fantasma"));
        assertThrows(ProjetosLocais.ProjetoNaoExisteException.class, () -> projetos().parar("../x"));
        assertEquals(List.of(), comandos);
    }

    @Test
    void reiniciarContainerSoSeEleEDoProjeto() throws Exception {
        escrever("meu-app/docker-compose.yml", "services: {}\n");
        saidaDoPs = linha("meu-app-web-1", "running", "meu-app", tmp.resolve("meu-app").toString());
        projetos().reiniciarContainer("meu-app", "meu-app-web-1");
        assertEquals(List.of("docker", "restart", "meu-app-web-1"), comandos.get(comandos.size() - 1));
        int antes = comandos.size();
        assertThrows(IOException.class, () -> projetos().reiniciarContainer("meu-app", "alheio-1"));
        assertFalse(comandos.subList(antes, comandos.size()).stream().anyMatch(c -> c.get(1).equals("restart")), "não reinicia o que não é do projeto");
    }

    @Test
    void aEscritaNaoPassaPeloExecutorQueSoLe() {
        // a fronteira: os comandos de ação não são leitura, e a leitura da lista é
        assertTrue(ComandosLocais.ehLeitura(ComandosLocais.lerContainersDosProjetos()));
        assertFalse(ComandosLocais.ehLeitura(ComandosLocais.comporProjeto("meu-app", "/x/docker-compose.yml", ComandosLocais.AcaoDeProjeto.INICIAR)));
        assertFalse(ComandosLocais.ehLeitura(ComandosLocais.reiniciarContainerDoProjeto("meu-app-web-1")));
        assertThrows(IllegalArgumentException.class, () -> ComandosLocais.comporProjeto("Meu App", "/x", ComandosLocais.AcaoDeProjeto.PARAR));
        assertThrows(IllegalArgumentException.class, () -> ComandosLocais.comporProjeto("ok", "", ComandosLocais.AcaoDeProjeto.PARAR));
        assertThrows(IllegalArgumentException.class, () -> ComandosLocais.reiniciarContainerDoProjeto("a b"));
    }
}
