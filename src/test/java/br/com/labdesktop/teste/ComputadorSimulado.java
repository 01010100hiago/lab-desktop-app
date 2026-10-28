package br.com.labdesktop.teste;

import br.com.labdesktop.local.ComandosLocais;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Um computador de mentira para os testes do laboratório local: responde, por roteiro, os comandos de {@link ComandosLocais} e os de instalação
 * (Homebrew, winget, abrir o Docker), guarda o estado do Docker (containers, redes, volumes, imagens) e anota TUDO o que foi rodado, para os
 * testes provarem a ordem, a fronteira leitura x escrita e que nada rodou antes do clique.
 */
public final class ComputadorSimulado implements ExecutorDeProcesso {

    /** Um container simulado. */
    public static final class Cont {
        /** O rótulo do agente do app (a versão do pacote); vazio se não foi o app. */
        public String rotulo = "";
        /** O rótulo do laboratório local ({@code br.com.labdesktop.local=1}); vazio se não foi ele. */
        public String rotuloLocal = "";
        /** A marca da pasta dos projetos (rótulo {@code br.com.labdesktop.pasta}). */
        public String marcaDaPasta = "";
        /** As portas como o {@code docker ps} as mostra; se vazio, saem de {@link #portaPublicada}. */
        public String portas = "";
        public String estado = "running";
        public int portaPublicada;
        public String imagem;
        public List<String> argumentos = List.of();
    }

    public boolean dockerInstalado = true;
    public boolean dockerParado;
    public boolean composeInstalado = true;
    public boolean gitInstalado = true;
    /**
     * O Mac sem as ferramentas de linha de comando da Apple: o único git é o ATALHO de /usr/bin, que ao rodar abre o aviso de instalação do
     * sistema (aqui, {@link #avisoDoMacDisparado}). O app nunca pode rodá-lo.
     */
    public boolean gitSoOAtalhoDoMac;
    public boolean avisoDoMacDisparado;
    public boolean wslInstalado = true;
    public boolean virtualizacaoLigada = true;
    public String versaoDoDocker = "29.6.2";
    /** Quantas consultas ao Docker ainda respondem "parado" depois de mandarem abri-lo (o motor demora para subir). */
    public int consultasAteSubir = 2;
    private int abriuHa = -1;

    public final Map<String, String> redes = new LinkedHashMap<>(); // nome -> rótulo do app (vazio se não é dele)
    public final Map<String, Integer> containersNaRede = new LinkedHashMap<>();
    public final Set<String> volumes = new LinkedHashSet<>();
    /** Os volumes que levam o rótulo do app. */
    public final Set<String> volumesDoApp = new LinkedHashSet<>();
    public final Set<String> imagens = new LinkedHashSet<>();
    /** As imagens que levam o rótulo do app (construídas por ele). */
    public final Set<String> imagensDoApp = new LinkedHashSet<>();
    public final Map<String, Cont> containers = new LinkedHashMap<>();
    public final Set<String> programasAusentes = new LinkedHashSet<>();
    public String logsDoContainer = "node: escutando em 0.0.0.0:7070";

    public final List<List<String>> comandos = new CopyOnWriteArrayList<>();
    public final List<byte[]> entradas = new CopyOnWriteArrayList<>();
    private final List<Falha> falhas = new ArrayList<>();
    private final List<Gancho> ganchos = new ArrayList<>();

    private record Falha(Predicate<List<String>> quando, int status, String stderr, boolean umaVez, boolean[] jaFalhou) { }

    private record Gancho(Predicate<List<String>> quando, Runnable acao) { }

    public ComputadorSimulado falharEm(Predicate<List<String>> quando, int status, String stderr) {
        falhas.add(new Falha(quando, status, stderr, false, new boolean[1]));
        return this;
    }

    /** Remove as falhas programadas (o usuário "consertou" o computador). */
    public ComputadorSimulado consertar() {
        falhas.clear();
        return this;
    }

    public ComputadorSimulado antesDe(Predicate<List<String>> quando, Runnable acao) {
        ganchos.add(new Gancho(quando, acao));
        return this;
    }

