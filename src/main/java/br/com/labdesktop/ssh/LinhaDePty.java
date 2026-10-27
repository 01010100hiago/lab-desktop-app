package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.ValidadorDeNomes;

/**
 * A linha de comando FIXA do terminal da sessão do Claude (ARQUITETURA 7.1). O único pedaço que varia é o nome do projeto, e ele só
 * entra na linha depois de passar por {@code ^[a-z0-9][a-z0-9-]{0,48}$}: nada que o usuário digita, nem nada que o servidor devolve,
 * vira texto de comando por outro caminho.
 */
public final class LinhaDePty {

    /** O tipo de terminal que o app anuncia ao servidor (o JediTerm emula o xterm). */
    public static final String TIPO_DE_TERMINAL = "xterm-256color";

    /** A sessão tmux que o painel web e o agente já usam ({@code tmux ... -t work}). */
    public static final String SESSAO_TMUX = "work";

    private LinhaDePty() { }

    /**
     * Liga o mouse do tmux na sessão antes de anexar ({@code set-option mouse on}, as duas ordens do tmux na mesma chamada, separadas por um
     * {@code ';'} entre aspas para o shell do servidor não o tomar como fim de comando). Sem isso o histórico fica preso no tmux: a tela dele
     * é "alternativa" para o terminal, o trackpad e a roda não rolam nada e as setas vão para o Claude. Com o mouse ligado o JediTerm
     * repassa a rolagem ao tmux, que sobe o histórico. Selecionar texto passa a pedir Shift ao arrastar.
     * <p>
     * Anexa à sessão tmux {@code work} do container {@code lab-claude-<projeto>}. Sem {@code -d} por padrão;
     * com {@code donoExclusivo}, {@code attach -d} derruba os outros clientes (os de uma queda abrupta, por exemplo).
     *
     * @throws IllegalArgumentException se o nome do projeto não passa na regra do agente
     */
    public static String sessaoDoClaude(String projeto, boolean donoExclusivo) {
        if (!ValidadorDeNomes.projetoValido(projeto)) {
            throw new IllegalArgumentException("Nome de projeto inválido para o terminal.");
        }
        return "docker exec -it -e LANG=C.UTF-8 -e TERM=" + TIPO_DE_TERMINAL + " -e COLORTERM=truecolor lab-claude-" + projeto
            + " tmux -u set-option -t " + SESSAO_TMUX + " mouse on ';' attach" + (donoExclusivo ? " -d" : "") + " -t " + SESSAO_TMUX;
    }
}
