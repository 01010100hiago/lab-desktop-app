package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.EntradaRemota.Tipo;
import br.com.labdesktop.ssh.EstadoConexao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O cliente SFTP contra o servidor SFTP do MINA embutido (RootedFileSystem sobre uma pasta temporária). */
class ClienteSftpMinaTest {

    private static String perms(Path p) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    // ---------------------------------------------------------------------------------------------
    // Listar
    // ---------------------------------------------------------------------------------------------

    @Test
    void listaOsItensComTipoTamanhoPermissaoEDonoESemPontoEPontoPonto(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createDirectories(c.orbita().resolve("src"));
            c.escrever(CenarioDeSftp.ORBITA + "/README.md", "# oi\n");
            Files.setPosixFilePermissions(c.orbita().resolve("README.md"), PosixFilePermissions.fromString("rw-r-----"));
            ListagemDePasta l = c.cliente.listar(CenarioDeSftp.ORBITA, 100);
            assertEquals(2, l.total());
            assertFalse(l.truncada());
            assertTrue(l.totalExato());
            EntradaRemota readme = l.entradas().stream().filter(e -> e.nome().equals("README.md")).findFirst().orElseThrow();
            assertEquals(Tipo.ARQUIVO, readme.tipo());
            assertEquals(5, readme.tamanho());
            assertEquals(0640, readme.permissoes());
            assertEquals(CenarioDeSftp.ORBITA + "/README.md", readme.caminho());
            assertNotNull(readme.modificadoEm());
            assertFalse(readme.dono().isBlank(), "o usuário vem em texto");
            EntradaRemota src = l.entradas().stream().filter(e -> e.nome().equals("src")).findFirst().orElseThrow();
            assertTrue(src.ehPasta());
            assertTrue(l.entradas().stream().noneMatch(e -> e.nome().equals(".") || e.nome().equals("..")));
            assertTrue(l.lidaEm() != null);
        }
    }

    @Test
    void linksGanhamOTipoDoDestinoEUmLinkQuebradoEQuebrado(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createDirectories(c.orbita().resolve("dados"));
            c.escrever(CenarioDeSftp.ORBITA + "/a.txt", "x");
            Files.createSymbolicLink(c.orbita().resolve("pasta-link"), Path.of("dados"));
            Files.createSymbolicLink(c.orbita().resolve("arquivo-link"), Path.of("a.txt"));
            Files.createSymbolicLink(c.orbita().resolve("quebrado"), Path.of("nao-existe"));
            ListagemDePasta l = c.cliente.listar(CenarioDeSftp.ORBITA, 100);
            EntradaRemota pasta = achar(l, "pasta-link");
            EntradaRemota arq = achar(l, "arquivo-link");
            EntradaRemota q = achar(l, "quebrado");
            assertTrue(pasta.link() && pasta.ehPasta());
            assertEquals("dados", pasta.destinoDoLink());
            assertTrue(arq.link() && arq.ehArquivo());
            assertEquals(1, arq.tamanho(), "o tamanho do arquivo para o qual o link aponta");
            assertTrue(q.link());
            assertEquals(Tipo.LINK_QUEBRADO, q.tipo());
            assertEquals("nao-existe", q.destinoDoLink());
        }
    }

    private static EntradaRemota achar(ListagemDePasta l, String nome) {
        return l.entradas().stream().filter(e -> e.nome().equals(nome)).findFirst().orElseThrow(() -> new AssertionError("sem " + nome));
    }

    @Test
    void pastaGrandeGuardaOLimiteEDizOTotal(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path pasta = c.orbita().resolve("muitos");
            Files.createDirectories(pasta);
            for (int i = 0; i < 2600; i++) {
                Files.createFile(pasta.resolve(String.format("arq-%05d.txt", i)));
            }
            ListagemDePasta l = c.cliente.listar(CenarioDeSftp.ORBITA + "/muitos", ListagemDePasta.LIMITE_PADRAO);
            assertEquals(2000, l.entradas().size());
            assertEquals(2600, l.total(), "a contagem não para no limite");
            assertTrue(l.truncada());
            assertTrue(l.totalExato());
        }
    }

    @Test
    void filtroNoServidorAchaOQueEstaAlemDoLimite(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path pasta = c.orbita().resolve("muitos");
            Files.createDirectories(pasta);
            for (int i = 0; i < 2300; i++) {
                Files.createFile(pasta.resolve(String.format("arq-%05d.txt", i)));
            }
            Files.createFile(pasta.resolve("o-que-procuro.md"));
            ListagemDePasta l = c.cliente.listar(CenarioDeSftp.ORBITA + "/muitos", 2000, n -> n.contains("procuro"));
            assertEquals(1, l.total());
            assertFalse(l.truncada());
            assertEquals("o-que-procuro.md", l.entradas().get(0).nome());
        }
    }

    @Test
    void pastaSemPermissaoDizSemPermissaoEmPortugues(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path fechada = c.orbita().resolve("fechada");
            Files.createDirectories(fechada);
            Files.setPosixFilePermissions(fechada, PosixFilePermissions.fromString("---------"));
            try {
                ErroSftp e = assertThrows(ErroSftp.class, () -> c.cliente.listar(CenarioDeSftp.ORBITA + "/fechada", 10));
                assertEquals(ErroSftp.Tipo.SEM_PERMISSAO, e.tipo());
                assertEquals("Sem permissão para abrir esta pasta.", e.mensagemSimples());
            } finally {
                Files.setPosixFilePermissions(fechada, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    void pastaQueNaoExisteEPastaQueEUmArquivo(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.ORBITA + "/a.txt", "x");
            assertEquals(ErroSftp.Tipo.NAO_EXISTE, assertThrows(ErroSftp.class, () -> c.cliente.listar(CenarioDeSftp.ORBITA + "/nada", 10)).tipo());
            assertThrows(ErroSftp.class, () -> c.cliente.listar(CenarioDeSftp.ORBITA + "/a.txt", 10));
        }
    }

    @Test
    void entradasHostisDeUmServidorMauSaoDescartadas() {
        for (String nome : new String[] {"../../etc/passwd", "a/b", "/abs", "", ".", "..", "x\0y"}) {
            assertTrue(ClienteSftpMina.paraEntrada("/pasta", new DirEntry(nome, nome, new Attributes())).isEmpty(), "'" + nome + "'");
        }
        var ok = ClienteSftpMina.paraEntrada("/pasta", new DirEntry("normal.txt", "-rw-r--r-- 1 ana dev 3 Oct 24 10:00 normal.txt",
            new Attributes().size(3).perms(0100644)));
        assertTrue(ok.isPresent());
        assertEquals("/pasta/normal.txt", ok.get().caminho());
        assertEquals("ana", ok.get().dono());
        assertEquals("dev", ok.get().grupo());
    }

    @Test
    void trocarOArquivoPorUmNovoSoMudaODonoSeOsDoisSaoConhecidosEDiferentes() {
        assertTrue(ClienteSftpMina.trocaMudariaODono("labuser", "root"));
        assertFalse(ClienteSftpMina.trocaMudariaODono("labuser", "labuser"));
        assertFalse(ClienteSftpMina.trocaMudariaODono("labuser", ""), "dono do antigo desconhecido: não bloqueia");
        assertFalse(ClienteSftpMina.trocaMudariaODono(null, "root"));
    }

    @Test
    void donoEGrupoSaiDaLinhaLsQuandoOServidorNaoDizEmTexto() {
        assertArrayEquals(new String[] {"ana", "dev"}, ClienteSftpMina.donoEGrupoDoLongName("-rw-r--r--    1 ana      dev           3 Oct 24 10:00 x y"));
        assertNull(ClienteSftpMina.donoEGrupoDoLongName("lixo"));
        assertNull(ClienteSftpMina.donoEGrupoDoLongName(null));
    }

    // ---------------------------------------------------------------------------------------------
    // Ler e gravar
    // ---------------------------------------------------------------------------------------------

    @Test
    void estatisticasExisteELerArquivoComLimite(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.ORBITA + "/a.txt", "0123456789");
            EntradaRemota e = c.cliente.estatisticas(CenarioDeSftp.ORBITA + "/a.txt");
            assertEquals("a.txt", e.nome());
            assertEquals(10, e.tamanho());
            assertTrue(c.cliente.existe(CenarioDeSftp.ORBITA + "/a.txt"));
            assertFalse(c.cliente.existe(CenarioDeSftp.ORBITA + "/b.txt"));
            assertEquals("0123456789", new String(c.cliente.lerArquivo(CenarioDeSftp.ORBITA + "/a.txt", 100), StandardCharsets.UTF_8));
            assertEquals("0123", new String(c.cliente.lerArquivo(CenarioDeSftp.ORBITA + "/a.txt", 4), StandardCharsets.UTF_8));
            assertEquals(ErroSftp.Tipo.NAO_EXISTE, assertThrows(ErroSftp.class, () -> c.cliente.estatisticas("/nao/existe")).tipo());
            assertTrue(c.cliente.estatisticas("/").ehPasta());
        }
    }

    @Test
    void umLinkQuebradoExisteEEstatisticasNaoOSegue(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createSymbolicLink(c.orbita().resolve("quebrado"), Path.of("nada"));
            assertTrue(c.cliente.existe(CenarioDeSftp.ORBITA + "/quebrado"));
            EntradaRemota e = c.cliente.estatisticas(CenarioDeSftp.ORBITA + "/quebrado");
            assertTrue(e.link());
            assertEquals(Tipo.LINK_QUEBRADO, e.tipo());
        }
    }

    @Test
    void gravarAtomicoTrocaOConteudoMantemAsPermissoesENaoDeixaTemporario(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arquivo = c.escrever(CenarioDeSftp.ORBITA + "/run.sh", "echo antigo\n");
            Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rwxr-x---"));
            c.cliente.gravarAtomico(CenarioDeSftp.ORBITA + "/run.sh", "echo novo\n".getBytes(StandardCharsets.UTF_8), 0750);
            assertEquals("echo novo\n", Files.readString(arquivo));
            assertEquals("rwxr-x---", perms(arquivo), "as permissões do original ficam");
            try (var s = Files.list(c.orbita())) {
                assertEquals(List.of("run.sh"), s.map(p -> p.getFileName().toString()).toList(), "sem temporário esquecido");
            }
        }
    }

    @Test
    void gravarAtomicoEmArquivoNovoCriaComAsPermissoesPedidas(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.cliente.gravarAtomico(CenarioDeSftp.ORBITA + "/novo.txt", "oi".getBytes(StandardCharsets.UTF_8), 0600);
            assertEquals("oi", Files.readString(c.orbita().resolve("novo.txt")));
            assertEquals("rw-------", perms(c.orbita().resolve("novo.txt")));
        }
    }

    @Test
    void semPermissoesPedidasUmArquivoNovoNasceFechadoEUmQueJaExistiaMantemAsDele(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            // o temporário nasce vazio e 0600, ANTES de receber qualquer byte (um .env com senha nunca fica legível por outros, nem por um instante)
            c.cliente.gravarAtomico(CenarioDeSftp.ORBITA + "/segredo.env", "SENHA=1".getBytes(StandardCharsets.UTF_8), -1);
            assertEquals("rw-------", perms(c.orbita().resolve("segredo.env")));
            Path antigo = c.escrever(CenarioDeSftp.ORBITA + "/leitura.txt", "a");
            Files.setPosixFilePermissions(antigo, PosixFilePermissions.fromString("rw-r--r--"));
            c.cliente.gravarAtomico(CenarioDeSftp.ORBITA + "/leitura.txt", "b".getBytes(StandardCharsets.UTF_8), -1);
            assertEquals("rw-r--r--", perms(antigo), "quem já existia mantém as permissões que tinha");
        }
    }

    @Test
    void gravarAtomicoSemOExtensaoDoOpensshUsaOCaminhoDeReservaSemPerderODado(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arquivo = c.escrever(CenarioDeSftp.ORBITA + "/conf.ini", "antigo");
            Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-r-----"));
            c.cliente.forcarSemTrocaAtomica(true); // o SFTP v3 puro: rename não sobrescreve
            c.cliente.gravarAtomico(CenarioDeSftp.ORBITA + "/conf.ini", "novo".getBytes(StandardCharsets.UTF_8), 0640);
            assertEquals("novo", Files.readString(arquivo));
            assertEquals("rw-r-----", perms(arquivo));
            try (var s = Files.list(c.orbita())) {
                assertEquals(List.of("conf.ini"), s.map(p -> p.getFileName().toString()).toList(), "nem o desvio nem o temporário ficam");
            }
        }
    }

    @Test
    void pastaSomenteLeituraDizSemPermissaoENaoTocaNoArquivo(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arquivo = c.escrever(CenarioDeSftp.ORBITA + "/trava/conf.ini", "antigo");
            Path pasta = c.orbita().resolve("trava");
            Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                ErroSftp e = assertThrows(ErroSftp.class,
                    () -> c.cliente.gravarAtomico(CenarioDeSftp.ORBITA + "/trava/conf.ini", "novo".getBytes(StandardCharsets.UTF_8), 0644));
                assertEquals(ErroSftp.Tipo.SEM_PERMISSAO, e.tipo());
                assertEquals("Sem permissão para gravar este arquivo.", e.mensagemSimples());
                assertEquals("antigo", Files.readString(arquivo));
            } finally {
                Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Criar, renomear, apagar
    // ---------------------------------------------------------------------------------------------

    @Test
    void criaPastaEArquivoVazioERecusaOQueJaExiste(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.cliente.criarPasta(CenarioDeSftp.ORBITA + "/nova");
            c.cliente.criarArquivoVazio(CenarioDeSftp.ORBITA + "/vazio.txt");
            assertTrue(Files.isDirectory(c.orbita().resolve("nova")));
            assertEquals(0, Files.size(c.orbita().resolve("vazio.txt")));
            assertEquals(ErroSftp.Tipo.JA_EXISTE, assertThrows(ErroSftp.class, () -> c.cliente.criarPasta(CenarioDeSftp.ORBITA + "/nova")).tipo());
            c.escrever(CenarioDeSftp.ORBITA + "/cheio.txt", "conteudo");
            assertEquals(ErroSftp.Tipo.JA_EXISTE, assertThrows(ErroSftp.class, () -> c.cliente.criarArquivoVazio(CenarioDeSftp.ORBITA + "/cheio.txt")).tipo());
            assertEquals("conteudo", Files.readString(c.orbita().resolve("cheio.txt")), "criar nunca zera um arquivo que existe");
        }
    }

    @Test
    void renomearEMoverNuncaSobrescrevem(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.ORBITA + "/a.txt", "A");
            c.escrever(CenarioDeSftp.ORBITA + "/b.txt", "B");
            Files.createDirectories(c.orbita().resolve("pasta"));
            c.cliente.renomear(CenarioDeSftp.ORBITA + "/a.txt", CenarioDeSftp.ORBITA + "/c.txt");
            assertEquals("A", Files.readString(c.orbita().resolve("c.txt")));
            assertFalse(Files.exists(c.orbita().resolve("a.txt")));
            ErroSftp e = assertThrows(ErroSftp.class, () -> c.cliente.renomear(CenarioDeSftp.ORBITA + "/c.txt", CenarioDeSftp.ORBITA + "/b.txt"));
            assertEquals(ErroSftp.Tipo.JA_EXISTE, e.tipo());
            assertEquals("B", Files.readString(c.orbita().resolve("b.txt")), "o destino ficou intacto");
            c.cliente.renomear(CenarioDeSftp.ORBITA + "/c.txt", CenarioDeSftp.ORBITA + "/pasta/c.txt");
            assertTrue(Files.exists(c.orbita().resolve("pasta/c.txt")));
            assertEquals(ErroSftp.Tipo.NAO_EXISTE, assertThrows(ErroSftp.class,
                () -> c.cliente.renomear(CenarioDeSftp.ORBITA + "/nao.txt", CenarioDeSftp.ORBITA + "/x.txt")).tipo());
        }
    }

    @Test
    void apagarPastaRecursivoLevaTudoEUmLinkSaiSemApagarODestino(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path de = c.orbita().resolve("lixo");
            Files.createDirectories(de.resolve("a/b"));
            Files.writeString(de.resolve("a/b/x.txt"), "x");
            Files.writeString(de.resolve("y.txt"), "y");
            // um link DENTRO da pasta, para uma pasta de fora que tem que sobreviver
            Path preciosa = c.local(CenarioDeSftp.PROJETOS + "/outro");
            Files.writeString(preciosa.resolve("valioso.txt"), "NAO APAGUE");
            Files.createSymbolicLink(de.resolve("a/para-outro"), Path.of("../../../outro"));
            c.cliente.apagar(CenarioDeSftp.ORBITA + "/lixo", true);
            assertFalse(Files.exists(de), "a pasta foi embora");
            assertEquals("NAO APAGUE", Files.readString(preciosa.resolve("valioso.txt")), "o link foi apagado, não o que está do outro lado");
        }
    }

    @Test
    void apagarUmLinkParaPastaApagaSoOLink(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createDirectories(c.orbita().resolve("real"));
            Files.writeString(c.orbita().resolve("real/a.txt"), "a");
            Files.createSymbolicLink(c.orbita().resolve("atalho"), Path.of("real"));
            c.cliente.apagar(CenarioDeSftp.ORBITA + "/atalho", true);
            assertFalse(Files.exists(c.orbita().resolve("atalho"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals("a", Files.readString(c.orbita().resolve("real/a.txt")));
        }
    }

    @Test
    void pastaNaoVaziaSemRecursivoNaoApaga(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.ORBITA + "/cheia/a.txt", "a");
            assertThrows(ErroSftp.class, () -> c.cliente.apagar(CenarioDeSftp.ORBITA + "/cheia", false));
            assertTrue(Files.exists(c.orbita().resolve("cheia/a.txt")));
        }
    }

    @Test
    void resumoContaArquivosPastasETamanhoSemSeguirLinks(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createDirectories(c.orbita().resolve("r/a/b"));
            Files.writeString(c.orbita().resolve("r/1.txt"), "12345");
            Files.writeString(c.orbita().resolve("r/a/2.txt"), "1234567890");
            Files.writeString(c.orbita().resolve("r/a/b/3.txt"), "1");
            Files.createSymbolicLink(c.orbita().resolve("r/link"), Path.of("../../outro"));
            ResumoDeTamanho r = c.cliente.resumir(CenarioDeSftp.ORBITA + "/r", Cancelamento.NUNCA);
            assertEquals(2, r.pastas());
            assertEquals(4, r.arquivos(), "3 arquivos e o link, que conta como item e não como pasta");
            assertEquals(16, r.bytes() - tamanhoDoLink(c));
            assertTrue(r.completo());
            ResumoDeTamanho unico = c.cliente.resumir(CenarioDeSftp.ORBITA + "/r/1.txt", Cancelamento.NUNCA);
            assertEquals(1, unico.arquivos());
            assertEquals(5, unico.bytes());
        }
    }

    private static long tamanhoDoLink(CenarioDeSftp c) throws IOException {
        return Files.readAttributes(c.orbita().resolve("r/link"), java.nio.file.attribute.BasicFileAttributes.class,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).size();
    }

    // ---------------------------------------------------------------------------------------------
    // Enviar e baixar
    // ---------------------------------------------------------------------------------------------

    @Test
    void enviaEBaixaUmArquivoGrandeComProgressoETamanhoConferido(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            byte[] dados = new byte[3 * 1024 * 1024 + 123];
            new java.util.Random(7).nextBytes(dados);
            Path local = Files.write(tmp.resolve("grande.bin"), dados);
            List<Long> vistos = new ArrayList<>();
            AtomicLong total = new AtomicLong();
            c.cliente.enviar(local, CenarioDeSftp.ORBITA + "/grande.bin", (feitos, t) -> {
                vistos.add(feitos);
                total.set(t);
            }, Cancelamento.NUNCA);
            assertArrayEquals(dados, Files.readAllBytes(c.orbita().resolve("grande.bin")));
            assertEquals(dados.length, total.get());
            assertTrue(vistos.size() > 10, "andamento em vários passos: " + vistos.size());
            assertEquals(dados.length, vistos.get(vistos.size() - 1));
            assertTrue(vistos.get(0) == 0 && vistos.stream().sorted().toList().equals(vistos), "só cresce");
            try (var s = Files.list(c.orbita())) {
                assertEquals(1, s.count(), "sem temporário de envio");
            }

            Path baixado = tmp.resolve("baixado.bin");
            List<Long> baixados = new ArrayList<>();
            c.cliente.baixar(CenarioDeSftp.ORBITA + "/grande.bin", baixado, (f, t) -> baixados.add(f), Cancelamento.NUNCA);
            assertArrayEquals(dados, Files.readAllBytes(baixado));
            assertEquals(dados.length, baixados.get(baixados.size() - 1));
            try (var s = Files.list(tmp)) {
                assertFalse(s.anyMatch(p -> p.getFileName().toString().contains("labdesktop-parcial")), "sem parcial esquecido");
            }
        }
    }

    @Test
    void cancelarNoMeioDoEnvioNaoDeixaNadaNemEstragaODestino(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.ORBITA + "/grande.bin", "VERSAO ANTIGA");
            byte[] dados = new byte[8 * 1024 * 1024];
            Path local = Files.write(tmp.resolve("grande.bin"), dados);
            Cancelamento cancelamento = new Cancelamento();
            ErroSftp e = assertThrows(ErroSftp.class, () -> c.cliente.enviar(local, CenarioDeSftp.ORBITA + "/grande.bin", (f, t) -> {
                if (f > 256 * 1024) {
                    cancelamento.cancelar();
                }
            }, cancelamento));
            assertEquals(ErroSftp.Tipo.CANCELADO, e.tipo());
            assertEquals("VERSAO ANTIGA", Files.readString(c.orbita().resolve("grande.bin")), "o destino não foi tocado");
            try (var s = Files.list(c.orbita())) {
                assertEquals(1, s.count(), "o temporário foi apagado");
            }
        }
    }

    @Test
    void cancelarNoMeioDoDownloadApagaOParcial(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.write(c.orbita().resolve("grande.bin"), new byte[8 * 1024 * 1024]);
            Cancelamento cancelamento = new Cancelamento();
            Path destino = tmp.resolve("saida.bin");
            ErroSftp e = assertThrows(ErroSftp.class, () -> c.cliente.baixar(CenarioDeSftp.ORBITA + "/grande.bin", destino, (f, t) -> {
                if (f > 256 * 1024) {
                    cancelamento.cancelar();
                }
            }, cancelamento));
            assertEquals(ErroSftp.Tipo.CANCELADO, e.tipo());
            assertFalse(Files.exists(destino));
            try (var s = Files.list(tmp)) {
                assertTrue(s.noneMatch(p -> p.getFileName().toString().contains("parcial")));
            }
        }
    }

    @Test
    void enviarSubstituiOArquivoMantendoAsPermissoesDoExistente(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path remoto = c.escrever(CenarioDeSftp.ORBITA + "/x.sh", "velho");
            Files.setPosixFilePermissions(remoto, PosixFilePermissions.fromString("rwxr-x---"));
            Path local = Files.writeString(tmp.resolve("x.sh"), "novo conteudo");
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-r--r--"));
            c.cliente.enviar(local, CenarioDeSftp.ORBITA + "/x.sh", Progresso.NENHUM, Cancelamento.NUNCA);
            assertEquals("novo conteudo", Files.readString(remoto));
            assertEquals("rwxr-x---", perms(remoto));
        }
    }

    @Test
    void baixarUmaPastaComoArquivoRecusaEEnviarUmaPastaLocalTambem(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.createDirectories(c.orbita().resolve("pasta"));
            assertThrows(ErroSftp.class, () -> c.cliente.baixar(CenarioDeSftp.ORBITA + "/pasta", tmp.resolve("p"), Progresso.NENHUM, Cancelamento.NUNCA));
            Path dirLocal = Files.createDirectories(tmp.resolve("dirlocal"));
            assertThrows(ErroSftp.class, () -> c.cliente.enviar(dirLocal, CenarioDeSftp.ORBITA + "/d", Progresso.NENHUM, Cancelamento.NUNCA));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Caminho, conexão
    // ---------------------------------------------------------------------------------------------

    @Test
    void caminhoComControleENuloNemChegamAoServidor(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            assertThrows(ErroSftp.class, () -> c.cliente.listar("/a\nb", 10));
            assertThrows(ErroSftp.class, () -> c.cliente.apagar("/a\u0000b", true));
            assertThrows(ErroSftp.class, () -> c.cliente.criarPasta("/a\rb"));
            assertThrows(ErroSftp.class, () -> c.cliente.lerArquivo(null, 1));
        }
    }

    @Test
    void servidorSemSftpDizQueNaoAceitaTransferenciaDeArquivos(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp, false)) {
            ErroSftp e = assertThrows(ErroSftp.class, () -> c.cliente.listar("/", 10));
            assertEquals(ErroSftp.Tipo.SFTP_INDISPONIVEL, e.tipo());
            assertEquals("Este servidor não aceita transferência de arquivos (SFTP).", e.mensagemSimples());
        }
    }

    @Test
    void semConexaoDizQueODesconectouEUmaCaidaNoMeioSeCuraComAReconexao(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.ORBITA + "/a.txt", "x");
            assertEquals(1, c.cliente.listar(CenarioDeSftp.ORBITA, 10).total());
            // o servidor derruba a sessão no meio: a próxima chamada vê a queda (ou já pega a sessão refeita)
            c.ssh.sshd.getActiveSessions().forEach(s -> s.close(true));
            AtomicBoolean viuAQueda = new AtomicBoolean();
            Espera.ate(Duration.ofSeconds(20), () -> "estado=" + c.conexao.info(), () -> {
                try {
                    c.cliente.listar(CenarioDeSftp.ORBITA, 10);
                    return viuAQueda.get() && c.conexao.info().estado() == EstadoConexao.CONECTADO;
                } catch (ErroSftp e) {
                    assertEquals(ErroSftp.Tipo.SEM_CONEXAO, e.tipo());
                    assertEquals("O servidor desconectou, reconectando…", e.mensagemSimples());
                    viuAQueda.set(true);
                    return false;
                }
            });
            assertTrue(viuAQueda.get(), "a queda foi vista como 'O servidor desconectou, reconectando…'");
            assertEquals(1, c.cliente.listar(CenarioDeSftp.ORBITA, 10).total(), "depois de reconectar, o canal novo funciona");
        }
    }

    @Test
    void clienteFechadoNaoReabreSozinho(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.cliente.listar("/", 10);
            c.cliente.close();
            c.cliente.close(); // idempotente
            assertEquals(ErroSftp.Tipo.SEM_CONEXAO, assertThrows(ErroSftp.class, () -> c.cliente.listar("/", 10)).tipo());
        }
    }

    @Test
    void homeEACanonicaDoServidor(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            assertEquals("/", c.cliente.caminhoCanonico("."));
            assertEquals("../outro", c.cliente.lerLink(link(c)));
        }
    }

    private static String link(CenarioDeSftp c) throws IOException {
        Files.createSymbolicLink(c.orbita().resolve("l"), Path.of("../outro"));
        return CenarioDeSftp.ORBITA + "/l";
    }
}
