package br.com.labdesktop.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.git.ComandoGit.Natureza;
import br.com.labdesktop.local.ProgramaNaoEncontradoException;
import br.com.labdesktop.projetos.ExecutorDeArgumentos;
import br.com.labdesktop.ssh.ComandoDemorouException;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** O executor da leitura NÃO deixa passar escrita (pela forma do comando, não pelo que o objeto diz), e cada erro de execução vira uma falha explicada. */
class ExecutorDeGitTest {

    private static final Duration P = Duration.ofSeconds(5);
    private final List<List<String>> rodou = new ArrayList<>();

    private ExecutorDeArgumentos gravador() {
        return (argumentos, ambiente, stdin, prazo) -> {
            rodou.add(argumentos);
            return new ResultadoExec(0, "ok".getBytes(), new byte[0], false);
        };
    }

    @Test
    void aLeituraRecusaEscritaETemporarioEnaoRodaNada() {
        ExecutorDeGit leitura = ExecutorDeGit.soDeLeitura(gravador());
        for (ComandoGit c : List.of(ComandosDeGit.iniciar(ComandosDeGitTest.PASTA), ComandosDeGit.adicionarDaLista(ComandosDeGitTest.PASTA, new byte[0]), ComandosDeGit.commitar(ComandosDeGitTest.PASTA, "x"),
            ComandosDeGit.gravarIdentidadeLocal(ComandosDeGitTest.PASTA, "user.name", "Ana"), ComandosDeGit.limparPreparo(ComandosDeGitTest.PASTA), ComandosDeGit.criarAreaTemporaria(ComandosDeGitTest.AREA),
            ComandosDeGit.escreverExclusoes(ComandosDeGitTest.AREA, new byte[0]), ComandosDeGit.apagarAreaTemporaria(ComandosDeGitTest.AREA))) {
            ErroDoGit e = assertThrows(ErroDoGit.class, () -> leitura.rodar(c, P), c.argumentos().toString());
            assertTrue(e.falha().frase().contains("não é permitido"));
        }
        assertTrue(rodou.isEmpty(), "nada chegou ao executor: " + rodou);
    }

    @Test
    void aConferenciaAceitaATemporariaMasNuncaAEscrita() throws Exception {
        ExecutorDeGit conferir = ExecutorDeGit.paraConferir(gravador());
        conferir.rodar(ComandosDeGit.criarAreaTemporaria(ComandosDeGitTest.AREA), P);
        conferir.rodar(ComandosDeGit.status(ComandosDeGitTest.PASTA), P);
        assertEquals(2, rodou.size());
        assertThrows(ErroDoGit.class, () -> conferir.rodar(ComandosDeGit.iniciar(ComandosDeGitTest.PASTA), P));
        assertThrows(ErroDoGit.class, () -> conferir.rodar(ComandosDeGit.adicionarDaLista(ComandosDeGitTest.PASTA, new byte[0]), P));
        assertEquals(2, rodou.size());
    }

    @Test
    void umComandoQueSeDizLeituraMasTemFormaDeEscritaEBarradoMesmoAssim() {
        ExecutorDeGit leitura = ExecutorDeGit.soDeLeitura(gravador());
        ComandoGit mentiroso = new ComandoGit(Natureza.LEITURA, List.of("git", "-C", "/srv/lab/projects/loja-web", "add", "-f", "x"), Set.of(), Map.of(), null);
        assertThrows(ErroDoGit.class, () -> leitura.rodar(mentiroso, P));
        ComandoGit rm = new ComandoGit(Natureza.LEITURA, List.of("rm", "-rf", "--", "/srv/lab/projects/loja-web"), Set.of(), Map.of(), null);
        assertThrows(ErroDoGit.class, () -> leitura.rodar(rm, P));
        assertTrue(rodou.isEmpty());
    }

    @Test
    void oCompletoDeixaTudoPassarMasSoOCriadorOUsa() throws Exception {
        ExecutorDeGit completo = ExecutorDeGit.completo(gravador());
        completo.rodar(ComandosDeGit.iniciar(ComandosDeGitTest.PASTA), P);
        assertEquals(1, rodou.size());
    }

    @Test
    void cadaErroDeExecucaoViraUmaFalhaExplicada() {
        assertEquals(FalhaDoGit.Tipo.GIT_NAO_INSTALADO, falha((a, e, s, p) -> {
            throw new ProgramaNaoEncontradoException("git");
        }).tipo());
        assertEquals(FalhaDoGit.Tipo.DEMOROU, falha((a, e, s, p) -> {
            throw new ComandoDemorouException(30);
        }).tipo());
        assertEquals(FalhaDoGit.Tipo.SEM_CONEXAO, falha((a, e, s, p) -> {
            throw new IOException("caiu");
        }).tipo());
        assertEquals(FalhaDoGit.Tipo.OUTRO, falha((a, e, s, p) -> {
            throw new IllegalArgumentException("Argumento fora da regra do comando remoto.");
        }).tipo());
    }

    private static FalhaDoGit falha(ExecutorDeArgumentos ex) {
        return assertThrows(ErroDoGit.class, () -> ExecutorDeGit.soDeLeitura(ex).rodar(ComandosDeGit.status(ComandosDeGitTest.PASTA), P)).falha();
    }

    @Test
    void codigoDeSaidaNaoZeroNaoELancadoQuemChamaDecide() throws Exception {
        ExecutorDeGit e = ExecutorDeGit.soDeLeitura((a, env, s, p) -> new ResultadoExec(128, new byte[0], "fatal: not a git repository".getBytes(), false));
        ExecutorDeGit.Saida s = e.rodar(ComandosDeGit.status(ComandosDeGitTest.PASTA), P);
        assertEquals(128, s.status());
        assertTrue(s.stderr().contains("not a git repository"));
    }
}
