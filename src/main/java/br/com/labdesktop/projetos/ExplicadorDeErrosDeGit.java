package br.com.labdesktop.projetos;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.Locale;

/**
 * Traduz o que o {@code git} diz quando o clone falha para uma frase em português simples, com o que o usuário pode fazer. O texto do servidor é
 * NÃO confiável: sai limpo (sem controle, sem HTML) e redigido (nenhum token), e as frases conhecidas viram a explicação.
 */
public final class ExplicadorDeErrosDeGit {

    private ExplicadorDeErrosDeGit() { }

    /** A frase para o usuário (nunca vazia). */
    public static String explicar(String erroDoGit) {
        String t = erroDoGit == null ? "" : erroDoGit;
        String m = t.toLowerCase(Locale.ROOT);
        if (m.contains("host key verification failed")) {
            return "O servidor ainda não conhece o computador que guarda o repositório. Entre no servidor e rode uma vez "
                + "ssh -T git@<o endereço do site> para confiar nele; depois tente de novo.";
        }
        if (m.contains("terminal prompts disabled") || m.contains("could not read username") || m.contains("authentication failed")
            || m.contains("permission denied (publickey") || m.contains("could not read from remote repository")
            || m.contains("repository not found") || m.contains("repository '") && m.contains("not found") || m.contains("invalid username or password") || m.contains("fatal: could not read password")) {
            return "Não consegui acessar o repositório. Se ele é privado, deixe o servidor logado nele (por exemplo gh auth login) ou "
                + "use uma chave de deploy; e confira se o endereço está certo.";
        }
        if (m.contains("could not resolve host") || m.contains("name or service not known") || m.contains("temporary failure in name resolution")
            || m.contains("failed to connect") || m.contains("connection timed out") || m.contains("network is unreachable")) {
            return "O servidor não alcançou o endereço do repositório (sem internet ou endereço errado).";
        }
        if (m.contains("already exists and is not an empty directory")) {
            return "Já existe uma pasta com esse nome no servidor. Escolha outro nome para o projeto.";
        }
        if (m.contains("git: command not found") || m.contains("git: não encontrado") || m.contains("git: not found")
            || m.contains("executable file not found") || m.contains("no such file or directory") && m.contains("git")) {
            return "O git não está instalado onde os projetos ficam. Instale o git e tente de novo.";
        }
        if (m.contains("no space left on device")) {
            return "Acabou o espaço em disco no servidor.";
        }
        if (m.contains("permission denied")) {
            return "Sem permissão para criar a pasta do projeto no servidor.";
        }
        String limpo = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(t.strip())), 300);
        return limpo.isEmpty() ? "O git não conseguiu clonar o repositório (sem mensagem)." : "O git não conseguiu clonar: " + limpo;
    }
}
