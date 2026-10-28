package br.com.labdesktop.local;

import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * O {@link ExecutorDeProcesso} de verdade: um {@link ProcessBuilder} com os argumentos em lista (sem shell), o PATH acrescido das pastas
 * conhecidas da plataforma (o {@code docker} do Mac chama o {@code docker-credential-desktop} pelo PATH), saída com teto e o processo morto,
 * com os filhos, se a thread for interrompida (Cancelar) ou o prazo estourar. Não grava nada em log: o stdout de um comando de token seria um
 * segredo.
 */
public final class ProcessosLocais implements ExecutorDeProcesso {

    private final LocalizadorDeProgramas localizador;
    private final String separadorDoPath;

    public ProcessosLocais(LocalizadorDeProgramas localizador, PlataformaLocal plataforma) {
        this.localizador = localizador;
        this.separadorDoPath = plataforma.separadorDoPath();
    }

    @Override
    public ResultadoExec executar(List<String> argumentos, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
        exigirArgumentosValidos(argumentos);
        String programa = argumentos.get(0);
        Optional<Path> achado;
        if (programa.contains("/") || programa.contains("\\")) {
            // um caminho explícito (um programa do próprio JDK, nos testes): só se for absoluto e executável
            Path caminho = Path.of(programa);
            achado = caminho.isAbsolute() && java.nio.file.Files.isRegularFile(caminho) && java.nio.file.Files.isExecutable(caminho)
                ? Optional.of(caminho) : Optional.empty();
        } else {
            achado = localizador.localizar(programa);
        }
        if (achado.isEmpty()) {
            throw new ProgramaNaoEncontradoException(programa);
        }
        List<String> comando = new ArrayList<>(argumentos);
        comando.set(0, achado.get().toString());
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        String path = env.getOrDefault("PATH", "");
        for (String pasta : localizador.pastas()) {
            if (!pasta.isBlank() && !(separadorDoPath + path + separadorDoPath).contains(separadorDoPath + pasta + separadorDoPath)) {
                path = path.isEmpty() ? pasta : path + separadorDoPath + pasta;
            }
        }
        env.put("PATH", path);
        Process p = pb.start();
        CapturaDeSaida out = new CapturaDeSaida(p.getInputStream(), "processo-saida");
        CapturaDeSaida err = new CapturaDeSaida(p.getErrorStream(), "processo-erro");
        Thread entrada = Thread.ofVirtual().name("processo-entrada").start(() -> {
            try (OutputStream o = p.getOutputStream()) {
                o.write(stdin);
            } catch (IOException e) {
                // o programa fechou a entrada antes de ler tudo: normal para quem não lê stdin
            }
        });
        try {
            if (!p.waitFor(prazo.toMillis(), TimeUnit.MILLISECONDS)) {
                matar(p);
                throw new ComandoDemorouException(prazo.toSeconds());
            }
        } catch (InterruptedException e) {
            matar(p); // o Cancelar da tela: o processo não pode ficar rodando sozinho
            throw e;
        } finally {
            entrada.interrupt();
        }
        out.esperar();
        err.esperar();
        return new ResultadoExec(p.exitValue(), out.bytes(), err.bytes(), out.truncado() || err.truncado());
    }

    private static void matar(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    static void exigirArgumentosValidos(List<String> argumentos) {
        if (argumentos == null || argumentos.isEmpty()) {
            throw new IllegalArgumentException("Comando local vazio.");
        }
        for (String a : argumentos) {
            if (a == null || a.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Argumento inválido no comando local.");
            }
        }
        String programa = argumentos.get(0);
        if (programa.isBlank() || programa.contains(" ") && !programa.contains("/") && !programa.contains("\\")) {
            throw new IllegalArgumentException("O primeiro item é o nome do programa, não uma linha de comando.");
        }
    }
}
