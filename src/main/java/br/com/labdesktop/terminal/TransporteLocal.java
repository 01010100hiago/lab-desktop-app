package br.com.labdesktop.terminal;

import br.com.labdesktop.ssh.ConexaoDoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * O {@link TransporteDeTerminal} de "Este computador": um PTY do próprio computador (pty4j: {@code forkpty} no Mac e no Linux, ConPTY no
 * Windows), sem SSH. {@code ShellDoServidor} e {@code ShellDoProjeto} abrem o shell do usuário (no Windows, o PowerShell); {@code resize} vira
 * {@code setWinSize}; fechar manda o fim da conversa ao shell e o arranca se ele não sair. A sessão do Claude local NÃO existe (opção avançada,
 * desligada: tudo funciona sem IA); pedi-la é recusado com uma frase.
 *
 * <p>O que roda NUNCA vem de texto: o programa é o shell do usuário ({@code $SHELL} ou o padrão do sistema) com {@code -l}, e a pasta do projeto
 * é o diretório do processo, nunca parte de uma linha de comando. A disponibilidade é a do cofre: sempre disponível, e bloqueada quando o cofre
 * fecha.
 */
public final class TransporteLocal implements TransporteDeTerminal {

    private static final String AVISO_SEM_PASTA = "A pasta do projeto não foi encontrada; abrindo na pasta pessoal.\r\n";

    private final ConexaoDoServidor conexao;
    private final boolean windows;
    private final Map<String, String> ambiente;
    private final Path casa;
    private final String shellDoUsuario;

    /**
     * @param ambiente as variáveis de ambiente do app (de onde saem {@code SHELL} e {@code PATH})
     * @param casa     a pasta pessoal do usuário
     */
    public TransporteLocal(ConexaoDoServidor conexao, boolean windows, Map<String, String> ambiente, Path casa) {
        this.conexao = conexao;
        this.windows = windows;
        this.ambiente = Map.copyOf(ambiente);
        this.casa = casa;
        this.shellDoUsuario = ambiente.get("SHELL");
    }

    public static TransporteLocal doSistema(ConexaoDoServidor conexao) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return new TransporteLocal(conexao, windows, System.getenv(), Path.of(System.getProperty("user.home")));
    }

    @Override
    public CanalDeTerminal abrir(ComandoDeTerminal comando, TamanhoDeTerminal tamanho) throws IOException {
        return switch (comando) {
            case ComandoDeTerminal.ShellDoServidor s -> abrirShell(casa, "", tamanho);
            case ComandoDeTerminal.ShellDoProjeto s -> {
                Path pasta = Path.of(s.pasta());
                yield Files.isDirectory(pasta) ? abrirShell(pasta, "", tamanho) : abrirShell(casa, AVISO_SEM_PASTA, tamanho);
            }
            case ComandoDeTerminal.SessaoDoClaude s -> throw new TerminalRecusadoException(
                "As sessões do Claude locais estão desligadas (opção avançada). O terminal comum funciona normalmente.", null);
        };
    }

    /** O programa do shell: o do usuário no Mac e no Linux, o PowerShell no Windows. Visível para teste. */
    String[] comandoDoShell() {
        if (windows) {
            return new String[] {"powershell.exe", "-NoLogo"};
        }
        String s = shellDoUsuario;
        if (s != null && s.startsWith("/") && Files.isExecutable(Path.of(s))) {
            return new String[] {s, "-l"};
        }
        return new String[] {Files.isExecutable(Path.of("/bin/zsh")) ? "/bin/zsh" : "/bin/sh", "-l"};
    }

    private CanalDeTerminal abrirShell(Path pasta, String aviso, TamanhoDeTerminal tamanho) throws IOException {
        Map<String, String> env = new HashMap<>(ambiente);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.putIfAbsent("LANG", "en_US.UTF-8"); // um app aberto pelo Finder chega sem LANG: sem ele o shell não fala UTF-8
        try {
            PtyProcess p = new PtyProcessBuilder(comandoDoShell()).setEnvironment(env).setDirectory(pasta.toString())
                .setInitialColumns(tamanho.colunas()).setInitialRows(tamanho.linhas()).setRedirectErrorStream(true).setConsole(false).start();
            return new CanalLocal(p, aviso);
        } catch (IOException | RuntimeException e) {
            throw new IOException("Não consegui abrir o terminal deste computador: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }

    @Override
    public Runnable observarDisponibilidade(Consumer<Disponibilidade> ouvinte) {
        return conexao.observar(info -> ouvinte.accept(info.estado() == EstadoConexao.BLOQUEADO_COFRE
            ? Disponibilidade.BLOQUEADO : Disponibilidade.DISPONIVEL));
    }

    /** O PTY do pty4j visto como {@link CanalDeTerminal}. */
    private static final class CanalLocal implements CanalDeTerminal {

        private final PtyProcess processo;
        private final InputStream saida;

        CanalLocal(PtyProcess processo, String aviso) {
            this.processo = processo;
            this.saida = aviso.isEmpty() ? processo.getInputStream()
                : new SequenceInputStream(new ByteArrayInputStream(aviso.getBytes(StandardCharsets.UTF_8)), processo.getInputStream());
        }

        @Override
        public InputStream saida() {
            return saida;
        }

        @Override
        public OutputStream entrada() {
            return processo.getOutputStream();
        }

        @Override
        public void redimensionar(TamanhoDeTerminal t) throws IOException {
            processo.setWinSize(new WinSize(t.colunas(), t.linhas()));
        }

        @Override
        public boolean aberto() {
            return processo.isAlive();
        }

        @Override
        public Integer aguardarFim() throws InterruptedException {
            return processo.waitFor();
        }

        @Override
        public Integer statusDeSaida() {
            return processo.isAlive() ? null : processo.exitValue();
        }

        /** Manda o fim ao shell (SIGHUP) e o arranca se ele não sair em 2 s. Volta na hora. */
        @Override
        public void fechar() {
            if (!processo.isAlive()) {
                return;
            }
            processo.destroy();
            Thread.ofVirtual().start(() -> {
                try {
                    if (!processo.waitFor(2, TimeUnit.SECONDS)) {
                        matar();
                    }
                } catch (InterruptedException e) {
                    matar();
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public void abortar() {
            matar();
        }

        /**
         * SIGKILL no shell e nos filhos. O {@code destroyForcibly} do pty4j manda só SIGHUP (um programa que ignora o fim, como o do teste,
         * sobreviveria), e o {@code descendants()} dele não existe: o {@link ProcessHandle} do sistema, pelo pid, faz as duas coisas.
         */
        private void matar() {
            try {
                ProcessHandle.of(processo.pid()).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                });
            } catch (UnsupportedOperationException e) {
                // sem pid neste sistema: resta o destroyForcibly do pty4j
            }
            processo.destroyForcibly();
        }
    }
}
