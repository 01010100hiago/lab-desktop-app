package br.com.labdesktop.local;

import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.PacoteDoAgente;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TODOS os comandos que o laboratório local roda NESTE computador, num lugar só. Cada um é uma LISTA de argumentos (nunca texto para um shell:
 * no Windows não há shell POSIX, e uma lista não deixa nada que o usuário digita virar comando), com o programa em primeiro ({@code docker},
 * {@code git}); o que varia (nome do container, da rede, porta, versão) já passou por regex em {@link ParametrosDoLaboratorio} e
 * {@link PacoteDoAgente}.
 *
 * <p>Dois grupos, como em {@link ComandosRemotos}: as LEITURAS (verificações, diagnóstico) não mudam nada e são as únicas que rodam sem clique;
 * as ESCRITAS (rede, imagem, container, volumes) só depois do clique do usuário. {@link #ehLeitura} é a fronteira, por forma inteira (cada
 * argumento confere), e um teste a confere contra os construtores.
 */
public final class ComandosLocais {

    /** Marca o que o laboratório local criou (rede, container): é como o app sabe que é dele e nunca mexe no que não é. */
    public static final String ROTULO_LOCAL = "br.com.labdesktop.local";

    private static final String DOCKER = "docker";

    private ComandosLocais() { }

    // =====================================================================================================
    // LEITURAS
    // =====================================================================================================

    public static List<String> versaoDoDocker() {
        return List.of(DOCKER, "version", "--format", "{{.Server.Version}}");
    }

    public static List<String> versaoDoComposeDoDocker() {
        return List.of(DOCKER, "compose", "version", "--short");
    }

    public static List<String> versaoDoGit() {
        return List.of("git", "--version");
    }

    /** O estado do WSL (Windows). Sai com 0 se o WSL 2 está instalado. */
    public static List<String> estadoDoWsl() {
        return List.of("wsl", "--status");
    }

    /** Se a virtualização está ligada na BIOS (Windows): imprime {@code True} ou {@code False}. Texto FIXO do PowerShell. */
    public static List<String> virtualizacaoDoWindows() {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled");
    }

