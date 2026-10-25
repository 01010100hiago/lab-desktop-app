package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.EditorRemoto.Aberto;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Abrir, salvar com segurança (conflito, backup local, atômico, permissões), desfazer e salvar com sudo. */
class EditorRemotoTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static EditorRemoto editor(CenarioDeSftp c, Path backups, ProvisionadorRemoto.Executor sudo) {
        return new EditorRemoto(c.servicoDoProjeto(), new BackupsLocais(backups, "lab"), sudo);
    }

    private static ErroSftp erro(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ErroSftp.class, e);
    }

    @Test
    void abreTextoComOsDadosParaConferirDepois(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(O + "/README.md", "# Órbita\nlinha 2\n");
            Aberto a = editor(c, tmp.resolve("bk"), null).abrir(O + "/README.md");
            assertEquals("# Órbita\nlinha 2\n", a.texto());
            assertEquals("README.md", a.nome());
            assertEquals(O + "/README.md", a.caminhoReal());
            assertEquals(Files.size(c.orbita().resolve("README.md")), a.tamanho());
            assertEquals(0644, a.permissoes());
            assertEquals("\n", a.quebraDeLinha());
        }
    }

    @Test
    void binarioPastaGrandeENaoUtf8NaoAbremEMandamBaixar(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Files.write(c.orbita().resolve("foto.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
            Files.write(c.orbita().resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9});
            Files.write(c.orbita().resolve("grande.log"), new byte[DetectorDeTexto.LIMITE + 1]);
            Files.write(c.orbita().resolve("no-limite.txt"), "a".repeat(DetectorDeTexto.LIMITE).getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(c.orbita().resolve("pasta"));
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            for (String nome : new String[] {"foto.png", "latin1.txt", "grande.log", "pasta"}) {
                ErroSftp erro = erro(() -> e.abrir(O + "/" + nome));
                assertEquals(ErroSftp.Tipo.NAO_E_TEXTO, erro.tipo(), nome);
            }
            assertTrue(erro(() -> e.abrir(O + "/grande.log")).mensagemSimples().contains("Baixar"));
            assertTrue(erro(() -> e.abrir(O + "/foto.png")).mensagemSimples().contains("Baixar"));
            assertEquals(DetectorDeTexto.LIMITE, e.abrir(O + "/no-limite.txt").texto().length(), "exatamente 1 MB ainda abre");
        }
    }

    @Test
    void salvaPreservandoPermissoesGuardaBackupELiberaOTemporario(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/run.sh", "#!/bin/sh\necho antigo\n");
            Files.setPosixFilePermissions(arq, PosixFilePermissions.fromString("rwxr-x---"));
            Path backups = tmp.resolve("bk");
            EditorRemoto e = editor(c, backups, null);
            Aberto aberto = e.abrir(O + "/run.sh");
            Aberto salvo = e.salvar(aberto, "#!/bin/sh\necho novo\n", false);
            assertEquals("#!/bin/sh\necho novo\n", Files.readString(arq));
            assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(arq)));
            assertEquals(Files.size(arq), salvo.tamanho(), "a base da próxima gravação é o arquivo como ficou");
            // o conteúdo ANTERIOR foi para o backup local, no lugar combinado
            var bk = new BackupsLocais(backups, "lab").ultimo(O + "/run.sh").orElseThrow();
            assertEquals("#!/bin/sh\necho antigo\n", Files.readString(bk.arquivo()));
            assertTrue(bk.arquivo().toString().replace('\\', '/').endsWith(O + "/run.sh"));
            assertTrue(bk.arquivo().startsWith(backups.resolve("lab")));
            try (var s = Files.list(c.orbita())) {
                assertEquals(List.of("run.sh"), s.map(p -> p.getFileName().toString()).toList(), "nada de .bak nem temporário no servidor");
            }
            // salvar de novo, sem reabrir, funciona (a base é a devolvida)
            e.salvar(salvo, "terceiro\n", false);
            assertEquals("terceiro\n", Files.readString(arq));
        }
    }

    @Test
    void quebraDeLinhaDoWindowsSobreviveAoSalvar(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/win.txt", "a\r\nb\r\n");
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto a = e.abrir(O + "/win.txt");
            assertEquals("a\nb\n", a.texto());
            e.salvar(a, "a\nb\nc\n", false);
            assertArrayEquals("a\r\nb\r\nc\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(arq));
        }
    }

    @Test
    void conflitoQuandoOArquivoMudouNoServidorDesdeQueFoiAberto(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/conf.yml", "versao: 1\n");
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto aberto = e.abrir(O + "/conf.yml");
            // alguém mexe no arquivo por fora (outro conteúdo, outra data)
            Files.writeString(arq, "versao: 2 (de outra pessoa)\n");
            Files.setLastModifiedTime(arq, FileTime.from(Instant.now().plusSeconds(120)));
            ErroSftp erro = erro(() -> e.salvar(aberto, "versao: 1 (minha edição)\n", false));
            assertEquals(ErroSftp.Tipo.ARQUIVO_MUDOU, erro.tipo());
            assertEquals("O arquivo mudou no servidor desde que você abriu.", erro.mensagemSimples());
            assertEquals("versao: 2 (de outra pessoa)\n", Files.readString(arq), "nada foi gravado");
            // Sobrescrever mesmo assim: grava, e a versão da outra pessoa vai para o backup (nada se perde)
            e.salvar(aberto, "versao: 1 (minha edição)\n", true);
            assertEquals("versao: 1 (minha edição)\n", Files.readString(arq));
            var bk = new BackupsLocais(tmp.resolve("bk"), "lab").ultimo(O + "/conf.yml").orElseThrow();
            assertEquals("versao: 2 (de outra pessoa)\n", Files.readString(bk.arquivo()));
            // Recarregar traz a versão do servidor
            assertEquals("versao: 1 (minha edição)\n", e.abrir(O + "/conf.yml").texto());
        }
    }

    @Test
    void conflitoSoPeloTamanhoOuSoPelaData(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/a.txt", "12345");
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto aberto = e.abrir(O + "/a.txt");
            Instant data = Files.getLastModifiedTime(arq).toInstant();
            Files.writeString(arq, "54321"); // mesmo tamanho
            Files.setLastModifiedTime(arq, FileTime.from(data.plusSeconds(30)));
            assertEquals(ErroSftp.Tipo.ARQUIVO_MUDOU, erro(() -> e.salvar(aberto, "x", false)).tipo(), "só a data mudou");
            Files.writeString(arq, "123456"); // outro tamanho
            Files.setLastModifiedTime(arq, FileTime.from(data));
            assertEquals(ErroSftp.Tipo.ARQUIVO_MUDOU, erro(() -> e.salvar(aberto, "x", false)).tipo(), "só o tamanho mudou");
        }
    }

    @Test
    void arquivoApagadoNoServidorEConflitoESobrescreverORecria(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/some.txt", "x");
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto aberto = e.abrir(O + "/some.txt");
            Files.delete(arq);
            ErroSftp erro = erro(() -> e.salvar(aberto, "novo", false));
            assertEquals(ErroSftp.Tipo.ARQUIVO_MUDOU, erro.tipo());
            assertTrue(erro.mensagemSimples().contains("apagado"));
            e.salvar(aberto, "novo", true);
            assertEquals("novo", Files.readString(arq));
        }
    }

    @Test
    void semBackupNaoGrava(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/a.txt", "original");
            Path naoEPasta = Files.writeString(tmp.resolve("arquivo-no-lugar-da-pasta"), "x");
            EditorRemoto e = new EditorRemoto(c.servicoDoProjeto(), new BackupsLocais(naoEPasta, "lab"), null);
            Aberto aberto = e.abrir(O + "/a.txt");
            ErroSftp erro = erro(() -> e.salvar(aberto, "novo", false));
            assertTrue(erro.mensagemSimples().contains("NÃO salvei"), erro.mensagemSimples());
            assertEquals("original", Files.readString(arq), "se a cópia de segurança não pôde ser feita, nada é trocado");
        }
    }

    @Test
    void pastaSemPermissaoDeGravacaoDizSemPermissaoEOArquivoFicaIntacto(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/trava/conf.ini", "antigo");
            Path pasta = c.orbita().resolve("trava");
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto aberto = e.abrir(O + "/trava/conf.ini");
            Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                ErroSftp erro = erro(() -> e.salvar(aberto, "novo", false));
                assertEquals(ErroSftp.Tipo.SEM_PERMISSAO, erro.tipo());
                assertEquals("antigo", Files.readString(arq));
            } finally {
                Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    @Test
    void desfazerUltimaEdicaoVoltaAoConteudoAnteriorEPodeSerDesfeito(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/nota.txt", "versão A\n");
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto a = e.abrir(O + "/nota.txt");
            assertTrue(e.ultimaVersaoGuardada(a).isEmpty(), "ainda não há o que desfazer");
            Aberto b = e.salvar(a, "versão B\n", false);
            assertTrue(e.ultimaVersaoGuardada(b).isPresent());
            Aberto desfeito = e.desfazerUltimaEdicao(b);
            assertEquals("versão A\n", Files.readString(arq));
            assertEquals("versão A\n", desfeito.texto(), "o editor recebe o texto restaurado");
            // o que estava lá (B) virou cópia: desfazer o desfazer traz B de volta
            Aberto refeito = e.desfazerUltimaEdicao(desfeito);
            assertEquals("versão B\n", Files.readString(arq));
            assertEquals("versão B\n", refeito.texto());
            assertEquals(ErroSftp.Tipo.NAO_EXISTE, erro(() -> editor(c, tmp.resolve("outro-bk"), null).desfazerUltimaEdicao(a)).tipo());
        }
    }

    @Test
    void naoAbreNemSalvaForaDaRaizDoProjeto(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            c.escrever(CenarioDeSftp.PROJETOS + "/outro/segredo.txt", "de outro projeto");
            Files.createSymbolicLink(c.orbita().resolve("espia"), Path.of("../outro/segredo.txt"));
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, erro(() -> e.abrir(CenarioDeSftp.PROJETOS + "/outro/segredo.txt")).tipo());
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, erro(() -> e.abrir(O + "/espia")).tipo(), "o link leva para fora");
            assertEquals(ErroSftp.Tipo.FORA_DA_RAIZ, erro(() -> e.abrir(O + "/../outro/segredo.txt")).tipo());
        }
    }

    @Test
    void editarPeloLinkDentroDaRaizGravaNoArquivoRealSemApagarOLink(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path real = c.escrever(O + "/real.txt", "antes");
            Files.createSymbolicLink(c.orbita().resolve("atalho.txt"), Path.of("real.txt"));
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            Aberto a = e.abrir(O + "/atalho.txt");
            assertEquals(O + "/real.txt", a.caminhoReal());
            e.salvar(a, "depois", false);
            assertEquals("depois", Files.readString(real));
            assertTrue(Files.isSymbolicLink(c.orbita().resolve("atalho.txt")), "o link continua link");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Salvar com sudo
    // ---------------------------------------------------------------------------------------------

    /** Faz de conta que o servidor roda o comando do sudo: confere a linha e grava o conteúdo no caminho da primeira linha do stdin. */
    private static final class SudoDeMentira implements ProvisionadorRemoto.Executor {
        final List<String> comandos = new ArrayList<>();
        final List<String> caminhos = new ArrayList<>();
        int status;
        String stderr = "";

        @Override
        public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws java.io.IOException {
            comandos.add(comando);
            String tudo = new String(stdin, StandardCharsets.UTF_8);
            int i = tudo.indexOf('\n');
            String caminho = tudo.substring(0, i);
            caminhos.add(caminho);
            if (status == 0) {
                Files.write(Path.of(caminho), tudo.substring(i + 1).getBytes(StandardCharsets.UTF_8));
            }
            return new ResultadoExec(status, new byte[0], stderr.getBytes(StandardCharsets.UTF_8), false);
        }
    }

    @Test
    void salvarComSudoUsaUmComandoFixoECaminhoPeloStdin(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/de-root.conf", "antigo\n");
            SudoDeMentira sudo = new SudoDeMentira();
            // o caminho real, no disco do teste, é o que o "servidor" de mentira usa
            ProvisionadorRemoto.Executor traduzindo = (cmd, in, prazo) -> {
                String s = new String(in, StandardCharsets.UTF_8);
                int i = s.indexOf('\n');
                String reescrito = c.local(s.substring(0, i)) + "\n" + s.substring(i + 1);
                return sudo.executar(cmd, reescrito.getBytes(StandardCharsets.UTF_8), prazo);
            };
            EditorRemoto e = editor(c, tmp.resolve("bk"), traduzindo);
            Aberto a = e.abrir(O + "/de-root.conf");
            Aberto salvo = e.salvarComSudo(a, "novo\n", false);
            assertEquals("novo\n", Files.readString(arq));
            assertEquals(List.of(EditorRemoto.COMANDO_DO_SUDO), sudo.comandos, "SEMPRE o mesmo comando, sem o caminho dentro");
            assertFalse(sudo.comandos.get(0).contains("de-root"), "o caminho nunca entra na linha de comando");
            assertTrue(sudo.comandos.get(0).contains("sudo -n tee"), "sudo -n: nunca pergunta senha");
            assertEquals(Files.size(arq), salvo.tamanho());
            assertEquals("antigo\n", Files.readString(new BackupsLocais(tmp.resolve("bk"), "lab").ultimo(O + "/de-root.conf").orElseThrow().arquivo()),
                "o backup local também vale no sudo");
        }
    }

    @Test
    void comandoDoSudoNaoTemControleNemInterpolacao() {
        String cmd = EditorRemoto.COMANDO_DO_SUDO;
        assertTrue(CaminhoRemoto.semControle(cmd));
        assertTrue(cmd.startsWith("sh -c '") && cmd.endsWith("'"));
        assertTrue(cmd.contains("read -r p") && cmd.contains("\"$p\""), "o caminho é lido do stdin e usado entre aspas duplas");
    }

    @Test
    void salvarComSudoRecusaCaminhoComControleSemNemChamarOServidor(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            SudoDeMentira sudo = new SudoDeMentira();
            EditorRemoto e = editor(c, tmp.resolve("bk"), sudo);
            Aberto estranho = new Aberto(O + "/a\nb", O + "/a\nb", "x", "\n", false, 1, Instant.now(), 0644, "ana");
            assertEquals(ErroSftp.Tipo.NOME_INVALIDO, erro(() -> e.salvarComSudo(estranho, "novo", true)).tipo());
            assertTrue(sudo.comandos.isEmpty());
        }
    }

    @Test
    void sudoQuePedeSenhaViraFraseSimplesEOArquivoNaoMuda(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            Path arq = c.escrever(O + "/a.conf", "antigo");
            SudoDeMentira sudo = new SudoDeMentira();
            sudo.status = 1;
            sudo.stderr = "sudo: a password is required\n";
            EditorRemoto e = editor(c, tmp.resolve("bk"), (cmd, in, prazo) -> {
                String s = new String(in, StandardCharsets.UTF_8);
                return sudo.executar(cmd, s.getBytes(StandardCharsets.UTF_8), prazo);
            });
            Aberto a = e.abrir(O + "/a.conf");
            ErroSftp erro = erro(() -> e.salvarComSudo(a, "novo", false));
            assertEquals("Este usuário não pode usar sudo sem senha neste servidor, então não consegui salvar assim.", erro.mensagemSimples());
            assertEquals("antigo", Files.readString(arq));
        }
    }

    @Test
    void mensagensDoSudo() {
        assertTrue(EditorRemoto.mensagemDoSudo("sudo: a terminal is required to read the password").contains("sem senha"));
        assertTrue(EditorRemoto.mensagemDoSudo("labuser is not in the sudoers file.").contains("não tem permissão"));
        assertTrue(EditorRemoto.mensagemDoSudo("sh: sudo: not found").contains("não tem o sudo"));
        assertEquals("O servidor recusou salvar com sudo: tee: /x: Read-only file system",
            EditorRemoto.mensagemDoSudo("tee: /x: Read-only file system\nresto"));
        assertEquals("O servidor recusou salvar com sudo.", EditorRemoto.mensagemDoSudo(""));
    }

    @Test
    void semExecutorNaoHaSudo(@TempDir Path tmp) throws Exception {
        try (CenarioDeSftp c = new CenarioDeSftp(tmp)) {
            EditorRemoto e = editor(c, tmp.resolve("bk"), null);
            assertFalse(e.temSudo());
            Aberto a = new Aberto(O + "/a", O + "/a", "x", "\n", false, 1, Instant.now(), 0644, "ana");
            assertEquals(ErroSftp.Tipo.OUTRO, erro(() -> e.salvarComSudo(a, "x", true)).tipo());
        }
    }
}
