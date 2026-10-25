package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.ServicoDeArquivos.ResultadoDoLote;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** As regras do modo (projeto e servidor) sobre um servidor SFTP de verdade: cerca, links, protegidos. */
class ServicoDeArquivosTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static ErroSftp.Tipo tipoDe(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ErroSftp.class, e).tipo();
    }

    // ---------------------------------------------------------------------------------------------
    // Cerca
    // ---------------------------------------------------------------------------------------------

    @Test
    void modoProjetoNaoSaiDaPastaDoProjetoPorCaminho(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            ServicoDeArquivos s = c.servicoDoProjeto();
            for (String fuga : new String[] {O + "/..", O + "/../outro", "/", "/etc", CenarioDeSftp.PROJETOS, O + "/a/../../outro/x"}) {
                assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.listar(fuga, 10, n -> true)), fuga);
            }
            assertEquals(0, s.listar(O + "/sub/../", 10, n -> true).total());
        }
    }

    @Test
    void linkParaForaDaRaizNaoSeSegue(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.PROJETOS + "/outro/segredo.txt", "do outro projeto");
            Files.createSymbolicLink(c.orbita().resolve("para-outro"), Path.of("../outro"));
            Files.createSymbolicLink(c.orbita().resolve("para-tudo"), Path.of("../../../.."));
            Files.createSymbolicLink(c.orbita().resolve("para-segredo"), Path.of("../outro/segredo.txt"));
            ServicoDeArquivos s = c.servicoDoProjeto();
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.listar(O + "/para-outro", 10, n -> true)));
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.listar(O + "/para-tudo", 10, n -> true)));
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.listar(O + "/para-outro/x", 10, n -> true)), "nem por baixo do link");
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.resolverDentroDaRaiz(O + "/para-segredo")));
            // a listagem da pasta do projeto mostra o link (como link), sem entrar nele
            ListagemDePasta l = s.listar(O, 100, n -> true);
            assertTrue(l.entradas().stream().anyMatch(e -> e.nome().equals("para-outro") && e.link()));
        }
    }

    @Test
    void linkParaDentroDaRaizSeguraEAbre(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/real/dentro.txt", "x");
            Files.createSymbolicLink(c.orbita().resolve("atalho"), Path.of("real"));
            Files.createSymbolicLink(c.orbita().resolve("abs"), c.orbita().resolve("real")); // absoluto no disco do teste
            ServicoDeArquivos s = c.servicoDoProjeto();
            assertEquals(O + "/real", s.resolverDentroDaRaiz(O + "/atalho"));
            ListagemDePasta l = s.listar(O + "/atalho", 10, n -> true);
            assertEquals(1, l.total());
            assertEquals(O + "/atalho/dentro.txt", l.entradas().get(0).caminho(), "mantém o caminho pelo qual o usuário chegou");
        }
    }

    @Test
    void lacoDeLinksNaoTravaEViraRecusa(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createSymbolicLink(c.orbita().resolve("a"), Path.of("b"));
            Files.createSymbolicLink(c.orbita().resolve("b"), Path.of("a"));
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> c.servicoDoProjeto().listar(O + "/a", 10, n -> true)));
        }
    }

    @Test
    void modoServidorProjetosNaoSobeAcimaDaPastaDosProjetos(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            ServicoDeArquivos s = c.servicoDoServidor(RaizDoServidor.PROJETOS);
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.listar("/srv/lab", 10, n -> true)));
            assertEquals(2, s.listar(CenarioDeSftp.PROJETOS, 10, n -> true).total());
            assertEquals(ErroSftp.Tipo.NOME_INVALIDO, tipoDe(() -> s.listar("relativo", 10, n -> true)));
        }
    }

    @Test
    void modoTudoVaiAteARaiz(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            ServicoDeArquivos s = c.servicoDoServidor(RaizDoServidor.TUDO);
            assertTrue(s.listar("/", 10, n -> true).entradas().stream().anyMatch(e -> e.nome().equals("srv")));
            assertTrue(s.listar("/srv/lab", 10, n -> true).entradas().stream().anyMatch(e -> e.nome().equals("projects")));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Criar, renomear, mover
    // ---------------------------------------------------------------------------------------------

    @Test
    void criaPastaEArquivoComNomeValidado(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            ServicoDeArquivos s = c.servicoDoProjeto();
            assertEquals(O + "/docs", s.criarPasta(O, "docs"));
            assertEquals(O + "/docs/notas.md", s.criarArquivoVazio(O + "/docs", "notas.md"));
            assertTrue(Files.isDirectory(c.orbita().resolve("docs")));
            assertTrue(Files.isRegularFile(c.orbita().resolve("docs/notas.md")));
            for (String ruim : new String[] {"../fora", "a/b", "", "..", "x\ny"}) {
                assertEquals(ErroSftp.Tipo.NOME_INVALIDO, tipoDe(() -> s.criarPasta(O, ruim)), ruim);
            }
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.criarPasta(O + "/..", "x")));
            assertFalse(Files.exists(c.local(CenarioDeSftp.PROJETOS + "/x")));
        }
    }

    @Test
    void renomearMudaONomeNaMesmaPastaERecusaOQueExiste(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/a.txt", "A");
            c.escrever(O + "/b.txt", "B");
            ServicoDeArquivos s = c.servicoDoProjeto();
            EntradaRemota a = s.estatisticas(O + "/a.txt");
            assertEquals(O + "/novo.txt", s.renomear(a, "novo.txt"));
            assertEquals("A", Files.readString(c.orbita().resolve("novo.txt")));
            assertEquals(ErroSftp.Tipo.JA_EXISTE, tipoDe(() -> s.renomear(s.estatisticas(O + "/novo.txt"), "b.txt")));
            assertEquals(ErroSftp.Tipo.NOME_INVALIDO, tipoDe(() -> s.renomear(s.estatisticas(O + "/b.txt"), "../escapou.txt")));
            assertFalse(Files.exists(c.local(CenarioDeSftp.PROJETOS + "/escapou.txt")));
        }
    }

    @Test
    void naoRenomeiaARaizDoModo(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            ServicoDeArquivos s = c.servicoDoProjeto();
            EntradaRemota raiz = new EntradaRemota("orbita", O, EntradaRemota.Tipo.PASTA, false, 0, null, 0755, "", "", null);
            assertEquals(ErroSftp.Tipo.PROTEGIDO, tipoDe(() -> s.renomear(raiz, "outro-nome")));
            assertTrue(Files.isDirectory(c.orbita()));
        }
    }

    @Test
    void moverVariosComRelatorioDoQueNaoDeu(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/a.txt", "A");
            c.escrever(O + "/b.txt", "B");
            c.escrever(O + "/destino/b.txt", "ja existia");
            Files.createDirectories(c.orbita().resolve("pasta"));
            ServicoDeArquivos s = c.servicoDoProjeto();
            ResultadoDoLote r = s.mover(List.of(s.estatisticas(O + "/a.txt"), s.estatisticas(O + "/b.txt")), O + "/destino");
            assertEquals(1, r.feitos());
            assertEquals(1, r.falhas().size());
            assertEquals("b.txt", r.falhas().get(0).nome());
            assertTrue(r.falhas().get(0).mensagem().contains("Já existe"), r.falhas().get(0).mensagem());
            assertEquals("A", Files.readString(c.orbita().resolve("destino/a.txt")));
            assertEquals("ja existia", Files.readString(c.orbita().resolve("destino/b.txt")), "nada foi sobrescrito");
            assertEquals("B", Files.readString(c.orbita().resolve("b.txt")), "o que não foi continua onde estava");
        }
    }

    @Test
    void moverUmaPastaParaDentroDelaMesmaOuFora(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createDirectories(c.orbita().resolve("a/b"));
            ServicoDeArquivos s = c.servicoDoProjeto();
            EntradaRemota a = s.estatisticas(O + "/a");
            ResultadoDoLote r = s.mover(List.of(a), O + "/a/b");
            assertEquals(0, r.feitos());
            assertTrue(r.falhas().get(0).mensagem().contains("dentro dela mesma"), r.falhas().get(0).mensagem());
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.mover(List.of(a), CenarioDeSftp.PROJETOS + "/outro")));
            assertTrue(Files.isDirectory(c.orbita().resolve("a/b")));
            // mover para a pasta onde já está não é erro nem faz nada
            assertEquals(0, s.mover(List.of(a), O).feitos());
            assertTrue(s.mover(List.of(a), O).tudoCerto());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Apagar
    // ---------------------------------------------------------------------------------------------

    @Test
    void apagarRecusaARaizDoModoARaizDosProjetosAHomeEBarra(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            ServicoDeArquivos projeto = c.servicoDoProjeto();
            ServicoDeArquivos tudo = c.servicoDoServidor(RaizDoServidor.TUDO);
            EntradaRemota raizDoProjeto = pasta("orbita", O);
            EntradaRemota raizDosProjetos = pasta("projects", CenarioDeSftp.PROJETOS);
            EntradaRemota lab = pasta("lab", "/srv/lab");
            EntradaRemota barra = pasta("/", "/");
            assertEquals(ErroSftp.Tipo.PROTEGIDO, tipoDe(() -> projeto.apagar(raizDoProjeto)));
            assertEquals(ErroSftp.Tipo.PROTEGIDO, tipoDe(() -> tudo.apagar(raizDosProjetos)));
            assertEquals(ErroSftp.Tipo.PROTEGIDO, tipoDe(() -> tudo.apagar(lab)), "ancestral da raiz dos projetos");
            assertEquals(ErroSftp.Tipo.PROTEGIDO, tipoDe(() -> tudo.apagar(barra)));
            assertEquals(ErroSftp.Tipo.PROTEGIDO, tipoDe(() -> tudo.resumirParaApagar(raizDosProjetos, Cancelamento.NUNCA)));
            assertTrue(Files.isDirectory(c.orbita()));
            assertTrue(Files.isDirectory(c.local(CenarioDeSftp.PROJETOS + "/outro")));
        }
    }

    private static EntradaRemota pasta(String nome, String caminho) {
        return new EntradaRemota(nome, caminho, EntradaRemota.Tipo.PASTA, false, 0, null, 0755, "", "", null);
    }

    @Test
    void apagarNoModoProjetoNaoAlcancaForaDaPasta(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.PROJETOS + "/outro/a.txt", "x");
            EntradaRemota outro = pasta("outro", CenarioDeSftp.PROJETOS + "/outro");
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> c.servicoDoProjeto().apagar(outro)));
            EntradaRemota disfarcado = pasta("x", O + "/../outro");
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> c.servicoDoProjeto().apagar(disfarcado)));
            assertTrue(Files.exists(c.local(CenarioDeSftp.PROJETOS + "/outro/a.txt")));
        }
    }

    @Test
    void apagarPastaComResumoEDepoisApagaTudoSemSeguirLink(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/build/a/1.txt", "12345");
            c.escrever(O + "/build/2.txt", "67");
            Files.createSymbolicLink(c.orbita().resolve("build/para-fora"), Path.of("../../outro"));
            c.escrever(CenarioDeSftp.PROJETOS + "/outro/vivo.txt", "vivo");
            ServicoDeArquivos s = c.servicoDoProjeto();
            EntradaRemota build = s.estatisticas(O + "/build");
            ResumoDeTamanho r = s.resumirParaApagar(build, Cancelamento.NUNCA);
            assertEquals(1, r.pastas());
            assertTrue(r.itens() >= 3, "dois arquivos, a pasta interna e o link");
            s.apagar(build);
            assertFalse(Files.exists(c.orbita().resolve("build")));
            assertEquals("vivo", Files.readString(c.local(CenarioDeSftp.PROJETOS + "/outro/vivo.txt")));
        }
    }

    @Test
    void apagarUmLinkParaPastaDeFora_apagaSoOLink(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.PROJETOS + "/outro/vivo.txt", "vivo");
            Files.createSymbolicLink(c.orbita().resolve("atalho"), Path.of("../outro"));
            ServicoDeArquivos s = c.servicoDoProjeto();
            ListagemDePasta l = s.listar(O, 10, n -> true);
            EntradaRemota atalho = l.entradas().stream().filter(e -> e.nome().equals("atalho")).findFirst().orElseThrow();
            assertTrue(atalho.link());
            s.apagar(atalho);
            assertFalse(Files.exists(c.orbita().resolve("atalho"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("vivo", Files.readString(c.local(CenarioDeSftp.PROJETOS + "/outro/vivo.txt")));
        }
    }
}
