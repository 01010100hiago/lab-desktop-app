package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Conflito;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Decisao;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Escolha;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Enviar e baixar (arquivo e pasta), progresso, cancelamento e as quatro respostas ao "já existe". */
class FilaDeTransferenciasTest {

    private static final String O = CenarioDeSftp.ORBITA;
    private static final Duration PRAZO = Duration.ofSeconds(30);

    private static FilaDeTransferencias fila(CenarioDeSftp c, Function<Conflito, Decisao> decisor) {
        return new FilaDeTransferencias(c.servicoDoProjeto(), decisor::apply, Runnable::run, 2);
    }

    private static Retrato esperarFim(FilaDeTransferencias f, long id) {
        Espera.ate(PRAZO, () -> "retratos=" + f.retratos(), () -> achar(f, id).estado().terminou());
        return achar(f, id);
    }

    private static Retrato achar(FilaDeTransferencias f, long id) {
        return f.retratos().stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    private static Decisao sempre(Escolha e, boolean todos) {
        return new Decisao(e, todos);
    }

    // ---------------------------------------------------------------------------------------------
    // Enviar
    // ---------------------------------------------------------------------------------------------

    @Test
    void enviaUmArquivoComProgressoEMensagemDeResultado(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            byte[] dados = new byte[4 * 1024 * 1024];
            new java.util.Random(1).nextBytes(dados);
            Path local = Files.write(tmp.resolve("relatorio.pdf"), dados);
            List<Retrato> vistos = new CopyOnWriteArrayList<>();
            f.observar(l -> l.forEach(vistos::add));
            long id = f.enviar(List.of(local), O);
            Retrato fim = esperarFim(f, id);
            assertEquals(Estado.CONCLUIDA, fim.estado(), fim.mensagem());
            assertEquals("1 arquivo enviado", fim.mensagem());
            assertEquals(dados.length, fim.bytesFeitos());
            assertEquals(dados.length, fim.bytesTotal());
            assertEquals(1.0, fim.fracao());
            assertEquals("relatorio.pdf para orbita", fim.titulo());
            assertEquals(1, fim.itensFeitos());
            assertTrue(Files.readAllBytes(c.orbita().resolve("relatorio.pdf")).length == dados.length);
            assertTrue(vistos.stream().anyMatch(r -> r.estado() == Estado.EM_ANDAMENTO), "passou por 'em andamento'");
            assertTrue(vistos.stream().anyMatch(r -> r.estado() == Estado.NA_FILA || r.estado() == Estado.EM_ANDAMENTO));
            assertTrue(vistos.stream().anyMatch(r -> r.atual().equals("relatorio.pdf")));
        }
    }

