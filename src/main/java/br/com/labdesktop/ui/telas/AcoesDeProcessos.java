package br.com.labdesktop.ui.telas;

import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.AcoesDaInterface;
import java.util.function.BooleanSupplier;

/**
 * O que a lista de processos precisa de fora: se as ações de escrita estão liberadas, o que se oferece para cada processo (só a regra, sem rede) e como abrir a confirmação.
 * Existe para a lista não conhecer o servidor, as ações da janela nem o diálogo, e para os testes entregarem uma versão simples.
 */
interface AcoesDeProcessos {

    /** As ações de escrita estão ligadas (a mesma trava das outras)? Desligadas, os botões aparecem desligados com a explicação. */
    boolean liberadas();

    /** O que se oferece para este processo (proteção, permissão, container). Sem rede. */
    Oferta avaliar(ProcessoAlvo alvo);

    /** Abre a confirmação de Encerrar ou Reiniciar. {@code depois} roda (na EDT) quando algo aconteceu e a lista deve se atualizar. */
    void abrir(ProcessoAlvo alvo, String origem, Acao acao, Runnable depois);

    /** As de produção, sobre o servidor e as ações da janela; {@code null} onde não há nada vivo (testes de tela sem servidor). */
    static AcoesDeProcessos de(ServidorLab servidor, AcoesDaInterface acoes, BooleanSupplier liberadas) {
        if (servidor == null || acoes == null) {
            return null;
        }
        return new AcoesDeProcessos() {
            @Override
            public boolean liberadas() {
                return liberadas.getAsBoolean();
            }

            @Override
            public Oferta avaliar(ProcessoAlvo alvo) {
                return servidor.controleDeProcessos().avaliar(alvo);
            }

            @Override
            public void abrir(ProcessoAlvo alvo, String origem, Acao acao, Runnable depois) {
                acoes.encerrarOuReiniciarProcesso(servidor, alvo, origem, acao, depois);
            }
        };
    }
}
