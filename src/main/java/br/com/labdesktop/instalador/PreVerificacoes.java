package br.com.labdesktop.instalador;

import br.com.labdesktop.instalador.RelatorioDeVerificacao.Administrador;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * As verificações prévias da instalação do agente: SÓ LEITURA, por comandos fixos de {@link ComandosRemotos}, antes de qualquer
 * escrita. Cada uma vira uma linha com a marca (ok, aviso ou problema) e uma frase simples; o que o app não pode fazer sozinho
 * (dar permissão ao usuário, por exemplo) aparece com o comando EXATO que o usuário, ou quem administra o servidor, deve rodar. O app
 * nunca tenta elevar privilégio e nunca digita senha de sudo: {@code sudo -n} ou nada.
 *
 * <p>Verifica: sistema ({@code /etc/os-release}), arquitetura, permissão (root, grupo docker, {@code sudo -n}), shell e tmux (o terminal
 * não depende deles; o tmux só é preciso para sessões do Claude), Docker e o plugin compose, container já existente, porta livre,
 * espaço em disco (mínimo de 2 GB) e saída para a internet.
 */
public final class PreVerificacoes {

    /** Espaço livre mínimo (em KiB) na pasta pessoal e na do Docker: imagem base, build e dados do agente. */
    public static final long MINIMO_DE_DISCO_KIB = 2L * 1024 * 1024;

    private static final Duration PRAZO = Duration.ofSeconds(30);

    private final ExecutorDeComandos executor;
    private final ParametrosDaInstalacao parametros;
    private final PacoteDoAgente pacote;

    public PreVerificacoes(ExecutorDeComandos executor, ParametrosDaInstalacao parametros, PacoteDoAgente pacote) {
        this.executor = executor;
        this.parametros = parametros;
        this.pacote = pacote;
    }

    /**
     * Roda todas as verificações (só leitura) e devolve o relatório.
     *
     * @throws IOException          a conexão caiu (não é "problema": o app não sabe)
     * @throws InterruptedException cancelado
     */
    public RelatorioDeVerificacao executar(ModoDaInstalacao modo) throws IOException, InterruptedException {
        List<ResultadoDaVerificacao> r = new ArrayList<>();

        Map<String, String> os = chaveValor(rodar(ComandosRemotos.lerOsRelease()).stdout(), '=');
        String id = limparAspas(os.getOrDefault("ID", "")).toLowerCase();
        String nome = limparAspas(os.getOrDefault("PRETTY_NAME", id.isEmpty() ? "sistema desconhecido" : id));
        r.add(sistema(id, nome));

        r.add(arquitetura(rodar(ComandosRemotos.lerArquitetura()).stdout().strip()));

        Map<String, String> perm = chaveValor(rodar(ComandosRemotos.lerPermissoes()).stdout(), '=');
        String usuario = perm.getOrDefault("usuario", "");
        boolean root = "0".equals(perm.get("uid"));
        boolean sudo = "sim".equals(perm.get("sudo"));
        boolean noGrupoDocker = List.of(perm.getOrDefault("grupos", "").split("\\s+")).contains("docker");
        Administrador adm = root ? Administrador.ROOT : sudo ? Administrador.SUDO_SEM_SENHA : Administrador.NENHUM;

        Map<String, String> dk = chaveValor(rodar(ComandosRemotos.lerDocker()).stdout(), '=');
        String dockerEstado = dk.getOrDefault("docker", "erro");
        boolean dockerOk = dockerEstado.startsWith("ok:");
        boolean dockerComSudo = "so-com-sudo".equals(dockerEstado);
        boolean dockerPresente = dockerOk || dockerComSudo;
        String comandoDocker = dockerOk ? ComandosRemotos.DOCKER_DIRETO : dockerComSudo ? ComandosRemotos.DOCKER_COM_SUDO : null;
        boolean suportada = ComandosRemotos.distribuicaoSuportada(id);
        boolean vaiInstalar = !dockerPresente && "ausente".equals(dockerEstado) && suportada && adm != Administrador.NENHUM;

        r.add(permissao(usuario, adm, noGrupoDocker, dockerEstado));
        r.add(shell(perm.getOrDefault("shell", "")));
        r.add(tmux(perm.get("tmux")));
        r.add(docker(dockerEstado, dockerOk ? dockerEstado.substring(3) : "", vaiInstalar, suportada, nome, adm));
        r.add(compose(dk.get("compose"), dockerPresente));

        ContainerExistente existente = comandoDocker == null ? ContainerExistente.NENHUM : lerContainer(comandoDocker);
        r.add(container(modo, existente));
        r.add(pasta(rodar(ComandosRemotos.lerDonoDaPasta(parametros)).stdout().strip()));
        r.add(porta(modo, existente));
        r.add(disco());
        r.add(internet(modo));

        // A ordem da tela é a do enum Item: a lista já está nela, exceto onde a leitura exigiu antecipar o Docker.
        r.sort((a, b) -> Integer.compare(a.item().ordinal(), b.item().ordinal()));
        return new RelatorioDeVerificacao(r, modo, comandoDocker, adm, id, dockerPresente, "ausente".equals(dockerEstado));
    }

