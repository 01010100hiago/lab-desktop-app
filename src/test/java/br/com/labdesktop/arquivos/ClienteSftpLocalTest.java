package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.EntradaRemota.Tipo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Os arquivos de "Este computador": o {@link ClienteSftp} sobre {@code java.nio}, com as mesmas regras do cliente do SFTP. */
class ClienteSftpLocalTest {

    @TempDir
    Path tmp;

    private ClienteSftpLocal c;
    private Path raiz;

    @BeforeEach
    void montar() throws Exception {
        raiz = tmp.toRealPath().resolve("lab");
        Files.createDirectories(raiz);
        c = new ClienteSftpLocal(new CaminhosLocais(false), tmp.toRealPath().toString());
    }

    private String t(String relativo) {
        return raiz.resolve(relativo).toString();
    }

    private static ErroSftp.Tipo tipoDe(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ErroSftp.class, e).tipo();
    }

    @Test
    void listaComTipoTamanhoPermissaoEOrdemQueATelaEsperaEEscondeNadaAlemDePontoEPontoPonto() throws Exception {
        Files.createDirectories(raiz.resolve("pasta"));
        Files.writeString(raiz.resolve("a.txt"), "12345");
        Files.writeString(raiz.resolve(".oculto"), "x");
        Files.setPosixFilePermissions(raiz.resolve("a.txt"), PosixFilePermissions.fromString("rw-r-----"));

        ListagemDePasta l = c.listar(raiz.toString(), 100);

        assertEquals(3, l.total());
        assertFalse(l.truncada());
        EntradaRemota a = l.entradas().stream().filter(e -> e.nome().equals("a.txt")).findFirst().orElseThrow();
        assertEquals(Tipo.ARQUIVO, a.tipo());
        assertEquals(5, a.tamanho());
        assertEquals(0640, a.permissoes());
        assertEquals(System.getProperty("user.name"), a.dono());
        assertEquals(raiz.resolve("a.txt").toString(), a.caminho());
        assertTrue(l.entradas().stream().anyMatch(e -> e.nome().equals("pasta") && e.ehPasta()));
        assertTrue(l.entradas().stream().anyMatch(e -> e.nome().equals(".oculto") && e.oculto()));
        assertTrue(l.entradas().stream().noneMatch(e -> e.nome().equals(".") || e.nome().equals("..")));
    }

    @Test
    void limiteEFiltroPorNome() throws Exception {
        for (int i = 0; i < 12; i++) {
            Files.writeString(raiz.resolve("f" + i), "x");
        }
        ListagemDePasta l = c.listar(raiz.toString(), 5);
        assertEquals(5, l.entradas().size());
        assertEquals(12, l.total());
        assertTrue(l.truncada());
        assertEquals(1, c.listar(raiz.toString(), 5, n -> n.equals("f3")).total());
    }

    @Test
    void linkTemOTipoDoDestinoEOQuebradoViraLinkQuebrado() throws Exception {
        Files.createDirectories(raiz.resolve("real"));
        Files.createSymbolicLink(raiz.resolve("atalho"), raiz.resolve("real"));
        Files.createSymbolicLink(raiz.resolve("quebrado"), raiz.resolve("nao-existe"));
        ListagemDePasta l = c.listar(raiz.toString(), 100);
        EntradaRemota atalho = l.entradas().stream().filter(e -> e.nome().equals("atalho")).findFirst().orElseThrow();
        assertTrue(atalho.link());
        assertEquals(Tipo.PASTA, atalho.tipo(), "vê através do link");
        assertEquals(raiz.resolve("real").toString(), atalho.destinoDoLink());
        assertEquals(Tipo.LINK_QUEBRADO, l.entradas().stream().filter(e -> e.nome().equals("quebrado")).findFirst().orElseThrow().tipo());
        EntradaRemota pura = c.estatisticas(t("atalho"));
        assertTrue(pura.link(), "estatisticas não segue o link");
        assertEquals(raiz.resolve("real").toString(), c.lerLink(t("atalho")));
    }

    @Test
    void erroDeLeituraVemEmPortuguesComOTipoCerto() {
        assertEquals(ErroSftp.Tipo.NAO_EXISTE, tipoDe(() -> c.listar(t("nao-existe"), 10)));
        assertEquals(ErroSftp.Tipo.NOME_INVALIDO, tipoDe(() -> c.listar("relativo", 10)));
        assertEquals(ErroSftp.Tipo.NOME_INVALIDO, tipoDe(() -> c.listar(t("a\nb"), 10)));
        assertEquals(ErroSftp.Tipo.NAO_EXISTE, tipoDe(() -> c.lerArquivo(t("nao-existe"), 10)));
    }

    @Test
    void lerArquivoRespeitaOLimiteEExisteVeLinkQuebrado() throws Exception {
        Files.writeString(raiz.resolve("g.txt"), "0123456789");
        assertArrayEquals("0123".getBytes(StandardCharsets.UTF_8), c.lerArquivo(t("g.txt"), 4));
        Files.createSymbolicLink(raiz.resolve("q"), raiz.resolve("zzz"));
        assertTrue(c.existe(t("q")), "um link quebrado conta: ele existe");
        assertFalse(c.existe(t("nada")));
    }

    @Test
    void gravarAtomicoTrocaPorCimaSemDeixarTemporarioEMantemAsPermissoes() throws Exception {
        Path f = raiz.resolve("conf.txt");
        Files.writeString(f, "velho");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));

        c.gravarAtomico(f.toString(), "novo conteúdo".getBytes(StandardCharsets.UTF_8), -1);

        assertEquals("novo conteúdo", Files.readString(f));
        assertEquals(0644, ClienteSftpLocal.bitsDe(Files.getPosixFilePermissions(f)), "-1 mantém as do arquivo existente");
        try (var s = Files.list(raiz)) {
            assertEquals(List.of("conf.txt"), s.map(p -> p.getFileName().toString()).toList(), "nenhum temporário sobrou");
        }
        c.gravarAtomico(t("novo.txt"), "x".getBytes(StandardCharsets.UTF_8), 0600);
        assertEquals(0600, ClienteSftpLocal.bitsDe(Files.getPosixFilePermissions(raiz.resolve("novo.txt"))));
    }

    @Test
    void gravarEmPastaSemPermissaoDizSemPermissaoESemLixo() throws Exception {
        Path ro = raiz.resolve("somente-leitura");
        Files.createDirectories(ro);
        Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
            assertEquals(ErroSftp.Tipo.SEM_PERMISSAO, tipoDe(() -> c.gravarAtomico(ro.resolve("x").toString(), new byte[] {1}, -1)));
        } finally {
            Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void enviarEBaixarCopiamComProgressoEConferemOTamanho(@TempDir Path fora) throws Exception {
        byte[] dados = new byte[300_000];
        new java.util.Random(7).nextBytes(dados);
        Path origem = fora.resolve("origem.bin");
        Files.write(origem, dados);
        AtomicLong ultimo = new AtomicLong();

        c.enviar(origem, t("enviado.bin"), (feitos, total) -> {
            ultimo.set(feitos);
            assertEquals(dados.length, total);
        }, Cancelamento.NUNCA);
        assertArrayEquals(dados, Files.readAllBytes(raiz.resolve("enviado.bin")));
        assertEquals(dados.length, ultimo.get());

        Path baixado = fora.resolve("baixado.bin");
        c.baixar(t("enviado.bin"), baixado, Progresso.NENHUM, Cancelamento.NUNCA);
        assertArrayEquals(dados, Files.readAllBytes(baixado));
    }

    @Test
    void cancelarNoMeioApagaOTemporarioEDeixaODestinoComoEstava(@TempDir Path fora) throws Exception {
        Path origem = fora.resolve("grande.bin");
        Files.write(origem, new byte[1_000_000]);
        Files.writeString(raiz.resolve("destino.bin"), "antigo");
        Cancelamento cancela = new Cancelamento();
        ErroSftp e = assertThrows(ErroSftp.class, () -> c.enviar(origem, t("destino.bin"), (feitos, total) -> cancela.cancelar(), cancela));
        assertEquals(ErroSftp.Tipo.CANCELADO, e.tipo());
        assertEquals("antigo", Files.readString(raiz.resolve("destino.bin")));
        try (var s = Files.list(raiz)) {
            assertEquals(1, s.count(), "o temporário foi apagado");
        }
    }

    @Test
    void criarRenomearEJaExiste() throws Exception {
        c.criarPasta(t("nova"));
        assertEquals(ErroSftp.Tipo.JA_EXISTE, tipoDe(() -> c.criarPasta(t("nova"))));
        c.criarArquivoVazio(t("vazio.txt"));
        assertEquals(ErroSftp.Tipo.JA_EXISTE, tipoDe(() -> c.criarArquivoVazio(t("vazio.txt"))));
        Files.writeString(raiz.resolve("vazio.txt"), "não pode zerar");
        assertEquals(ErroSftp.Tipo.JA_EXISTE, tipoDe(() -> c.criarArquivoVazio(t("vazio.txt"))));
        assertEquals("não pode zerar", Files.readString(raiz.resolve("vazio.txt")), "nunca zera um arquivo existente");
        c.renomear(t("vazio.txt"), t("renomeado.txt"));
        assertTrue(Files.exists(raiz.resolve("renomeado.txt")));
        Files.writeString(raiz.resolve("outro.txt"), "x");
        assertEquals(ErroSftp.Tipo.JA_EXISTE, tipoDe(() -> c.renomear(t("renomeado.txt"), t("outro.txt"))), "nunca sobrescreve");
        assertEquals("x", Files.readString(raiz.resolve("outro.txt")));
    }

    @Test
    void apagarNuncaSegueLinkEPastaNaoVaziaSoComRecursivo(@TempDir Path fora) throws Exception {
        Files.writeString(fora.resolve("importante.txt"), "fora da pasta");
        Files.createDirectories(raiz.resolve("p/sub"));
        Files.writeString(raiz.resolve("p/sub/x.txt"), "x");
        Files.createSymbolicLink(raiz.resolve("p/para-fora"), fora);

        assertEquals(ErroSftp.Tipo.PASTA_NAO_VAZIA, tipoDe(() -> c.apagar(t("p"), false)));
        c.apagar(t("p"), true);

        assertFalse(Files.exists(raiz.resolve("p")));
        assertTrue(Files.exists(fora.resolve("importante.txt")), "o link foi apagado como link: o destino ficou intacto");
        Files.createSymbolicLink(raiz.resolve("l"), fora);
        c.apagar(t("l"), false);
        assertTrue(Files.exists(fora), "apagar um link nunca apaga o que ele aponta");
    }

    @Test
    void resumirContaArquivosPastasEBytesSemSeguirLinks(@TempDir Path fora) throws Exception {
        Files.createDirectories(raiz.resolve("d/e"));
        Files.writeString(raiz.resolve("d/a"), "123");
        Files.writeString(raiz.resolve("d/e/b"), "45");
        Files.writeString(fora.resolve("grande"), "x".repeat(1000));
        Files.createSymbolicLink(raiz.resolve("d/l"), fora);
        ResumoDeTamanho r = c.resumir(t("d"), Cancelamento.NUNCA);
        assertEquals(3, r.arquivos(), "a, b e o link (como item)");
        assertEquals(1, r.pastas());
        assertEquals(5, r.bytes(), "o conteúdo de fora do link não entra");
        assertTrue(r.completo());
    }

    @Test
    void caminhoCanonicoDoPontoEAPastaPessoalEOResto() throws Exception {
        assertEquals(tmp.toRealPath().toString(), c.caminhoCanonico("."));
        assertEquals("/a/c", c.caminhoCanonico("/a/b/../c"));
    }

    @Test
    void fechadoNaoOperaMais() {
        c.close();
        assertEquals(ErroSftp.Tipo.SEM_CONEXAO, tipoDe(() -> c.listar(raiz.toString(), 10)));
    }

    @Test
    void servicoDeArquivosComACercaFuncionaIgualSobreOClienteLocal() throws Exception {
        Files.createDirectories(raiz.resolve("projeto"));
        Files.writeString(raiz.resolve("projeto/README.md"), "# oi");
        Path fora = Files.createDirectories(tmp.resolve("fora"));
        Files.createSymbolicLink(raiz.resolve("projeto/escapa"), fora);
        String casa = tmp.toRealPath().toString();
        ServicoDeArquivos s = new ServicoDeArquivos(c, Escopo.projeto(raiz.toString(), "projeto", casa));
        ListagemDePasta l = s.listar(raiz.resolve("projeto").toString(), 100, n -> true);
        assertTrue(l.entradas().stream().anyMatch(e -> e.nome().equals("README.md")));
        assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, tipoDe(() -> s.listar(raiz.resolve("projeto/escapa").toString(), 100, n -> true)),
            "a cerca resolve o link e recusa o que sai da pasta do projeto: igual ao remoto");
    }

    /** Os atributos de uma JUNÇÃO do Windows como o Java a vê: pasta comum, mas "outra coisa" (ponto de reparse), e não link simbólico. */
    private record AtributosDeJuncao(boolean pasta, boolean outra, boolean simbolico) implements java.nio.file.attribute.BasicFileAttributes {
        @Override public java.nio.file.attribute.FileTime lastModifiedTime() { return java.nio.file.attribute.FileTime.fromMillis(0); }
        @Override public java.nio.file.attribute.FileTime lastAccessTime() { return java.nio.file.attribute.FileTime.fromMillis(0); }
        @Override public java.nio.file.attribute.FileTime creationTime() { return java.nio.file.attribute.FileTime.fromMillis(0); }
        @Override public boolean isRegularFile() { return false; }
        @Override public boolean isDirectory() { return pasta; }
        @Override public boolean isSymbolicLink() { return simbolico; }
        @Override public boolean isOther() { return outra; }
        @Override public long size() { return 0; }
        @Override public Object fileKey() { return null; }
    }

    @Test
    void umaJuncaoDoWindowsEApagadaSemDescerPorElaEOConteudoDoDestinoFica(@TempDir Path destino) throws Exception {
        Path alvo = Files.createDirectories(destino.toRealPath().resolve("destino"));
        Files.writeString(alvo.resolve("precioso.txt"), "nao apague");
        Path juncao = raiz.resolve("pasta").resolve("juncao");
        Files.createDirectories(juncao.getParent());
        Files.createSymbolicLink(juncao, alvo); // no Mac faz o papel do ponto de reparse: o que importa é o que o visitante decide

        var visitante = ClienteSftpLocal.visitanteDeApagar(raiz.resolve("pasta"));
        var resultado = visitante.preVisitDirectory(juncao, new AtributosDeJuncao(true, true, false));

        assertEquals(java.nio.file.FileVisitResult.SKIP_SUBTREE, resultado, "não desce pela junção");
        assertFalse(Files.exists(juncao, java.nio.file.LinkOption.NOFOLLOW_LINKS), "a junção em si foi apagada");
        assertTrue(Files.exists(alvo.resolve("precioso.txt")), "o conteúdo do destino ficou");
        // uma pasta de verdade segue o caminho normal
        assertEquals(java.nio.file.FileVisitResult.CONTINUE, visitante.preVisitDirectory(raiz.resolve("pasta").resolve("x"), new AtributosDeJuncao(true, false, false)));
        // e a raiz do apagar nunca é tratada como junção (é o que o usuário mandou apagar)
        assertEquals(java.nio.file.FileVisitResult.CONTINUE, visitante.preVisitDirectory(raiz.resolve("pasta"), new AtributosDeJuncao(true, true, false)));
    }

    @Test
    void ehLigacaoReconheceLinkSimbolicoEPontoDeReparseMasNaoPastaNemArquivoComuns() {
        assertTrue(ClienteSftpLocal.ehLigacao(new AtributosDeJuncao(true, true, false)), "junção");
        assertTrue(ClienteSftpLocal.ehLigacao(new AtributosDeJuncao(false, false, true)), "link simbólico");
        assertFalse(ClienteSftpLocal.ehLigacao(new AtributosDeJuncao(true, false, false)), "pasta comum");
        assertFalse(ClienteSftpLocal.ehLigacao(new AtributosDeJuncao(false, false, false)), "arquivo comum");
    }

    @Test
    void apagarRecursivoComUmLinkDePastaDentroApagaOLinkENuncaOQueEleApontaNoDisco(@TempDir Path fora) throws Exception {
        Path alvo = Files.createDirectories(fora.toRealPath().resolve("de-fora"));
        Files.writeString(alvo.resolve("a.txt"), "x");
        Path p = Files.createDirectories(raiz.resolve("p").resolve("fundo"));
        Files.writeString(p.resolve("b.txt"), "y");
        Files.createSymbolicLink(raiz.resolve("p").resolve("atalho"), alvo);

        c.apagar(t("p"), true);

        assertFalse(Files.exists(raiz.resolve("p")));
        assertTrue(Files.exists(alvo.resolve("a.txt")), "o destino do link intacto");
    }
}
