package br.com.labdesktop.local;

import br.com.labdesktop.instalador.ResultadoDaVerificacao.Estado;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Preparar este computador", a conferência: SÓ LEITURA, por comandos fixos de {@link ComandosLocais} e pela {@link SondaDoComputador}. Nada se
 * instala aqui; para o que falta, cada resultado traz os {@link PlanoDeInstalacao} que o usuário pode escolher (e só então o app os roda).
 *
 * <p>Confere: sistema, processador (arm64 ou amd64), Docker instalado e RODANDO (instalado e parado é um caso à parte, com "Abrir o Docker"),
 * Compose, espaço livre (mínimo de 5 GB na pasta do laboratório), memória (mínimo de 4 GB), virtualização e WSL 2 (só no Windows), a porta do
 * agente e o git (para importar projetos). O {@code node} NÃO é requisito: o agente roda no container.
 */
public final class VerificacoesDoComputador {

    /** Espaço livre mínimo: imagem base, imagem do agente, projetos. */
    public static final long MINIMO_DE_DISCO_BYTES = 5L * 1024 * 1024 * 1024;
    /** Memória física mínima. */
    public static final long MINIMO_DE_MEMORIA_BYTES = 4L * 1024 * 1024 * 1024;
    /** Quantas portas acima da pedida se procuram quando ela está ocupada. */
    static final int TENTATIVAS_DE_PORTA = 30;

    private static final Duration LEITURA = Duration.ofSeconds(25);

    private final ExecutorDeProcesso executor;
    private final SondaDoComputador sonda;
    private final PlataformaLocal plataforma;

    public VerificacoesDoComputador(ExecutorDeProcesso executor, SondaDoComputador sonda, PlataformaLocal plataforma) {
        this.executor = executor;
        this.sonda = sonda;
        this.plataforma = plataforma;
    }

    /**
     * Roda todas as verificações (só leitura).
     *
     * @param pastaDoLaboratorio onde o laboratório vai morar (o espaço livre é o do disco dela)
     * @param portaDoAgente      a porta que o agente vai usar
     * @throws InterruptedException cancelado
     */
    public RelatorioDoComputador executar(String pastaDoLaboratorio, int portaDoAgente) throws InterruptedException {
        List<ResultadoDoComputador> r = new ArrayList<>();
        SistemaOperacional so = plataforma.sistema();
        boolean temBrew = sonda.localizarPrograma("brew").isPresent();
        boolean temWinget = sonda.localizarPrograma("winget").isPresent();

        r.add(sistema(so));
        r.add(arquitetura());
        EstadoDoDocker docker = estadoDoDocker();
        r.add(docker(docker, so, temBrew, temWinget));
        r.add(compose(docker));
        r.add(disco(pastaDoLaboratorio));
        r.add(memoria());
        if (plataforma.exigeVirtualizacao()) {
            r.add(virtualizacao());
        }
        int sugerida = portaSugerida(portaDoAgente);
        r.add(porta(portaDoAgente, sugerida));
        r.add(git(so, temBrew, temWinget));
        return new RelatorioDoComputador(r, so, docker.estado == Docker.OK, sugerida);
    }

    // =====================================================================================================
    // Uma verificação de cada vez
    // =====================================================================================================

    private ResultadoDoComputador sistema(SistemaOperacional so) {
        String d = TextoSeguro.truncar(TextoSeguro.limparLinha(sonda.descricaoDoSistema()), 60);
        if (so == SistemaOperacional.LINUX) {
            return ResultadoDoComputador.aviso(ItemDoComputador.SISTEMA, d + ": no Linux o app só confere e mostra a orientação em texto; "
                + "instalar o Docker Engine pede administrador.", null, List.of());
        }
        return ResultadoDoComputador.ok(ItemDoComputador.SISTEMA, d + ".");
    }