    // =====================================================================================================
    // Uma verificação de cada vez
    // =====================================================================================================

    private static ResultadoDaVerificacao sistema(String id, String nome) {
        String limpo = TextoSeguro.truncar(TextoSeguro.limparLinha(nome), 80);
        if (ComandosRemotos.distribuicaoSuportada(id)) {
            return ResultadoDaVerificacao.ok(Item.SISTEMA, limpo + ": o app sabe instalar o Docker aqui, se faltar.");
        }
        return ResultadoDaVerificacao.aviso(Item.SISTEMA, limpo + ": o app ainda não instala o Docker neste sistema (só em Debian e "
            + "Ubuntu). Se o Docker já estiver instalado, não faz diferença.", null);
    }

    private static ResultadoDaVerificacao arquitetura(String uname) {
        String a = TextoSeguro.truncar(TextoSeguro.limparLinha(uname), 30);
        return switch (uname) {
            case "x86_64", "amd64" -> ResultadoDaVerificacao.ok(Item.ARQUITETURA, "Processador x86_64 (Intel ou AMD de 64 bits).");
            case "aarch64", "arm64" -> ResultadoDaVerificacao.ok(Item.ARQUITETURA, "Processador ARM de 64 bits (aarch64).");
            default -> ResultadoDaVerificacao.problema(Item.ARQUITETURA, "Processador " + (a.isEmpty() ? "desconhecido" : a)
                + ": o agente só tem imagem para x86_64 e ARM de 64 bits.", null);
        };
    }

    private ResultadoDaVerificacao permissao(String usuario, Administrador adm, boolean noGrupoDocker, String dockerEstado) {
        String quem = usuarioDeTela(usuario);
        boolean usuarioValido = ValidadorDeNomes.usuarioSshValido(usuario);
        switch (dockerEstado.startsWith("ok:") ? "ok" : dockerEstado) {
            case "ok":
                if (adm == Administrador.ROOT) {
                    return ResultadoDaVerificacao.ok(Item.PERMISSAO, "Você entra como administrador (root) neste servidor.");
                }
                return ResultadoDaVerificacao.ok(Item.PERMISSAO, noGrupoDocker
                    ? "O usuário " + quem + " está no grupo docker e usa o Docker sem sudo."
                    : "O usuário " + quem + " consegue usar o Docker.");
            case "so-com-sudo":
                return ResultadoDaVerificacao.aviso(Item.PERMISSAO, "O usuário " + quem + " não está no grupo docker, então o app fala "
                    + "com o Docker por sudo sem senha. Para usar o Docker direto (e simplificar o terminal), quem administra o servidor "
                    + "pode rodar o comando abaixo; vale na próxima conexão.", usuarioValido ? "sudo usermod -aG docker " + usuario : null);
            case "sem-permissao":
                return ResultadoDaVerificacao.problema(Item.PERMISSAO, "O usuário " + quem + " não pode usar o Docker neste servidor: "
                    + "não está no grupo docker e não tem sudo sem senha. O app não tenta elevar privilégio sozinho. Peça a quem "
                    + "administra o servidor para rodar o comando abaixo e depois conecte de novo.",
                    usuarioValido ? "sudo usermod -aG docker " + usuario : null);
            case "ausente":
                if (adm == Administrador.NENHUM) {
                    return ResultadoDaVerificacao.problema(Item.PERMISSAO, "O usuário " + quem + " não é administrador e não tem sudo sem "
                        + "senha, então o app não consegue instalar o Docker. Peça a quem administra o servidor para instalar o Docker "
                        + "(e depois clique em Já instalei, continuar), ou para liberar sudo sem senha a este usuário com o comando abaixo.",
                        usuarioValido ? "echo \"" + usuario + " ALL=(ALL) NOPASSWD:ALL\" | sudo tee /etc/sudoers.d/90-lab-desktop" : null);
                }
                return ResultadoDaVerificacao.ok(Item.PERMISSAO, adm == Administrador.ROOT
                    ? "Você entra como administrador (root): o app pode instalar o Docker."
                    : "O usuário " + quem + " tem sudo sem senha: o app pode instalar o Docker.");
            default:
                return ResultadoDaVerificacao.ok(Item.PERMISSAO, adm == Administrador.NENHUM
                    ? "O usuário " + quem + " não é administrador."
                    : "O usuário " + quem + (adm == Administrador.ROOT ? " é administrador (root)." : " tem sudo sem senha."));
        }
    }

