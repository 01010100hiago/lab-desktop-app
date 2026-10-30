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

    /**
     * O que o Windows diz da virtualização da BIOS: imprime {@code True} ou {@code False}. Texto FIXO do PowerShell. Com o Hyper-V ou o WSL 2
     * funcionando ele diz {@code False} (o hipervisor tomou conta), então só serve de dica quando o WSL falta: nunca bloqueia sozinho.
     */
    public static List<String> virtualizacaoDoWindows() {
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled");
    }

    /**
     * As redes com este nome exato: {@code nome|rótulos}. SEM aspas no formato: no Windows o argumento com aspas chegaria quebrado ao docker.exe
     * (ver {@link LeituraDoDocker}).
     */
    public static List<String> lerRede(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "network", "ls", "--filter", "name=^" + p.rede() + "$", "--format", "{{.Name}}|{{.Labels}}");
    }

    /** Quantos containers estão ligados à rede (para não removê-la em uso). */
    public static List<String> lerContainersNaRede(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "network", "inspect", "-f", "{{len .Containers}}", p.rede());
    }

    /** Os containers com este nome exato: {@code nome|rótulos|estado|portas} (os rótulos do app dizem se foi ele que criou; as portas, onde ele escuta). */
    public static List<String> lerContainer(ParametrosDoLaboratorio p) {
        return lerContainerPeloNome(p.container());
    }

    /** O mesmo, para um nome só (o agente antigo guardado como {@code <nome>-anterior} numa atualização). */
    public static List<String> lerContainerPeloNome(String nome) {
        return List.of(DOCKER, "ps", "-a", "--filter", "name=^" + exigirNome(nome) + "$", "--format",
            "{{.Names}}|{{.Labels}}|{{.State}}|{{.Ports}}");
    }

    /** Os volumes com este nome exato: {@code nome|rótulos}. */
    public static List<String> lerVolume(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "volume", "ls", "--filter", "name=^" + p.volume() + "$", "--format", "{{.Name}}|{{.Labels}}");
    }

    /** As imagens do agente que o app construiu (têm o rótulo local), uma {@code nome:versão} por linha, de qualquer versão. */
    public static List<String> lerImagensDoApp(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "image", "ls", "--filter", "reference=" + p.imagemBase(), "--filter", "label=" + ROTULO_LOCAL + "=1", "--format",
            "{{.Repository}}:{{.Tag}}");
    }

    /** Sai com 0 se a imagem existe. */
    public static List<String> lerImagem(ParametrosDoLaboratorio p, String versao) {
        return List.of(DOCKER, "image", "inspect", "-f", "{{.Id}}", p.imagem(versao));
    }

    public static List<String> lerLogs(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "logs", "--tail", "40", p.container());
    }

    /**
     * O registro do agente local: as últimas 200 linhas do container, com a hora de cada uma. LEITURA. Mesma forma de {@link #lerLogs}, com o limite e a marca de hora
     * fixos; o nome do container passa pela regra de nome (quem pede é o laboratório local, com o nome que o app mesmo gravou).
     */
    public static List<String> lerRegistroDoAgente(String container) {
        return List.of(DOCKER, "logs", "--tail", "200", "--timestamps", exigirNome(container));
    }

    /** O estado do container do agente ({@code running}, {@code exited}...); sai com status diferente de 0 se não existe. */
    public static List<String> lerEstadoDoContainer(String container) {
        return List.of(DOCKER, "inspect", "-f", "{{.State.Status}}", exigirNome(container));
    }

    /**
     * Os containers de projetos do compose, de qualquer projeto: {@code nome|estado|status|portas|imagem|rótulos}. Os rótulos vão por último e
     * inteiros ({@code k=v,k=v}): o app tira deles o nome do projeto do compose e a pasta de trabalho. SEM aspas no formato (Windows).
     */
    public static List<String> lerContainersDosProjetos() {
        return List.of(DOCKER, "ps", "-a", "--filter", "label=com.docker.compose.project", "--format",
            "{{.Names}}|{{.State}}|{{.Status}}|{{.Ports}}|{{.Image}}|{{.Labels}}");
    }

    // =====================================================================================================
    // ESCRITAS (só depois do clique do usuário)
    // =====================================================================================================

    /** O que Iniciar, Parar e Reiniciar um projeto fazem com cada arquivo compose dele (a mesma coisa que o agente do lab faz). */
    public enum AcaoDeProjeto {
        INICIAR(List.of("up", "-d")), REINICIAR(List.of("restart")), PARAR(List.of("down")); // down SEM -v: volume nenhum some por aqui

        private final List<String> argumentos;

        AcaoDeProjeto(List<String> argumentos) {
            this.argumentos = argumentos;
        }
    }

    /**
     * {@code docker compose -p <projeto do compose> -f <arquivo> <ação>}. O {@code -p} explícito é o do agente (um {@code COMPOSE_PROJECT_NAME}
     * num {@code .env} não pode desviar a ação para outro projeto); o arquivo é o caminho do SISTEMA, absoluto, e a pasta de trabalho do compose
     * é a dele (os volumes relativos resolvem a partir dali).
     */
    public static List<String> comporProjeto(String projetoDoCompose, String arquivoDoSistema, AcaoDeProjeto acao) {
        if (projetoDoCompose == null || !projetoDoCompose.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException("Nome de projeto do compose inválido.");
        }
        if (arquivoDoSistema == null || arquivoDoSistema.isBlank() || arquivoDoSistema.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Arquivo compose inválido.");
        }
        List<String> c = new ArrayList<>(List.of(DOCKER, "compose", "-p", projetoDoCompose, "-f", arquivoDoSistema));
        c.addAll(acao.argumentos);
        return List.copyOf(c);
    }

    /** {@code docker restart <container>}: o botão Reiniciar de um container do projeto. */
    public static List<String> reiniciarContainerDoProjeto(String container) {
        return List.of(DOCKER, "restart", exigirNome(container));
    }

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
        return List.of(DOCKER, "build", "--label", ROTULO_LOCAL + "=1", "-f", "Dockerfile.monitor", "-t", p.imagem(pacote.versao()), "-");
    }

    /**
     * O grupo dono do soquete do Docker como o container o enxerga (um container descartável com {@code stat}); no Docker Desktop é o 0. ESCRITA (cria e
     * apaga um container), só depois do clique. Serve ao {@code --group-add} do agente, que não roda como root.
     */
    public static List<String> lerGidDoSoquete(ParametrosDoLaboratorio p, PacoteDoAgente pacote) {
        return List.of(DOCKER, "run", "--rm", "--network", "none", "-v", ComandosRemotos.SOQUETE_DO_DOCKER + ":" + ComandosRemotos.SOQUETE_DO_DOCKER,
            p.imagem(pacote.versao()), "stat", "-c", "%g", ComandosRemotos.SOQUETE_DO_DOCKER);
    }

    /**
     * Entrega a pasta de dados de um volume que JÁ existia (de uma versão anterior) ao usuário do agente. Container descartável, sem rede, como root só
     * para a troca de usuário. Um volume novo já nasce com o usuário certo.
     */
    public static List<String> acertarDonoDosDados(ParametrosDoLaboratorio p, PacoteDoAgente pacote) {
        return List.of(DOCKER, "run", "--rm", "--network", "none", "--user", "0:0", "-v", p.volume() + ":/srv/painel/dados", p.imagem(pacote.versao()),
            "chown", "-R", ComandosRemotos.USUARIO_DO_AGENTE, "/srv/painel/dados");
    }

    /**
     * Sobe o container do agente do laboratório local. NÃO usa {@code --network host} (o Docker Desktop roda numa máquina virtual: a rede do
     * "host" é a dela, não a do computador): publica a porta SÓ no loopback ({@code -p 127.0.0.1:porta:porta}) e o agente escuta em todas as
     * interfaces DO CONTAINER ({@code PAINEL_ESCUTA=todas}). Exige o token desde o primeiro minuto.
     *
     * <p><b>O que o container recebe, de fato</b> (o aviso ao usuário, em {@link AvisoDeSeguranca}, diz o mesmo): o soquete do Docker
     * ({@code /var/run/docker.sock}), {@code --pid host} (a visão dos processos) e, no Mac e no Linux, três arquivos da máquina só para leitura
     * ({@code /etc/hostname}, {@code /etc/os-release} e {@code /etc/passwd}, ver {@link PlataformaLocal#montagemDaMaquina()}). NÃO recebe a raiz do
     * disco nem a pasta dos projetos (o agente de monitoramento não abre nenhuma das duas). Roda como o usuário 10001, sem capacidades, sem ganhar
     * privilégio e com o sistema de arquivos só leitura ({@link ComandosRemotos#opcoesDeEndurecimento}). O soquete é o poder grande: o agente só faz
     * {@code GET} em três caminhos da API (filtro {@code docker-proxy.js} dele), mas quem o comprometesse alcançaria a API inteira, que no Linux
     * equivale a ser root e no Mac e no Windows alcança as pastas que o Docker Desktop compartilha. Por isso a porta é só do loopback e o token é exigido
     * desde o primeiro minuto. A opção mais fechada do README do agente (um segundo container, sem rede, é o único com o soquete) ainda não é feita pelo app.
     *
     * @param montagemDaMaquina {@link PlataformaLocal#montagemDaMaquina()}
     * @param gidDoSoquete      {@link #lerGidDoSoquete}
     */
    public static List<String> subirContainer(ParametrosDoLaboratorio p, PacoteDoAgente pacote, List<String> montagemDaMaquina, String gidDoSoquete) {
        List<String> c = new ArrayList<>(List.of(DOCKER, "run", "-d", "--name", p.container(), "--restart", "unless-stopped",
            "-p", "127.0.0.1:" + p.porta() + ":" + p.porta(), "--pid", "host"));
        c.addAll(ComandosRemotos.opcoesDeEndurecimento(gidDoSoquete));
        c.addAll(List.of("--label", ComandosRemotos.ROTULO_DO_AGENTE + "=" + pacote.versao(), "--label", ROTULO_LOCAL + "=1",
            "--log-opt", "max-size=10m", "--log-opt", "max-file=3",
            "-e", "PORT=" + p.porta(), "-e", "PAINEL_PERFIL=monitor", "-e", "PAINEL_EXIGE_TOKEN=1", "-e", "PAINEL_ESCUTA=todas",
            "-v", ComandosRemotos.SOQUETE_DO_DOCKER + ":" + ComandosRemotos.SOQUETE_DO_DOCKER));
        c.addAll(montagemDaMaquina);
        c.addAll(List.of("-v", p.volume() + ":" + br.com.labdesktop.instalador.PastasNoContainer.DADOS, p.imagem(pacote.versao())));
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

    /**
     * Cria o volume dos dados do agente ANTES do {@code docker run}, marcado como do app: se o run falhar, o volume que ESTA execução criou é
     * desfeito, e um volume que sobrou (cancelou no meio) ainda é reconhecido como do app na hora de remover.
     */
    public static List<String> criarVolume(ParametrosDoLaboratorio p) {
        return List.of(DOCKER, "volume", "create", "--label", ROTULO_LOCAL + "=1", p.volume());
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
            lerRede(p), lerContainersNaRede(p), lerContainer(p), lerVolume(p), lerImagensDoApp(p), lerImagem(p, "zzversao"), lerLogs(p), lerRegistroDoAgente("zzcontainer"), lerEstadoDoContainer("zzcontainer"),
            lerContainersDosProjetos());
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
