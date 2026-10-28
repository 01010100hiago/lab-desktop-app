package br.com.labdesktop.projetos;

/**
 * Tudo o que pode dar errado ao criar, importar ou completar um projeto, já com a frase para o usuário (texto puro: a tela nunca a trata como
 * HTML). O {@link Tipo} diz o que a tela faz: mostrar a frase e deixar tentar de novo, ou dizer que o resultado é incerto.
 */
public final class FalhaDoProjeto extends Exception {

    private static final long serialVersionUID = 1L;

    /** As classes de falha que a tela trata de jeitos diferentes. */
    public enum Tipo {
        /** O servidor (ou o app) recusou o pedido: a frase diz o motivo (nome já existe, reservado, repositório privado...). */
        RECUSADO,
        /** Sem conexão com o servidor: nada foi enviado. */
        SEM_CONEXAO,
        /** Sem resposta a tempo (ou a conexão caiu no meio): o projeto PODE ter sido criado. */
        INCERTO,
        /** O token foi recusado, ou falta o código do 2FA: o fluxo padrão do app cuida. */
        NAO_AUTORIZADO,
        /** O usuário cancelou. */
        CANCELADO,
        /** Sem permissão para escrever na pasta (a tela pode mostrar o comando exato). */
        SEM_PERMISSAO,
        /** Qualquer outra falha. */
        OUTRO
    }

    private final Tipo tipo;
    private final String comando;

    public FalhaDoProjeto(Tipo tipo, String mensagem) {
        this(tipo, mensagem, null, null);
    }

    public FalhaDoProjeto(Tipo tipo, String mensagem, String comando) {
        this(tipo, mensagem, comando, null);
    }

    public FalhaDoProjeto(Tipo tipo, String mensagem, String comando, Throwable causa) {
        super(mensagem, causa);
        this.tipo = tipo;
        this.comando = comando;
    }

    public Tipo tipo() {
        return tipo;
    }

    /** A frase para o usuário. */
    public String mensagem() {
        return getMessage();
    }

    /** O comando exato que o usuário pode rodar à mão (quando falta permissão), ou {@code null}. */
    public String comandoParaCopiar() {
        return comando;
    }
}