    private static ResultadoDaVerificacao shell(String shell) {
        String base = shell.isBlank() ? "" : shell.substring(shell.lastIndexOf('/') + 1);
        if ("bash".equals(base)) {
            return ResultadoDaVerificacao.ok(Item.SHELL, "O shell do usuário é o bash.");
        }
        if (base.isEmpty()) {
            return ResultadoDaVerificacao.aviso(Item.SHELL, "Não consegui ver qual é o shell do usuário. O terminal abre o shell normal "
                + "dele; a instalação não depende disso.", null);
        }
        return ResultadoDaVerificacao.aviso(Item.SHELL, "O shell do usuário é o " + TextoSeguro.truncar(TextoSeguro.limparLinha(base), 30)
            + ", não o bash. O terminal abre esse shell normalmente e a instalação não depende disso.", null);
    }

    private static ResultadoDaVerificacao tmux(String tmux) {
        if ("sim".equals(tmux)) {
            return ResultadoDaVerificacao.ok(Item.TMUX, "O tmux está instalado (só é preciso para sessões do Claude).");
        }
        return ResultadoDaVerificacao.aviso(Item.TMUX, "O tmux não está instalado. Só é preciso para sessões do Claude: o agente de "
            + "monitoramento e o terminal funcionam sem ele.", null);
    }

    private static ResultadoDaVerificacao docker(String estado, String versao, boolean vaiInstalar, boolean suportada, String nomeDoSistema,
                                                 Administrador adm) {
        String v = TextoSeguro.truncar(TextoSeguro.limparLinha(versao), 30);
        if (estado.startsWith("ok:")) {
            return ResultadoDaVerificacao.ok(Item.DOCKER, "Docker " + v + " instalado e funcionando.");
        }
        return switch (estado) {
            case "so-com-sudo" -> ResultadoDaVerificacao.ok(Item.DOCKER, "Docker instalado e funcionando (por sudo sem senha).");
            case "ausente" -> {
                if (vaiInstalar) {
                    yield ResultadoDaVerificacao.aviso(Item.DOCKER, "O Docker não está instalado. O app pode instalá-lo, pelo repositório "
                        + "oficial da Docker: será o primeiro passo, depois do seu clique.", null);
                }
                if (!suportada) {
                    yield ResultadoDaVerificacao.problema(Item.DOCKER, "O Docker não está instalado e o app ainda não o instala em "
                        + TextoSeguro.truncar(TextoSeguro.limparLinha(nomeDoSistema), 60) + " (só em Debian e Ubuntu). Instale o Docker "
                        + "nele (docs.docker.com/engine/install) e depois clique em Já instalei, continuar.", null);
                }
                yield ResultadoDaVerificacao.problema(Item.DOCKER, "O Docker não está instalado e o app não pode instalá-lo sem "
                    + "administrador (veja Permissão do usuário).", null);
            }
            case "sem-permissao" -> ResultadoDaVerificacao.problema(Item.DOCKER, "O Docker está instalado, mas este usuário não pode "
                + "usá-lo (veja Permissão do usuário).", null);
            case "parado" -> ResultadoDaVerificacao.problema(Item.DOCKER, "O Docker está instalado, mas parado. Quem administra o servidor "
                + "precisa ligá-lo com o comando abaixo.", "sudo systemctl start docker");
            default -> ResultadoDaVerificacao.problema(Item.DOCKER, "Não consegui falar com o Docker deste servidor.", null);
        };
    }

