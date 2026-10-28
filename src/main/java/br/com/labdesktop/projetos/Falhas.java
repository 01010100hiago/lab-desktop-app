package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.ErroSftp;

/** Traduz os erros de arquivo para as falhas do pacote de projetos. */
final class Falhas {

    private Falhas() { }

    static FalhaDoProjeto de(ErroSftp e, String oQueFazia) {
        return switch (e.tipo()) {
            case SEM_CONEXAO, SFTP_INDISPONIVEL -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_CONEXAO,
                "Sem conexão com o servidor para " + oQueFazia + ". Espere a conexão voltar e tente de novo.", null, e);
            case SEM_PERMISSAO -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_PERMISSAO, "Sem permissão para " + oQueFazia + " nessa pasta.", null, e);
            case CANCELADO -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.", null, e);
            default -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "Não consegui " + oQueFazia + ": " + e.mensagemSimples(), null, e);
        };
    }
}
