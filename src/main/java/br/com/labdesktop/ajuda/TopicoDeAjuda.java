package br.com.labdesktop.ajuda;

import java.util.List;

/**
 * Um tópico da Ajuda, no molde que cabe numa tela: a pergunta (o título), uma frase de "Resumo rápido", os passos numerados, o texto longo numa seção recolhível
 * ("Mais detalhes"), os botões que abrem telas e o "Veja também" (até 3 outros tópicos). Um arquivo por tópico em {@code ajuda/*.txt}, no formato de
 * {@link GuiaDeAjuda#interpretar}.
 *
 * @param id       o nome do arquivo sem {@code .txt}
 * @param titulo   a pergunta ("Como conecto o app a um servidor?")
 * @param rotulo   o nome curto, de no máximo 32 letras, que a árvore e o seletor mostram ("Conectar a um servidor")
 * @param palavras palavras que o usuário pode digitar na busca e que o texto não tem
 * @param resumo   a frase de resumo rápido
 * @param corpo    o que aparece sempre: passos e parágrafos curtos
 * @param detalhes o que fica atrás de "Mais detalhes" (vazio: a seção nem aparece)
 * @param telas    os botões "Ir para ..." (cada um abre uma tela)
 * @param veja     os ids dos outros tópicos do "Veja também"
 * @param onde     as linhas que este tópico dá à lista rápida "Onde fica...?"
 * @param soComAcoes o tópico fala de botões que só aparecem com as ações de escrita ligadas ({@code @acoes: sim}): a Ajuda mostra no topo a frase fixa {@link GuiaDeAjuda#FRASE_DAS_ACOES}
 */
public record TopicoDeAjuda(String id, String titulo, String rotulo, List<String> palavras, String resumo, List<Bloco> corpo, List<Bloco> detalhes, List<Link> telas,
                            List<String> veja, List<OndeFica> onde, boolean soComAcoes) {

    /** Uma peça do texto. */
    public sealed interface Bloco permits Paragrafo, Subtitulo, Passo, TabelaDeAtalhos { }

    /** Um parágrafo (pode ter {@code {atalho:chave}}, que vira a tecla do menu). */
    public record Paragrafo(String texto) implements Bloco { }

    /** Um título de grupo dentro do tópico. */
    public record Subtitulo(String texto) implements Bloco { }

    /** Um passo numerado ({@code 1. Clique em...}); pode ter {@code {atalho:chave}}. */
    public record Passo(int numero, String texto) implements Bloco { }

    /** O lugar da tabela de atalhos, gerada do menu. */
    public record TabelaDeAtalhos() implements Bloco { }

    /** Um botão que abre uma tela. */
    public record Link(String texto, Destino destino) { }

    /**
     * Uma linha da lista rápida "Onde fica...?": o que o usuário procura, o caminho exato ("Servidor > Resumo > Registro do servidor") e, se há, a tela que o botão "Ir" abre.
     * O caminho pode ter {@code {atalho:chave}}.
     */
    public record OndeFica(String oQue, String caminho, Destino destino) { }

    /** Os passos do corpo. */
    public List<Passo> passos() {
        return corpo.stream().filter(b -> b instanceof Passo).map(b -> (Passo) b).toList();
    }
}
