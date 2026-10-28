package br.com.labdesktop.projetos;

import java.util.Locale;

/**
 * O que NÃO vai quando se importa uma pasta do computador. Sempre fica de fora: {@code node_modules} (volta com o {@code npm install}) e
 * {@code .DS_Store}. {@code .git} só vai se o usuário marcou. Os arquivos de segredos NÃO vão por padrão ({@code .env} e {@code .env.*} menos os exemplos,
 * {@code *.env}, {@code .envrc}, {@code .npmrc}, {@code *.pem}, {@code id_rsa*}, {@code id_ed25519*}, e uma pasta chamada {@code .env}): é onde ficam as
 * senhas e as chaves, e o usuário avisado decide se quer levá-los (a caixa "levar os .env" vale para todos).
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
        if (ehSegredo(n, ehPasta)) {
            return incluirEnv;
        }
        return true;
    }

    /**
     * Onde ficam senhas e chaves: {@code .env} e {@code .env.*} (menos os exemplos), {@code *.env}, {@code .envrc}, {@code .npmrc}, {@code *.pem},
     * {@code id_rsa*}, {@code id_ed25519*}, {@code id_ecdsa*}, e uma PASTA chamada {@code .env} (que é de segredos ou de um ambiente virtual: de um jeito ou de
     * outro, não vai sem o usuário marcar).
     */
    static boolean ehSegredo(String n, boolean ehPasta) {
        if (n.equals(".env")) {
            return true;
        }
        if (ehPasta) {
            return false;
        }
        if (n.startsWith(".env.")) {
            return !(n.endsWith(".example") || n.endsWith(".sample") || n.endsWith(".template") || n.endsWith(".dist"));
        }
        return n.endsWith(".env") || n.equals(".envrc") || n.equals(".npmrc") || n.endsWith(".pem") || n.startsWith("id_rsa") || n.startsWith("id_ed25519")
            || n.startsWith("id_ecdsa");
    }
}
