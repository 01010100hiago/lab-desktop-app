package br.com.labdesktop.cloudflare;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.ssh.ConexaoSsh;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O servidor do usuário, simulado para os testes da Cloudflare: um {@code SshServer} embutido do MINA com SFTP sobre uma pasta (o cliente vê {@code /} como ela) e um {@code docker}
 * de mentira nos comandos {@code exec} (só os que o app roda: {@code inspect}, {@code exec cloudflared ... ingress validate}, {@code restart} e {@code compose config}), com o estado do
 * cloudflared (rodando, quantos reinícios, o que o arquivo tinha no último reinício). A forma da pasta é a do lab: {@code /srv/lab/projects/demo},
 * {@code config.yml} do cloudflared. O app conecta DE VERDADE (SSH e SFTP), como fará no lab.
 */
public final class LabSimulado implements AutoCloseable {

    public static final String PROJETOS = "/srv/lab/projects";
    public static final String CONFIG = "/srv/lab/infra/cloudflared/config.yml";
    public static final String CONFIG_NO_CONTAINER = "/etc/cloudflared/config.yml";
    public static final String UUID = "11111111-2222-3333-4444-555555555555";

    public final Path raiz;
    public final ServidorSshDeTeste ssh;
    public final CofreFalso cofre;
    public final ConexaoSsh conexao;
    public final List<String> comandos = new CopyOnWriteArrayList<>();
    public final AtomicInteger reinicios = new AtomicInteger();
    public volatile boolean rodando = true;
    public volatile boolean containerExiste = true;
    /** O primeiro reinício derruba o cloudflared (a regra nova não deixou ele subir); o seguinte o levanta. */
    public volatile boolean oPrimeiroReinicioNaoVolta;
    public volatile boolean validarSempreFalha;
    public volatile boolean semPermissaoNoDocker;
    public volatile String configNoUltimoReinicio = "";
    public volatile String composeConferido = "";

    public LabSimulado(Path pasta) throws IOException, GeneralSecurityException {
        this.raiz = pasta.toAbsolutePath().toRealPath().resolve("servidor");
        Files.createDirectories(raiz.resolve("srv/lab/projects/demo"));
        Files.createDirectories(raiz.resolve("srv/lab/infra/cloudflared"));
        ssh = ServidorSshDeTeste.ed25519();
        ssh.comSftp(raiz);
        ssh.comandoRemoto(this::docker);
        cofre = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin()));
        ssh.aceitar(cofre.par().getPublic());
        conexao = new ConexaoSsh(CofreFalso.ID, cofre, ConfigSsh.rapida(), tunel -> SondaDoAgente.Resultado.OK);
        conexao.iniciar();
        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + conexao.info(), () -> conexao.info().estado() == EstadoConexao.CONECTADO);
    }

    public AmbienteDeProjetos ambiente() {
        return AmbienteDeProjetos.remoto(PROJETOS, FabricaDeClienteSftp.doSftp(conexao::abrirSftp), conexao::executar);
    }

    public Path arquivo(String caminhoDoServidor) {
        return raiz.resolve(caminhoDoServidor.substring(1));
    }

    public Path escrever(String caminhoDoServidor, String conteudo) throws IOException {
        Path p = arquivo(caminhoDoServidor);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, conteudo, StandardCharsets.UTF_8);
    }

    public String ler(String caminhoDoServidor) throws IOException {
        return Files.readString(arquivo(caminhoDoServidor), StandardCharsets.UTF_8);
    }

    /** Os arquivos de uma pasta (nomes). */
    public List<String> nomes(String pastaDoServidor) throws IOException {
        try (var s = Files.list(arquivo(pastaDoServidor))) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    public ConfiguracaoCloudflare configPorArquivo() {
        return new ConfiguracaoCloudflare("a".repeat(32), "Conta de Teste", "b".repeat(32), "example.com", UUID, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab", CONFIG,
            "cloudflared", null);
    }

    /** O que o servidor responde aos comandos do app. */
    private int docker(String linha, String stdin, StringBuilder saida) {
        comandos.add(linha);
        String[] p = linha.split(" ");
        int i = 0;
        if (p[i].equals("sudo")) {
            i += 2;
        }
        if (!p[i].equals("docker")) {
            saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("comando desconhecido: ").append(p[i]).append('\n');
            return 127;
        }
        if (semPermissaoNoDocker) {
            saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("permission denied while trying to connect to the Docker daemon socket\n");
            return 1;
        }
        String sub = p[i + 1];
        switch (sub) {
            case "inspect" -> {
                if (!containerExiste || !p[i + 2].equals("cloudflared")) {
                    saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("Error: No such object: ").append(p[i + 2]).append('\n');
                    return 1;
                }
                saida.append("[{\"State\":{\"Running\":").append(rodando).append("},\"Config\":{\"Image\":\"cloudflare/cloudflared:latest\"},")
                    .append("\"Mounts\":[{\"Type\":\"bind\",\"Source\":\"").append(CONFIG).append("\",\"Destination\":\"").append(CONFIG_NO_CONTAINER).append("\"}]}]\n");
                return 0;
            }
            case "exec" -> {
                // docker exec cloudflared cloudflared tunnel --config <caminho> ingress validate
                if (!rodando) {
                    saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("Error response from daemon: container is not running\n");
                    return 1;
                }
                String texto;
                try {
                    texto = ler(CONFIG);
                } catch (IOException e) {
                    saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("config não existe\n");
                    return 1;
                }
                try {
                    ConfigYmlDoCloudflared.Analise a = ConfigYmlDoCloudflared.analisar(texto);
                    if (validarSempreFalha || texto.contains("INVALIDO") || !a.temIngress() || !a.temRegraFinal()) {
                        saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("The last ingress rule must match all URLs (i.e. it should not have a hostname or path filter)\n");
                        return 1;
                    }
                } catch (ConfigYmlDoCloudflared.Problema e) {
                    saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("error parsing YAML: ").append(e.getMessage()).append('\n');
                    return 1;
                }
                saida.append("Validating rules from ").append(CONFIG_NO_CONTAINER).append("\nOK\n");
                return 0;
            }
            case "restart" -> {
                int n = reinicios.incrementAndGet();
                try {
                    configNoUltimoReinicio = ler(CONFIG);
                } catch (IOException e) {
                    configNoUltimoReinicio = "";
                }
                rodando = !(oPrimeiroReinicioNaoVolta && n == 1);
                return 0;
            }
            case "compose" -> {
                composeConferido = stdin;
                if (stdin.contains("INVALIDO")) {
                    saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("service \"app\" refers to undefined network x\n");
                    return 1;
                }
                return 0;
            }
            default -> {
                saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("docker: comando desconhecido ").append(sub).append('\n');
                return 1;
            }
        }
    }

    @Override
    public void close() throws IOException {
        conexao.fechar().join();
        ssh.close();
    }
}
