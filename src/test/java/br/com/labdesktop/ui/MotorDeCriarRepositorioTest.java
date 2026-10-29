package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Criar escreve no projeto: o motor da janela confere a trava das ações NA HORA de criar (mesmo que o botão estivesse ligado quando a janela abriu) e conferir nunca precisa dela. */
class MotorDeCriarRepositorioTest {

    private final AtomicInteger conferencias = new AtomicInteger();
    private final AtomicInteger criacoes = new AtomicInteger();

    private final OperacoesDeGit operacoes = new OperacoesDeGit() {
        @Override
        public ConferenciaDoRepositorio conferirCriacao() {
            conferencias.incrementAndGet();
            return ConferenciaDoRepositorio.parou(ConferenciaDoRepositorio.Situacao.GIT_AUSENTE, null, "");
        }

        @Override
        public ResultadoDaCriacao criarRepositorio(PedidoDeCriacao pedido, OperacoesDeGit.Observador observador, Cancelamento cancelamento) {
            criacoes.incrementAndGet();
            return new ResultadoDaCriacao(true, true, "ok", List.of(), List.of(), null, 1, "abc1234", null);
        }
    };

    @Test
    void semATravaCriarNaoChamaODominioEDizQueNadaFoiCriado() throws Exception {
        MotorDeCriarRepositorio m = new MotorDeCriarRepositorio(operacoes, () -> false);
        ResultadoDaCriacao r = m.criar(new PedidoDeCriacao(null, null, null, false), OperacoesDeGit.Observador.NENHUM, new Cancelamento());
        assertFalse(r.criou());
        assertFalse(r.repositorioCriado());
        assertTrue(r.mensagem().startsWith("As ações de escrita estão desligadas nesta versão do app."), r.mensagem());
        assertTrue(r.mensagem().endsWith("Nada foi criado."));
        assertEquals(0, criacoes.get());
    }

    @Test
    void conferirNaoPrecisaDaTrava() throws Exception {
        new MotorDeCriarRepositorio(operacoes, () -> false).conferir();
        assertEquals(1, conferencias.get());
    }

    @Test
    void aTravaEhLidaNaHoraDeCriarNaoSoQuandoAJanelaAbriu() throws Exception {
        AtomicBoolean trava = new AtomicBoolean(true);
        MotorDeCriarRepositorio m = new MotorDeCriarRepositorio(operacoes, trava::get);
        assertTrue(m.criar(null, OperacoesDeGit.Observador.NENHUM, new Cancelamento()).criou());
        assertEquals(1, criacoes.get());
        trava.set(false); // alguém desligou as ações com a janela aberta
        assertFalse(m.criar(null, OperacoesDeGit.Observador.NENHUM, new Cancelamento()).criou());
        assertEquals(1, criacoes.get(), "a segunda vez não chegou ao domínio");
    }
}
