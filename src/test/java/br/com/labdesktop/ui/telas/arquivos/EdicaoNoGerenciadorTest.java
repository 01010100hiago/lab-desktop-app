package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.BackupsLocais;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.EditorRemoto;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeSalvar;
import br.com.labdesktop.ui.dialogos.DialogoFecharEditor;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Abrir e editar texto: números de linha, modificado, salvar com segurança, conflito, sudo, desfazer, buscar e fechar. */
@ExtendWith(VerificaEdt.class)
class EdicaoNoGerenciadorTest {

    private static final String O = CenarioDeSftp.ORBITA;

    private static CenaDeArquivos cena(Path tmp, boolean escrita) throws Exception {
        Visual.tema(Tema.CLARO);
        CenaDeArquivos c = new CenaDeArquivos(tmp, escrita, "orbita", CenaDeArquivos::projetoDeExemplo, true);
        c.esperarNormal();
        return c;
    }

    /** Abre o arquivo pelo duplo clique e devolve o editor. */
    private static EditorDeTexto abrir(CenaDeArquivos c, String nome) {
        c.duploClique(nome);
        c.esperarQue("editor de " + nome + " aberto", () -> c.g.editoresAbertos().containsKey(O + "/" + nome));
        return Visual.naEdt(() -> c.g.editoresAbertos().get(O + "/" + nome));
    }

    private static void digitar(EditorDeTexto e, String texto) {
        Visual.naEdt(() -> e.area().append(texto));
    }

    private static void apertar(EditorDeTexto e, int vk, int mod) {
        Visual.naEdt(() -> {
            var t = e.area();
            KeyStroke tecla = KeyStroke.getKeyStroke(vk, mod);
            Object nome = e.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(tecla);
            var acao = nome == null ? null : e.getActionMap().get(nome);
            if (acao == null) {
                nome = t.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(tecla);
                acao = nome == null ? null : t.getActionMap().get(nome);
            }
            assertNotNull(acao, "a tecla " + tecla + " não está ligada no editor");
            acao.actionPerformed(new java.awt.event.ActionEvent(t, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
        });
    }

    private static int cmd() {
        return br.com.labdesktop.ui.componentes.Teclas.comando();
    }

    // ---------------------------------------------------------------------------------------------
    // Abrir
    // ---------------------------------------------------------------------------------------------

    @Test
    void abreTextoNumaAbaComFonteMonoNumerosDeLinhaERealce(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "docker-compose.yml");
            assertTrue(Visual.naEdt(() -> e.area().getText()).startsWith("services:"));
            assertEquals("docker-compose.yml", Visual.naEdt(e::textoDoNome));
            assertTrue(Visual.naEdt(() -> e.rolagem().getLineNumbersEnabled()), "números de linha");
            assertEquals("text/yaml", Visual.naEdt(() -> e.area().getSyntaxEditingStyle()));
            assertTrue(Visual.naEdt(() -> e.area().getFont().getFamily()).toLowerCase().contains("mono")
                || Visual.naEdt(() -> e.area().getFont().getFamily()).toLowerCase().contains("courier")
                || Visual.naEdt(() -> e.area().getFont().getFamily()).toLowerCase().contains("jetbrains"), "fonte monoespaçada");
            assertTrue(Visual.naEdt(() -> e.area().isEditable()));
            assertTrue(Visual.naEdt(() -> c.g.abas().isVisible()), "com um editor aberto aparece a faixa de abas");
            assertTrue(Visual.naEdt(() -> c.g.abas().tem("editor:1")));
            assertEquals("Linha 1, coluna 1", Visual.naEdt(e::textoDaPosicao));
            assertFalse(Visual.naEdt(() -> e.botaoSalvar().isEnabled()), "nada para salvar ainda");
            assertTrue(Visual.naEdt(() -> TextosEmEditor.de(e)).contains("em " + O), "o cabeçalho diz em que pasta o arquivo está");
        }
    }

