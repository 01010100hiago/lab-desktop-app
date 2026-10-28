package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.Topico;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Processos" de um projeto: os programas do projeto que estão entre os 20 que mais usam o processador do servidor, com Encerrar e Reiniciar (o mesmo bloco da aba Processos do
 * servidor, filtrado pelo projeto). Não depende do detalhe do projeto: lê {@code /api/servidor} e {@code /api/servidor/detalhe} só enquanto a aba está à vista. Para o projeto inteiro,
 * "Reiniciar" na Visão geral continua sendo o jeito.
 */
final class PaginaProcessosDoProjeto extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;

    private final BlocoDeProcessos bloco;
    private transient Inscricao inscricaoDoServidor;
    private transient Inscricao inscricaoDoDetalhe;
    private InfoServidor info;
    private DetalheServidor detalhe;

    PaginaProcessosDoProjeto(ContextoDoProjeto ctx) {
        super(ctx);
        this.bloco = new BlocoDeProcessos(ctx.acoesDeProcessos, ctx.projeto);
        setName("pagina-processos-do-projeto");
        iniciar();
    }

    @Override
    boolean independeDoDetalhe() {
        return true;
    }

    @Override
    JComponent montarConteudo() {
        JPanel conteudo = new br.com.labdesktop.ui.componentes.Painel(new java.awt.BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Dimension getMaximumSize() {
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        conteudo.add(bloco, java.awt.BorderLayout.NORTH);
        return conteudo;
    }

    @Override
    void aoAparecer() {
        if (ctx.servidor == null || inscricaoDoServidor != null) {
            return;
        }
        inscricaoDoServidor = ctx.servidor.assinar(Topico.SERVIDOR, new OuvinteDeTopico<InfoServidor>() {
            @Override
            public void aoAtualizar(InfoServidor valor) {
                receber(valor, null);
            }

            @Override
            public void aoFalhar(ErroApi erro) { }
        });
        inscricaoDoDetalhe = ctx.servidor.assinar(Topico.DETALHE_SERVIDOR, new OuvinteDeTopico<DetalheServidor>() {
            @Override
            public void aoAtualizar(DetalheServidor valor) {
                receber(null, valor);
            }

            @Override
            public void aoFalhar(ErroApi erro) { }
        });
    }

    @Override
    void aoSumir() {
        inscricaoDoServidor = soltar(inscricaoDoServidor);
        inscricaoDoDetalhe = soltar(inscricaoDoDetalhe);
    }

    /** Recebe a leitura do servidor (na EDT). Público para os testes alimentarem a aba com dados fixos. */
    void receber(InfoServidor novoInfo, DetalheServidor novoDetalhe) {
        if (novoInfo != null) {
            info = novoInfo;
        }
        if (novoDetalhe != null) {
            detalhe = novoDetalhe;
        }
        bloco.atualizar(info, detalhe);
    }

    @Override
    void atualizarConteudo() {
        bloco.atualizar(info, detalhe);
    }

    BlocoDeProcessos bloco() {
        return bloco;
    }
}
