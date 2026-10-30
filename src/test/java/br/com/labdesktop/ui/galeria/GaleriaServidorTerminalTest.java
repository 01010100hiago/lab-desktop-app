package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.PaginaTerminal;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.ui.terminal.PainelTerminal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A imagem de galeria da aba Terminal do servidor de exemplo: um shell de verdade (canal SSH com PTY no servidor embutido) cujo "servidor" é um roteiro com a saída de
 * {@code docker ps} e {@code uptime} INVENTADA (containers loja-web, db, cache; identificadores de container sem significado). Sem a extensão {@code VerificaEdt}: o emulador do
 * JediTerm pede repintura da thread dele (a mesma exceção dos testes de terminal que já existem).
 */
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaServidorTerminalTest {

    /** O que o servidor de exemplo escreve no terminal: o título da aba, o aviso de entrada, {@code docker ps}, {@code uptime} e um prompt novo. */
    static final String SAIDA = "\u001b]0;labuser@servidor-demo: ~\u0007"
        + "Linux servidor-demo 6.1.0-exemplo-amd64 #1 SMP PREEMPT_DYNAMIC Debian 6.1.0 (2026-09-01) x86_64\r\n\r\n"
        + "labuser@servidor-demo:~$ docker ps\r\n"
        + "CONTAINER ID   IMAGE                COMMAND                  CREATED       STATUS       PORTS                    NAMES\r\n"
        + "3f9a1c7e5b02   loja-web-web         \"docker-entrypoint.s…\"   3 hours ago   Up 3 hours   0.0.0.0:3000->3000/tcp   loja-web-web-1\r\n"
        + "c81d44a09e17   postgres:16-alpine   \"docker-entrypoint.s…\"   3 hours ago   Up 3 hours   5432/tcp                 loja-web-db-1\r\n"
        + "7b25e0f3a9c4   redis:7-alpine       \"docker-entrypoint.s…\"   3 hours ago   Up 3 hours   6379/tcp                 loja-web-cache-1\r\n"
        + "e04f8b71d2a6   painel-vendas-web    \"docker-entrypoint.s…\"   2 days ago    Up 2 days    0.0.0.0:3100->3000/tcp   painel-vendas-web-1\r\n"
        + "a9d3c5160b8e   nginx:1.27-alpine    \"/docker-entrypoint.…\"   5 days ago    Up 5 days    0.0.0.0:80->80/tcp       proxy\r\n"
        + "labuser@servidor-demo:~$ uptime\r\n"
        + " 14:32:07 up 9 days,  3:12,  1 user,  load average: 0.42, 0.61, 0.58\r\n"
        + "labuser@servidor-demo:~$ ";

    @TempDir
    Path tmp;

    @Test
    void terminal() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            // o servidor de terminal de mentira troca a fábrica de comandos do SSH embutido; o exec do agente (segurança, git...) precisa continuar como estava
            var execDoAgente = c.cenario.ssh.sshd.getCommandFactory();
            ServidorDeTerminalFalso terminal = new ServidorDeTerminalFalso(c.cenario.ssh);
            c.cenario.ssh.sshd.setCommandFactory(execDoAgente);
            terminal.roteiro(s -> {
                s.escrever(SAIDA);
                ServidorDeTerminalFalso.roteiroDeEco(s);
            });

            c.abrir(Aba.TERMINAL);
            PaginaTerminal pagina = Visual.naEdt(() -> c.tela().terminais());
            assertTrue(Visual.naEdt(pagina::novoTerminal), "o terminal abriu");
            PainelTerminal painelDoTerminal = Visual.naEdt(() -> pagina.terminais().get(0));
            Visual.renderizar(c.painel, 1440, 860); // dá o tamanho de verdade antes de ligar o shell: o terminal calcula colunas e linhas por ele
            Visual.naEdt(painelDoTerminal::iniciar);
            Espera.ate(CenaDoServidor.PRAZO, () -> "tela do terminal: " + tela(painelDoTerminal), () -> tela(painelDoTerminal).contains("loja-web-cache-1") && tela(painelDoTerminal).contains("load average"));
            Espera.ate(CenaDoServidor.PRAZO, () -> "prompt final: " + tela(painelDoTerminal), () -> tela(painelDoTerminal).stripTrailing().endsWith("labuser@servidor-demo:~$"));
            String t = tela(painelDoTerminal);
            assertTrue(t.contains("docker ps") && t.contains("loja-web-db-1"), t);
            assertFalse(t.contains("/Users/") || t.contains(ComputadorDeTeste.usuario()), t);
            c.esperarTextos("a aba Terminal com o shell aberto", "Novo terminal", "Fechar terminal");
            c.fotografar("servidor-terminal");
        }
    }

    private static String tela(PainelTerminal t) {
        return Visual.naEdt(() -> t.widget() == null ? "" : t.widget().getTerminalTextBuffer().getScreenLines());
    }
}
