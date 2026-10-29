package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.CriadorDeRepositorio;
import br.com.labdesktop.git.GitDeTeste;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A janela "Criar repositório Git" de ponta a ponta com o {@code git} DE VERDADE numa pasta temporária (o motor é o {@link CriadorDeRepositorio} sobre "Este computador"): o usuário abre, vê a conferência,
 * digita nome e e-mail, clica no botão e o repositório aparece sem o {@code .env}; e com a trava das ações desligada nada é criado no disco.
 */
@ExtendWith(VerificaEdt.class)
class CriarRepositorioNaJanelaComGitDeVerdadeTest {

    @TempDir
    Path tmp;

    private DialogoCriarRepositorio abrir(GitDeTeste g, Path projeto, boolean liberada, java.util.concurrent.atomic.AtomicInteger criacoes) {
        CriadorDeRepositorio criador = new CriadorDeRepositorio(g.ambiente(), projeto.toString());
        DialogoCriarRepositorio.Motor motor = new DialogoCriarRepositorio.Motor() {
            @Override
            public ConferenciaDoRepositorio conferir() throws Exception {
                return criador.conferirCriacao();
            }

            @Override
            public ResultadoDaCriacao criar(PedidoDeCriacao pedido, OperacoesDeGit.Observador observador, Cancelamento cancelamento) throws Exception {
                criacoes.incrementAndGet();
                return criador.criarRepositorio(pedido, observador, cancelamento);
            }
        };
        DialogoCriarRepositorio d = Visual.naEdt(() -> new DialogoCriarRepositorio("lab", projeto.getFileName().toString(), motor, liberada, false, null, null, null, new AvisosGravados(),
            new CopiadorDeTexto(() -> new Clipboard("teste"))));
        Visual.naEdt(d::conferir);
        Espera.ate(Duration.ofSeconds(30), "conferência", () -> Visual.naEdt(() -> d.conferenciaMostrada() != null));
        return d;
    }

    private static List<String> arquivosNoDisco(Path p) throws Exception {
        try (Stream<Path> s = Files.walk(p)) {
            return s.map(x -> p.relativize(x).toString()).sorted().toList();
        }
    }

    @Test
    void doCliqueAoRepositorioSemOEnv() throws Exception {
        assumeTrue(GitDeTeste.temGit());
        Visual.tema(Tema.CLARO);
        GitDeTeste g = new GitDeTeste(tmp);
        Path p = g.projeto("loja-web");
        g.escrever(p, ".env", "SENHA=1\n");
        g.escrever(p, "src/app.js", "x\n");
        g.escrever(p, "node_modules/x/i.js", "x\n");
        java.util.concurrent.atomic.AtomicInteger criacoes = new java.util.concurrent.atomic.AtomicInteger();
        DialogoCriarRepositorio d = abrir(g, p, true, criacoes);
        String t = Visual.naEdt(() -> TextosDeTela.de(d.raiz()));
        assertTrue(t.contains("1 arquivo, mais o .gitignore (que o app cria)"), t);
        assertFalse(Files.exists(p.resolve(".git")), "a conferência não criou nada");
        assertFalse(Files.exists(p.resolve(".gitignore")));
        assertFalse(Visual.naEdt(() -> d.botaoCriar().isEnabled()), "falta o nome e o e-mail");
        Visual.naEdt(() -> {
            d.campoNome().setText("Ana Silva");
            d.campoEmail().setText("ana@example.com");
        });
        assertTrue(Visual.naEdt(() -> d.botaoCriar().isEnabled()));
        assertEquals("Criar repositório em loja-web (lab)", Visual.naEdt(() -> d.botaoCriar().getText()));
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        Espera.ate(Duration.ofSeconds(60), "resultado", () -> "RESULTADO".equals(Visual.naEdt(d::faseAtual)));
        assertTrue(d.resultadoMostrado().criou(), d.resultadoMostrado().mensagem());
        assertEquals(List.of(".gitignore", "src/app.js"), List.of(g.git(p, "ls-files").strip().split("\n")), "o .env e o node_modules ficaram de fora");
        assertEquals("Ana Silva", g.git(p, "log", "-1", "--format=%an").strip());
        assertTrue(Files.exists(p.resolve(".env")), "o arquivo continua no disco");
        assertEquals(1, criacoes.get());
        assertTrue(Visual.naEdt(() -> TextosDeTela.de(d.raiz())).contains("Repositório criado"));
    }

    @Test
    void comATravaDesligadaNadaEhCriadoNoDisco() throws Exception {
        assumeTrue(GitDeTeste.temGit());
        Visual.tema(Tema.CLARO);
        GitDeTeste g = new GitDeTeste(tmp);
        Path p = g.projeto("loja-web");
        g.escrever(p, "src/app.js", "x\n");
        Files.writeString(g.casa.resolve(".gitconfig"), "[user]\n\tname = Dona\n\temail = dona@example.com\n");
        List<String> antes = arquivosNoDisco(p);
        java.util.concurrent.atomic.AtomicInteger criacoes = new java.util.concurrent.atomic.AtomicInteger();
        DialogoCriarRepositorio d = abrir(g, p, false, criacoes);
        assertFalse(Visual.naEdt(() -> d.botaoCriar().isEnabled()));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", Visual.naEdt(() -> d.botaoCriar().getToolTipText()));
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        assertEquals(0, criacoes.get());
        assertEquals(antes, arquivosNoDisco(p), "nem .git, nem .gitignore: nada foi escrito no projeto");
    }
}
