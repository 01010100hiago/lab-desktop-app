package br.com.labdesktop.cloudflare;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import java.util.List;

/**
 * Onde mora a lista de regras do túnel e como ela é mudada: num {@code config.yml} do servidor ({@link TunelPorArquivo}) ou na API da Cloudflare
 * ({@link TunelPelaApi}). A publicação e a remoção só falam com esta interface. Duas fases, como em tudo no app: {@code preparar...} só LÊ e calcula a mudança
 * (a diferença para mostrar ao usuário); {@link Mudanca#aplicar} escreve, e só depois do clique de confirmação.
 */
public interface Tunel {

    /** Uma frase de onde estão as regras ("o config.yml em /srv/..." ou "a configuração do túnel na Cloudflare"). */
    String onde();

    /** {@link #onde()} depois de "em" ("em o" vira "no", "em a" vira "na"). */
    default String emOnde() {
        return contrair("n", onde());
    }

    /** {@link #onde()} depois de "de" ("de o" vira "do", "de a" vira "da"). */
    default String deOnde() {
        return contrair("d", onde());
    }

    private static String contrair(String letra, String sintagma) {
        if (sintagma.startsWith("o ") || sintagma.startsWith("a ")) {
            return letra + sintagma;
        }
        return (letra.equals("n") ? "em " : "de ") + sintagma;
    }

    /** Tem de reiniciar o cloudflared depois de mudar (o arquivo)? Senão a Cloudflare entrega as regras sozinha (a API). */
    boolean reiniciaOCloudflared();

    /**
     * A regra do túnel leva uma marca do app (o comentário do {@code config.yml})? Sem marca (a API não guarda comentário em regra), quem diz que o endereço é do app
     * é só o registro de DNS: sem ele, o app não tira a regra.
     */
    boolean regraTemMarca();

    /** A regra {@code hostname -> servico}, a acrescentar. */
    Mudanca prepararAcrescimo(String hostname, String servico, String observacao) throws FalhaDoProjeto;

    /** Tirar a regra de {@code hostname}; {@code servicoEsperado} é o que o app sabe que ele criou (a API não guarda marca na regra). */
    Mudanca prepararRemocao(String hostname, String servicoEsperado) throws FalhaDoProjeto;

    /**
     * Uma mudança calculada. {@code bloqueio} não nulo quer dizer que o app NÃO pode seguir (a frase diz por quê); {@code semEfeito} quer dizer que não há o que
     * escrever (a regra já estava lá, ou já não está).
     *
     * @param diferenca  as linhas a mostrar (o que sai com "-", o que entra com "+")
     * @param avisos     o que o usuário precisa saber (regra final acrescentada, arquivo vazio...)
     */
    interface Mudanca {
        String diferenca();

        List<String> avisos();

        String bloqueio();

        boolean semEfeito();

        /**
         * Escreve (cópia datada, conferência, reinício) e devolve o que fez, para {@link Aplicada#desfazer}. Se falha no meio, DESFAZ o que ela mesma fez
         * antes de lançar.
         */
        Aplicada aplicar(ObservadorDaEtapa obs, Cancelamento cancelamento) throws FalhaDoProjeto;
    }

    /** O que uma mudança já fez; desfaz só isso. */
    interface Aplicada {
        /** A cópia que guardou, se houve ({@code null} na API). */
        String copia();

        /**
         * Devolve o que esta mudança escreveu (arquivo da cópia, configuração anterior) e, no arquivo, reinicia o cloudflared de novo.
         *
         * @return o que NÃO conseguiu desfazer, em frases para o usuário fazer à mão (vazio: desfez tudo)
         */
        List<String> desfazer();
    }
}