    @Test
    void enviaPastaRecursivaComPastaVaziaESemSeguirLinkLocal(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            Path origem = Files.createDirectories(tmp.resolve("meu-site"));
            Files.writeString(origem.resolve("index.html"), "<h1>oi</h1>");
            Files.createDirectories(origem.resolve("css/temas"));
            Files.writeString(origem.resolve("css/temas/escuro.css"), "body{}");
            Files.createDirectories(origem.resolve("vazia"));
            Path fora = Files.writeString(tmp.resolve("segredo.txt"), "NAO ENVIE");
            Files.createSymbolicLink(origem.resolve("atalho.txt"), fora);
            long id = f.enviar(List.of(origem), O);
            Retrato fim = esperarFim(f, id);
            assertEquals(Estado.CONCLUIDA, fim.estado(), fim.mensagem());
            assertEquals("2 arquivos enviados, 1 link ou item especial ignorado", fim.mensagem());
            assertEquals("<h1>oi</h1>", Files.readString(c.orbita().resolve("meu-site/index.html")));
            assertEquals("body{}", Files.readString(c.orbita().resolve("meu-site/css/temas/escuro.css")));
            assertTrue(Files.isDirectory(c.orbita().resolve("meu-site/vazia")), "pasta vazia também vai");
            assertFalse(Files.exists(c.orbita().resolve("meu-site/atalho.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "link do computador não é seguido");
        }
    }

    @Test
    void enviarVariosItensDeUmaVezDaUmaSoTransferencia(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            Path a = Files.writeString(tmp.resolve("a.txt"), "A");
            Path b = Files.writeString(tmp.resolve("b.txt"), "B");
            long id = f.enviar(List.of(a, b), O);
            Retrato fim = esperarFim(f, id);
            assertEquals("2 itens para orbita", fim.titulo());
            assertEquals("2 arquivos enviados", fim.mensagem());
            assertEquals(1, f.retratos().size());
        }
    }

    @Test
    void conflitoSubstituirPularEManterOsDois(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/relatorio.md", "do servidor");
            Path local = Files.writeString(tmp.resolve("relatorio.md"), "do meu Mac");
            List<Conflito> perguntas = new ArrayList<>();

            try (FilaDeTransferencias f = fila(c, x -> { perguntas.add(x); return sempre(Escolha.PULAR, false); })) {
                Retrato fim = esperarFim(f, f.enviar(List.of(local), O));
                assertEquals("do servidor", Files.readString(c.orbita().resolve("relatorio.md")), "pular não toca no destino");
                assertEquals("0 arquivos enviados, 1 pulado", fim.mensagem());
                assertEquals(1, fim.pulados());
                assertEquals(Estado.CONCLUIDA, fim.estado());
            }
            assertEquals(1, perguntas.size());
            Conflito q = perguntas.get(0);
            assertEquals(FilaDeTransferencias.Tipo.ENVIO, q.tipo());
            assertEquals("relatorio.md", q.nome());
            assertEquals(O + "/relatorio.md", q.destino());
            assertEquals(10, q.tamanhoDaOrigem());
            assertEquals(11, q.tamanhoDoDestino());
            assertNotNull(q.modificadoDoDestino());

            try (FilaDeTransferencias f = fila(c, x -> sempre(Escolha.MANTER_OS_DOIS, false))) {
                Retrato fim = esperarFim(f, f.enviar(List.of(local), O));
                assertEquals("1 arquivo enviado", fim.mensagem());
                assertEquals("do servidor", Files.readString(c.orbita().resolve("relatorio.md")));
                assertEquals("do meu Mac", Files.readString(c.orbita().resolve("relatorio (2).md")), "o novo ganha outro nome");
            }
            try (FilaDeTransferencias f = fila(c, x -> sempre(Escolha.MANTER_OS_DOIS, false))) {
                esperarFim(f, f.enviar(List.of(local), O));
                assertTrue(Files.exists(c.orbita().resolve("relatorio (3).md")), "o segundo 'manter os dois' acha o próximo número livre");
            }
            try (FilaDeTransferencias f = fila(c, x -> sempre(Escolha.SUBSTITUIR, false))) {
                Retrato fim = esperarFim(f, f.enviar(List.of(local), O));
                assertEquals("1 arquivo enviado", fim.mensagem());
                assertEquals("do meu Mac", Files.readString(c.orbita().resolve("relatorio.md")));
            }
        }
    }

