package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.GanchosDaInstalacao;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaRemocao;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * O laboratório local com o Docker de VERDADE deste computador (Docker Desktop, Colima...): constrói a imagem do agente pelo pacote (tar.gz pela
 * entrada do {@code docker build}), sobe o container com a porta publicada SÓ em 127.0.0.1, registra um token pelo {@code docker exec}, fala com o
 * agente por HTTP de verdade, confere o mapeamento da pasta dos projetos (o daemon resolve o caminho do anfitrião a partir de dentro do container)
 * e remove tudo. Só roda com {@code -Plabdesktop.dockerReal=true}. Tudo leva o prefixo {@code labteste-}: nada do que o usuário já tem no Docker é
 * tocado, e o {@code @AfterEach} remove o que sobrar.
 */
@EnabledIfSystemProperty(named = "labdesktop.dockerReal", matches = "true")
class LaboratorioLocalComDockerDeVerdadeTest {

    private static final String CONTAINER = "labteste-agente";
    private static final String REDE = "labteste-edge";
    private static final String IMAGEM = "labteste-agente";

    @TempDir
    Path tmp;

    private final PlataformaLocal plataforma = PlataformaLocal.detectar();
    private final ProcessosLocais processos = new ProcessosLocais(LocalizadorDeProgramas.doSistema(plataforma), plataforma);

    @org.junit.jupiter.api.BeforeEach
    void exigeOPacoteDeVerdade() {
        org.junit.jupiter.api.Assumptions.assumeFalse(br.com.labdesktop.teste.PacoteDeTeste.ehSintetico(),
            "o app não leva o pacote do agente de verdade: para o Docker de verdade rode ./gradlew empacotarAgente");
    }

    @AfterEach
    void limpar() throws Exception {
        for (List<String> c : List.of(List.of("docker", "rm", "-f", CONTAINER), List.of("docker", "rm", "-f", CONTAINER + "-anterior"),
            List.of("docker", "network", "rm", REDE), List.of("docker", "volume", "rm", CONTAINER + "-dados"))) {
            try {
                processos.executar(c, Duration.ofSeconds(60));
            } catch (java.io.IOException e) {
                // não existe mais: ótimo
            }
        }
        for (String versao : List.of(PacoteDoAgente.doClasspath().versao())) {
            try {
                processos.executar(List.of("docker", "rmi", IMAGEM + ":" + versao), Duration.ofSeconds(60));
            } catch (java.io.IOException e) {
                // idem
            }
        }
    }