    @Test
    void abrirDeNovoOMesmoArquivoSoVaiParaAAbaQueJaExiste(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            abrir(c, "README.md");
            Visual.naEdt(() -> c.g.abas().selecionar("navegador"));
            c.duploClique("README.md");
            assertEquals(1, Visual.naEdt(() -> c.g.editoresAbertos().size()));
            assertEquals("editor:1", Visual.naEdt(() -> c.g.abas().selecionada()));
        }
    }

    @Test
    void binarioEArquivoGrandeNaoAbremEOferecemBaixar(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            c.duploClique("logo.png");
            DialogoConfirmar d = c.esperarDialogo(DialogoConfirmar.class);
            assertEquals("Baixar", Visual.naEdt(() -> d.botaoConfirmar().getText()));
            assertTrue(Visual.naEdt(() -> TextosEmEditor.de(d.raiz())).contains("não parece um arquivo de texto"));
            assertTrue(Visual.naEdt(() -> c.g.editoresAbertos().isEmpty()));

            Path destino = Files.createDirectories(tmp.resolve("Downloads"));
            c.escolhedor.pastaDeDownload = destino;
            Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
            Espera.ate(CenaDeArquivos.PRAZO, "baixou o binário", () -> Files.exists(destino.resolve("logo.png")));
            assertEquals(20, Files.size(destino.resolve("logo.png")));

            Files.write(c.sftp.orbita().resolve("enorme.log"), new byte[1024 * 1024 + 1]);
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("enorme listado", () -> c.nomes().contains("enorme.log"));
            c.duploClique("enorme.log");
            c.esperarQue("segundo diálogo", () -> c.dialogos.mostrados.size() == 2);
            assertTrue(Visual.naEdt(() -> TextosEmEditor.de(c.dialogos.ultimo().raiz())).contains("grande demais para editar aqui"));
            assertTrue(Visual.naEdt(() -> c.g.editoresAbertos().isEmpty()));
        }
    }

    @Test
    void seOArquivoSumiuAntesDeAbrirDizEAtualizaAPasta(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Files.delete(c.sftp.orbita().resolve("Dockerfile"));
            c.duploClique("Dockerfile");
            Espera.ate(CenaDeArquivos.PRAZO, "aviso", () -> c.avisos.disse("erro", "não existe"));
            c.esperarQue("a pasta foi lida de novo", () -> !c.nomes().contains("Dockerfile"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Modificado e salvar
    // ---------------------------------------------------------------------------------------------

    @Test
    void digitarMarcaModificadoSalvarGravaGuardaBackupELimpaOAsterisco(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "README.md");
            String original = Files.readString(c.sftp.orbita().resolve("README.md"));
            digitar(e, "\nUma linha nova.\n");
            assertEquals("README.md *", Visual.naEdt(e::textoDoNome));
            assertEquals("README.md *", Visual.naEdt(() -> c.g.abas().botao("editor:1").getText()), "o asterisco também está na aba");
            assertTrue(Visual.naEdt(() -> e.botaoSalvar().isEnabled()));
            apertar(e, KeyEvent.VK_S, cmd()); // Cmd+S
            c.esperarQue("salvou", () -> !Visual.naEdt(e::modificado));
            assertEquals(original + "\nUma linha nova.\n", Files.readString(c.sftp.orbita().resolve("README.md")));
            assertEquals("README.md", Visual.naEdt(e::textoDoNome));
            assertEquals("README.md", Visual.naEdt(() -> c.g.abas().botao("editor:1").getText()));
            assertTrue(c.avisos.disse("ok", "Salvei README.md"));
            // o que estava no servidor foi para uma cópia no Mac, no lugar combinado
            var bk = new BackupsLocais(c.backups, "lab").ultimo(O + "/README.md").orElseThrow();
            assertEquals(original, Files.readString(bk.arquivo()));
            assertTrue(bk.arquivo().startsWith(c.backups.resolve("lab")));
            c.esperarQue("há o que desfazer", () -> e.botaoDesfazer().isEnabled());
            try (var s = Files.list(c.sftp.orbita())) {
                assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".bak")), "nada de .bak no servidor");
            }
        }
    }

    @Test
    void voltarAoTextoOriginalDesfazOModificado(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "README.md");
            Visual.naEdt(() -> e.area().insert("X", 0));
            assertTrue(Visual.naEdt(e::modificado));
            Visual.naEdt(() -> e.area().replaceRange("", 0, 1));
            assertFalse(Visual.naEdt(e::modificado), "o texto voltou a ser igual ao salvo");
            assertEquals("README.md", Visual.naEdt(() -> c.g.abas().botao("editor:1").getText()));
        }
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void salvarMantemAsPermissoesEAQuebraDeLinhaDoWindows(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Path script = c.sftp.escrever(O + "/rodar.sh", "#!/bin/sh\r\necho oi\r\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
            Visual.naEdt(c.g::atualizar);
            c.esperarQue("rodar.sh listado", () -> c.nomes().contains("rodar.sh"));
            EditorDeTexto e = abrir(c, "rodar.sh");
            digitar(e, "echo tchau\n");
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            c.esperarQue("salvou", () -> !Visual.naEdt(e::modificado));
            assertEquals("#!/bin/sh\r\necho oi\r\necho tchau\r\n", Files.readString(script));
            assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Conflito
    // ---------------------------------------------------------------------------------------------

    @Test
    void arquivoQueMudouNoServidorAvisaComTresSaidas(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "package.json");
            Path arq = c.sftp.orbita().resolve("package.json");
            Files.writeString(arq, "{\"de\": \"outra pessoa\"}\n");
            Files.setLastModifiedTime(arq, FileTime.from(Instant.now().plusSeconds(300)));
            digitar(e, "// minha edição\n");
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            DialogoConflitoDeSalvar d = c.esperarDialogo(DialogoConflitoDeSalvar.class);
            assertEquals("Cancelar", Visual.naEdt(() -> d.botaoCancelar().getText()));
            assertEquals("Recarregar", Visual.naEdt(() -> d.botaoRecarregar().getText()));
            assertEquals("Sobrescrever mesmo assim", Visual.naEdt(() -> d.botaoSobrescrever().getText()));
            assertTrue(Visual.naEdt(() -> TextosEmEditor.de(d.raiz())).contains("mudou no servidor desde que você abriu"));
            assertEquals("{\"de\": \"outra pessoa\"}\n", Files.readString(arq), "nada foi gravado");

            // Cancelar: nada acontece
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            assertTrue(Visual.naEdt(e::modificado));
            assertEquals("{\"de\": \"outra pessoa\"}\n", Files.readString(arq));

            // Sobrescrever mesmo assim
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            c.esperarQue("segundo aviso", () -> c.dialogos.mostrados.size() == 2);
            DialogoConflitoDeSalvar d2 = c.dialogo(DialogoConflitoDeSalvar.class);
            Visual.naEdt(() -> d2.botaoSobrescrever().doClick(0));
            c.esperarQue("sobrescreveu", () -> !Visual.naEdt(e::modificado));
            assertTrue(Files.readString(arq).contains("// minha edição"));
            var bk = new BackupsLocais(c.backups, "lab").ultimo(O + "/package.json").orElseThrow();
            assertEquals("{\"de\": \"outra pessoa\"}\n", Files.readString(bk.arquivo()), "a versão da outra pessoa está guardada");
        }
    }

    @Test
    void recarregarTrazAVersaoDoServidorEDescartaOQueFoiDigitado(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "package.json");
            Path arq = c.sftp.orbita().resolve("package.json");
            Files.writeString(arq, "{\"novo\": true}\n");
            Files.setLastModifiedTime(arq, FileTime.from(Instant.now().plusSeconds(300)));
            digitar(e, "// vai ser perdido\n");
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            DialogoConflitoDeSalvar d = c.esperarDialogo(DialogoConflitoDeSalvar.class);
            Visual.naEdt(() -> d.botaoRecarregar().doClick(0));
            c.esperarQue("recarregou", () -> Visual.naEdt(() -> e.area().getText()).equals("{\"novo\": true}\n"));
            assertFalse(Visual.naEdt(e::modificado));
            // e agora salvar não tem conflito
            digitar(e, "// de novo\n");
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            c.esperarQue("salvou sem conflito", () -> !Visual.naEdt(e::modificado));
            assertEquals(1, c.dialogos.mostrados.size());
        }
    }

    @Test
    void arquivoApagadoNoServidorDizApagadoENaoOfereceRecarregar(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "Dockerfile");
            Files.delete(c.sftp.orbita().resolve("Dockerfile"));
            digitar(e, "# comentário\n");
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            DialogoConflitoDeSalvar d = c.esperarDialogo(DialogoConflitoDeSalvar.class);
            assertTrue(Visual.naEdt(() -> TextosEmEditor.de(d.raiz())).contains("foi apagado do servidor"));
            assertEquals("Salvar mesmo assim", Visual.naEdt(() -> d.botaoSobrescrever().getText()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sudo
    // ---------------------------------------------------------------------------------------------

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void semPermissaoOfereceSalvarComSudoSemPerguntarSenhaECancelarEOPadrao(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Path pasta = Files.createDirectories(c.sftp.orbita().resolve("trava"));
            Path arq = c.sftp.escrever(O + "/trava/conf.ini", "[a]\nb=1\n");
            Visual.naEdt(() -> c.g.irPara(O + "/trava"));
            c.esperarQue("na pasta", () -> (O + "/trava").equals(c.g.caminhoAtual()) && c.g.situacao() == GerenciadorDeArquivos.Situacao.NORMAL);
            c.duploClique("conf.ini");
            c.esperarQue("aberto", () -> c.g.editoresAbertos().containsKey(O + "/trava/conf.ini"));
            EditorDeTexto e = Visual.naEdt(() -> c.g.editoresAbertos().get(O + "/trava/conf.ini"));
            Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("r-xr-xr-x"));
            List<String> comandos = new CopyOnWriteArrayList<>();
            List<String> entradas = new CopyOnWriteArrayList<>();
            c.sftp.ssh.comandoRemoto((linha, stdin, saida) -> {
                comandos.add(linha);
                entradas.add(stdin);
                int i = stdin.indexOf('\n');
                try {
                    Files.writeString(c.sftp.local(stdin.substring(0, i)), stdin.substring(i + 1));
                } catch (java.io.IOException ex) {
                    saida.append("\u0001tee: ").append(ex.getMessage()).append('\n');
                    return 1;
                }
                return 0;
            });
            try {
                digitar(e, "c=2\n");
                Visual.naEdt(() -> e.botaoSalvar().doClick(0));
                DialogoConfirmar d = c.esperarDialogo(DialogoConfirmar.class);
                assertEquals("Salvar com sudo", Visual.naEdt(() -> d.botaoConfirmar().getText()));
                assertEquals("Cancelar", Visual.naEdt(() -> d.botaoCancelar().getText()));
                assertEquals(Botao.Variante.PRIMARIO, Visual.naEdt(() -> d.botaoCancelar().variante()), "Cancelar é o botão em destaque");
                String texto = Visual.naEdt(() -> TextosEmEditor.de(d.raiz()));
                assertTrue(texto.contains("sem digitar senha"), texto);
                assertEquals("[a]\nb=1\n", Files.readString(arq), "ainda não gravou");
                Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
                c.esperarQue("salvou com sudo", () -> !Visual.naEdt(e::modificado));
                assertEquals("[a]\nb=1\nc=2\n", Files.readString(arq));
                assertEquals(List.of(EditorRemoto.COMANDO_DO_SUDO), comandos);
                assertTrue(entradas.get(0).startsWith(O + "/trava/conf.ini\n"), "o caminho vai pelo stdin");
            } finally {
                Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void sudoRecusadoMostraAFraseSimplesNoEditor(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            Path pasta = Files.createDirectories(c.sftp.orbita().resolve("trava"));
            c.sftp.escrever(O + "/trava/conf.ini", "x");
            Visual.naEdt(() -> c.g.irPara(O + "/trava"));
            c.esperarQue("na pasta", () -> (O + "/trava").equals(c.g.caminhoAtual()) && c.g.situacao() == GerenciadorDeArquivos.Situacao.NORMAL);
            c.duploClique("conf.ini");
            c.esperarQue("aberto", () -> c.g.editoresAbertos().containsKey(O + "/trava/conf.ini"));
            EditorDeTexto e = Visual.naEdt(() -> c.g.editoresAbertos().get(O + "/trava/conf.ini"));
            Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("r-xr-xr-x"));
            c.sftp.ssh.comandoRemoto((linha, stdin, saida) -> {
                saida.append("\u0001sudo: a password is required\n");
                return 1;
            });
            try {
                digitar(e, "y");
                Visual.naEdt(() -> e.botaoSalvar().doClick(0));
                DialogoConfirmar d = c.esperarDialogo(DialogoConfirmar.class);
                Visual.naEdt(() -> d.botaoConfirmar().doClick(0));
                c.esperarQue("aviso no editor", () -> Visual.naEdt(e::textoDoAviso).contains("não pode usar sudo sem senha"));
                assertTrue(Visual.naEdt(e::modificado), "nada foi salvo: o texto digitado continua");
                assertEquals("x", Files.readString(c.sftp.orbita().resolve("trava/conf.ini")));
            } finally {
                Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Desfazer a última edição
    // ---------------------------------------------------------------------------------------------

    @Test
    void desfazerAUltimaEdicaoRestauraDaCopiaLocal(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "README.md");
            String original = Files.readString(c.sftp.orbita().resolve("README.md"));
            assertFalse(Visual.naEdt(() -> e.botaoDesfazer().isEnabled()), "sem cópia local não há o que desfazer");
            digitar(e, "ERRADO\n");
            Visual.naEdt(() -> e.botaoSalvar().doClick(0));
            c.esperarQue("salvou", () -> !Visual.naEdt(e::modificado) && Visual.naEdt(() -> e.botaoDesfazer().isEnabled()));
            Visual.naEdt(() -> e.botaoDesfazer().doClick(0));
            c.esperarQue("desfez", () -> Visual.naEdt(() -> e.area().getText()).equals(original));
            assertEquals(original, Files.readString(c.sftp.orbita().resolve("README.md")));
            assertFalse(Visual.naEdt(e::modificado));
            assertTrue(c.avisos.disse("ok", "Voltei README.md à versão anterior"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Buscar
    // ---------------------------------------------------------------------------------------------

    @Test
    void cmdFAbreABuscaEscFechaSemApagarOTexto(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "README.md");
            String antes = Visual.naEdt(() -> e.area().getText());
            assertFalse(Visual.naEdt(e::buscaAberta));
            apertar(e, KeyEvent.VK_F, cmd());
            assertTrue(Visual.naEdt(e::buscaAberta));
            Visual.naEdt(() -> e.campoDeBusca().setText("rodar"));
            assertEquals("1 resultado", Visual.naEdt(e::resultadoDaBusca));
            assertEquals("rodar", Visual.naEdt(() -> e.area().getSelectedText()), "selecionou o que achou");
            Visual.naEdt(() -> e.campoDeBusca().setText("nao-tem-isto"));
            assertEquals("Não achei", Visual.naEdt(e::resultadoDaBusca));
            Visual.naEdt(() -> e.campoDeBusca().setText("COMO"));
            assertEquals("1 resultado", Visual.naEdt(e::resultadoDaBusca), "por padrão não diferencia maiúsculas");
            // Esc no campo de busca fecha a busca e não mexe no texto
            Object nomeDaAcao = Visual.naEdt(() -> e.campoDeBusca().getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)));
            Visual.naEdt(() -> e.campoDeBusca().getActionMap().get(nomeDaAcao).actionPerformed(null));
            assertFalse(Visual.naEdt(e::buscaAberta));
            assertEquals(antes, Visual.naEdt(() -> e.area().getText()));
            assertFalse(Visual.naEdt(e::modificado));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fechar
    // ---------------------------------------------------------------------------------------------

    @Test
    void fecharSemMudancaFechaNaHora(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "README.md");
            Visual.naEdt(() -> e.botaoFechar().doClick(0));
            assertTrue(Visual.naEdt(() -> c.g.editoresAbertos().isEmpty()));
            assertFalse(Visual.naEdt(() -> c.g.abas().isVisible()), "sem editor, a faixa de abas some");
            assertTrue(c.dialogos.mostrados.isEmpty());
        }
    }

    @Test
    void fecharComMudancaPerguntaSalvarDescartarOuCancelar(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            EditorDeTexto e = abrir(c, "README.md");
            String original = Files.readString(c.sftp.orbita().resolve("README.md"));
            digitar(e, "mudei\n");
            Visual.naEdt(() -> e.botaoFechar().doClick(0));
            DialogoFecharEditor d = c.dialogo(DialogoFecharEditor.class);
            assertTrue(Visual.naEdt(() -> TextosEmEditor.de(d.raiz())).contains("ainda não salvou"));
            Visual.naEdt(() -> d.botaoCancelar().doClick(0));
            assertEquals(1, Visual.naEdt(() -> c.g.editoresAbertos().size()), "cancelar mantém o editor");

            Visual.naEdt(() -> e.botaoFechar().doClick(0));
            DialogoFecharEditor d2 = c.dialogo(DialogoFecharEditor.class);
            Visual.naEdt(() -> d2.botaoDescartar().doClick(0));
            assertTrue(Visual.naEdt(() -> c.g.editoresAbertos().isEmpty()));
            assertEquals(original, Files.readString(c.sftp.orbita().resolve("README.md")), "descartar não grava");

            EditorDeTexto e2 = abrir(c, "README.md");
            digitar(e2, "vou salvar\n");
            Visual.naEdt(() -> e2.botaoFechar().doClick(0));
            DialogoFecharEditor d3 = c.dialogo(DialogoFecharEditor.class);
            Visual.naEdt(() -> d3.botaoSalvar().doClick(0));
            c.esperarQue("salvou e fechou", () -> Visual.naEdt(() -> c.g.editoresAbertos().isEmpty()));
            assertTrue(Files.readString(c.sftp.orbita().resolve("README.md")).endsWith("vou salvar\n"));
        }
    }

    @Test
    void temAlteracoesNaoSalvasDizSeHaEditorSujo(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, true)) {
            assertFalse(Visual.naEdt(c.g::temAlteracoesNaoSalvas));
            EditorDeTexto e = abrir(c, "README.md");
            assertFalse(Visual.naEdt(c.g::temAlteracoesNaoSalvas));
            digitar(e, "x");
            assertTrue(Visual.naEdt(c.g::temAlteracoesNaoSalvas));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Só leitura
    // ---------------------------------------------------------------------------------------------

    @Test
    void semAsAcoesLigadasOEditorSoLe(@TempDir Path tmp) throws Exception {
        try (CenaDeArquivos c = cena(tmp, false)) {
            EditorDeTexto e = abrir(c, "README.md");
            assertFalse(Visual.naEdt(() -> e.area().isEditable()));
            assertFalse(Visual.naEdt(() -> e.botaoSalvar().isVisible()));
            assertFalse(Visual.naEdt(() -> e.botaoDesfazer().isVisible()));
            assertTrue(Visual.naEdt(e::textoDoAviso).contains("Somente leitura nesta versão"));
            assertTrue(Visual.naEdt(() -> e.botaoBuscar().isVisible()), "buscar vale");
            // Cmd+S não grava nada
            String antes = Files.readString(c.sftp.orbita().resolve("README.md"));
            apertar(e, KeyEvent.VK_S, cmd());
            Espera.naoAcontece(Duration.ofMillis(400), "gravou em somente leitura", () -> !lerLocal(c.sftp.orbita().resolve("README.md")).equals(antes));
        }
    }

    private static String lerLocal(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Textos de um componente, para as conferências. */
    static final class TextosEmEditor {
        private TextosEmEditor() { }

        static String de(java.awt.Component c) {
            return br.com.labdesktop.ui.TextosDeTela.de(c);
        }
    }
}