    @Test
    void aplicarATodosPerguntaUmaVezSoEValeParaOResto(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            for (String n : new String[] {"a.txt", "b.txt", "c.txt"}) {
                c.escrever(O + "/pasta/" + n, "servidor " + n);
            }
            Path origem = Files.createDirectories(tmp.resolve("pasta"));
            for (String n : new String[] {"a.txt", "b.txt", "c.txt", "d.txt"}) {
                Files.writeString(origem.resolve(n), "mac " + n);
            }
            List<Conflito> perguntas = Collections.synchronizedList(new ArrayList<>());
            try (FilaDeTransferencias f = fila(c, x -> { perguntas.add(x); return sempre(Escolha.SUBSTITUIR, true); })) {
                Retrato fim = esperarFim(f, f.enviar(List.of(origem), O));
                assertEquals(1, perguntas.size(), "'aplicar a todos' não pergunta de novo");
                assertEquals("4 arquivos enviados", fim.mensagem());
                for (String n : new String[] {"a.txt", "b.txt", "c.txt", "d.txt"}) {
                    assertEquals("mac " + n, Files.readString(c.orbita().resolve("pasta/" + n)));
                }
            }
            // sem 'aplicar a todos', pergunta em cada um
            perguntas.clear();
            try (FilaDeTransferencias f = fila(c, x -> { perguntas.add(x); return sempre(Escolha.PULAR, false); })) {
                esperarFim(f, f.enviar(List.of(origem), O));
                assertEquals(4, perguntas.size());
            }
        }
    }

    @Test
    void cancelarNoConflitoPara(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            c.escrever(O + "/a.txt", "servidor");
            Path a = Files.writeString(tmp.resolve("a.txt"), "mac");
            Retrato fim = esperarFim(f, f.enviar(List.of(a), O));
            assertEquals(Estado.CANCELADA, fim.estado());
            assertEquals("servidor", Files.readString(c.orbita().resolve("a.txt")));
        }
    }

    @Test
    void conflitoComUmaPastaDeMesmoNomeEErroDoItemENaoSobrescreve(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.SUBSTITUIR, true))) {
            Files.createDirectories(c.orbita().resolve("dados/importante"));
            Path a = Files.writeString(tmp.resolve("dados"), "sou um arquivo");
            Retrato fim = esperarFim(f, f.enviar(List.of(a), O));
            assertEquals(Estado.FALHOU, fim.estado());
            assertTrue(fim.mensagem().contains("Já existe uma pasta com este nome"), fim.mensagem());
            assertTrue(Files.isDirectory(c.orbita().resolve("dados/importante")));
        }
    }

    @Test
    void cancelarNoMeioDeUmEnvioGrandeParaSemDeixarArquivoPela(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            Path grande = Files.write(tmp.resolve("grande.bin"), new byte[24 * 1024 * 1024]);
            Runnable liberar = c.ssh.segurarEscritasAPartirDe(1024 * 1024); // o envio não termina sozinho antes do cancelamento
            long id = f.enviar(List.of(grande), O);
            Espera.ate(PRAZO, () -> "andando", () -> achar(f, id).bytesFeitos() > 512 * 1024);
            f.cancelar(id);
            liberar.run();
            Retrato fim = esperarFim(f, id);
            assertEquals(Estado.CANCELADA, fim.estado());
            assertEquals("Cancelada.", fim.mensagem());
            try (var s = Files.list(c.orbita())) {
                assertEquals(0, s.count(), "nem o arquivo nem o temporário ficaram no servidor");
            }
        }
    }

    @Test
    void semConexaoNoMeioDizQueDesconectouEFalha(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path a = Files.writeString(tmp.resolve("a.txt"), "A");
            c.conexao.fechar().join();
            try (FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
                Retrato fim = esperarFim(f, f.enviar(List.of(a), O));
                assertEquals(Estado.FALHOU, fim.estado());
                assertTrue(fim.mensagem().contains("desconectou"), fim.mensagem());
            }
        }
    }

    @Test
    void naoEnviaParaForaDaRaizDoModo(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.SUBSTITUIR, true))) {
            Path a = Files.writeString(tmp.resolve("a.txt"), "A");
            Retrato fim = esperarFim(f, f.enviar(List.of(a), O + "/../outro"));
            assertEquals(Estado.FALHOU, fim.estado());
            assertFalse(Files.exists(c.local(CenarioDeSftp.PROJETOS + "/outro/a.txt")));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Baixar
    // ---------------------------------------------------------------------------------------------

    @Test
    void baixaArquivoEPastaRecursivaSemSeguirLink(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            c.escrever(O + "/leia.txt", "leia-me");
            c.escrever(O + "/app/src/main.js", "console.log(1)");
            c.escrever(O + "/app/README.md", "# app");
            Files.createDirectories(c.orbita().resolve("app/vazia"));
            c.escrever(CenarioDeSftp.PROJETOS + "/outro/segredo.txt", "NAO BAIXE");
            Files.createSymbolicLink(c.orbita().resolve("app/espia"), Path.of("../../outro"));
            ServicoDeArquivos s = c.servicoDoProjeto();
            List<EntradaRemota> itens = List.of(s.estatisticas(O + "/leia.txt"), s.estatisticas(O + "/app"));
            Path destino = tmp.resolve("Downloads");
            long id = f.baixar(itens, destino);
            Retrato fim = esperarFim(f, id);
            assertEquals(Estado.CONCLUIDA, fim.estado(), fim.mensagem());
            assertEquals("3 arquivos baixados, 1 link ou item especial ignorado", fim.mensagem());
            assertEquals("leia-me", Files.readString(destino.resolve("leia.txt")));
            assertEquals("console.log(1)", Files.readString(destino.resolve("app/src/main.js")));
            assertEquals("# app", Files.readString(destino.resolve("app/README.md")));
            assertTrue(Files.isDirectory(destino.resolve("app/vazia")));
            assertFalse(Files.exists(destino.resolve("app/espia")), "o link do servidor não é seguido");
            try (var st = Files.walk(destino)) {
                assertTrue(st.noneMatch(p -> p.getFileName().toString().contains("parcial")));
            }
        }
    }

    @Test
    void downloadComConflitoLocalPerguntaEMantemOsDois(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/foto.txt", "do servidor");
            Path destino = Files.createDirectories(tmp.resolve("Downloads"));
            Files.writeString(destino.resolve("foto.txt"), "que já estava no Mac");
            ServicoDeArquivos s = c.servicoDoProjeto();
            EntradaRemota e = s.estatisticas(O + "/foto.txt");
            List<Conflito> perguntas = new ArrayList<>();
            try (FilaDeTransferencias f = fila(c, x -> { perguntas.add(x); return sempre(Escolha.MANTER_OS_DOIS, false); })) {
                Retrato fim = esperarFim(f, f.baixar(List.of(e), destino));
                assertEquals("1 arquivo baixado", fim.mensagem());
            }
            assertEquals("que já estava no Mac", Files.readString(destino.resolve("foto.txt")));
            assertEquals("do servidor", Files.readString(destino.resolve("foto (2).txt")));
            assertEquals(FilaDeTransferencias.Tipo.DOWNLOAD, perguntas.get(0).tipo());

            try (FilaDeTransferencias f = fila(c, x -> sempre(Escolha.SUBSTITUIR, false))) {
                esperarFim(f, f.baixar(List.of(e), destino));
            }
            assertEquals("do servidor", Files.readString(destino.resolve("foto.txt")));
            try (FilaDeTransferencias f = fila(c, x -> sempre(Escolha.PULAR, false))) {
                Files.writeString(destino.resolve("foto.txt"), "mudei de novo");
                Retrato fim = esperarFim(f, f.baixar(List.of(e), destino));
                assertEquals(1, fim.pulados());
                assertEquals("mudei de novo", Files.readString(destino.resolve("foto.txt")));
            }
        }
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void umArquivoSemPermissaoNaoDerrubaOsOutros(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            c.escrever(O + "/pasta/ok.txt", "ok");
            Path trancado = c.escrever(O + "/pasta/trancado.txt", "segredo");
            Files.setPosixFilePermissions(trancado, PosixFilePermissions.fromString("---------"));
            try {
                EntradaRemota pasta = c.servicoDoProjeto().estatisticas(O + "/pasta");
                Path destino = tmp.resolve("saida");
                Retrato fim = esperarFim(f, f.baixar(List.of(pasta), destino));
                assertEquals(Estado.COM_PROBLEMAS, fim.estado(), fim.mensagem());
                assertEquals(1, fim.erros());
                assertTrue(fim.mensagem().contains("trancado.txt: Sem permissão"), fim.mensagem());
                assertEquals("ok", Files.readString(destino.resolve("pasta/ok.txt")));
                assertFalse(Files.exists(destino.resolve("pasta/trancado.txt")), "sem arquivo pela metade");
            } finally {
                Files.setPosixFilePermissions(trancado, PosixFilePermissions.fromString("rw-------"));
            }
        }
    }

    @Test
    void linkParaPastaNoTopoNaoEBaixadoELinkParaArquivoE(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp); FilaDeTransferencias f = fila(c, x -> sempre(Escolha.CANCELAR, false))) {
            c.escrever(O + "/real.txt", "conteudo");
            Files.createDirectories(c.orbita().resolve("dados"));
            Files.createSymbolicLink(c.orbita().resolve("l-arq"), Path.of("real.txt"));
            Files.createSymbolicLink(c.orbita().resolve("l-pasta"), Path.of("dados"));
            ListagemDePasta l = c.servicoDoProjeto().listar(O, 20, n -> true);
            List<EntradaRemota> itens = l.entradas().stream().filter(e -> e.nome().startsWith("l-")).toList();
            Path destino = tmp.resolve("d");
            Retrato fim = esperarFim(f, f.baixar(itens, destino));
            assertEquals("conteudo", Files.readString(destino.resolve("l-arq")));
            assertFalse(Files.exists(destino.resolve("l-pasta")));
            assertEquals("1 arquivo baixado, 1 link ou item especial ignorado", fim.mensagem());
        }
    }

    @Test
    void duasTransferenciasPorVezEAsOutrasEsperamNaFila(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            List<Retrato> vistos = new CopyOnWriteArrayList<>();
            try (FilaDeTransferencias f = new FilaDeTransferencias(c.servicoDoProjeto(), x -> sempre(Escolha.CANCELAR, false), Runnable::run, 1)) {
                f.observar(l -> vistos.addAll(l));
                List<Long> ids = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    ids.add(f.enviar(List.of(Files.write(tmp.resolve("g" + i + ".bin"), new byte[3 * 1024 * 1024])), O));
                }
                for (long id : ids) {
                    esperarFim(f, id);
                }
                assertTrue(vistos.stream().anyMatch(r -> r.estado() == Estado.NA_FILA), "alguma ficou esperando a vaga");
                assertTrue(f.retratos().stream().allMatch(r -> r.estado() == Estado.CONCLUIDA));
                assertEquals(0, f.emAberto());
                f.limparConcluidas();
                assertTrue(f.retratos().isEmpty());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Funções puras
    // ---------------------------------------------------------------------------------------------

    @Test
    void nomeComNumeroParaManterOsDois() {
        assertEquals("relatorio (2).md", FilaDeTransferencias.comNumero("relatorio.md", 2));
        assertEquals("LEIA (3)", FilaDeTransferencias.comNumero("LEIA", 3));
        assertEquals(".env (2)", FilaDeTransferencias.comNumero(".env", 2));
        assertEquals("a.tar (2).gz", FilaDeTransferencias.comNumero("a.tar.gz", 2));
    }

    @Test
    void nomeVindoDoServidorNuncaEscapaDaPastaLocal(@TempDir Path tmp) {
        assertEquals(tmp.resolve("ok.txt"), FilaDeTransferencias.dentroDe(tmp, "ok.txt"));
        for (String ruim : new String[] {"../fora.txt", "a/b", "..", ".", "", "x\ny", "/etc/passwd"}) {
            assertNull(FilaDeTransferencias.dentroDe(tmp, ruim), ruim);
        }
    }

    @Test
    void somaDoQueVaiSerEnviadoParaOAvisoDe500MB(@TempDir Path tmp) throws IOException {
        Files.write(tmp.resolve("a.bin"), new byte[1000]);
        Files.createDirectories(tmp.resolve("p/q"));
        Files.write(tmp.resolve("p/q/b.bin"), new byte[234]);
        assertEquals(1234, FilaDeTransferencias.somarLocal(List.of(tmp.resolve("a.bin"), tmp.resolve("p"))));
        assertEquals(500L * 1024 * 1024, FilaDeTransferencias.LIMITE_DE_AVISO);
    }
}