    private static ResultadoDaVerificacao compose(String compose, boolean dockerPresente) {
        if (compose == null || !dockerPresente) {
            return ResultadoDaVerificacao.aviso(Item.COMPOSE, "Ainda não deu para conferir o plugin do Docker Compose (depende do Docker). "
                + "O agente não precisa dele; só os projetos que usam compose.", null);
        }
        if ("ausente".equals(compose) || compose.isBlank()) {
            return ResultadoDaVerificacao.aviso(Item.COMPOSE, "O plugin do Docker Compose não está instalado. O agente não precisa dele; só "
                + "os projetos que usam compose.", null);
        }
        return ResultadoDaVerificacao.ok(Item.COMPOSE, "Docker Compose " + TextoSeguro.truncar(TextoSeguro.limparLinha(compose), 30) + " instalado.");
    }

    /** O que o servidor já tem com o nome do container. */
    private record ContainerExistente(boolean existe, String versaoDoApp, String estado) {
        static final ContainerExistente NENHUM = new ContainerExistente(false, "", "");

        boolean doApp() {
            return existe && !versaoDoApp.isEmpty();
        }
    }

    private ContainerExistente lerContainer(String docker) throws IOException, InterruptedException {
        ResultadoExec x = rodar(ComandosRemotos.lerContainer(docker, parametros));
        for (String linha : x.stdout().split("\\R")) {
            String[] p = linha.strip().split("\\|", -1);
            if (p.length >= 3 && p[0].equals(parametros.container())) {
                return new ContainerExistente(true, p[1].strip(), p[2].strip());
            }
        }
        return ContainerExistente.NENHUM;
    }

    private ResultadoDaVerificacao container(ModoDaInstalacao modo, ContainerExistente c) {
        String nome = parametros.container();
        if (modo == ModoDaInstalacao.ATUALIZAR) {
            if (!c.existe()) {
                return ResultadoDaVerificacao.problema(Item.CONTAINER, "Não há um agente chamado " + nome + " neste servidor para atualizar.", null);
            }
            if (!c.doApp()) {
                return ResultadoDaVerificacao.problema(Item.CONTAINER, "O container " + nome + " não foi criado por este app, então o app "
                    + "não o atualiza (ele não mexe no que não instalou).", null);
            }
            String de = TextoSeguro.truncar(TextoSeguro.limparLinha(c.versaoDoApp()), 40);
            return ResultadoDaVerificacao.ok(Item.CONTAINER, "O agente instalado pelo app está na versão " + de + "; vai para a " + pacote.versao()
                + ", mantendo os dados e o token.");
        }
        if (!c.existe()) {
            return ResultadoDaVerificacao.ok(Item.CONTAINER, "Não há nenhum container chamado " + nome + ": nada será substituído.");
        }
        if (!c.doApp()) {
            return ResultadoDaVerificacao.problema(Item.CONTAINER, "Já existe um container chamado " + nome + " que NÃO foi criado por este app "
                + "(pode ser o agente de outro painel). O app não mexe nele. Para testar ao lado, use outro nome nas opções avançadas.", null);
        }
        return ResultadoDaVerificacao.aviso(Item.CONTAINER, "Já existe um agente deste app (versão " + TextoSeguro.truncar(TextoSeguro.limparLinha(c.versaoDoApp()), 40)
            + ") chamado " + nome + ". Ele será substituído, mantendo os dados.", null);
    }

    private ResultadoDaVerificacao pasta(String situacao) {
        String pasta = "~/" + parametros.pasta();
        return switch (situacao) {
            case "alheia" -> ResultadoDaVerificacao.problema(Item.PASTA, "A pasta " + pasta + " já existe no servidor e NÃO foi criada por este app. "
                + "O app não mexe no que não criou: escolha outro nome para a pasta nas opções avançadas, ou renomeie essa pasta.", null);
            case "app" -> ResultadoDaVerificacao.ok(Item.PASTA, "A pasta " + pasta + " é do app (de uma instalação anterior): será reaproveitada.");
            default -> ResultadoDaVerificacao.ok(Item.PASTA, "A pasta " + pasta + " será criada.");
        };
    }

