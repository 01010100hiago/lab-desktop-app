package br.com.labdesktop.processos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** O comando que o usuário copia: guardado pelo nome, com a conferência antes, e NUNCA com um nome que o shell interprete. */
class ComandoParaODonoTest {

    private static final AmbienteDeProcessos.Tipo LINUX = AmbienteDeProcessos.Tipo.SERVIDOR_LINUX;

    @Test
    void valoresConhecidosParaLinux() {
        ComandoParaODono c = ComandoParaODono.doProcesso(3200, "postgres", Sinal.TERM, LINUX);
        assertEquals("ps -o pid,user,lstart,comm -p 3200", c.conferir());
        assertEquals("ps -o comm= -p 3200 | grep -Eq '(^|/)postgres$' && sudo kill -TERM 3200", c.executar());
        assertEquals("ps -o pid,user,lstart,comm -p 3200\nps -o comm= -p 3200 | grep -Eq '(^|/)postgres$' && sudo kill -TERM 3200", c.texto());
        assertTrue(c.temComando());
        assertEquals("", c.motivoSem());
        assertEquals("ps -o comm= -p 77 | grep -Eq '(^|/)my-app\\.v2$' && sudo kill -KILL 77", ComandoParaODono.doProcesso(77, "my-app.v2", Sinal.KILL, LINUX).executar(),
            "o ponto do nome é escapado");
    }

    @Test
    void nomeComprido_oPsCortaEm15LetrasEOComandoCompara15() {
        ComandoParaODono c = ComandoParaODono.doProcesso(500, "um-programa-bem-comprido", Sinal.TERM, LINUX);
        assertEquals("ps -o comm= -p 500 | grep -Eq '(^|/)um-programa-bem' && sudo kill -TERM 500", c.executar());
    }

    @Test
    void nomeDeUmaLinhaDeComandoUsaSoOPrograma() {
        assertEquals("ps -o comm= -p 500 | grep -Eq '(^|/)node$' && sudo kill -TERM 500", ComandoParaODono.doProcesso(500, "/usr/bin/node --inspect server.js", Sinal.TERM, LINUX).executar());
    }

    @Test
    void windowsUsaOFiltroDoNumeroEDoNomeDaImagem() {
        ComandoParaODono c = ComandoParaODono.doProcesso(77, "notepad.exe", Sinal.KILL, AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS);
        assertEquals("tasklist /FI \"PID eq 77\"", c.conferir());
        assertEquals("taskkill /PID 77 /FI \"PID eq 77\" /FI \"IMAGENAME eq notepad.exe\" /F", c.executar());
        assertEquals("taskkill /PID 77 /FI \"PID eq 77\" /FI \"IMAGENAME eq notepad.exe\"", ComandoParaODono.doProcesso(77, "notepad.exe", Sinal.TERM, AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS).executar());
        assertFalse(c.executar().contains(" /T"), "sem a árvore de filhos");
    }

    @Test
    void containerConfereONomeExato() {
        ComandoParaODono c = ComandoParaODono.doContainer(Acao.ENCERRAR, "meu-app");
        assertEquals("sudo docker ps -a --filter \"name=^meu-app$\"", c.conferir());
        assertEquals("[ \"$(sudo docker inspect -f '{{.Name}}' meu-app)\" = \"/meu-app\" ] && sudo docker stop meu-app", c.executar());
        assertTrue(ComandoParaODono.doContainer(Acao.REINICIAR, "meu-app").executar().endsWith("&& sudo docker restart meu-app"));
    }

    @Test
    void nomeMaliciosoNaoViraComandoENaoVazaNaFrase() {
        for (String ruim : new String[] {"nginx; rm -rf ~", "a b", "$(reboot)", "`id`", "a'b", "a\"b", "a|b", "a&&b", "a>b", "-9", "a\nb", "a‮b", "", "x y.exe", "a*b"}) {
            ComandoParaODono l = ComandoParaODono.doProcesso(3200, ruim, Sinal.TERM, LINUX);
            assertFalse(l.temComando(), "nome fora da regra: " + ruim.replace("\n", "\\n"));
            assertEquals("", l.texto());
            assertFalse(l.motivoSem().isBlank());
            assertFalse(l.motivoSem().contains("\n") || l.motivoSem().contains("‮"), "a frase não repete o caractere perigoso");
            ComandoParaODono w = ComandoParaODono.doProcesso(3200, ruim, Sinal.TERM, AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS);
            assertFalse(w.temComando(), "no Windows também: " + ruim.replace("\n", "\\n"));
        }
        assertFalse(ComandoParaODono.doProcesso(3200, "notepad", Sinal.TERM, AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS).temComando(), "no Windows só .exe");
        assertFalse(ComandoParaODono.doContainer(Acao.ENCERRAR, "a;b").temComando());
        assertFalse(ComandoParaODono.doContainer(Acao.ENCERRAR, "$(id)").temComando());
        assertFalse(ComandoParaODono.doProcesso(1, "sleep", Sinal.TERM, LINUX).temComando(), "pid 1 nunca");
        assertFalse(ComandoParaODono.doProcesso(null, "sleep", Sinal.TERM, LINUX).temComando());
    }

    /** Roda o comando guardado num shell de verdade (trocando o {@code sudo kill} por um {@code echo}): o nome certo passa, o outro não. */
    @Test
    void aGuardaFuncionaNumShellDeVerdade() throws IOException, InterruptedException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")) && Files.isExecutable(Path.of("/bin/ps")) || Files.isExecutable(Path.of("/usr/bin/ps")));
        Process p = new ProcessBuilder("/bin/sh", "-c", "sleep 60 >/dev/null 2>&1 & echo $!").start();
        int pid = Integer.parseInt(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        p.waitFor(10, TimeUnit.SECONDS);
        try {
            String certo = ComandoParaODono.doProcesso(pid, "sleep", Sinal.TERM, LINUX).executar().replace("sudo kill -TERM " + pid, "echo passou");
            String errado = ComandoParaODono.doProcesso(pid, "node", Sinal.TERM, LINUX).executar().replace("sudo kill -TERM " + pid, "echo passou");
            String parecido = ComandoParaODono.doProcesso(pid, "slee", Sinal.TERM, LINUX).executar().replace("sudo kill -TERM " + pid, "echo passou");
            assertEquals("passou", executar(certo));
            assertEquals("", executar(errado), "outro nome: o kill nem roda");
            assertEquals("", executar(parecido), "nome parecido (só o começo) também não");
            // o número já não é de ninguém
            new ProcessBuilder("/bin/sh", "-c", "kill -KILL " + pid).start().waitFor(10, TimeUnit.SECONDS);
            for (int i = 0; i < 50 && !executar(certo).isEmpty(); i++) {
                Thread.sleep(100);
            }
            assertEquals("", executar(certo), "processo que saiu: nada roda");
        } finally {
            new ProcessBuilder("/bin/sh", "-c", "kill -KILL " + pid + " 2>/dev/null").start().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String executar(String comando) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("/bin/sh", "-c", comando).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor(10, TimeUnit.SECONDS);
        return s;
    }
}
