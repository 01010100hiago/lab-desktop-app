package br.com.labdesktop.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.local.ConexaoLocal;
import br.com.labdesktop.local.ConfigLocal;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.teste.CofreFalso;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O terminal de "Este computador": um PTY de verdade (pty4j) com o shell do usuário, sem SSH. Abre, escreve, lê, redimensiona e fecha. */
class TransporteLocalTest {

    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ConexaoLocal conexao;
    private TransporteLocal transporte;
    private final List<CanalDeTerminal> abertos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "o teste do PTY real usa /bin/sh");
        CofreFalso cofre = new CofreFalso("127.0.0.1", 22, List.of()).comoLocal(7070);
        conexao = new ConexaoLocal(CofreFalso.ID, cofre, ConfigLocal.rapida(), p -> SondaDoAgente.Resultado.OK,
            br.com.labdesktop.util.Agendador.doFundo(), (c, in, prazo) -> {
                throw new IOException("sem comando");
            });
        // /bin/sh de propósito: o shell do usuário muda de máquina para máquina, o resultado do teste não pode
        transporte = new TransporteLocal(conexao, false, Map.of("SHELL", "/bin/sh", "PATH", System.getenv("PATH") == null ? "/usr/bin:/bin" : System.getenv("PATH"),
            "HOME", tmp.toString()), tmp);
    }

    @AfterEach
    void desmontar() {
        abertos.forEach(CanalDeTerminal::abortar);
        conexao.fechar().join();
    }

    private CanalDeTerminal abrir(ComandoDeTerminal c, TamanhoDeTerminal t) throws IOException {
        CanalDeTerminal canal = transporte.abrir(c, t);
        abertos.add(canal);
        ler(canal);
        return canal;
    }

    /** Um leitor por canal (o fluxo do PTY bloqueia na leitura e o available() dele não serve): guarda tudo o que chega. */
    private final java.util.Map<CanalDeTerminal, StringBuffer> recebido = new java.util.concurrent.ConcurrentHashMap<>();

    private void ler(CanalDeTerminal canal) {
        StringBuffer sb = new StringBuffer();
        recebido.put(canal, sb);
        Thread.ofVirtual().start(() -> {
            byte[] b = new byte[4096];
            try {
                int n;
                while ((n = canal.saida().read(b)) > 0) {
                    sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // canal fechado
            }
        });
    }

    /** Espera aparecer o texto (ou o prazo) no que o canal já mandou. */
    private String lerAte(CanalDeTerminal canal, String texto) throws Exception {
        StringBuffer sb = recebido.get(canal);
        long limite = System.nanoTime() + PRAZO.toNanos();
        while (System.nanoTime() < limite && !sb.toString().contains(texto)) {
            Thread.sleep(20);
        }
        assertTrue(sb.toString().contains(texto), "esperava \"" + texto + "\" e chegou: " + sb);
        return sb.toString();
    }

    private static void digitar(CanalDeTerminal canal, String linha) throws IOException {
        canal.entrada().write((linha + "\n").getBytes(StandardCharsets.UTF_8));
        canal.entrada().flush();
    }

    @Test
    void abreOShellEscreveELeASaidaSemSsh() throws Exception {
        CanalDeTerminal canal = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
        assertTrue(canal.aberto());
        digitar(canal, "echo ola-$((40+2))");
        String saida = lerAte(canal, "ola-42");
        assertTrue(saida.contains("ola-42"));
    }

    @Test
    void oShellDoServidorAbreNaPastaPessoalEODoProjetoNaPastaDoProjeto() throws Exception {
        Path projeto = Files.createDirectories(tmp.resolve("meu-projeto")).toRealPath();
        CanalDeTerminal c1 = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
        digitar(c1, "pwd -P");
        lerAte(c1, tmp.toRealPath().toString());

        CanalDeTerminal c2 = abrir(new ComandoDeTerminal.ShellDoProjeto(projeto.toString()), TamanhoDeTerminal.PADRAO);
        digitar(c2, "pwd -P");
        lerAte(c2, projeto.toString());
    }

    @Test
    void projetoSemPastaAbreNaPastaPessoalEDizIsso() throws Exception {
        CanalDeTerminal canal = abrir(new ComandoDeTerminal.ShellDoProjeto(tmp.resolve("nao-existe").toString()), TamanhoDeTerminal.PADRAO);
        String saida = lerAte(canal, "pasta pessoal");
        assertTrue(saida.contains("não foi encontrada"), saida);
        digitar(canal, "pwd -P");
        lerAte(canal, tmp.toRealPath().toString());
    }

    @Test
    void tamanhoInicialEResizeChegamAoProgramaPorSigwinch() throws Exception {
        CanalDeTerminal canal = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, new TamanhoDeTerminal(90, 20));
        digitar(canal, "stty size");
        lerAte(canal, "20 90");
        canal.redimensionar(new TamanhoDeTerminal(130, 41));
        digitar(canal, "stty size");
        lerAte(canal, "41 130");
    }

    @Test
    void ambienteTraTermUtf8() throws Exception {
        CanalDeTerminal canal = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
        digitar(canal, "echo T=$TERM C=$COLORTERM L=${LANG:+definido}");
        String s = lerAte(canal, "L=definido");
        assertTrue(s.contains("T=xterm-256color") && s.contains("C=truecolor"), s);
    }

    @Test
    void exitDoShellFechaOCanalComOStatusDeSaida() throws Exception {
        CanalDeTerminal canal = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
        digitar(canal, "exit 3");
        assertEquals(3, canal.aguardarFim());
        assertFalse(canal.aberto());
        assertEquals(3, canal.statusDeSaida());
    }

    @Test
    void fecharMandaOFimAoShellEOCanalTermina() throws Exception {
        CanalDeTerminal canal = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
        digitar(canal, "echo pronto");
        lerAte(canal, "pronto");
        canal.fechar();
        Espera.ate(PRAZO, "o shell saiu depois do fechar", () -> !canal.aberto());
        assertNotNull(canal.aguardarFim() == null ? Integer.valueOf(-1) : canal.aguardarFim());
    }

    @Test
    void abortarMataNaHoraMesmoUmProgramaPresoQueIgnoraOFim() throws Exception {
        CanalDeTerminal canal = abrir(ComandoDeTerminal.ShellDoServidor.INSTANCIA, TamanhoDeTerminal.PADRAO);
        digitar(canal, "trap '' HUP TERM; echo preso; while true; do sleep 1; done");
        lerAte(canal, "preso");
        canal.abortar();
        Espera.ate(PRAZO, "o processo preso foi morto", () -> !canal.aberto());
    }

    @Test
    void aSessaoDoClaudeLocalEstaDesligadaECaiNaRecusaComFrase() {
        TerminalRecusadoException e = assertThrows(TerminalRecusadoException.class,
            () -> transporte.abrir(new ComandoDeTerminal.SessaoDoClaude("lab"), TamanhoDeTerminal.PADRAO));
        assertTrue(e.getMessage().contains("desligadas"), e.getMessage());
    }

    @Test
    void aDisponibilidadeEDoCofre_sempreDisponivelEBloqueadaQuandoOCofreFecha() throws Exception {
        AtomicReference<TransporteDeTerminal.Disponibilidade> ultima = new AtomicReference<>();
        Runnable soltar = transporte.observarDisponibilidade(ultima::set);
        Espera.ate(PRAZO, "primeiro valor", () -> ultima.get() != null);
        assertEquals(TransporteDeTerminal.Disponibilidade.DISPONIVEL, ultima.get());
        conexao.bloquear();
        Espera.ate(PRAZO, "bloqueado", () -> ultima.get() == TransporteDeTerminal.Disponibilidade.BLOQUEADO);
        soltar.run();
    }

    @Test
    void oShellDoWindowsEOPowerShellEODoMacOuLinuxOShellDoUsuarioComL() {
        TransporteLocal win = new TransporteLocal(conexao, true, Map.of(), tmp);
        assertEquals(List.of("powershell.exe", "-NoLogo"), List.of(win.comandoDoShell()));
        TransporteLocal mac = new TransporteLocal(conexao, false, Map.of("SHELL", "/bin/sh"), tmp);
        assertEquals(List.of("/bin/sh", "-l"), List.of(mac.comandoDoShell()));
        TransporteLocal semShell = new TransporteLocal(conexao, false, Map.of(), tmp);
        assertEquals("-l", semShell.comandoDoShell()[1]);
        TransporteLocal shellFalso = new TransporteLocal(conexao, false, Map.of("SHELL", "relativo; rm -rf /"), tmp);
        assertTrue(shellFalso.comandoDoShell()[0].startsWith("/bin/"), "um SHELL que não é caminho absoluto de programa é ignorado");
    }
}
