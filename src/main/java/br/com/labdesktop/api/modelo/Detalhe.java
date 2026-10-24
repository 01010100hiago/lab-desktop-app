package br.com.labdesktop.api.modelo;

/**
 * O resultado de {@code GET /api/projetos/:p}: a forma do projeto comum ({@link DetalheProjeto}) ou a da sessão
 * raiz ({@link DetalheRaiz}, {@code :p = lab}). Quem consome decide por {@code instanceof}.
 */
public sealed interface Detalhe permits DetalheProjeto, DetalheRaiz {

    String nome();

    Limite limite();

    /** A sessão do Claude, na forma comum aos dois. */
    SessaoProjeto sessao();
}
