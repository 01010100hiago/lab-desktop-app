package br.com.labdesktop.projetos;

import java.util.Locale;

/**
 * O que NÃO vai quando se importa uma pasta do computador. Sempre fica de fora: {@code node_modules} (volta com o {@code npm install}) e
 * {@code .DS_Store}. {@code .git} só vai se o usuário marcou. {@code .env} (e {@code .env.*}, com exceção dos exemplos) NÃO vai por padrão:
 * é onde ficam as senhas, e o usuário avisado decide se quer levá-lo.
 *
 * @param incluirGit a pasta {@code .git} (o histórico) vai junto
 * @param incluirEnv os arquivos {@code .env} vão junto (NÃO é o padrão)
 */
public record FiltroDeEnvio(boolean incluirGit, boolean incluirEnv) {

    /** O padrão: sem {@code .git} e sem {@code .env}. */
    public static final FiltroDeEnvio PADRAO = new FiltroDeEnvio(false, false);

    /** Este NOME (de uma pasta ou de um arquivo, em qualquer nível) entra no envio? */
    public boolean aceita(String nome, boolean ehPasta) {
        String n = nome.toLowerCase(Locale.ROOT);
        if (n.equals("node_modules") || n.equals(".ds_store")) {
            return false;
        }
        if (n.equals(".git")) {
            return incluirGit;
        }
        if (!ehPasta && ehArquivoDeSegredos(n)) {
            return incluirEnv;
        }
        return true;
    }

    private static boolean ehArquivoDeSegredos(String n) {
        if (!n.equals(".env") && !n.startsWith(".env.")) {
            return false;
        }
        return !(n.endsWith(".example") || n.endsWith(".sample") || n.endsWith(".template") || n.endsWith(".dist"));
    }
}
