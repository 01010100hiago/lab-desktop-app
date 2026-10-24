package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;

/**
 * Falha de um comando do agente rodado por SSH. O
 * {@code codigo} é o do agente quando ele respondeu (o motivo da falha), ou um
 * código do app quando não houve resposta utilizável ({@link #SEM_RESPOSTA}). A mensagem vem do servidor, então
 * é tratada como não confiável (limpa e redigida).
 */
public class ErroProvisionamento extends Exception {

    private static final long serialVersionUID = 1L;

    /** O comando não respondeu uma linha JSON legível (docker fora do PATH, container parado...). */
    public static final String SEM_RESPOSTA = "sem_resposta";
    /** Não há sessão SSH autenticada para rodar o comando. */
    public static final String SEM_SESSAO = "sem_sessao";
    public static final String TOTP_INCORRETO = "totp_incorreto";
    public static final String TOTP_JA_ATIVO = "totp_ja_ativo";
    public static final String TOKEN_CONFLITO = "token_conflito";
    /** Revogar o acesso de um nome que não está registrado. */
    public static final String TOKEN_INEXISTENTE = "token_inexistente";

    private final String codigo;

    public ErroProvisionamento(String codigo, String mensagem) {
        this(codigo, mensagem, null);
    }

    public ErroProvisionamento(String codigo, String mensagem, Throwable causa) {
        super(TextoSeguro.limparLinha(Redator.redigir(mensagem)), causa);
        this.codigo = codigo == null ? SEM_RESPOSTA : TextoSeguro.limparLinha(codigo);
    }

    public String codigo() {
        return codigo;
    }
}
