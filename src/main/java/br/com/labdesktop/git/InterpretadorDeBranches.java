package br.com.labdesktop.git;

import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê a saída de {@code git for-each-ref --format=%(HEAD)%1f%(refname:short)%1f%(objectname:short) refs/heads}: uma linha por branch, {@code *} ou espaço, o nome e o hash curto, separados por U+001F.
 * Só entra o branch cujo nome passa pela lista branca ({@link ValidadorDeGit#branchValido}): é o que o app aceita pôr de volta num comando.
 */
public final class InterpretadorDeBranches {

    /** O formato que o app pede ao git. */
    public static final String FORMATO = "%(HEAD)%1f%(refname:short)%1f%(objectname:short)";

    private InterpretadorDeBranches() { }

    public static List<Branch> interpretar(String saida) {
        List<Branch> r = new ArrayList<>();
        if (saida == null) {
            return r;
        }
        for (String linha : saida.split("\n")) {
            String[] c = linha.split("\u001f", 3);
            if (c.length < 3) {
                continue;
            }
            String nome = TextoSeguro.limparLinha(c[1]);
            if (!ValidadorDeGit.branchValido(nome)) {
                continue;
            }
            r.add(new Branch(nome, c[0].strip().equals("*"), c[2].strip()));
        }
        return r;
    }
}
