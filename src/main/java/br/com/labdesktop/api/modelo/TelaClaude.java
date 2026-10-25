package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /api/projetos/:p/claude/tela} (CONTRATO-API 3.10): a tela do Claude já analisada pelo agente. É texto do que
 * aparece no terminal da sessão, portanto NÃO confiável: tudo entra limpo e a tela só o mostra como texto.
 *
 * @param pergunta        a pergunta pendente com opções numeradas, ou {@code null}
 * @param executando      a linha "em execução" ({@code Pondering…}), ou {@code null}
 * @param ultimaFerramenta a última ferramenta usada, ou {@code null}
 * @param esperandoVoce   o prompt está vazio e nada roda nem pergunta
 * @param tela            as últimas linhas da tela, sem a moldura
 */
public record TelaClaude(
    @JsonProperty("pergunta") Pergunta pergunta,
    @JsonProperty("executando") String executando,
    @JsonProperty("ultimaFerramenta") String ultimaFerramenta,
    @JsonProperty("esperandoVoce") Boolean esperandoVoce,
    @JsonProperty("tarefas") List<Tarefa> tarefas,
    @JsonProperty("atividade") List<String> atividade,
    @JsonProperty("tela") String tela) {

    public TelaClaude {
        executando = Textos.linha(executando);
        ultimaFerramenta = Textos.linha(ultimaFerramenta);
        tarefas = Textos.lista(tarefas);
        atividade = Textos.linhas(atividade);
        tela = Textos.bloco(tela);
    }

    /** Uma pergunta que o Claude fez (com 2 ou mais opções numeradas). */
    public record Pergunta(
        @JsonProperty("titulo") String titulo,
        @JsonProperty("contexto") String contexto,
        @JsonProperty("opcoes") List<Opcao> opcoes) {

        public Pergunta {
            titulo = Textos.linha(titulo);
            contexto = Textos.bloco(contexto);
            opcoes = Textos.lista(opcoes);
        }
    }

    /** Uma opção da pergunta. */
    public record Opcao(
        @JsonProperty("numero") Integer numero,
        @JsonProperty("texto") String texto,
        @JsonProperty("descricao") String descricao,
        @JsonProperty("selecionada") Boolean selecionada) {

        public Opcao {
            texto = Textos.linha(texto);
            descricao = Textos.linha(descricao);
        }
    }

    /** Um item da lista de tarefas do Claude. */
    public record Tarefa(
        @JsonProperty("feito") Boolean feito,
        @JsonProperty("texto") String texto) {

        public Tarefa {
            texto = Textos.linha(texto);
        }
    }

    public boolean temPergunta() {
        return pergunta != null;
    }

    public boolean estaEsperando() {
        return Boolean.TRUE.equals(esperandoVoce);
    }
}