    public ComputadorSimulado semPrograma(String nome) {
        programasAusentes.add(nome);
        return this;
    }

    public static Predicate<List<String>> contem(String trecho) {
        return c -> String.join(" ", c).contains(trecho);
    }

    public void limparRegistro() {
        comandos.clear();
        entradas.clear();
    }

    /** Só os comandos de ESCRITA que rodaram (o que não é leitura pela fronteira de {@link ComandosLocais}). */
    public List<List<String>> escritas() {
        return comandos.stream().filter(c -> !ComandosLocais.ehLeitura(c)).toList();
    }

    // ---------------------------------------------------------------------------------------------------

    private static ResultadoExec ok(String saida) {
        return new ResultadoExec(0, saida.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    private static ResultadoExec erro(int status, String stderr) {
        return new ResultadoExec(status, new byte[0], stderr.getBytes(StandardCharsets.UTF_8), false);
    }

    private boolean dockerResponde() {
        if (!dockerInstalado || dockerParado) {
            return false;
        }
        return true;
    }

    @Override
    public synchronized ResultadoExec executar(List<String> a, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("interrompido");
        }
        comandos.add(List.copyOf(a));
        entradas.add(stdin.clone());
        for (Gancho g : ganchos) {
            if (g.quando().test(a)) {
                g.acao().run();
            }
        }
        for (Falha f : falhas) {
            if (f.quando().test(a) && !(f.umaVez() && f.jaFalhou()[0])) {
                f.jaFalhou()[0] = true;
                return erro(f.status(), f.stderr());
            }
        }
        String programa = a.get(0);
        if (programa.equals("git") && gitSoOAtalhoDoMac) {
            avisoDoMacDisparado = true;
            return erro(1, "xcode-select: note: No developer tools were found, requesting install.");
        }
        if (programasAusentes.contains(programa) || (programa.equals("docker") && !dockerInstalado)
            || (programa.equals("git") && !gitInstalado) || (programa.equals("wsl") && !wslInstalado && false)) {
            throw new ProgramaNaoEncontradoException(programa);
        }
        return switch (programa) {
            case "docker" -> docker(a, stdin);
            case "git" -> ok("git version 2.50.1\n");
            case "wsl" -> wslInstalado ? ok("") : erro(1, "WSL não instalado");
            case "powershell" -> ok(virtualizacaoLigada ? "True\n" : "False\n");
            case "brew" -> instalar("brew");
            case "winget" -> instalar("winget");
            case "xcode-select" -> {
                gitInstalado = true;
                gitSoOAtalhoDoMac = false;
                yield ok("");
            }
            case "open", "colima" -> {
                abriuHa = 0; // o motor sobe depois de algumas consultas
                yield ok("");
            }
            default -> throw new AssertionError("Comando que o app não devia rodar (ou que o simulador não conhece): " + a);
        };
    }

    private ResultadoExec instalar(String como) {
        String t = String.join(" ", comandos.get(comandos.size() - 1));
        if (t.contains("docker")) {
            dockerInstalado = true;
            dockerParado = true;
        }
        if (t.contains("git") || t.contains("Git.Git")) {
            gitInstalado = true;
        }
        return ok("==> instalado\n");
    }

    /** Responde os comandos do agente por SSH (o token e o 2FA): o {@link AgenteDoLab#comandosPorSsh()}. */
    public ServidorSshDeTeste.Manipulador execDoAgente;

    private ResultadoExec docker(List<String> a, byte[] stdin) {
        if (a.size() >= 2 && a.get(1).equals("exec") && execDoAgente != null && dockerResponde()) {
            StringBuilder saida = new StringBuilder();
            int status = execDoAgente.executar(String.join(" ", a), new String(stdin, StandardCharsets.UTF_8), saida);
            return new ResultadoExec(status, saida.toString().getBytes(StandardCharsets.UTF_8), new byte[0], false);
        }
        if (a.size() >= 2 && a.get(1).equals("version")) {
            if (abriuHa >= 0 && dockerParado) {
                abriuHa++;
                if (abriuHa > consultasAteSubir) {
                    dockerParado = false;
                }
            }
            if (dockerParado) {
                return erro(1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?");
            }
            return ok(versaoDoDocker + "\n");
        }
        if (!dockerResponde()) {
            return erro(1, "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?");
        }
        String v = a.size() > 1 ? a.get(1) : "";
        switch (v) {
            case "compose":
                return composeInstalado ? ok("2.29.7\n") : erro(1, "docker: 'compose' is not a docker command.");
            case "network":
                return rede(a);
            case "ps": {
                String nome = filtro(a);
                Cont c = containers.get(nome);
                return ok(c == null ? "" : nome + "|" + rotulosDe(c) + "|" + c.estado + "|" + portasDe(c) + "\n");
            }
            case "volume": {
                if (a.get(2).equals("ls")) {
                    String nome = filtro(a);
                    return ok(volumes.contains(nome) ? nome + "|" + (volumesDoApp.contains(nome) ? "br.com.labdesktop.local=1" : "") + "\n" : "");
                }
                if (a.get(2).equals("create")) {
                    String nome = a.get(a.size() - 1);
                    volumes.add(nome);
                    if (a.contains("br.com.labdesktop.local=1")) {
                        volumesDoApp.add(nome);
                    }
                    return ok(nome + "\n");
                }
                if (a.get(2).equals("rm")) {
                    volumesDoApp.remove(a.get(3));
                    return volumes.remove(a.get(3)) ? ok(a.get(3) + "\n") : erro(1, "Error: No such volume");
                }
                break;
            }
            case "image": {
                if (a.get(2).equals("ls")) {
                    String base = a.get(a.indexOf("--filter") + 1).substring("reference=".length());
                    StringBuilder l = new StringBuilder();
                    for (String i : imagensDoApp) {
                        if (i.startsWith(base + ":")) {
                            l.append(i).append('\n');
                        }
                    }
                    return ok(l.toString());
                }
                return imagens.contains(a.get(a.size() - 1)) ? ok("sha256:abc\n") : erro(1, "Error: No such image");
            }
            case "build": {
                if (stdin.length == 0) {
                    return erro(1, "unable to prepare context: path not found");
                }
                imagens.add(a.get(a.indexOf("-t") + 1));
                if (a.contains("br.com.labdesktop.local=1")) {
                    imagensDoApp.add(a.get(a.indexOf("-t") + 1));
                }
                return ok("Successfully built\n");
            }
            case "run":
                return run(a);
            case "rm": {
                String n = a.get(a.size() - 1);
                return containers.remove(n) != null ? ok(n + "\n") : erro(1, "Error: No such container: " + n);
            }
            case "rename": {
                Cont c = containers.get(a.get(2));
                if (c == null || containers.containsKey(a.get(3))) {
                    return erro(1, "Error response from daemon: cannot rename");
                }
                containers.remove(a.get(2));
                containers.put(a.get(3), c);
                return ok("");
            }
            case "stop": {
                Cont c = containers.get(a.get(2));
                if (c == null) {
                    return erro(1, "Error: No such container");
                }
                c.estado = "exited";
                return ok(a.get(2) + "\n");
            }
            case "start": {
                Cont c = containers.get(a.get(2));
                if (c == null) {
                    return erro(1, "Error: No such container");
                }
                c.estado = "running";
                return ok(a.get(2) + "\n");
            }
            case "rmi": {
                imagensDoApp.remove(a.get(2));
                return imagens.remove(a.get(2)) ? ok("Untagged\n") : erro(1, "Error: No such image");
            }
            case "logs":
                return ok(logsDoContainer + "\n");
            case "inspect": {
                Cont c = containers.get(a.get(a.size() - 1));
                return c == null ? erro(1, "Error: No such object") : ok(c.estado + "\n");
            }
            default:
                break;
        }
        throw new AssertionError("Comando do Docker que o app não devia rodar (ou que o simulador não conhece): " + a);
    }

    private ResultadoExec rede(List<String> a) {
        switch (a.get(2)) {
            case "ls": {
                String nome = filtro(a);
                return ok(redes.containsKey(nome) ? nome + "|" + (redes.get(nome).isBlank() ? "" : "br.com.labdesktop.local=" + redes.get(nome)) + "\n" : "");
            }
            case "create": {
                String nome = a.get(a.size() - 1);
                if (redes.containsKey(nome)) {
                    return erro(1, "network with name " + nome + " already exists");
                }
                redes.put(nome, "1");
                return ok("abc123\n");
            }
            case "inspect":
                return redes.containsKey(a.get(a.size() - 1)) ? ok(containersNaRede.getOrDefault(a.get(a.size() - 1), 0) + "\n")
                    : erro(1, "Error: No such network");
            case "rm": {
                String nome = a.get(3);
                if (!redes.containsKey(nome)) {
                    return erro(1, "Error: No such network");
                }
                if (containersNaRede.getOrDefault(nome, 0) > 0) {
                    return erro(1, "network has active endpoints");
                }
                redes.remove(nome);
                return ok(nome + "\n");
            }
            default:
                throw new AssertionError("network: " + a);
        }
    }

    private ResultadoExec run(List<String> a) {
        String nome = a.get(a.indexOf("--name") + 1);
        String imagem = a.get(a.size() - 1);
        if (containers.containsKey(nome)) {
            return erro(125, "Conflict. The container name is already in use");
        }
        if (!imagens.contains(imagem)) {
            return erro(125, "Unable to find image locally");
        }
        Cont c = new Cont();
        c.imagem = imagem;
        c.argumentos = List.copyOf(a);
        for (int i = 0; i < a.size() - 1; i++) {
            if (a.get(i).equals("--label") && a.get(i + 1).startsWith("br.com.labdesktop.agente=")) {
                c.rotulo = a.get(i + 1).substring("br.com.labdesktop.agente=".length());
            }
            if (a.get(i).equals("--label") && a.get(i + 1).startsWith("br.com.labdesktop.pasta=")) {
                c.marcaDaPasta = a.get(i + 1).substring("br.com.labdesktop.pasta=".length());
            }
            if (a.get(i).equals("--label") && a.get(i + 1).startsWith("br.com.labdesktop.local=")) {
                c.rotuloLocal = a.get(i + 1).substring("br.com.labdesktop.local=".length());
            }
            if (a.get(i).equals("-p")) {
                String[] p = a.get(i + 1).split(":");
                c.portaPublicada = Integer.parseInt(p[1]);
            }
            if (a.get(i).equals("-v") && a.get(i + 1).matches("^[a-z0-9_.-]+:" + br.com.labdesktop.instalador.PastasNoContainer.DADOS + "$")) {
                volumes.add(a.get(i + 1).split(":")[0]);
            }
        }
        containers.put(nome, c);
        return ok("a1b2c3d4e5f6\n");
    }

    /** Como o {@code {{.Labels}}} do Docker mostra: {@code chave=valor,chave=valor}, com um rótulo alheio no meio para provar a leitura. */
    private static String rotulosDe(Cont c) {
        List<String> l = new ArrayList<>();
        l.add("org.exemplo.alheio=a,b|c");
        if (!c.rotulo.isBlank()) {
            l.add("br.com.labdesktop.agente=" + c.rotulo);
        }
        if (!c.rotuloLocal.isBlank()) {
            l.add("br.com.labdesktop.local=" + c.rotuloLocal);
        }
        if (!c.marcaDaPasta.isBlank()) {
            l.add("br.com.labdesktop.pasta=" + c.marcaDaPasta);
        }
        return String.join(",", l);
    }

    private static String portasDe(Cont c) {
        if (!c.portas.isBlank()) {
            return c.portas;
        }
        return c.portaPublicada == 0 ? "" : "127.0.0.1:" + c.portaPublicada + "->" + c.portaPublicada + "/tcp";
    }

    private static String filtro(List<String> a) {
        for (int i = 0; i < a.size() - 1; i++) {
            if (a.get(i).equals("--filter") && a.get(i + 1).startsWith("name=^")) {
                String f = a.get(i + 1).substring("name=^".length());
                return f.endsWith("$") ? f.substring(0, f.length() - 1) : f;
            }
        }
        return "";
    }

    public static ComandoDemorouException demorou() {
        return new ComandoDemorouException(1200);
    }
}
