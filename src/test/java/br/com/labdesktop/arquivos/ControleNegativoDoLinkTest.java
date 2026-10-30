package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.teste.SistemaDoTeste;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controle negativo, só para a prova no CI do Windows: o que o app antigo fazia com um link relativo para fora da pasta do projeto. */
class ControleNegativoDoLinkTest {

    @TempDir
    Path tmp;

    @Test
    void oAppAntigoListaOQueEstaForaDaPastaDoProjeto() throws Exception {
        assumeTrue(SistemaDoTeste.windows(), "só no Windows");
        Path real = tmp.toRealPath();
        Path raiz = Files.createDirectories(real.resolve("lab"));
        Path projeto = Files.createDirectories(raiz.resolve("projeto"));
        Path fora = Files.createDirectories(real.resolve("fora"));
        Files.writeString(fora.resolve("segredo.txt"), "x");
        Files.createSymbolicLink(projeto.resolve("escapa"), Path.of("..\\..\\fora"));
        ClienteSftpLocal c = new ClienteSftpLocal(SistemaDoTeste.caminhos(), SistemaDoTeste.app(real));
        ServicoDeArquivos s = new ServicoDeArquivos(c, Escopo.projeto(SistemaDoTeste.app(raiz), "projeto", SistemaDoTeste.app(real)));
        String que;
        try {
            que = "LISTOU: " + s.listar(SistemaDoTeste.app(projeto.resolve("escapa")), 100, n -> true).entradas().stream().map(EntradaRemota::nome).toList();
        } catch (ErroSftp e) {
            que = "RECUSOU: " + e.tipo() + " " + e.getMessage();
        } catch (RuntimeException e) {
            que = "EXCECAO: " + e;
        }
        fail("CONTROLE-NEGATIVO " + que + " | lerLink=" + safe(c, SistemaDoTeste.app(projeto.resolve("escapa"))));
    }

    private static String safe(ClienteSftpLocal c, String caminho) {
        try {
            return c.lerLink(caminho);
        } catch (ErroSftp e) {
            return "erro " + e.getMessage();
        }
    }
}
