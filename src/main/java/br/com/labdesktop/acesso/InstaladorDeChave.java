package br.com.labdesktop.acesso;

import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instala a chave PÚBLICA do app no {@code authorized_keys} do usuário, com UM comando fixo ({@link ComandosDoAcesso#instalarChave()}) numa sessão já
 * autenticada. A chave vai por STDIN, nunca na linha de comando, e é VALIDADA e remontada aqui antes de sair: só entram o tipo
 * {@code ssh-ed25519}, o conteúdo em base64 e um comentário limpo; as opções da linha são as fixas da ARQUITETURA 5.8 (sem agente, sem X11 e
 * {@code permitopen} só para o agente do painel). Sem {@code sudo}, sem tocar em outro arquivo. Idempotente: instalar duas vezes deixa UMA linha.
 */
public final class InstaladorDeChave {

    /** O que o comando fez. */
    public enum Resultado {
        ADICIONADA,
        JA_EXISTIA,
        /** A chave já aparecia só comentada ou com restrição: o app acrescentou uma linha própria (a antiga ficou como estava). */
        ADICIONADA_APESAR_DE_LINHA_INUTIL
    }

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final Pattern PUBLICA = Pattern.compile("^ssh-ed25519 ([A-Za-z0-9+/]{43,100}={0,2})(?: (.{0,200}))?$");
    private static final int COMPRIMENTO_MAXIMO_DO_COMENTARIO = 60;

    private InstaladorDeChave() { }

    /**
     * A linha completa a acrescentar, remontada a partir da chave pública do cofre. O comentário vira só letras, números, ponto, arroba, sublinhado e
     * hífen (o nome do computador pode ter acento, apóstrofo ou espaço), e as opções são as fixas.
     *
     * @param publica          a chave pública no formato {@code ssh-ed25519 AAAA... comentário}
     * @param portaDoAgente    a porta do agente no servidor (1 a 65535), para o {@code permitopen}
     * @throws IllegalArgumentException a chave não é uma chave pública Ed25519 válida, ou a porta não vale
     */
    public static String linhaParaInstalar(String publica, int portaDoAgente) {
        if (portaDoAgente < 1 || portaDoAgente > 65_535) {
            throw new IllegalArgumentException("Porta do agente fora de 1 a 65535.");
        }
        Matcher m = PUBLICA.matcher(publica == null ? "" : publica.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("A chave pública não é uma chave Ed25519 válida.");
        }
        String comentario = limparComentario(m.group(2));
        return "no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:" + portaDoAgente + "\" ssh-ed25519 " + m.group(1)
            + (comentario.isEmpty() ? "" : " " + comentario);
    }

    /** O conteúdo base64 da chave (o que se procura no arquivo para não duplicar). */
    static String conteudoDaChave(String publica) {
        Matcher m = PUBLICA.matcher(publica == null ? "" : publica.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("A chave pública não é uma chave Ed25519 válida.");
        }
        return m.group(1);
    }

    private static String limparComentario(String texto) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.strip().replaceAll("[^A-Za-z0-9._@-]+", "-").replaceAll("^-+|-+$", "");
        return limpo.length() > COMPRIMENTO_MAXIMO_DO_COMENTARIO ? limpo.substring(0, COMPRIMENTO_MAXIMO_DO_COMENTARIO) : limpo;
    }

    /**
     * Roda o comando fixo com a chave por stdin.
     *
     * @param publica       a chave pública do cofre
     * @param portaDoAgente a porta do agente no servidor
     * @throws FalhaDeEntradaPorSenha o comando terminou com erro (com a causa em palavras simples)
     */
    public static Resultado instalar(ProvisionadorRemoto.Executor executor, String publica, int portaDoAgente)
        throws FalhaDeEntradaPorSenha, IOException, InterruptedException {
        String linha = linhaParaInstalar(publica, portaDoAgente);
        byte[] entrada = (conteudoDaChave(publica) + "\n" + linha + "\n").getBytes(StandardCharsets.UTF_8);
        ResultadoExec r = executor.executar(ComandosDoAcesso.instalarChave(), entrada, PRAZO);
        if (r.status() == 0) {
            String resposta = respostaEntreOsMarcadores(r.stdout());
            if (ComandosDoAcesso.SAIDA_ADICIONADA.equals(resposta)) {
                return Resultado.ADICIONADA;
            }
            if (ComandosDoAcesso.SAIDA_JA_EXISTIA.equals(resposta)) {
                return Resultado.JA_EXISTIA;
            }
            if (ComandosDoAcesso.SAIDA_ADICIONADA_APESAR.equals(resposta)) {
                return Resultado.ADICIONADA_APESAR_DE_LINHA_INUTIL;
            }
        }
        throw new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.FALHOU_AO_INSTALAR, "Entrei com a senha, mas a instalação da chave não terminou bem: "
            + explicar(r.status()));
    }

    /**
     * A resposta do comando: a linha logo depois do marcador de início, desde que o marcador de fim venha depois. Qualquer outra coisa que o shell do usuário
     * imprima (um {@code .bashrc} que escreve, um aviso) fica de fora; sem os dois marcadores, {@code null}.
     */
    static String respostaEntreOsMarcadores(String stdout) {
        String[] linhas = stdout.split("\\R");
        for (int i = 0; i < linhas.length; i++) {
            if (linhas[i].strip().equals(ComandosDoAcesso.MARCADOR_DE_INICIO) && i + 2 < linhas.length && linhas[i + 2].strip().equals(ComandosDoAcesso.MARCADOR_DE_FIM)) {
                return linhas[i + 1].strip();
            }
        }
        return null;
    }

    private static String explicar(int codigo) {
        return switch (codigo) {
            case 2 -> "o servidor não informou a pasta pessoal do usuário. Nada foi acrescentado ao authorized_keys.";
            case 3 -> "não consegui ajustar as permissões da pasta .ssh deste usuário. Nada foi acrescentado ao authorized_keys.";
            case 4 -> "o servidor recebeu a chave num formato que não esperava. Nada foi acrescentado ao authorized_keys.";
            case 5 -> "não consegui criar o arquivo authorized_keys (disco cheio ou sem permissão). Nada foi acrescentado a ele.";
            case 6 -> "não consegui gravar a linha no authorized_keys (disco cheio?). A chave pode ter sido instalada só em parte: confira o arquivo.";
            case 7 -> "a linha foi acrescentada, mas não consegui fechar a permissão do arquivo (deveria ser 600). A chave está instalada: confira a permissão.";
            default -> "o comando terminou com um erro inesperado (código " + codigo + "), então a chave pode ter sido instalada. Confira o authorized_keys.";
        };
    }
}
