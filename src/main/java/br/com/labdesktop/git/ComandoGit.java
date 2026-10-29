package br.com.labdesktop.git;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UM comando do git, pronto para rodar: a lista de argumentos (nunca texto para um shell), quais posições são texto livre (entram entre aspas simples por SSH), o ambiente e a entrada.
 * Só {@link ComandosDeGit} fabrica estes objetos: é o único lugar do app que sabe escrever uma linha do git.
 *
 * @param natureza  o que o comando faz (o executor de leitura recusa o que não é leitura)
 * @param argumentos o programa e os argumentos
 * @param livres    as posições de {@code argumentos} que são texto livre
 * @param ambiente  variáveis a mais ({@code GIT_TERMINAL_PROMPT=0} e companhia)
 * @param entrada   o que vai à entrada do programa (a lista de arquivos de um {@code git add}); nunca {@code null}
 */
public record ComandoGit(Natureza natureza, List<String> argumentos, Set<Integer> livres, Map<String, String> ambiente, byte[] entrada) {

    /** O que um comando faz com o disco. */
    public enum Natureza {
        /** Só lê o repositório ou o projeto. */
        LEITURA,
        /** Escreve SÓ numa pasta temporária do sistema, FORA do projeto (a área que calcula a lista de arquivos); apagada no fim. */
        TEMPORARIO,
        /** Escreve no projeto: só nasce do clique de "Criar repositório" (e só com a trava das ações). */
        ESCRITA
    }

    public ComandoGit {
        argumentos = List.copyOf(argumentos);
        livres = Set.copyOf(livres);
        ambiente = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(ambiente)); // em ordem de nome: a linha que vai ao servidor é sempre a mesma
        entrada = entrada == null ? new byte[0] : entrada.clone();
    }

    /** O comando como texto, para a tela mostrar e o usuário copiar (cada argumento separado por espaço; o texto livre entre aspas se precisa). */
    public String comoTexto() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argumentos.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String a = argumentos.get(i);
            sb.append(a.matches("[A-Za-z0-9_@%+=:.,/~-]+") ? a : "'" + a.replace("'", "'\\''") + "'");
        }
        return sb.toString();
    }
}