    private ResultadoDoComputador arquitetura() {
        String a = sonda.arquitetura();
        return switch (a) {
            case "arm64" -> ResultadoDoComputador.ok(ItemDoComputador.ARQUITETURA, "Processador ARM de 64 bits (arm64).");
            case "amd64" -> ResultadoDoComputador.ok(ItemDoComputador.ARQUITETURA, "Processador de 64 bits da Intel ou da AMD (amd64).");
            default -> ResultadoDoComputador.problema(ItemDoComputador.ARQUITETURA, "Processador " + TextoSeguro.truncar(TextoSeguro.limparLinha(a), 30)
                + ": o agente só tem imagem para arm64 e amd64.", null, List.of());
        };
    }

    private enum Docker { OK, AUSENTE, PARADO, SEM_PERMISSAO, ERRO }

    private record EstadoDoDocker(Docker estado, String versao, String detalhe, boolean desktopInstalado, boolean colimaInstalado) { }

    private EstadoDoDocker estadoDoDocker() throws InterruptedException {
        boolean desktop = plataforma.caminhosDoDockerDesktop().stream().anyMatch(sonda::existe);
        boolean colima = sonda.localizarPrograma("colima").isPresent();
        try {
            ResultadoExec x = executor.executar(ComandosLocais.versaoDoDocker(), LEITURA);
            if (x.status() == 0) {
                return new EstadoDoDocker(Docker.OK, TextoSeguro.truncar(TextoSeguro.limparLinha(x.stdout().strip()), 30), "", desktop, colima);
            }
            String erro = (x.stderr() + " " + x.stdout()).toLowerCase(Locale.ROOT);
            String detalhe = TextoSeguro.truncar(TextoSeguro.limparLinha((x.stderr().isBlank() ? x.stdout() : x.stderr()).strip()), 200);
            if (erro.contains("permission denied")) {
                return new EstadoDoDocker(Docker.SEM_PERMISSAO, "", detalhe, desktop, colima);
            }
            if (erro.contains("cannot connect") || erro.contains("daemon running") || erro.contains("error during connect")
                || erro.contains("pipe") || erro.contains("docker daemon") || erro.contains("is the docker")) {
                return new EstadoDoDocker(Docker.PARADO, "", detalhe, desktop, colima);
            }
            return new EstadoDoDocker(Docker.ERRO, "", detalhe, desktop, colima);
        } catch (ProgramaNaoEncontradoException e) {
            // o Docker Desktop instalado mas sem o cliente no PATH conta como "instalado e parado": o botão Abrir resolve
            return new EstadoDoDocker(desktop ? Docker.PARADO : Docker.AUSENTE, "", "", desktop, colima);
        } catch (IOException e) {
            return new EstadoDoDocker(Docker.ERRO, "", TextoSeguro.truncar(TextoSeguro.limparLinha(String.valueOf(e.getMessage())), 200), desktop, colima);
        }
    }

    private ResultadoDoComputador docker(EstadoDoDocker d, SistemaOperacional so, boolean temBrew, boolean temWinget) {
        return switch (d.estado) {
            case OK -> ResultadoDoComputador.ok(ItemDoComputador.DOCKER, "Docker " + d.versao + " instalado e funcionando.");
            case AUSENTE -> ResultadoDoComputador.problema(ItemDoComputador.DOCKER,
                "O Docker não está instalado. Ele é o que roda o laboratório. O app não instala nada sozinho: escolha uma das opções abaixo e confirme.",
                null, PlanosDeInstalacao.docker(so, temBrew, temWinget));
            case PARADO -> new ResultadoDoComputador(ItemDoComputador.DOCKER, Estado.PROBLEMA, "O Docker está instalado, mas não está rodando. "
                + (plataforma.comandoParaAbrirODocker(d.desktopInstalado, d.colimaInstalado).isEmpty() ? plataforma.orientacaoParaAbrirODocker()
                    : "Clique em Abrir o Docker e espere ele ligar (a primeira vez demora)."),
                so == SistemaOperacional.LINUX ? "sudo systemctl start docker" : null, List.of(),
                !plataforma.comandoParaAbrirODocker(d.desktopInstalado, d.colimaInstalado).isEmpty());
            case SEM_PERMISSAO -> {
                String usuario = sonda.nomeDoUsuario();
                yield ResultadoDoComputador.problema(ItemDoComputador.DOCKER, "O Docker está instalado, mas este usuário não pode usá-lo (não está no "
                    + "grupo docker). O app não tenta elevar privilégio: rode o comando abaixo, saia e entre de novo no sistema.",
                    ValidadorDeNomes.usuarioSshValido(usuario) ? "sudo usermod -aG docker " + usuario : null, List.of());
            }
            case ERRO -> ResultadoDoComputador.problema(ItemDoComputador.DOCKER, "Não consegui falar com o Docker"
                + (d.detalhe.isBlank() ? "." : ": " + d.detalhe), null, List.of());
        };
    }

