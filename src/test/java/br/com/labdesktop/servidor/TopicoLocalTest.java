package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.Detalhe;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.local.ProjetosLocais;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.teste.AgendadorFalso;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Os tópicos de "Este computador": a lista e o detalhe lidos da pasta e do Docker, que rodam SEM o agente (sem API) e traduzem o erro como o front espera. */
class TopicoLocalTest {

    @TempDir
    Path tmp;

    private AgendadorFalso relogio;
    private Sondador sondador;
    private ProjetosLocais projetos;

    private static final class Colecao<T> implements OuvinteDeTopico<T> {
        final List<T> valores = new ArrayList<>();
        final List<ErroApi> erros = new ArrayList<>();

        @Override
        public void aoAtualizar(T valor) {
            valores.add(valor);
        }

        @Override
        public void aoFalhar(ErroApi erro) {
            erros.add(erro);
        }
    }

    @BeforeEach
    void preparar() {
        relogio = new AgendadorFalso();
        sondador = new Sondador(relogio, Runnable::run, Runnable::run, e -> { });
        ExecutorDeProcesso docker = (a, stdin, prazo) -> new ResultadoExec(0, new byte[0], new byte[0], false);
        projetos = new ProjetosLocais(() -> tmp, docker);
    }

    @AfterEach
    void fechar() {
        sondador.close();
    }

    @Test
    void aListaLocalRodaSemAPIEComAMesmaChaveDaListaDoAgente() throws Exception {
        Files.createDirectories(tmp.resolve("meu-app"));
        Topico<VisaoProjetos> t = Topico.projetosLocais(projetos);
        assertEquals(Topico.PROJETOS.chave(), t.chave(), "o atualizarAgora(PROJETOS) das ações vale para os dois");
        Colecao<VisaoProjetos> c = new Colecao<>();
        sondador.assinar(t, c); // nenhuma API foi dada ao Sondador: o agente local pode nem existir
        relogio.executarVencidas();
        assertEquals(1, c.valores.size(), "consultou de imediato, sem agente");
        assertEquals(List.of("meu-app"), c.valores.get(0).lista().projetos().stream().map(p -> p.nome()).toList());
        assertEquals(null, c.valores.get(0).raiz());
        Files.createDirectories(tmp.resolve("outro"));
        relogio.avancar(Duration.ofSeconds(11));
        assertEquals(2, c.valores.size(), "a pasta nova aparece no ciclo de 10 s");
        assertEquals(List.of(), c.erros);
    }

    @Test
    void umTopicoDoAgenteNaoRodaSemAPI() {
        Colecao<VisaoProjetos> c = new Colecao<>();
        sondador.assinar(Topico.PROJETOS, c);
        relogio.avancar(Duration.ofMinutes(1));
        assertEquals(List.of(), c.valores, "o do agente continua parado sem API: só o local é independente dele");
        assertEquals(0, c.erros.size());
    }

    @Test
    void oDetalheLocalDizNaoEncontradoQuandoAPastaSome() throws Exception {
        Files.createDirectories(tmp.resolve("meu-app"));
        Colecao<Detalhe> c = new Colecao<>();
        sondador.assinar(Topico.detalheLocal(projetos, "meu-app"), c);
        relogio.executarVencidas();
        assertEquals(1, c.valores.size());
        assertTrue(c.valores.get(0) instanceof DetalheProjeto d && d.nome().equals("meu-app"));
        try (var s = Files.walk(tmp.resolve("meu-app"))) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        relogio.avancar(Duration.ofSeconds(11));
        assertEquals(1, c.erros.size());
        assertTrue(c.erros.get(0) instanceof ErroApi.NaoEncontrado, "a página do projeto volta para a lista");
    }

    @Test
    void erroDeLeituraViraErroDeServidorNuncaErroDeRedeQuePediriaVerificarAConexao() throws Exception {
        Path arquivoNoLugarDaPasta = tmp.resolve("meu-app");
        Files.writeString(arquivoNoLugarDaPasta, "x");
        Colecao<Detalhe> c = new Colecao<>();
        sondador.assinar(Topico.detalheLocal(projetos, "meu-app"), c);
        relogio.executarVencidas();
        assertEquals(1, c.erros.size());
        assertFalse(c.erros.get(0) instanceof ErroApi.ErroRede);
    }
}
