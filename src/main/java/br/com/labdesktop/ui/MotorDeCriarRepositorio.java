package br.com.labdesktop.ui;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * O que a janela "Criar repositório Git" usa do domínio: conferir (só leitura) e criar. Criar escreve no projeto e por isso confere a trava das ações ({@code labdesktop.acoes}) NA HORA de criar, de novo
 * (defesa em profundidade: a janela já mostra o botão desligado, mas a escrita não pode depender só de o botão estar desligado); sem a trava devolve "nada foi criado" sem chamar o domínio. Do mesmo
 * jeito, com repositório em subpasta a criação na pasta principal só passa com a confirmação explícita no pedido.
 */
public final class MotorDeCriarRepositorio implements DialogoCriarRepositorio.Motor {

    private final OperacoesDeGit operacoes;
    private final BooleanSupplier trava;

    public MotorDeCriarRepositorio(OperacoesDeGit operacoes, BooleanSupplier trava) {
        this.operacoes = operacoes;
        this.trava = trava;
    }

    @Override
    public ConferenciaDoRepositorio conferir() throws Exception {
        return operacoes.conferirCriacao();
    }

    @Override
    public ResultadoDaCriacao criar(PedidoDeCriacao pedido, OperacoesDeGit.Observador observador, Cancelamento cancelamento) throws Exception {
        if (!trava.getAsBoolean()) {
            return new ResultadoDaCriacao(false, false, DialogoCriarRepositorio.RAZAO_DA_TRAVA + " Nada foi criado.", List.of(), List.of(), null, 0, "", null);
        }
        if (pedido != null && pedido.conferencia() != null && pedido.conferencia().temRepositoriosAbaixo() && !pedido.confirmouRepositoriosAbaixo()) {
            // defesa em profundidade (o motor do domínio confere de novo): a janela só deixa criar com a caixa marcada, mas a escrita não pode depender só da tela
            return new ResultadoDaCriacao(false, false, "Já existe repositório Git em subpasta deste projeto, e criar um na pasta principal esconderia o histórico dele. "
                + "Isto só vale com a sua confirmação explícita na janela. Nada foi criado.", List.of(), List.of(), null, 0, "", null);
        }
        return operacoes.criarRepositorio(pedido, observador, cancelamento);
    }
}
