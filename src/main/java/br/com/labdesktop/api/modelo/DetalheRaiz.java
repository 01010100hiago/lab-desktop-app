package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /api/projetos/lab}: a sessão raiz (CONTRATO-API 3.8). Forma diferente do projeto comum: traz
 * {@code raiz: true}, {@code titulo}, {@code sessaoClaude} como OBJETO (no lugar de {@code sessao}) e
 * {@code loginPendente} como booleano (a URL de login da raiz não vem aqui).
 */
public record DetalheRaiz(
    @JsonProperty("nome") String nome,
    @JsonProperty("raiz") Boolean raiz,
    @JsonProperty("titulo") String titulo,
    @JsonProperty("sessaoClaude") SessaoProjeto sessaoClaude,
    @JsonProperty("loginPendente") Boolean loginPendente,
    @JsonProperty("limite") Limite limite) implements Detalhe {

    public DetalheRaiz {
        nome = Textos.linha(nome);
        titulo = Textos.linha(titulo);
    }

    @Override
    public SessaoProjeto sessao() {
        return sessaoClaude;
    }

    public boolean temLoginPendente() {
        return Boolean.TRUE.equals(loginPendente);
    }
}