    private static int portaLivre() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String docker(String... a) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(a));
        ResultadoExec x = processos.executar(cmd, Duration.ofSeconds(120));
        return x.status() + "|" + x.stdout().strip() + "|" + x.stderr().strip();
    }

    @Test
    void criarFalarComOAgenteConferirOMapeamentoERemoverTudoNoDockerDeVerdade() throws Exception {
        PacoteDoAgente pacote = PacoteDoAgente.doClasspath();
        int porta = portaLivre();
        Path projetos = tmp.toRealPath().resolve("LabLocal").resolve("projects");
        ParametrosDoLaboratorio p = new ParametrosDoLaboratorio(CONTAINER, porta, REDE, IMAGEM, projetos.toString());
        TokenAgente token = TokenAgente.gerar();
        String nome = "teste-docker-real";
        ProvisionadorRemoto prov = ProvisionadorRemoto.para(new ExecutorDeLinhaFixa(processos), CONTAINER, "docker");

        GanchosDaInstalacao ganchos = new GanchosDaInstalacao() {
            @Override
            public Sonda sondar() {
                try (ClienteApi a = new ClienteApi("labteste", porta, null, PedidoDeCodigo2fa.NUNCA)) {
                    a.versao();
                    return Sonda.RESPONDE;
                } catch (ErroApi.NaoAutorizado e) {
                    return Sonda.TOKEN_RECUSADO; // o agente já exige o token e está de pé
                } catch (ErroApi e) {
                    return Sonda.NAO_RESPONDE;
                }
            }

            @Override
            public boolean registrarToken(ParametrosDaInstalacao parametros, String docker) throws Exception {
                return prov.registrarToken(token.hashSha256Hex(), nome, Instant.now());
            }
        };
        LaboratorioLocal lab = new LaboratorioLocal(processos, p, plataforma, pacote, ganchos).comSonda(Duration.ofSeconds(60), Duration.ofSeconds(1));
        List<String> log = new ArrayList<>();
        ObservadorDaInstalacao obs = new ObservadorDaInstalacao() {
            @Override
            public void aoMudarPasso(br.com.labdesktop.instalador.Passo passo, br.com.labdesktop.instalador.EstadoDoPasso estado, String detalhe) { }

            @Override
            public void aoRegistrar(String linha) {
                log.add(linha);
            }
        };

        ResultadoDaInstalacao r = lab.criar(obs, Cancelamento.NUNCA);
        assertTrue(r.concluida(), r.erro() + "\n" + String.join("\n", log));

        // 1. a pasta foi criada, o agente responde por HTTP de verdade e só com o token
        assertTrue(Files.isDirectory(projetos));
        try (ClienteApi semToken = new ClienteApi("labteste", porta, null, PedidoDeCodigo2fa.NUNCA)) {
            org.junit.jupiter.api.Assertions.assertThrows(ErroApi.NaoAutorizado.class, semToken::versao, "o agente exige o token desde o primeiro minuto");
        }
        try (ClienteApi comToken = new ClienteApi("labteste", porta, token, PedidoDeCodigo2fa.NUNCA)) {
            var v = comToken.versao();
            assertEquals("monitor", v.perfil());
            assertEquals(nome, v.cliente());
            assertEquals(pacote.versao(), v.agente());
            assertEquals(Boolean.FALSE, v.temClaude());
        }

        // 2. a porta está publicada SÓ em 127.0.0.1
        String portas = docker("port", CONTAINER);
        assertTrue(portas.contains("127.0.0.1:" + porta), portas);
        assertFalse(portas.contains("0.0.0.0"), portas);

        // 3. o container leva os rótulos do app e a rede foi criada com o rótulo
        assertTrue(docker("ps", "-a", "--filter", "name=^" + CONTAINER + "$", "--format", "{{.Label \"br.com.labdesktop.local\"}}").contains("|1|"));
        assertTrue(docker("network", "ls", "--filter", "name=^" + REDE + "$", "--format", "{{.Name}}|{{.Label \"br.com.labdesktop.local\"}}").contains(REDE + "|1"));

        // 4. mapeamento de caminhos: a pasta dos projetos aparece no container E o daemon (pelo socket, de dentro do container) a resolve no caminho do anfitrião
        Path projeto = Files.createDirectories(projetos.resolve("projeto-teste").resolve("dados"));
        Files.writeString(projeto.resolve("x.txt"), "oi do anfitriao");
        String noContainer = plataforma.noContainer(projetos.toString());
        assertTrue(docker("exec", CONTAINER, "ls", noContainer + "/projeto-teste/dados").contains("x.txt"), "o container vê a pasta no mesmo caminho");
        String viaDaemon = docker("exec", CONTAINER, "docker", "run", "--rm", "-v", projeto + ":/d", "node:24-alpine", "cat", "/d/x.txt");
        assertTrue(viaDaemon.contains("oi do anfitriao"), "o daemon resolve o caminho do anfitrião: " + viaDaemon);

        // 5. rodar de novo não estraga nada e só lê
        List<String> comandosAntes = new ArrayList<>(log);
        ResultadoDaInstalacao denovo = lab.criar(obs, Cancelamento.NUNCA);
        assertTrue(denovo.concluida(), denovo.erro());
        assertEquals(r.concluida(), denovo.concluida());
        List<String> novos = log.subList(comandosAntes.size(), log.size());
        assertTrue(novos.stream().noneMatch(l -> l.startsWith("$ docker run") || l.startsWith("$ docker build") || l.startsWith("$ docker network create")),
            "a segunda vez só lê: " + novos);

        // 6. remover tira o agente, os dados, a imagem e a rede (do app, vazia), e NUNCA os projetos
        ResultadoDaRemocao rem = lab.remover(obs, Cancelamento.NUNCA);
        assertTrue(rem.removido(), rem.avisos().toString());
        assertTrue(docker("ps", "-a", "--filter", "name=^" + CONTAINER + "$", "--format", "{{.Names}}").endsWith("|"), "o container saiu");
        assertTrue(docker("network", "ls", "--filter", "name=^" + REDE + "$", "--format", "{{.Name}}").endsWith("|"), "a rede saiu");
        assertTrue(docker("volume", "ls", "-q", "--filter", "name=^" + CONTAINER + "-dados$").endsWith("|"), "os dados saíram");
        assertTrue(Files.exists(projeto.resolve("x.txt")), "os projetos do usuário nunca são apagados");
    }

    @Test
    void oBuildPelaEntradaEOsFiltrosDeNomeExatoFuncionamNoDockerDeVerdade() throws Exception {
        PacoteDoAgente pacote = PacoteDoAgente.doClasspath();
        ParametrosDoLaboratorio p = new ParametrosDoLaboratorio(CONTAINER, portaLivre(), REDE, IMAGEM, tmp.toString());
        ResultadoExec build = processos.executar(ComandosLocais.construirImagem(p, pacote), pacote.conteudo(), Duration.ofMinutes(10));
        assertEquals(0, build.status(), build.stderr());
        assertEquals(0, processos.executar(ComandosLocais.lerImagem(p, pacote.versao()), Duration.ofSeconds(30)).status());
        // o filtro name=^x$ é exato: um container "labteste-agente-x" não casa com "labteste-agente"
        assertEquals("", processos.executar(ComandosLocais.lerContainer(p), Duration.ofSeconds(30)).stdout().strip());
        assertEquals(Map.of().size(), 0);
    }
}
