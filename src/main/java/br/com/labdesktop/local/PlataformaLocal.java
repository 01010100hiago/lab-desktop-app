package br.com.labdesktop.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * Tudo o que muda de um sistema para outro no laboratório local, num lugar só e como texto puro (por isso os três sistemas se testam num Mac,
 * sem máquina Windows): onde moram os programas, como se abre o Docker, de onde baixá-lo, onde fica a pasta do laboratório e o que vai no
 * {@code docker run}. O resto do pacote não sabe em que sistema está.
 *
 * <p>Nada aqui roda nada: só devolve listas de argumentos e textos. Quem roda é o {@link ExecutorDeProcesso}, depois do clique do usuário.
 */
public final class PlataformaLocal {

    /** A página oficial de download do Docker Desktop (Mac e Windows). */
    public static final String URL_DO_DOCKER_DESKTOP = "https://www.docker.com/products/docker-desktop/";
    /** O guia oficial do Docker Engine no Linux. */
    public static final String URL_DO_DOCKER_NO_LINUX = "https://docs.docker.com/engine/install/";
    public static final String URL_DO_GIT = "https://git-scm.com/downloads";

    private final SistemaOperacional sistema;
    private final String casa;
    private final Map<String, String> ambiente;

    /**
     * @param casa     a pasta do usuário ({@code user.home}), como texto do sistema ({@code /Users/ana} ou {@code C:\Users\ana})
     * @param ambiente as variáveis de ambiente que importam ({@code ProgramFiles}, {@code LOCALAPPDATA}); pode ser vazio
     */
    public PlataformaLocal(SistemaOperacional sistema, String casa, Map<String, String> ambiente) {
        this.sistema = sistema;
        this.casa = casa;
        this.ambiente = Map.copyOf(ambiente);
    }

    public static PlataformaLocal detectar() {
        return new PlataformaLocal(SistemaOperacional.detectar(), System.getProperty("user.home"), System.getenv());
    }

    public SistemaOperacional sistema() {
        return sistema;
    }

    public String casa() {
        return casa;
    }

    public String separadorDoPath() {
        return sistema == SistemaOperacional.WINDOWS ? ";" : ":";
    }

    public String separadorDeArquivo() {
        return sistema == SistemaOperacional.WINDOWS ? "\\" : "/";
    }

    /** As pastas do {@code PATH} do processo. */
    public List<String> pastasDoPath(String path) {
        List<String> l = new ArrayList<>();
        if (path != null) {
            for (String p : path.split(java.util.regex.Pattern.quote(separadorDoPath()))) {
                if (!p.isBlank()) {
                    l.add(p);
                }
            }
        }
        return l;
    }

    /** As pastas onde os programas moram e que um aplicativo aberto pelo Finder ou pelo menu Iniciar não tem no PATH. */
    public List<String> diretoriosDeProgramas() {
        return switch (sistema) {
            // o git (e outros) de /usr/bin é só atalho da Apple: ver LocalizadorDeProgramas.ATALHOS_DO_MAC; o de verdade está nas três primeiras
            case MAC -> List.of("/opt/homebrew/bin", "/usr/local/bin", "/Library/Developer/CommandLineTools/usr/bin",
                "/Applications/Xcode.app/Contents/Developer/usr/bin", "/Applications/Docker.app/Contents/Resources/bin",
                casa + "/.docker/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin");
            case WINDOWS -> {
                String pf = ambiente.getOrDefault("ProgramFiles", "C:\\Program Files");
                String windir = ambiente.getOrDefault("SystemRoot", "C:\\Windows");
                String local = ambiente.getOrDefault("LOCALAPPDATA", casa + "\\AppData\\Local");
                yield List.of(pf + "\\Docker\\Docker\\resources\\bin", pf + "\\Git\\cmd", windir + "\\System32",
                    windir + "\\System32\\WindowsPowerShell\\v1.0", local + "\\Microsoft\\WindowsApps");
            }
            case LINUX -> List.of("/usr/local/bin", "/usr/bin", "/bin", "/snap/bin");
        };
    }

    /** A pasta do laboratório: {@code <pasta do usuário>/LabLocal}. */
    public String pastaDoLaboratorio() {
        return casa + separadorDeArquivo() + "LabLocal";
    }

    /** A raiz padrão dos projetos locais: {@code <pasta do usuário>/LabLocal/projects} (editável). */
    public String pastaDeProjetosPadrao() {
        return pastaDoLaboratorio() + separadorDeArquivo() + "projects";
    }

    /** O Docker Desktop instalado, por um caminho conhecido, para o app saber que ele existe mesmo fora do PATH. */
    public List<String> caminhosDoDockerDesktop() {
        return switch (sistema) {
            case MAC -> List.of("/Applications/Docker.app");
            case WINDOWS -> List.of(ambiente.getOrDefault("ProgramFiles", "C:\\Program Files") + "\\Docker\\Docker\\Docker Desktop.exe");
            case LINUX -> List.of();
        };
    }

    /**
     * O comando que ABRE o Docker (não instala nada): o app do Docker Desktop, ou o Colima. Vazio onde o app não sabe abrir sozinho (Linux:
     * ligar o serviço pede administrador).
     */
    public List<String> comandoParaAbrirODocker(boolean dockerDesktopInstalado, boolean colimaInstalado) {
        return switch (sistema) {
            case MAC -> dockerDesktopInstalado ? List.of("open", "-a", "Docker") : colimaInstalado ? List.of("colima", "start") : List.of();
            case WINDOWS -> dockerDesktopInstalado ? List.of(caminhosDoDockerDesktop().get(0)) : List.of();
            case LINUX -> List.of();
        };
    }

    /** O que o app diz para quem o Docker não abre sozinho. */
    public String orientacaoParaAbrirODocker() {
        return switch (sistema) {
            case MAC -> "Abra o Docker pelo Launchpad ou pela pasta Aplicativos e espere o ícone da baleia parar de animar.";
            case WINDOWS -> "Abra o Docker Desktop pelo menu Iniciar e espere ele dizer que está rodando.";
            case LINUX -> "Ligue o serviço do Docker (por exemplo: sudo systemctl start docker) e tente de novo.";
        };
    }

    /**
     * As opções do {@code docker run} que dão ao agente a visão da máquina (métricas): a raiz do disco SOMENTE LEITURA em {@code /host} no Mac e
     * no Linux (é uma das coisas que {@link AvisoDeSeguranca} diz ao usuário); no Windows o {@code /} não é montável como no Mac.
     */
    public List<String> montagemDaMaquina() {
        return sistema == SistemaOperacional.WINDOWS ? List.of() : List.of("-v", "/:/host:ro");
    }

    /** O caminho da pasta como o container do agente a enxerga ({@link MapeamentoDeCaminhos}). */
    public String noContainer(String caminhoDoAnfitriao) {
        return MapeamentoDeCaminhos.doContainer(sistema, caminhoDoAnfitriao);
    }

    /** O Docker precisa de virtualização ligada e do WSL 2 (só no Windows). */
    public boolean exigeVirtualizacao() {
        return sistema == SistemaOperacional.WINDOWS;
    }
}
