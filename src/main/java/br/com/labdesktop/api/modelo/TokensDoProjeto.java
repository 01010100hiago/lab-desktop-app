package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/tokens/:p} (CONTRATO-API 3.6): o consumo de UM projeto, em US$ equivalente de API (a conta é assinatura, não
 * cobra por token). Todo campo é anulável e as listas nunca são {@code null}; o que o agente acrescentar depois é ignorado.
 * Os nomes de modelo, o texto das tarefas e o repositório vêm do servidor e entram limpos.
 *
 * @param porDia  os últimos 30 dias, do mais antigo ao mais novo; dias sem uso vêm com zeros
 * @param modelos o consumo por modelo
 * @param sessoes até 30, a mais recente primeiro
 * @param harness as entregas do Harness do projeto, quando houver
 */
public record TokensDoProjeto(
    @JsonProperty("nome") String nome,
    @JsonProperty("totais") Totais totais,
    @JsonProperty("por_dia") List<Dia> porDia,
    @JsonProperty("modelos") List<Modelo> modelos,
    @JsonProperty("sessoes") List<SessaoDeConsumo> sessoes,
    @JsonProperty("harness") Harness harness) {

    public TokensDoProjeto {
        nome = Textos.linha(nome);
        porDia = Textos.lista(porDia);
        modelos = Textos.lista(modelos);
        sessoes = Textos.lista(sessoes);
    }

    /** Os quatro períodos que a tela mostra. */
    public record Totais(
        @JsonProperty("hoje") Acumulado hoje,
        @JsonProperty("semana") Acumulado semana,
        @JsonProperty("mes") Acumulado mes,
        @JsonProperty("tudo") Acumulado tudo) { }

    /** O consumo somado de um período. */
    public record Acumulado(
        @JsonProperty("respostas") Long respostas,
        @JsonProperty("entrada") Long entrada,
        @JsonProperty("saida") Long saida,
        @JsonProperty("cache_escrita") Long cacheEscrita,
        @JsonProperty("cache_leitura") Long cacheLeitura,
        @JsonProperty("custo_usd") Double custoUsd) { }

    /** Um dia do gráfico: {@code dia} é {@code AAAA-MM-DD}. */
    public record Dia(
        @JsonProperty("dia") String dia,
        @JsonProperty("custo_usd") Double custoUsd,
        @JsonProperty("saida") Long saida,
        @JsonProperty("entrada") Long entrada,
        @JsonProperty("cache_escrita") Long cacheEscrita,
        @JsonProperty("cache_leitura") Long cacheLeitura,
        @JsonProperty("respostas") Long respostas) {

        public Dia {
            dia = Textos.linha(dia);
        }
    }

    /** O consumo de um modelo do Claude. */
    public record Modelo(
        @JsonProperty("modelo") String modelo,
        @JsonProperty("respostas") Long respostas,
        @JsonProperty("entrada") Long entrada,
        @JsonProperty("saida") Long saida,
        @JsonProperty("cache_escrita") Long cacheEscrita,
        @JsonProperty("cache_leitura") Long cacheLeitura,
        @JsonProperty("custo_usd") Double custoUsd) {

        public Modelo {
            modelo = Textos.linha(modelo);
        }
    }

    /**
     * Uma sessão do Claude no projeto.
     *
     * @param atribuicao {@code direta} ou {@code estimada}
     */
    public record SessaoDeConsumo(
        @JsonProperty("id") String id,
        @JsonProperty("inicio") Instant inicio,
        @JsonProperty("fim") Instant fim,
        @JsonProperty("modelos") List<String> modelos,
        @JsonProperty("respostas") Long respostas,
        @JsonProperty("subagentes") Long subagentes,
        @JsonProperty("saida") Long saida,
        @JsonProperty("custo_usd") Double custoUsd,
        @JsonProperty("atribuicao") String atribuicao) {

        public SessaoDeConsumo {
            id = Textos.linha(id);
            modelos = Textos.linhas(modelos);
            atribuicao = Textos.linha(atribuicao);
        }
    }

    /** As entregas do Harness. {@code repo} só existe quando o projeto aponta para o GitHub. */
    public record Harness(
        @JsonProperty("entregas") List<Entrega> entregas,
        @JsonProperty("iniciados") Long iniciados,
        @JsonProperty("tokens_total") Long tokensTotal,
        @JsonProperty("repo") String repo) {

        public Harness {
            entregas = Textos.lista(entregas);
            repo = Textos.linha(repo);
        }
    }

    /**
     * Uma entrega do Harness. {@code pr}, {@code tokens} e {@code merge} podem ser {@code null}; {@code merge} é booleano ou um
     * texto curto, por isso fica como {@code Object} e a tela usa {@link #mergeEmTexto()}.
     */
    public record Entrega(
        @JsonProperty("quando") Instant quando,
        @JsonProperty("tarefa") String tarefa,
        @JsonProperty("trilha") String trilha,
        @JsonProperty("pr") Integer pr,
        @JsonProperty("tokens") Long tokens,
        @JsonProperty("merge") Object merge) {

        public Entrega {
            tarefa = Textos.linha(tarefa);
            trilha = Textos.linha(trilha);
            merge = merge instanceof String s ? Textos.linha(s) : merge;
        }

        /** {@code mesclado}, {@code não mesclado} ou o texto curto que o agente mandou. */
        public String mergeEmTexto() {
            if (merge instanceof Boolean b) {
                return b ? "mesclado" : "não mesclado";
            }
            return merge instanceof String s && !s.isBlank() ? s : null;
        }
    }

    /** Há consumo a mostrar (qualquer dia com custo ou resposta). */
    public boolean temConsumo() {
        return porDia.stream().anyMatch(d -> d.custoUsd() != null && d.custoUsd() > 0)
            || (totais != null && totais.tudo() != null && totais.tudo().respostas() != null && totais.tudo().respostas() > 0);
    }
}
