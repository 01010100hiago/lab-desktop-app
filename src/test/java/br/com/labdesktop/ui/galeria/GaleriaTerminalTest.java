package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.ServidorDeTerminalFalso;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.terminal.PainelTerminal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A imagem da galeria da aba Terminal do projeto: o shell do servidor, já na pasta do projeto, com uma sessão INVENTADA (a saída é escrita aqui; o servidor de teste não tem shell). Sem o vigia de
 * EDT dos outros testes de tela: o JediTerm pede repintura da thread dele, como no {@code TerminalDoProjetoTest}. Sai em {@code build/galeria/projeto-terminal-*.png}.
 */
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaTerminalTest {

    @TempDir
    Path pasta;

    private static final String PROMPT = "labuser@servidor-demo:/srv/lab/projects/loja-web$ ";

    @Test
    void terminalDoProjeto() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            c.ligarOShell(s -> {
                s.escrever(PROMPT + "ls\r\n");
                s.escrever("\u001b[1;34mdocs\u001b[0m  docker-compose.yml  Dockerfile  package.json  \u001b[1;34mpublic\u001b[0m  README.md  \u001b[1;34mscripts\u001b[0m  \u001b[1;34msrc\u001b[0m  \u001b[1;34mtestes\u001b[0m\r\n");
                s.escrever(PROMPT + "docker compose ps\r\n");
                s.escrever("NAME                IMAGE                    STATUS         PORTS\r\n");
                s.escrever("loja-web-api-1      loja-web-api:latest      Up 3 hours     0.0.0.0:3041->4000/tcp\r\n");
                s.escrever("loja-web-banco-1    postgres:16-alpine       Up 3 hours     0.0.0.0:5440->5432/tcp\r\n");
                s.escrever("loja-web-web-1      loja-web-web:latest      Up 3 hours     0.0.0.0:3040->3000/tcp\r\n");
                s.escrever("loja-web-worker-1   loja-web-worker:latest   Up 3 hours\r\n");
                s.escrever(PROMPT + "git log --oneline -3\r\n");
                s.escrever("\u001b[33mc4e91fa\u001b[0m Corrige o cálculo do frete\r\n");
                s.escrever("\u001b[33m8a02d5e\u001b[0m Adiciona a página de pedidos\r\n");
                s.escrever("\u001b[33me17b6c4\u001b[0m Ajusta o total do carrinho\r\n");
                s.escrever(PROMPT);
                ServidorDeTerminalFalso.roteiroQueNaoLe(s);
            });
            TelaProjeto t = c.abrirProjeto(Aba.TERMINAL, null);
            c.esperarODetalhe(t);
            Visual.renderizar(c.painel, CenaDaGaleria.LARGURA, CenaDaGaleria.ALTURA);
            CenaDaGaleria.PaginaDaAba pagina = CenaDaGaleria.pagina(t, Aba.TERMINAL);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "o shell do projeto abriu sozinho: " + c.textos(), () -> Visual.naEdt(() -> pagina.<PainelTerminal>chamar("painelDoShell")) != null);
            PainelTerminal shell = Visual.naEdt(() -> pagina.<PainelTerminal>chamar("painelDoShell"));
            Visual.renderizar(c.painel, CenaDaGaleria.LARGURA, CenaDaGaleria.ALTURA); // o terminal mede a janela antes de abrir o canal
            Visual.naEdt(shell::iniciar);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "estado=" + shell.sessao().estado(), () -> shell.sessao().estado().ativa() && Visual.naEdt(() -> shell.widget() != null));
            Espera.ate(CenaDaGaleria.PRAZO, "a saída inventada chegou ao terminal", () -> {
                String tela = Visual.naEdt(() -> shell.widget().getTerminalTextBuffer().getScreenLines());
                return tela.contains("Ajusta o total do carrinho") && tela.trim().endsWith("$");
            });
            String tela = Visual.naEdt(() -> shell.widget().getTerminalTextBuffer().getScreenLines());
            assertTrue(tela.contains("loja-web-api-1") && tela.contains("README.md"), tela);
            CenaDaGaleria.semDadoDoComputador("Terminal", tela);
            assertEquals(1, c.terminal.comandos.size(), "o servidor viu só a linha do shell do projeto: " + c.terminal.comandos);
            assertTrue(c.terminal.comandos.get(0).startsWith("cd '/srv/lab/projects/loja-web'"), c.terminal.comandos.get(0));
            c.conferir("Terminal", "Terminal", "Shell no servidor, na pasta do projeto");
            c.fotografar("projeto-terminal");
        }
    }
}