    private ResultadoDaVerificacao porta(ModoDaInstalacao modo, ContainerExistente c) throws IOException, InterruptedException {
        int porta = parametros.porta();
        if (modo == ModoDaInstalacao.ATUALIZAR) {
            return ResultadoDaVerificacao.ok(Item.PORTA, "A porta " + porta + " é a do agente atual, que será substituído.");
        }
        ResultadoExec x = rodar(ComandosRemotos.lerPorta(porta));
        String saida = x.stdout().strip();
        if (saida.isEmpty() && x.status() != 0) {
            return ResultadoDaVerificacao.aviso(Item.PORTA, "Não consegui conferir se a porta " + porta + " está livre (o comando ss não "
                + "respondeu). Se estiver ocupada, o agente não sobe e o app mostra o erro.", null);
        }
        if (saida.isEmpty()) {
            return ResultadoDaVerificacao.ok(Item.PORTA, "A porta " + porta + " do servidor está livre (o agente escuta só em 127.0.0.1).");
        }
        if (c.doApp()) {
            return ResultadoDaVerificacao.ok(Item.PORTA, "A porta " + porta + " está em uso pelo agente deste app, que será substituído.");
        }
        return ResultadoDaVerificacao.problema(Item.PORTA, "A porta " + porta + " do servidor já está em uso por outro programa. Escolha "
            + "outra porta (a do painel, ao editar o servidor) ou libere esta.", null);
    }

    private ResultadoDaVerificacao disco() throws IOException, InterruptedException {
        ResultadoExec x = rodar(ComandosRemotos.lerDisco());
        long menor = Long.MAX_VALUE;
        for (String linha : x.stdout().split("\\R")) {
            String[] campos = linha.strip().split("\\s+");
            if (campos.length >= 6 && campos[0].length() > 0 && !"Filesystem".equals(campos[0])) {
                try {
                    menor = Math.min(menor, Long.parseLong(campos[3]));
                } catch (NumberFormatException e) {
                    // linha que não é de dados
                }
            }
        }
        if (menor == Long.MAX_VALUE) {
            return ResultadoDaVerificacao.aviso(Item.DISCO, "Não consegui ver quanto espaço livre há no servidor. O agente e a imagem ocupam "
                + "cerca de 300 MB; o app pede pelo menos 2 GB livres.", null);
        }
        String livre = gigas(menor);
        if (menor < MINIMO_DE_DISCO_KIB) {
            return ResultadoDaVerificacao.problema(Item.DISCO, "Só " + livre + " livres no servidor, e o app pede pelo menos 2 GB para "
                + "baixar a imagem base e construir o agente. Libere espaço e tente de novo.", null);
        }
        return ResultadoDaVerificacao.ok(Item.DISCO, livre + " livres (o mínimo é 2 GB).");
    }

    private ResultadoDaVerificacao internet(ModoDaInstalacao modo) throws IOException, InterruptedException {
        String saida = rodar(ComandosRemotos.lerInternet()).stdout().strip();
        if ("000".equals(saida)) {
            String frase = "O servidor não consegue acessar a internet (Docker Hub), que é de onde vem a imagem base do agente.";
            return modo == ModoDaInstalacao.ATUALIZAR
                ? ResultadoDaVerificacao.aviso(Item.INTERNET, frase + " Na atualização a imagem base já está no servidor, então pode dar certo.", null)
                : ResultadoDaVerificacao.problema(Item.INTERNET, frase + " Conecte-o à internet e tente de novo.", null);
        }
        if (saida.matches("^[1-5][0-9]{2}$")) {
            return ResultadoDaVerificacao.ok(Item.INTERNET, "O servidor acessa a internet (Docker Hub respondeu).");
        }
        return ResultadoDaVerificacao.aviso(Item.INTERNET, "Não consegui testar a saída para a internet (nem curl nem wget no servidor). "
            + "Se faltar, o passo de construir a imagem mostra o erro.", null);
    }

    // =====================================================================================================
    // Apoio
    // =====================================================================================================

    private ResultadoExec rodar(String comando) throws IOException, InterruptedException {
        return executor.executar(comando, new byte[0], PRAZO);
    }

    /** O nome do usuário para mostrar: só o que o regex de usuário aceita (vem do servidor). */
    private static String usuarioDeTela(String usuario) {
        return ValidadorDeNomes.usuarioSshValido(usuario) ? usuario : "do servidor";
    }

    static Map<String, String> chaveValor(String texto, char separador) {
        Map<String, String> m = new HashMap<>();
        for (String linha : texto.split("\\R")) {
            int i = linha.indexOf(separador);
            if (i > 0) {
                m.put(linha.substring(0, i).strip(), linha.substring(i + 1).strip());
            }
        }
        return m;
    }

    private static String limparAspas(String v) {
        String s = v.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String gigas(long kib) {
        double g = kib / (1024.0 * 1024.0);
        return g >= 10 ? String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.0f GB", g)
            : String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f GB", g);
    }
}