    /** As redes com este nome exato (uma linha por rede, com o rótulo do app e quantos containers estão nela). */
    public static List<String> lerRede(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "network", "ls", "--filter", "name=^" + p.rede() + "$", "--format", "{{.Name}}|{{.Label \"" + ROTULO_LOCAL + "\"}}");
    }

    /** Quantos containers estão ligados à rede (para não removê-la em uso). */
    public static List<String> lerContainersNaRede(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "network", "inspect", "-f", "{{len .Containers}}", p.rede());
    }

    /** Os containers com este nome exato: {@code nome|versão do pacote que o criou (rótulo, vazio se não foi o app)|estado}. */
    public static List<String> lerContainer(ParametrosDoLaboratorio p) {
        return lerContainerPeloNome(p.container());
    }

    /** O mesmo, para um nome só (o agente antigo guardado como {@code <nome>-anterior} numa atualização). */
    public static List<String> lerContainerPeloNome(String nome) {
        return List.of(DOCKER, "ps", "-a", "--filter", "name=^" + exigirNome(nome) + "$", "--format",
            "{{.Names}}|{{.Label \"" + ComandosRemotos.ROTULO_DO_AGENTE + "\"}}|{{.State}}");
    }

    public static List<String> lerVolume(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "volume", "ls", "-q", "--filter", "name=^" + p.volume() + "$");
    }

    /** Sai com 0 se a imagem existe. */
    public static List<String> lerImagem(ParametrosDoLaboratorio p, String versao) {
        return List.of(DOCKER, "image", "inspect", "-f", "{{.Id}}", p.imagem(versao));
    }

    public static List<String> lerLogs(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "logs", "--tail", "40", p.container());
    }

    /** O estado do container do agente ({@code running}, {@code exited}...); sai com status diferente de 0 se não existe. */
    public static List<String> lerEstadoDoContainer(String container) {
        return List.of(DOCKER, "inspect", "-f", "{{.State.Status}}", exigirNome(container));
    }

    // =====================================================================================================
    // ESCRITAS (só depois do clique do usuário)
    // =====================================================================================================

    /** Cria a rede dos projetos, marcada como do app. */
    public static List<String> criarRede(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "network", "create", "--label", ROTULO_LOCAL + "=1", p.rede());
    }

    public static List<String> removerRede(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "network", "rm", p.rede());
    }

    /**
     * Constrói a imagem do agente. O pacote (um tar.gz) vai pela ENTRADA do {@code docker build -}: o contexto de build é o próprio arquivo,
     * sem extrair nada em disco e sem caminho do sistema (igual no Mac, no Windows e no Linux). {@code -f Dockerfile.monitor} aponta o arquivo
     * dentro do pacote.
     */
    public static List<String> construirImagem(ParametrosDoLaboratorio p, PacoteDoAgente pacote) {
        return List.of(DOCKER, "build", "-f", "Dockerfile.monitor", "-t", p.imagem(pacote.versao()), "-");
    }

    /**
     * Sobe o container do agente do laboratório local. NÃO usa {@code --network host} (o Docker Desktop roda numa máquina virtual: a rede do
     * "host" é a dela, não a do computador): publica a porta SÓ no loopback ({@code -p 127.0.0.1:porta:porta}) e o agente escuta em todas as
     * interfaces DO CONTAINER . Exige a credencial do app desde o primeiro minuto. A pasta dos projetos entra no container no
     * caminho que o daemon também entende ({@link MapeamentoDeCaminhos}).
     *
     * @param montagemDaMaquina {@link PlataformaLocal#montagemDaMaquina()}
     * @param pastaNoContainer  {@link PlataformaLocal#noContainer(String)} da pasta dos projetos
     */
    public static List<String> subirContainer(ParametrosDoLaboratorio p, PacoteDoAgente pacote, List<String> montagemDaMaquina,
                                              String pastaNoContainer) {
        List<String> c = new ArrayList<>(List.of(DOCKER, "run", "-d", "--name", p.container(), "--restart", "unless-stopped",
            "-p", "127.0.0.1:" + p.porta() + ":" + p.porta(), "--pid", "host",
            "--label", ComandosRemotos.ROTULO_DO_AGENTE + "=" + pacote.versao(), "--label", ROTULO_LOCAL + "=1",
            "--log-opt", "max-size=10m", "--log-opt", "max-file=3",
            "-e", "PORT=" + p.porta(), "-e", "PAINEL_PERFIL=monitor", "-e", "PAINEL_EXIGE_TOKEN=1", "-e", "PAINEL_ESCUTA=todas",
            "-v", "/var/run/docker.sock:/var/run/docker.sock"));
        c.addAll(montagemDaMaquina);
        c.addAll(List.of("-v", p.volume() + ":" + br.com.labdesktop.instalador.PastasNoContainer.DADOS + "", "-v", p.pastaDosProjetos() + ":" + pastaNoContainer, p.imagem(pacote.versao())));
        return List.copyOf(c);
    }

    public static List<String> removerContainer(String container) {
        return List.of(DOCKER, "rm", "-f", exigirNome(container));
    }

    /** Renomeia um container (a atualização guarda o agente antigo como {@code <nome>-anterior} até o novo responder). */
    public static List<String> renomearContainer(String de, String para) {
        return List.of(DOCKER, "rename", exigirNome(de), exigirNome(para));
    }

    public static List<String> pararContainer(String nome) {
        return List.of(DOCKER, "stop", exigirNome(nome));
    }

    public static List<String> iniciarContainer(String nome) {
        return List.of(DOCKER, "start", exigirNome(nome));
    }

    public static List<String> removerVolume(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "volume", "rm", p.volume());
    }

    public static List<String> removerImagem(ParametrosDoLaboratorio p, String versao) {
        return List.of(DOCKER, "rmi", p.imagem(versao));
    }

    // =====================================================================================================
    // A fronteira
    // =====================================================================================================

    private static final String RE_NOME = "[a-z0-9][a-z0-9_.-]{0,40}";
    private static final String RE_NOME_DE_CONTAINER = "[a-z0-9][a-z0-9_.-]{0,60}"; // o nome do agente e o dele com -anterior
    private static final String RE_VERSAO = "[A-Za-z0-9][A-Za-z0-9_.-]{0,40}";
    private static final List<List<Pattern>> LEITURAS = montarLeituras();

    /**
     * O comando só LÊ? A comparação é pela FORMA INTEIRA: mesmo número de argumentos e cada um dentro da regex dele. Um argumento a mais, um
     * nome fora da regra ou outro verbo do Docker NÃO é leitura.
     */
    public static boolean ehLeitura(List<String> argumentos) {
        if (argumentos == null) {
            return false;
        }
        outer:
        for (List<Pattern> molde : LEITURAS) {
            if (molde.size() != argumentos.size()) {
                continue;
            }
            for (int i = 0; i < molde.size(); i++) {
                if (argumentos.get(i) == null || !molde.get(i).matcher(argumentos.get(i)).matches()) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static List<List<Pattern>> montarLeituras() {
        ParametrosDoLaboratorio p = new ParametrosDoLaboratorio("zzcontainer", 54321, "zzrede", "zzimagem", "/zzpasta");
        List<List<String>> modelos = List.of(versaoDoDocker(), versaoDoComposeDoDocker(), versaoDoGit(), estadoDoWsl(), virtualizacaoDoWindows(),
            lerRede(p), lerContainersNaRede(p), lerContainer(p), lerVolume(p), lerImagem(p, "zzversao"), lerLogs(p), lerEstadoDoContainer("zzcontainer"));
        List<List<Pattern>> moldes = new ArrayList<>();
        for (List<String> modelo : modelos) {
            List<Pattern> m = new ArrayList<>();
            for (String arg : modelo) {
                m.add(Pattern.compile(comoRegex(arg)));
            }
            moldes.add(List.copyOf(m));
        }
        return List.copyOf(moldes);
    }

    private static String comoRegex(String modelo) {
        String[][] sentinelas = {{"zzcontainer", RE_NOME_DE_CONTAINER}, {"zzrede", RE_NOME}, {"zzimagem", RE_NOME}, {"zzversao", RE_VERSAO}};
        StringBuilder re = new StringBuilder();
        int i = 0;
        outer:
        while (i < modelo.length()) {
            for (String[] s : sentinelas) {
                if (modelo.startsWith(s[0], i)) {
                    re.append(s[1]);
                    i += s[0].length();
                    continue outer;
                }
            }
            char c = modelo.charAt(i++);
            if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
                re.append('\\');
            }
            re.append(c);
        }
        return re.toString();
    }

    private static String exigirNome(String nome) {
        if (nome == null || !nome.matches("^[a-z0-9][a-z0-9_.-]{0,60}$")) {
            throw new IllegalArgumentException("Nome de container inválido.");
        }
        return nome;
    }
}