    private ResultadoDoComputador compose(EstadoDoDocker d) throws InterruptedException {
        if (d.estado != Docker.OK) {
            return ResultadoDoComputador.aviso(ItemDoComputador.COMPOSE, "Ainda não deu para conferir o Docker Compose (depende do Docker). O agente "
                + "não precisa dele; só os projetos que usam compose.", null, List.of());
        }
        try {
            ResultadoExec x = executor.executar(ComandosLocais.versaoDoComposeDoDocker(), LEITURA);
            if (x.status() == 0 && !x.stdout().isBlank()) {
                return ResultadoDoComputador.ok(ItemDoComputador.COMPOSE, "Docker Compose " + TextoSeguro.truncar(TextoSeguro.limparLinha(x.stdout().strip()), 30)
                    + " instalado.");
            }
        } catch (IOException e) {
            // cai no aviso
        }
        return ResultadoDoComputador.aviso(ItemDoComputador.COMPOSE, "O Docker Compose não respondeu. O agente não precisa dele; só os projetos que "
            + "usam compose.", null, List.of());
    }

    private ResultadoDoComputador disco(String pasta) {
        long livre = sonda.bytesLivres(pasta);
        if (livre < 0) {
            return ResultadoDoComputador.aviso(ItemDoComputador.DISCO, "Não consegui ver quanto espaço livre há no disco. O laboratório pede pelo menos "
                + "5 GB livres.", null, List.of());
        }
        if (livre < MINIMO_DE_DISCO_BYTES) {
            return ResultadoDoComputador.problema(ItemDoComputador.DISCO, "Só " + gigas(livre) + " livres neste disco, e o laboratório pede pelo menos 5 GB "
                + "(imagens do Docker e projetos). Libere espaço e clique em Verificar de novo.", null, List.of());
        }
        return ResultadoDoComputador.ok(ItemDoComputador.DISCO, gigas(livre) + " livres (o mínimo é 5 GB).");
    }

    private ResultadoDoComputador memoria() {
        long total = sonda.memoriaTotalBytes();
        if (total < 0) {
            return ResultadoDoComputador.aviso(ItemDoComputador.MEMORIA, "Não consegui ver a memória deste computador. O laboratório pede pelo menos 4 GB.",
                null, List.of());
        }
        if (total < MINIMO_DE_MEMORIA_BYTES) {
            return ResultadoDoComputador.problema(ItemDoComputador.MEMORIA, "Este computador tem " + gigas(total) + " de memória, e o laboratório pede "
                + "pelo menos 4 GB (o Docker usa uma parte).", null, List.of());
        }
        return ResultadoDoComputador.ok(ItemDoComputador.MEMORIA, gigas(total) + " de memória (o mínimo é 4 GB).");
    }

