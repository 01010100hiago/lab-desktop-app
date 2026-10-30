package br.com.labdesktop.git;

/**
 * Por que um comando do git não deu certo, em palavras simples.
 *
 * @param tipo             o que aconteceu
 * @param frase            a frase para o usuário (texto, nunca HTML; já sem segredos)
 * @param comandoParaCopiar o comando que o USUÁRIO roda se for o caso (nunca o app); {@code null} se não há
 */
public record FalhaDoGit(Tipo tipo, String frase, String comandoParaCopiar) {

    /** As causas que o app sabe explicar. */
    public enum Tipo {
        /** Não há o programa git nesta máquina. */
        GIT_NAO_INSTALADO,
        /** A pasta tem outro dono e o git recusa ({@code dubious ownership}, {@code safe.directory}). */
        PROPRIEDADE_DUVIDOSA,
        /** A pasta não é um repositório. */
        NAO_E_REPOSITORIO,
        /** O repositório ainda não tem nenhum commit. */
        SEM_COMMITS,
        /** O git não sabe quem assina os commits ({@code user.name} e {@code user.email}). */
        SEM_IDENTIDADE,
        /** Falta permissão de leitura ou de escrita. */
        SEM_PERMISSAO,
        /** O git é antigo demais para o que o app pede. */
        GIT_ANTIGO,
        /** O repositório tem configuração que faz o git rodar programas (filtro, fsmonitor, ganchos...): o app não lê por segurança. */
        CONFIGURACAO_QUE_EXECUTA,
        /** O comando passou do prazo. */
        DEMOROU,
        /** A conexão caiu ou o servidor não deixa rodar comando. */
        SEM_CONEXAO,
        /** Qualquer outra coisa: a frase traz o que o git disse. */
        OUTRO
    }

    public FalhaDoGit {
        frase = frase == null ? "" : frase;
    }

    public FalhaDoGit(Tipo tipo, String frase) {
        this(tipo, frase, null);
    }
}
