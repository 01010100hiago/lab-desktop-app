package br.com.labdesktop.teste;

import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.ExecutorDeComandos;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Um servidor Linux de mentira para o instalador do agente: responde, com os MESMOS comandos fixos de {@link ComandosRemotos}, o que
 * um Debian, um Ubuntu ou outro sistema diriam, e MUDA de estado quando um comando de escrita roda (instalar o Docker, criar a pasta,
 * construir a imagem, subir ou remover o container...). Assim a mesma classe serve às verificações, à sequência exata de comandos, à
 * idempotência (rodar duas vezes), às falhas com desfazer parcial, ao cancelamento e à retomada, sem SSH.
 *
 * <p>É também um {@link ExecutorDeComandos} e, para os testes com o {@code SshServer} embutido, um manipulador de comando remoto
 * ({@link #comoManipulador()}), com a pasta pessoal numa pasta temporária para o envio por SFTP cair num arquivo de verdade.
 *
 * <p>Qualquer comando que o simulador não conhece faz o teste FALHAR (é a prova de que o instalador só roda os comandos fixos).
 */
public final class ServidorSimulado implements ExecutorDeComandos {

    // ---- como o servidor é (os testes mudam antes de rodar) ----
    public String osRelease = "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nNAME=\"Debian GNU/Linux\"\nVERSION_ID=\"13\"\nID=debian\n";
    public String arquitetura = "x86_64";
    public String usuario = "labuser";
    public boolean root;
    public boolean sudoSemSenha = true;
    public boolean noGrupoDocker;
    public String shell = "/bin/bash";
    public boolean tmux = true;
    public boolean dockerInstalado = true;
    public boolean dockerParado;
    public boolean composeInstalado = true;
    public long discoLivreKib = 40L * 1024 * 1024;
    public String internet = "401";
    public boolean ssDisponivel = true;
    public boolean portaOcupada;

    // ---- o que existe (mudam quando os comandos de escrita rodam) ----
    public boolean pastaExiste;
    public String versaoExtraida;
    public boolean imagemExiste;
    /** {@code null}: nenhum container com o nome. */
    public String containerNome;
    public String rotuloDoContainer = "";
    public String estadoDoContainer = "running";
    /** O agente antigo guardado como {@code <container>-anterior} durante uma atualização (null = não há). */
    public String anteriorNome;
    public String anteriorRotulo = "";
    public String anteriorEstado = "exited";
    public boolean volumeExiste;
    public byte[] pacoteRecebido;
    /** O que o {@code docker logs} do container responde. */
    public String logsDoContainer = "node: tudo certo, escutando em 127.0.0.1:7000";

    // ---- o que aconteceu ----
    public final List<String> comandos = new CopyOnWriteArrayList<>();
    public final List<String> envios = new CopyOnWriteArrayList<>();
    private final List<Falha> falhas = new ArrayList<>();
    private final List<Gancho> ganchos = new ArrayList<>();
    private int dockerInstaladoPelosComandos;
    private final Path pastaPessoal;

    private final ParametrosDaInstalacao parametros;
    private final PacoteDoAgente pacote;

    private record Falha(Predicate<String> quando, int status, String stderr, boolean umaVez, boolean[] jaFalhou) { }

    private record Gancho(Predicate<String> quando, Runnable acao) { }

    public ServidorSimulado(ParametrosDaInstalacao parametros, PacoteDoAgente pacote, Path pastaPessoal) {
        this.parametros = parametros;
        this.pacote = pacote;
        this.pastaPessoal = pastaPessoal;
    }

    public ServidorSimulado(ParametrosDaInstalacao parametros, PacoteDoAgente pacote) {
        this(parametros, pacote, null);
    }

    // ---------------------------------------------------------------------------------------------------
    // Perfis prontos
    // ---------------------------------------------------------------------------------------------------

    public ServidorSimulado ubuntu() {
        osRelease = "PRETTY_NAME=\"Ubuntu 24.04.1 LTS\"\nNAME=\"Ubuntu\"\nVERSION_ID=\"24.04\"\nID=ubuntu\n";
        return this;
    }

    public ServidorSimulado fedora() {
        osRelease = "PRETTY_NAME=\"Fedora Linux 41 (Server Edition)\"\nNAME=\"Fedora Linux\"\nID=fedora\n";
        return this;
    }

    public ServidorSimulado semDocker() {
        dockerInstalado = false;
        return this;
    }

    public ServidorSimulado comoRoot() {
        root = true;
        usuario = "root";
        sudoSemSenha = false;
        return this;
    }

    public ServidorSimulado semPermissaoNenhuma() {
        root = false;
        sudoSemSenha = false;
        noGrupoDocker = false;
        return this;
    }

    // ---------------------------------------------------------------------------------------------------
    // Falhas e ganchos programados
    // ---------------------------------------------------------------------------------------------------

    /** Todo comando que passar por {@code quando} falha com este status e esta mensagem no stderr. */
    public ServidorSimulado falharEm(Predicate<String> quando, int status, String stderr) {
        falhas.add(new Falha(quando, status, stderr, false, new boolean[1]));
        return this;
    }

    /** Só a PRIMEIRA vez que um comando passar por {@code quando} falha (a segunda tentativa funciona). */
    public ServidorSimulado falharUmaVezEm(Predicate<String> quando, int status, String stderr) {
        falhas.add(new Falha(quando, status, stderr, true, new boolean[1]));
        return this;
    }

    /** Roda {@code acao} antes de responder a um comando (por exemplo, apertar Cancelar no meio). */
    public ServidorSimulado antesDe(Predicate<String> quando, Runnable acao) {
        ganchos.add(new Gancho(quando, acao));
        return this;
    }

    /** Remove as falhas programadas (o usuário "consertou" o servidor). */
    public ServidorSimulado consertar() {
        falhas.clear();
        return this;
    }

    public void limparRegistro() {
        comandos.clear();
        envios.clear();
    }

    /** Só os comandos de ESCRITA que rodaram (a fronteira é {@link ComandosRemotos#ehLeitura}). */
    public List<String> escritas() {
        return comandos.stream().filter(c -> !ComandosRemotos.ehLeitura(c)).toList();
    }

    // ---------------------------------------------------------------------------------------------------
    // ExecutorDeComandos
    // ---------------------------------------------------------------------------------------------------

    @Override
    public synchronized ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("interrompido");
        }
        comandos.add(comando);
        for (Gancho g : ganchos) {
            if (g.quando().test(comando)) {
                g.acao().run();
            }
        }
        for (Falha f : falhas) {
            if (f.quando().test(comando) && !(f.umaVez() && f.jaFalhou()[0])) {
                f.jaFalhou()[0] = true;
                return new ResultadoExec(f.status(), new byte[0], f.stderr().getBytes(StandardCharsets.UTF_8), false);
            }
        }
        return responder(comando);
    }

    @Override
    public synchronized void enviarArquivo(String caminho, byte[] conteudo) throws IOException {
        envios.add(caminho);
        for (Falha f : falhas) {
            if (f.quando().test("envio:" + caminho) && !(f.umaVez() && f.jaFalhou()[0])) {
                f.jaFalhou()[0] = true;
                throw new IOException(f.stderr());
            }
        }
        if (!pastaExiste) {
            throw new IOException("No such file or directory: " + caminho);
        }
        pacoteRecebido = conteudo.clone();
        if (pastaPessoal != null) {
            Path destino = pastaPessoal.resolve(caminho);
            Files.createDirectories(destino.getParent());
            Files.write(destino, conteudo);
        }
    }

    /** Uma troca no meio do envio (SFTP do {@code SshServer}): o arquivo caiu em {@code pastaPessoal}. */
    public synchronized void aoReceberPorSftp() throws IOException {
        Path arquivo = pastaPessoal.resolve(ComandosRemotos.caminhoDoPacote(parametros, pacote));
        pacoteRecebido = Files.readAllBytes(arquivo);
    }

    // ---------------------------------------------------------------------------------------------------
    // O servidor
    // ---------------------------------------------------------------------------------------------------

    private ResultadoExec ok(String stdout) {
        return new ResultadoExec(0, stdout.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    private ResultadoExec status(int status, String stdout, String stderr) {
        return new ResultadoExec(status, stdout.getBytes(StandardCharsets.UTF_8), stderr.getBytes(StandardCharsets.UTF_8), false);
    }

    private boolean dockerDireto() {
        return dockerInstalado && !dockerParado && (root || noGrupoDocker);
    }

    private boolean dockerPorSudo() {
        return dockerInstalado && !dockerParado && !dockerDireto() && (sudoSemSenha || root);
    }

    private ResultadoExec responder(String c) {
        if (c.equals(ComandosRemotos.lerOsRelease())) {
            return ok(osRelease);
        }
        if (c.equals(ComandosRemotos.lerArquitetura())) {
            return ok(arquitetura + "\n");
        }
        if (c.equals(ComandosRemotos.lerPermissoes())) {
            return ok("uid=" + (root ? 0 : 1000) + "\nusuario=" + usuario + "\ngrupos=" + usuario + (noGrupoDocker ? " docker" : "")
                + "\nsudo=" + (sudoSemSenha || root ? "sim" : "nao") + "\nshell=" + shell + "\ntmux=" + (tmux ? "sim" : "nao") + "\n");
        }
        if (c.equals(ComandosRemotos.lerDocker())) {
            return ok(estadoDoDocker());
        }
        for (String dk : new String[] {ComandosRemotos.DOCKER_DIRETO, ComandosRemotos.DOCKER_COM_SUDO}) {
            ResultadoExec r = responderDocker(c, dk);
            if (r != null) {
                return r;
            }
        }
        if (c.equals(ComandosRemotos.lerPorta(parametros.porta()))) {
            if (!ssDisponivel) {
                return status(127, "", "ss: command not found");
            }
            return ok(portaOcupada ? "LISTEN 0 4096 127.0.0.1:" + parametros.porta() + " 0.0.0.0:*\n" : "");
        }
        if (c.equals(ComandosRemotos.lerDisco())) {
            return status(1, "Filesystem     1024-blocks      Used Available Capacity Mounted on\n/dev/sda1         100000000  50000000 " + discoLivreKib
                + "      50% /\n", "df: /var/lib/docker: No such file or directory");
        }
        if (c.equals(ComandosRemotos.lerInternet())) {
            return ok(internet);
        }
        if (c.equals(ComandosRemotos.lerSeAPastaExiste(parametros))) {
            return ok(pastaExiste ? "existe\n" : "nao\n");
        }
        if (c.equals(ComandosRemotos.lerVersaoExtraida(parametros))) {
            return versaoExtraida == null ? status(1, "", "cat: No such file") : ok(versaoExtraida + "\n");
        }
        if (c.equals(ComandosRemotos.lerSha256DoPacote(parametros, pacote))) {
            byte[] recebido = recebido();
            return recebido == null ? status(1, "", "sha256sum: No such file")
                : ok(PacoteDoAgente.sha256De(recebido) + "  /home/" + usuario + "/" + ComandosRemotos.caminhoDoPacote(parametros, pacote) + "\n");
        }
        if (c.equals(ComandosRemotos.criarPasta(parametros))) {
            pastaExiste = true;
            if (pastaPessoal != null) {
                try {
                    Files.createDirectories(pastaPessoal.resolve(parametros.pasta()).resolve("src"));
                } catch (IOException e) {
                    return status(1, "", e.toString());
                }
            }
            return ok("");
        }
        if (c.equals(ComandosRemotos.extrairPacote(parametros, pacote))) {
            if (recebido() == null) {
                return status(1, "", "tar: arquivo não encontrado");
            }
            versaoExtraida = pacote.versao();
            return ok("");
        }
        if (c.equals(ComandosRemotos.removerPasta(parametros))) {
            pastaExiste = false;
            versaoExtraida = null;
            pacoteRecebido = null;
            if (pastaPessoal != null) {
                apagar(pastaPessoal.resolve(parametros.pasta()));
            }
            return ok("");
        }
        if (c.equals(ComandosRemotos.lerSituacaoDoAgente(parametros.container()))) {
            return ok(!dockerInstalado ? "sem-docker\n" : containerNome == null ? "ausente\n" : "existe:" + estadoDoContainer + "\n");
        }
        // instalar o Docker: cada comando da lista conta; o último deixa o Docker pronto
        for (boolean comoRoot : new boolean[] {true, false}) {
            for (String distro : new String[] {"debian", "ubuntu"}) {
                List<String> lista = ComandosRemotos.instalarODocker(comoRoot, distro);
                int i = lista.indexOf(c);
                if (i >= 0) {
                    dockerInstaladoPelosComandos = i + 1;
                    if (i == lista.size() - 1) {
                        dockerInstalado = true;
                        dockerParado = false;
                    }
                    return ok("");
                }
            }
        }
        throw new AssertionError("Comando que o instalador não devia rodar (ou que o simulador não conhece): " + c);
    }

    /** O pacote que chegou: pela chamada direta ou, nos testes com o {@code SshServer}, pelo SFTP (o arquivo na pasta pessoal). */
    private byte[] recebido() {
        if (pacoteRecebido != null) {
            return pacoteRecebido;
        }
        if (pastaPessoal != null) {
            Path arquivo = pastaPessoal.resolve(ComandosRemotos.caminhoDoPacote(parametros, pacote));
            try {
                return Files.exists(arquivo) ? Files.readAllBytes(arquivo) : null;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static void apagar(Path pasta) {
        if (!Files.exists(pasta)) {
            return;
        }
        try (var s = Files.walk(pasta)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Quantos comandos da instalação do Docker já rodaram (para os testes). */
    public int comandosDeInstalacaoDoDocker() {
        return dockerInstaladoPelosComandos;
    }

    private String estadoDoDocker() {
        if (!dockerInstalado) {
            return "docker=ausente\n";
        }
        String compose = composeInstalado ? "compose=2.29.7\n" : "compose=ausente\n";
        if (dockerParado) {
            return "docker=parado\n" + compose;
        }
        if (dockerDireto()) {
            return "docker=ok:27.3.1\n" + compose;
        }
        return (dockerPorSudo() ? "docker=so-com-sudo\n" : "docker=sem-permissao\n") + compose;
    }

    /** Um comando do Docker (com o prefixo dado); {@code null} se {@code c} não é de Docker com este prefixo. */
    private ResultadoExec responderDocker(String c, String dk) {
        boolean permitido = dk.equals(ComandosRemotos.DOCKER_DIRETO) ? dockerDireto() : dockerPorSudo() || (root && dockerInstalado && !dockerParado);
        String imagem = parametros.imagem(pacote.versao());
        if (c.equals(ComandosRemotos.lerContainer(dk, parametros))) {
            return docker(permitido, (containerNome == null ? "" : containerNome + "|" + rotuloDoContainer + "|" + estadoDoContainer + "\n")
                + (anteriorNome == null ? "" : anteriorNome + "|" + anteriorRotulo + "|" + anteriorEstado + "\n"));
        }
        String ant = parametros.containerAnterior();
        if (c.equals(ComandosRemotos.renomearContainer(dk, parametros.container(), ant))) {
            if (!permitido || containerNome == null || anteriorNome != null) {
                return status(1, "", "Error response from daemon: cannot rename");
            }
            anteriorNome = ant;
            anteriorRotulo = rotuloDoContainer;
            anteriorEstado = estadoDoContainer;
            containerNome = null;
            rotuloDoContainer = "";
            return ok("");
        }
        if (c.equals(ComandosRemotos.renomearContainer(dk, ant, parametros.container()))) {
            if (!permitido || anteriorNome == null || containerNome != null) {
                return status(1, "", "Error response from daemon: cannot rename");
            }
            containerNome = parametros.container();
            rotuloDoContainer = anteriorRotulo;
            estadoDoContainer = anteriorEstado;
            anteriorNome = null;
            anteriorRotulo = "";
            return ok("");
        }
        if (c.equals(ComandosRemotos.pararContainer(dk, ant))) {
            if (!permitido || anteriorNome == null) {
                return status(1, "", "Error: No such container");
            }
            anteriorEstado = "exited";
            return ok(ant + "\n");
        }
        if (c.equals(ComandosRemotos.iniciarContainer(dk, parametros.container()))) {
            if (!permitido || containerNome == null) {
                return status(1, "", "Error: No such container");
            }
            estadoDoContainer = "running";
            return ok(parametros.container() + "\n");
        }
        if (c.equals(ComandosRemotos.removerContainerPeloNome(dk, ant))) {
            if (!permitido || anteriorNome == null) {
                return status(1, "", "Error: No such container: " + ant);
            }
            anteriorNome = null;
            anteriorRotulo = "";
            return ok(ant + "\n");
        }
        if (c.equals(ComandosRemotos.lerVolume(dk, parametros))) {
            return docker(permitido, volumeExiste ? parametros.volume() + "\n" : "");
        }
        if (c.equals(ComandosRemotos.lerImagem(dk, imagem))) {
            return permitido && imagemExiste ? ok("sha256:abc\n") : status(1, "", "Error: No such image");
        }
        if (c.equals(ComandosRemotos.construirImagem(dk, parametros, pacote))) {
            if (!permitido) {
                return status(1, "", "permission denied");
            }
            if (versaoExtraida == null) {
                return status(1, "", "unable to prepare context: path not found");
            }
            imagemExiste = true;
            return ok("Successfully built\n");
        }
        if (c.equals(ComandosRemotos.removerContainer(dk, parametros))) {
            if (!permitido || containerNome == null) {
                return status(1, "", "Error: No such container: " + parametros.container());
            }
            containerNome = null;
            rotuloDoContainer = "";
            return ok(parametros.container() + "\n");
        }
        if (c.equals(ComandosRemotos.subirContainer(dk, parametros, pacote))) {
            if (!permitido) {
                return status(1, "", "permission denied");
            }
            if (!imagemExiste) {
                return status(125, "", "Unable to find image locally");
            }
            if (containerNome != null) {
                return status(125, "", "Conflict. The container name is already in use");
            }
            containerNome = parametros.container();
            rotuloDoContainer = pacote.versao();
            estadoDoContainer = "running";
            volumeExiste = true;
            return ok("a1b2c3d4e5f6\n");
        }
        if (c.equals(ComandosRemotos.removerVolume(dk, parametros))) {
            if (!permitido || !volumeExiste) {
                return status(1, "", "Error: No such volume");
            }
            volumeExiste = false;
            return ok(parametros.volume() + "\n");
        }
        if (c.equals(ComandosRemotos.removerImagem(dk, imagem))) {
            if (!permitido || !imagemExiste) {
                return status(1, "", "Error: No such image");
            }
            imagemExiste = false;
            return ok("Untagged\n");
        }
        if (c.equals(ComandosRemotos.lerLogsDoContainer(dk, parametros))) {
            return ok(logsDoContainer + "\n");
        }
        return null;
    }

    private ResultadoExec docker(boolean permitido, String saida) {
        return permitido ? ok(saida) : status(1, "", "permission denied while trying to connect to the Docker daemon socket");
    }

    // ---------------------------------------------------------------------------------------------------
    // Para o SshServer embutido
    // ---------------------------------------------------------------------------------------------------

    /** O servidor como comando remoto do {@code ServidorSshDeTeste}: a linha vira {@link #executar} e a saída vai para stdout/stderr. */
    public ServidorSshDeTeste.Manipulador comoManipulador() {
        return comoManipulador((linha, stdin, saida) -> 127);
    }

    /**
     * Como {@link #comoManipulador()}, com um manipulador "à frente" que responde primeiro as linhas que não são do instalador (por
     * exemplo os comandos do agente por SSH do {@link AgenteDoLab}); o resto vai para o simulador.
     */
    public ServidorSshDeTeste.Manipulador comoManipulador(ServidorSshDeTeste.Manipulador aFrente) {
        BiFunction<String, StringBuilder, Integer> sim = (linha, saida) -> {
            try {
                ResultadoExec r = executar(linha, new byte[0], Duration.ofSeconds(5));
                saida.append(r.stdout());
                if (!r.stderr().isEmpty()) {
                    for (String l : r.stderr().split("\\R")) {
                        saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append(l).append('\n');
                    }
                }
                return r.status();
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        return (linha, stdin, saida) -> {
            if (linha.startsWith("docker exec -i ") || linha.startsWith("sudo -n docker exec -i ")) {
                return aFrente.executar(linha, stdin, saida);
            }
            return sim.apply(linha, saida);
        };
    }
}
