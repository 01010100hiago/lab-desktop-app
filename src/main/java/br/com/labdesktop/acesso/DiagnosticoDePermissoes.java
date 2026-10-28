package br.com.labdesktop.acesso;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a saída de {@link ComandosDoAcesso#lerPermissoesDaPastaPessoal()} e aponta o que faz o {@code sshd} (com {@code StrictModes}, o padrão) recusar uma chave
 * que está certa: a pasta pessoal, a {@code .ssh} ou o {@code authorized_keys} com escrita para o grupo ou para outros. Só diz; o app NUNCA altera isso (a pasta
 * pessoal não é dele). Modelo puro.
 */
public final class DiagnosticoDePermissoes {

    private static final Pattern LINHA = Pattern.compile("^([-dlcbps][-rwxsStT]{9})[.+@]?\\s+.*?(\\S+)\\s*$");
    private static final Pattern CAMINHO_SIMPLES = Pattern.compile("^[A-Za-z0-9/._-]{1,200}$");

    private DiagnosticoDePermissoes() { }

    /** As frases do que está errado, vazio se tudo está como o sshd quer (ou se a saída não foi entendida). */
    public static List<String> problemas(String saida) {
        List<String> achados = new ArrayList<>();
        for (String linha : saida == null ? new String[0] : saida.split("\\R")) {
            Matcher m = LINHA.matcher(linha.strip());
            if (!m.matches()) {
                continue;
            }
            String modo = m.group(1);
            String caminho = m.group(2);
            boolean grupoEscreve = modo.charAt(5) == 'w';
            boolean outrosEscrevem = modo.charAt(8) == 'w';
            if (!grupoEscreve && !outrosEscrevem) {
                continue;
            }
            String onde = caminho.endsWith("/authorized_keys") ? "o arquivo authorized_keys" : caminho.endsWith("/.ssh") ? "a pasta .ssh" : "a pasta pessoal do usuário";
            achados.add(onde + (CAMINHO_SIMPLES.matcher(caminho).matches() ? " (" + caminho + ")" : "") + " aceita escrita por grupo ou outros (" + modo
                + "), e o sshd recusa a chave nesse caso (StrictModes). O app não altera isso: quem administra o servidor tira a escrita com chmod go-w.");
        }
        return achados;
    }
}
