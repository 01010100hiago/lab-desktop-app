package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos;
import java.nio.file.Path;
import javax.swing.JCheckBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * As imagens da galeria do README que mostram a página de UM projeto (a Visão geral, os Logs, o Terminal, os Arquivos, os Processos, os Backups e a Internet) na JANELA INTEIRA, com a barra
 * lateral de servidores, nos dois temas. O projeto é o {@code loja-web}, inventado; o servidor é o SSH embutido com o agente de teste (nada de servidor, conta ou caminho de verdade).
 * Saem em {@code build/galeria/projeto-*-claro.png} e {@code -escuro.png}. O terminal tem o próprio teste ({@code GaleriaTerminalTest}), sem o vigia de EDT (o JediTerm pede repintura da thread dele).
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaProjetoTest {

    @TempDir
    Path pasta;

    @Test
    void visaoGeral() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            TelaProjeto t = c.abrirProjeto(Aba.VISAO_GERAL, DadosDaGaleria.leitorComHistorico(8, c.agora, 2));
            c.esperarODetalhe(t);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "a linha Código: " + c.textos(), () -> c.textos().contains("Ver commits"));
            c.conferir("Visão geral", "loja-web", "Código", "Branch main", "Corrige o cálculo do frete", "2 alterações ainda não salvas", "Ver commits", "lab-loja-web.example.com", "Containers");
            c.fotografar("projeto-visao-geral");
        }
    }

    @Test
    void logs() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            TelaProjeto t = c.abrirProjeto(Aba.LOGS, null);
            c.esperarODetalhe(t);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "os logs: " + c.textos(), () -> c.textos().contains("falha ao enviar o e-mail do pedido 1187"));
            Visual.renderizar(c.painel, CenaDaGaleria.LARGURA, CenaDaGaleria.ALTURA); // a área de logs ganha tamanho; "seguir o fim" leva ao fim (na janela isso acontece sozinho)
            JCheckBox seguir = CenaDaGaleria.pagina(t, Aba.LOGS).chamar("caixaSeguir");
            Visual.naEdt(() -> {
                seguir.doClick();
                seguir.doClick();
            });
            Visual.naEdt(() -> { });
            c.conferir("Logs", "Logs", "loja-web-web-1", "GET /produtos", "falha ao enviar o e-mail do pedido 1187");
            c.fotografar("projeto-logs");
        }
    }

    @Test
    void arquivos() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            TelaProjeto t = c.abrirProjeto(Aba.ARQUIVOS, null);
            GerenciadorDeArquivos g = Visual.naEdt(t::arquivos);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "os arquivos: " + c.textos(), () -> "NORMAL".equals(String.valueOf(Visual.naEdt(g::situacao))) && g.emVoo() == 0);
            c.conferir("Arquivos", "Arquivos", "docker-compose.yml", "README.md", "package.json", "src");
            c.fotografar("projeto-arquivos");
        }
    }

    @Test
    void processos() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            TelaProjeto t = c.abrirProjeto(Aba.PROCESSOS, null);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "os processos do projeto: " + c.textos(), () -> c.textos().contains("loja-web-api-1") && c.textos().contains("postgres"));
            c.conferir("Processos", "Processos do projeto", "node", "postgres", "loja-web-api-1", "loja-web-banco-1");
            assertFalse(c.textos().contains("dockerd"), "só os processos do projeto: " + c.textos());
            c.fotografar("projeto-processos");
        }
    }

    @Test
    void backups() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            TelaProjeto t = c.abrirProjeto(Aba.BACKUPS, null);
            c.esperarODetalhe(t);
            c.conferir("Backups", "Backups (5)", "Baixar", "loja-web-");
            assertTrue(c.textos().contains(".sql.gz"), c.textos());
            c.fotografar("projeto-backups");
        }
    }

    @Test
    void internet() throws Exception {
        try (CenaDaGaleria c = new CenaDaGaleria(pasta)) {
            c.ligarACloudflare(pasta);
            TelaProjeto t = c.abrirProjeto(Aba.INTERNET, null);
            c.esperarODetalhe(t);
            Espera.ate(CenaDaGaleria.PRAZO, () -> "a aba Internet: " + c.textos(), () -> c.textos().contains("lab-loja-web.example.com") && c.textos().contains("Publicar na internet"));
            c.conferir("Internet", "Internet", "Publicar na internet", "lab-loja-web.example.com");
            assertFalse(c.textos().contains("SEM REGRA"), "o endereço tem a regra no túnel: " + c.textos());
            c.fotografar("projeto-internet");
        }
    }
}
