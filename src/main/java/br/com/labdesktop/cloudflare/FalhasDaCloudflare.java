package br.com.labdesktop.cloudflare;

import br.com.labdesktop.projetos.FalhaDoProjeto;

/** Traduz os erros da API da Cloudflare para as falhas que as telas já tratam ({@link FalhaDoProjeto}), com a mesma frase. */
final class FalhasDaCloudflare {

    private FalhasDaCloudflare() { }

    static FalhaDoProjeto de(ErroCloudflare e) {
        FalhaDoProjeto.Tipo tipo = switch (e.tipo()) {
            case TOKEN_INVALIDO -> FalhaDoProjeto.Tipo.NAO_AUTORIZADO;
            case SEM_PERMISSAO -> FalhaDoProjeto.Tipo.SEM_PERMISSAO;
            case CANCELADO -> FalhaDoProjeto.Tipo.CANCELADO;
            case REDE -> e.incerto() ? FalhaDoProjeto.Tipo.INCERTO : FalhaDoProjeto.Tipo.SEM_CONEXAO;
            case SERVIDOR_DA_CLOUDFLARE -> e.incerto() ? FalhaDoProjeto.Tipo.INCERTO : FalhaDoProjeto.Tipo.OUTRO;
            default -> FalhaDoProjeto.Tipo.RECUSADO;
        };
        return new FalhaDoProjeto(tipo, e.mensagem(), null, e);
    }
}