    /** Windows: virtualização ligada na BIOS e WSL 2. Só o código de saída do {@code wsl} vale (a saída dele é UTF-16). */
    private ResultadoDoComputador virtualizacao() throws InterruptedException {
        boolean wslOk = false;
        try {
            wslOk = executor.executar(ComandosLocais.estadoDoWsl(), LEITURA).status() == 0;
        } catch (IOException e) {
            // sem wsl: instalar
        }
        if (!wslOk) {
            return ResultadoDoComputador.problema(ItemDoComputador.VIRTUALIZACAO, "O WSL 2 não está instalado (o Docker Desktop precisa dele). O app não "
                + "o instala: exige administrador e reiniciar o computador. Rode o comando abaixo no PowerShell como administrador, reinicie e "
                + "clique em Verificar de novo.", "wsl --install", List.of(PlanosDeInstalacao.wsl()));
        }
        try {
            ResultadoExec x = executor.executar(ComandosLocais.virtualizacaoDoWindows(), LEITURA);
            if (x.status() == 0 && x.stdout().strip().equalsIgnoreCase("False")) {
                return ResultadoDoComputador.problema(ItemDoComputador.VIRTUALIZACAO, "O WSL 2 está instalado, mas a virtualização está desligada na BIOS "
                    + "do computador. Reinicie, entre na BIOS (a tecla aparece ao ligar) e ligue a virtualização (Intel VT-x ou AMD-V/SVM).", null, List.of());
            }
        } catch (IOException e) {
            // não deu para ler a virtualização: o WSL 2 respondeu, então segue
        }
        return ResultadoDoComputador.ok(ItemDoComputador.VIRTUALIZACAO, "WSL 2 instalado e virtualização ligada.");
    }

    private int portaSugerida(int pedida) {
        for (int i = 0; i < TENTATIVAS_DE_PORTA; i++) {
            int p = pedida + i;
            if (p > 65_535) {
                break;
            }
            if (sonda.portaLivre(p)) {
                return p;
            }
        }
        return 0;
    }

    private ResultadoDoComputador porta(int pedida, int sugerida) {
        if (sugerida == pedida) {
            return ResultadoDoComputador.ok(ItemDoComputador.PORTA, "A porta " + pedida + " está livre (o agente escuta só em 127.0.0.1, neste computador).");
        }
        String quem = plataforma.sistema() == SistemaOperacional.MAC && (pedida == 7000 || pedida == 5000)
            ? " (no macOS, o AirPlay Receiver usa as portas 5000 e 7000)" : "";
        return ResultadoDoComputador.problema(ItemDoComputador.PORTA, "A porta " + pedida + " já está em uso por outro programa" + quem + "."
            + (sugerida > 0 ? " A porta " + sugerida + " está livre: use-a nas opções avançadas." : " Escolha outra porta nas opções avançadas."), null, List.of());
    }

    private ResultadoDoComputador git(SistemaOperacional so, boolean temBrew, boolean temWinget) throws InterruptedException {
        // No Mac, /usr/bin/git sem as ferramentas da Apple é só um atalho que abre o aviso de instalação do sistema: o localizador nunca o devolve,
        // e sem um git de verdade (Homebrew, ferramentas da Apple, Xcode) a conferência nem tenta rodar o git.
        boolean podeRodar = so != SistemaOperacional.MAC || sonda.localizarPrograma("git").isPresent();
        try {
            if (!podeRodar) {
                throw new ProgramaNaoEncontradoException("git");
            }
            ResultadoExec x = executor.executar(ComandosLocais.versaoDoGit(), LEITURA);
            if (x.status() == 0) {
                return ResultadoDoComputador.ok(ItemDoComputador.GIT, TextoSeguro.truncar(TextoSeguro.limparLinha(x.stdout().strip()), 60) + " instalado.");
            }
        } catch (IOException e) {
            // não achou ou não rodou: aviso abaixo
        }
        return ResultadoDoComputador.aviso(ItemDoComputador.GIT, "O git não está instalado. Só é preciso para importar projetos (um pacote futuro): o "
            + "laboratório funciona sem ele.", null, PlanosDeInstalacao.git(so, temBrew, temWinget));
    }

    private static String gigas(long bytes) {
        double g = bytes / (1024.0 * 1024.0 * 1024.0);
        return g >= 10 ? String.format(Locale.forLanguageTag("pt-BR"), "%.0f GB", g) : String.format(Locale.forLanguageTag("pt-BR"), "%.1f GB", g);
    }
}
