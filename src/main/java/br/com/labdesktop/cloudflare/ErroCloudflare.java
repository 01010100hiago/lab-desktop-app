package br.com.labdesktop.cloudflare;

/**
 * Tudo o que pode dar errado ao falar com a API da Cloudflare, já classificado e com a frase para o usuário (texto puro: a tela nunca a trata como
 * HTML). O texto que a própria Cloudflare devolveu entra na frase só depois de limpo ({@code TextoSeguro}) e sem nenhum segredo ({@code Redator} e
 * o token). Nunca carrega o token, o cabeçalho nem o corpo do pedido.
 */
public final class ErroCloudflare extends Exception {

    private static final long serialVersionUID = 1L;

    /** As classes de erro que a tela trata de jeitos diferentes. */
    public enum Tipo {
        /** 401: o token foi recusado (errado, apagado, expirado ou desativado). */
        TOKEN_INVALIDO,
        /** 403: o token é válido, mas não tem a permissão que este pedido exige. */
        SEM_PERMISSAO,
        /** 404: a conta, a zona, o túnel ou o registro não existe (ou o token não o enxerga). */
        NAO_ENCONTRADO,
        /** Já existe um registro com esse nome. */
        JA_EXISTE,
        /** 429: pedidos demais; a espera combinada esgotou. */
        LIMITE_DE_TAXA,
        /** 5xx: a Cloudflare está com problema. */
        SERVIDOR_DA_CLOUDFLARE,
        /** Sem internet, sem resposta a tempo ou a conexão caiu. */
        REDE,
        /** 4xx de pedido que a Cloudflare não aceitou (a frase traz o motivo dela). */
        RECUSADO,
        /** A resposta não tem a forma esperada. */
        RESPOSTA_INVALIDA,
        /** O usuário cancelou. */
        CANCELADO
    }

    private final Tipo tipo;
    private final int status;
    private final int codigo;
    private final boolean incerto;

    public ErroCloudflare(Tipo tipo, String mensagem, int status, int codigo, boolean incerto, Throwable causa) {
        super(mensagem, causa);
        this.tipo = tipo;
        this.status = status;
        this.codigo = codigo;
        this.incerto = incerto;
    }

    public ErroCloudflare(Tipo tipo, String mensagem) {
        this(tipo, mensagem, 0, 0, false, null);
    }

    public Tipo tipo() {
        return tipo;
    }

    /** A frase para o usuário. */
    public String mensagem() {
        return getMessage();
    }

    /** O status HTTP, ou 0 se não houve resposta. */
    public int status() {
        return status;
    }

    /** O primeiro código de erro que a Cloudflare mandou, ou 0. */
    public int codigoDaCloudflare() {
        return codigo;
    }

    /**
     * {@code true} se o pedido era de ESCRITA e o resultado é incerto (sem resposta, 5xx): pode ter sido feito. Quem chama deve conferir antes de
     * repetir, nunca repetir às cegas.
     */
    public boolean incerto() {
        return incerto;
    }
}
