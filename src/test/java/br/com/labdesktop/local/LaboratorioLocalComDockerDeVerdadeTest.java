package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.api.modelo.HistoricoDoAgente;
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
 * entrada do {@code docker build}), sobe o container com a porta publicada SÓ em 127.0.0.1, registra um token pelo {@code docker exec}
 * ({@code node acesso.js token-registrar} do agente de verdade), fala com o agente por HTTP de verdade pelo {@link ClienteApi} do app
 * ({@code /api/versao}, {@code /api/servidor}, {@code /api/servidor/detalhe}, {@code /api/servidor/historico}), confere o que o container recebe
 * (só o soquete, os três arquivos {@code :ro} e o volume; nem a raiz do disco nem a pasta dos projetos), que o agente não roda como root, o 429
 * {@code muitas_tentativas} depois de credenciais erradas demais (só de quem não traz token válido) e remove tudo. As respostas de verdade ficam em {@code build/agente-capturado/}
 * (de onde saem as fixtures {@code src/test/resources/agente-novo/}). Só roda com {@code -Plabdesktop.dockerReal=true}. Tudo leva o prefixo {@code labteste-}: nada do que o usuário já tem no Docker é
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

    /** Só o stdout (sem o status e o stderr de {@link #docker}), sem a quebra de linha do fim. */
    private String saida(String... a) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(a));
        ResultadoExec x = processos.executar(cmd, Duration.ofSeconds(120));
        assertEquals(0, x.status(), String.join(" ", cmd) + ": " + x.stderr());
        return x.stdout().strip();
    }

    /** Grava as respostas de verdade do agente em build/agente-capturado/ (de onde saem as fixtures de src/test/resources/agente-novo/). */
    private static void capturar(int porta, TokenAgente token) throws Exception {
        Path pasta = Files.createDirectories(Path.of("build", "agente-capturado"));
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        for (String[] r : new String[][] {{"/api/versao", "versao.json"}, {"/api/servidor", "servidor.json"}, {"/api/servidor/detalhe", "detalhe.json"},
            {"/api/servidor/historico?janela=10m", "historico-10m.json"}, {"/api/servidor/historico?janela=1h", "historico-1h.json"}}) {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + porta + r[0]))
                .header("Authorization", token.cabecalhoAuthorization()).GET().build();
            var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), r[0]);
            Files.writeString(pasta.resolve(r[1]), resp.body());
        }
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
            assertEquals(Boolean.FALSE, v.recursos().projetos(), "o agente de leitura não tem projetos: o app esconde a aba");
            assertTrue(v.entendeHistorico(), "o agente grava o histórico: o app chama /api/servidor/historico");
            assertEquals(Boolean.TRUE, v.exigeToken(), "o token é sempre exigido");
        }

        // 2. a porta está publicada SÓ em 127.0.0.1
        String portas = docker("port", CONTAINER);
        assertTrue(portas.contains("127.0.0.1:" + porta), portas);
        assertFalse(portas.contains("0.0.0.0"), portas);

        // 3. o container leva os rótulos do app e a rede foi criada com o rótulo
        assertTrue(docker("ps", "-a", "--filter", "name=^" + CONTAINER + "$", "--format", "{{.Label \"br.com.labdesktop.local\"}}").contains("|1|"));
        assertTrue(docker("network", "ls", "--filter", "name=^" + REDE + "$", "--format", "{{.Name}}|{{.Label \"br.com.labdesktop.local\"}}").contains(REDE + "|1"));

        // 4. o que o container recebe, de fato: o soquete, os três arquivos só leitura e o volume. Nem a raiz do disco nem a pasta dos projetos.
        Path projeto = Files.createDirectories(projetos.resolve("projeto-teste").resolve("dados"));
        Files.writeString(projeto.resolve("x.txt"), "oi do anfitriao");
        String montagens = saida("inspect", "-f", "{{range .Mounts}}{{.Destination}}={{.RW}};{{end}}", CONTAINER);
        List<String> destinos = new ArrayList<>();
        for (String m : montagens.split(";")) {
            if (!m.isBlank()) {
                String[] c = m.strip().split("=");
                destinos.add(c[0] + (c[1].equals("true") ? " rw" : " ro"));
            }
        }
        assertEquals(List.of("/host/etc/hostname ro", "/host/etc/os-release ro", "/host/etc/passwd ro", "/srv/painel/dados rw", "/var/run/docker.sock rw"),
            destinos.stream().sorted().toList(), "só o que o aviso diz: " + montagens);
        assertFalse(montagens.contains(projetos.toString()), "a pasta dos projetos não entra no container: " + montagens);

        // 5. o agente não roda como root (troca de usuário na partida) e fala com o Docker só por leitura
        // (com --pid host o PID 1 do container é o do anfitrião: o processo do agente se acha pela linha de comando, com `docker top`)
        String processo = saida("top", CONTAINER, "-eo", "uid,pid,args").lines().filter(l -> l.contains("node painel.js")).findFirst().orElse("");
        assertTrue(processo.strip().matches("10001\\s+[0-9]+\\s+node painel\\.js"), "o agente roda como o usuário 10001, nunca como root: [" + processo + "]");
        // o container endurecido: sem capacidades, sem ganhar privilégio, sistema de arquivos só leitura, com o usuário e o grupo do soquete
        assertEquals("10001:10001", saida("inspect", "-f", "{{.Config.User}}", CONTAINER));
        assertEquals("[ALL]", saida("inspect", "-f", "{{.HostConfig.CapDrop}}", CONTAINER));
        assertEquals("[]", saida("inspect", "-f", "{{.HostConfig.CapAdd}}", CONTAINER));
        assertEquals("true", saida("inspect", "-f", "{{.HostConfig.ReadonlyRootfs}}", CONTAINER));
        assertTrue(saida("inspect", "-f", "{{.HostConfig.SecurityOpt}}", CONTAINER).contains("no-new-privileges"));
        assertEquals("false", saida("inspect", "-f", "{{.HostConfig.Privileged}}", CONTAINER));
        // o token foi registrado pelo `docker exec ... acesso.js`, que roda como o mesmo usuário: os arquivos de dados são dele
        assertTrue(saida("exec", CONTAINER, "sh", "-c", "ls -ln /srv/painel/dados").lines().filter(l -> l.contains("tokens-clientes.json")).allMatch(l -> l.contains(" 10001 ")),
            "os dados são do usuário do agente");

        // 6. o app consulta de verdade, como o Resumo e os gráficos fazem
        try (ClienteApi api = new ClienteApi("labteste", porta, token, PedidoDeCodigo2fa.NUNCA)) {
            var servidor = api.servidor();
            assertTrue(servidor.hostname() != null && !servidor.hostname().isBlank(), "o nome da máquina vem de /host/etc/hostname");
            assertTrue(servidor.cpu() != null && servidor.cpu().nucleos() != null && servidor.cpu().nucleos() > 0, "CPU lida");
            assertTrue(servidor.memoria() != null && servidor.memoria().total() != null && servidor.memoria().total() > 0, "memória lida");
            assertTrue(servidor.docker() != null && servidor.docker().containers() != null && servidor.docker().containers() >= 1,
                "o Docker respondeu pelo soquete (pelo menos o próprio agente): " + servidor.docker());
            assertTrue(api.servidorDetalhe().maquina() != null, "o detalhe da máquina");

            // o histórico só tem ponto depois da primeira gravação (a grade começa vazia): espera até 40 s
            HistoricoDoAgente h = null;
            long limite = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < limite) {
                h = api.historico("10m");
                if (h.agora() != null && h.agora().cpu() != null && h.cpu().stream().anyMatch(java.util.Objects::nonNull)) {
                    break;
                }
                Thread.sleep(2000);
            }
            assertTrue(h != null && h.agora() != null && h.agora().cpu() != null, "o gráfico recebe a última leitura: " + h);
            assertEquals("10m", h.janela());
            assertTrue(h.cpu().stream().anyMatch(java.util.Objects::nonNull), "e pelo menos um ponto de CPU na grade");
            var tendencia = br.com.labdesktop.tendencias.DadosDeTendencia.deHistorico(br.com.labdesktop.tendencias.JanelaDeTempo.DEZ_MINUTOS, h, Instant.now());
            assertTrue(tendencia != null, "o bloco de Tendências do Resumo monta os dados");
            for (String j : List.of("1h", "1d", "7d", "30d")) {
                assertEquals(j, api.historico(j).janela());
            }
        }
        capturar(porta, token);

        // 8. rodar de novo não estraga nada e só lê
        List<String> comandosAntes = new ArrayList<>(log);
        ResultadoDaInstalacao denovo = lab.criar(obs, Cancelamento.NUNCA);
        assertTrue(denovo.concluida(), denovo.erro());
        assertEquals(r.concluida(), denovo.concluida());
        List<String> novos = log.subList(comandosAntes.size(), log.size());
        assertTrue(novos.stream().noneMatch(l -> l.startsWith("$ docker run") || l.startsWith("$ docker build") || l.startsWith("$ docker network create")),
            "a segunda vez só lê: " + novos);

        // 9. o limite de tentativas: credenciais erradas demais e o agente recusa quem não traz token válido por um tempo (429 muitas_tentativas); o token certo passa.
        // O app mostra uma frase de espera e não repete em laço.
        ErroApi.ServidorErro bloqueio = null;
        for (int i = 0; i < 40 && bloqueio == null; i++) {
            try (ClienteApi errado = new ClienteApi("labteste", porta, TokenAgente.gerar(), PedidoDeCodigo2fa.NUNCA)) {
                try {
                    errado.versao();
                    org.junit.jupiter.api.Assertions.fail("token errado não pode passar");
                } catch (ErroApi.NaoAutorizado esperado) {
                    // 401 até estourar o limite
                } catch (ErroApi.ServidorErro e) {
                    bloqueio = e;
                }
            }
        }
        assertTrue(bloqueio != null && bloqueio.muitasTentativas(), "depois de 30 credenciais recusadas o agente responde 429 muitas_tentativas");
        assertTrue(bloqueio.aguardeSegundos() > 0 && bloqueio.aguardeSegundos() <= 60, "diz quanto esperar: " + bloqueio.aguardeSegundos());
        String frase = br.com.labdesktop.ui.MensagensDeErro.daApi(bloqueio);
        assertTrue(frase.startsWith("Muitas tentativas; espere um pouco"), frase);
        try (ClienteApi certo = new ClienteApi("labteste", porta, token, PedidoDeCodigo2fa.NUNCA)) {
            assertEquals(nome, certo.versao().cliente(), "o limite é só de quem não traz token válido: o token certo nunca é bloqueado");
        }

        // 10. remover tira o agente, os dados, a imagem e a rede (do app, vazia), e NUNCA os projetos
        ResultadoDaRemocao rem = lab.remover(obs, Cancelamento.NUNCA);
        assertTrue(rem.removido(), rem.avisos().toString());
        assertTrue(docker("ps", "-a", "--filter", "name=^" + CONTAINER + "$", "--format", "{{.Names}}").endsWith("|"), "o container saiu");
        assertTrue(docker("network", "ls", "--filter", "name=^" + REDE + "$", "--format", "{{.Name}}").endsWith("|"), "a rede saiu");
        assertTrue(docker("volume", "ls", "-q", "--filter", "name=^" + CONTAINER + "-dados$").endsWith("|"), "os dados saíram");
        assertTrue(Files.exists(projeto.resolve("x.txt")), "os projetos do usuário nunca são apagados");
    }

    @Test
    void volumeDeVersaoAnteriorComArquivosDeRootEEntregueAoUsuarioDoAgenteESobeNoDockerDeVerdade() throws Exception {
        PacoteDoAgente pacote = PacoteDoAgente.doClasspath();
        int porta = portaLivre();
        Path projetos = tmp.toRealPath().resolve("LabLocal").resolve("projects");
        ParametrosDoLaboratorio p = new ParametrosDoLaboratorio(CONTAINER, porta, REDE, IMAGEM, projetos.toString());
        ResultadoExec build = processos.executar(ComandosLocais.construirImagem(p, pacote), pacote.conteudo(), Duration.ofMinutes(10));
        assertEquals(0, build.status(), build.stderr());
        // o volume de "uma instalação anterior": pasta e arquivos de root, modo 0600/0755, mais o arquivo de histórico do agente antigo
        saida("volume", "create", p.volume());
        saida("run", "--rm", "--network", "none", "--user", "0:0", "-v", p.volume() + ":/srv/painel/dados", p.imagem(pacote.versao()), "sh", "-c",
            "echo '{\"versao\":1,\"tokens\":[]}' > /srv/painel/dados/tokens-clientes.json && echo '{}' > /srv/painel/dados/metricas-antigas.json"
                + " && chown -R 0:0 /srv/painel/dados && chmod 755 /srv/painel/dados && chmod 600 /srv/painel/dados/*");
        TokenAgente token = TokenAgente.gerar();
        ProvisionadorRemoto prov = ProvisionadorRemoto.para(new ExecutorDeLinhaFixa(processos), CONTAINER, "docker");
        GanchosDaInstalacao ganchos = new GanchosDaInstalacao() {
            @Override
            public Sonda sondar() {
                try (ClienteApi a = new ClienteApi("labteste", porta, null, PedidoDeCodigo2fa.NUNCA)) {
                    a.versao();
                    return Sonda.RESPONDE;
                } catch (ErroApi.NaoAutorizado e) {
                    return Sonda.TOKEN_RECUSADO;
                } catch (ErroApi e) {
                    return Sonda.NAO_RESPONDE;
                }
            }

            @Override
            public boolean registrarToken(ParametrosDaInstalacao parametros, String docker) throws Exception {
                return prov.registrarToken(token.hashSha256Hex(), "volume-antigo", Instant.now());
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
        assertTrue(log.stream().anyMatch(l -> l.contains("chown -R 10001:10001")), "o app entregou a pasta de dados ao usuário do agente: " + log);
        String donos = saida("exec", CONTAINER, "sh", "-c", "ls -ln /srv/painel/dados");
        assertTrue(donos.lines().filter(l -> l.contains(".json")).allMatch(l -> l.contains(" 10001 ")), "todos os arquivos passaram ao usuário do agente: " + donos);
        try (ClienteApi api = new ClienteApi("labteste", porta, token, PedidoDeCodigo2fa.NUNCA)) {
            assertEquals("volume-antigo", api.versao().cliente(), "o token registrado pelo exec, como o mesmo usuário, vale");
            assertTrue(api.servidor().docker() != null, "e o agente lê o Docker pelo soquete com o grupo que o app deu");
        }
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
