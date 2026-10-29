package br.com.labdesktop.transporte;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o que o programa externo escreveu (a saída dele, TEXTO sem confiança) e diz, em palavras simples, o que aconteceu. Classe PURA: recebe o texto e
 * devolve uma {@link FalhaDoTunel}. A saída passa pelo {@link Redator}, é truncada e nunca é tratada como comando, HTML nem endereço a abrir: uma URL https de login da Cloudflare só
 * vira TEXTO copiável.
 *
 * <p>Os padrões são os que o {@code aws} e o {@code cloudflared} imprimem (não provados contra os serviços de verdade neste pacote: ver ARQUITETURA 7.14, "Limites").
 */
public final class InterpretadorDaSaida {

    /** Quanto da saída vai para a frase do usuário. */
    static final int TAMANHO_DO_TRECHO = 300;

    private static final Pattern LOGIN_AWS_EXPIRADO = Pattern.compile(
        "(?i)(sso session[^\\n]*expired|session[^\\n]*has expired|expiredtoken|token has expired|security token included in the request is expired|"
            + "error when retrieving token from sso|token for sso[^\\n]*(expired|invalid))");
    private static final Pattern SEM_CREDENCIAIS_AWS = Pattern.compile("(?i)(unable to locate credentials|nocredentialproviders|the config profile \\([^)]*\\) could not be found)");
    private static final Pattern INSTANCIA_SEM_SSM = Pattern.compile("(?i)(targetnotconnected|is not connected\\b)");
    private static final Pattern SEM_PLUGIN = Pattern.compile("(?i)(sessionmanagerplugin is not found|session-manager-plugin[^\\n]*(not found|no such file|nao encontrado))");
    private static final Pattern PORTA_OCUPADA = Pattern.compile("(?i)(address already in use|only one usage of each socket address|bind: [^\\n]*in use)");
    private static final Pattern PEDE_LOGIN = Pattern.compile("(?i)(log ?in|authenticat|browser|following url|open the url|visit)");
    private static final Pattern ENDERECO_HTTPS = Pattern.compile("https://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{4,600}");

    private InterpretadorDaSaida() { }

    /**
     * Interpreta uma saída. {@code saiu} diz se o programa já terminou (e com que código); se não, ele estava rodando mas não ficou pronto a tempo.
     *
     * @param codigo o código de saída, ou {@code null} se o programa ainda roda
     */
    public static FalhaDoTunel interpretar(Transporte transporte, String saida, Integer codigo, SistemaOperacional sistema, long segundosDePrazo) {
        String texto = saida == null ? "" : saida;
        if (transporte.tipo() == Transporte.Tipo.SSM) {
            if (SEM_PLUGIN.matcher(texto).find()) {
                return GuiaDeInstalacao.ausente(FerramentaExterna.PLUGIN_SSM, sistema);
            }
            if (LOGIN_AWS_EXPIRADO.matcher(texto).find()) {
                return loginDaAws(transporte, "Seu login da AWS expirou.");
            }
            if (SEM_CREDENCIAIS_AWS.matcher(texto).find()) {
                return loginDaAws(transporte, "O aws não achou um login válido neste computador.");
            }
            if (INSTANCIA_SEM_SSM.matcher(texto).find()) {
                return new FalhaDoTunel(FalhaDoTunel.Tipo.INSTANCIA_SEM_SSM,
                    "A instância não está conectada ao Session Manager (TargetNotConnected). Confira se o agente SSM está ligado nela, se ela tem permissão (papel) "
                        + "para o SSM e se o ID da instância e a região estão certos.");
            }
        } else if (transporte.tipo() == Transporte.Tipo.CLOUDFLARED) {
            String endereco = enderecoDeLogin(texto);
            if (endereco != null) {
                return new FalhaDoTunel(FalhaDoTunel.Tipo.CLOUDFLARE_PEDE_LOGIN,
                    "A Cloudflare pede o seu login no navegador. Abra o endereço abaixo (confira que ele é da Cloudflare), entre, e depois clique em Tentar agora. "
                        + "O app não abre nada sozinho.", endereco);
            }
        }
        if (PORTA_OCUPADA.matcher(texto).find()) {
            return new FalhaDoTunel(FalhaDoTunel.Tipo.PORTA_OCUPADA, "A porta local escolhida foi tomada por outro programa.");
        }
        String programa = transporte.tipo() == Transporte.Tipo.SSM ? "aws" : "cloudflared";
        String trecho = trecho(texto);
        if (codigo != null) {
            return new FalhaDoTunel(FalhaDoTunel.Tipo.SAIU,
                "O programa " + programa + " terminou sozinho (código " + codigo + ")." + (trecho.isEmpty() ? "" : " O que ele disse: " + trecho));
        }
        return new FalhaDoTunel(FalhaDoTunel.Tipo.PRAZO_ESTOURADO,
            "O túnel não ficou pronto em " + segundosDePrazo + " s." + (trecho.isEmpty() ? "" : " O que o programa disse: " + trecho));
    }

    private static FalhaDoTunel loginDaAws(Transporte t, String inicio) {
        String comando = t.perfilAws() == null ? "aws sso login" : "aws sso login --profile " + t.perfilAws();
        return new FalhaDoTunel(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, inicio + " No terminal rode: " + comando + " e tente de novo.", comando);
    }

    /** A URL https de login, se a saída traz uma junto de palavras de login; senão {@code null}. */
    static String enderecoDeLogin(String texto) {
        Matcher chave = PEDE_LOGIN.matcher(texto);
        if (!chave.find()) {
            return null;
        }
        Matcher url = ENDERECO_HTTPS.matcher(texto);
        int de = Math.max(0, chave.start() - 80); // a URL costuma vir DEPOIS da frase, mas a frase pode ser a da própria linha
        return url.find(de) ? url.group() : null;
    }

    /** O fim da saída, redigido, em uma linha e curto. */
    static String trecho(String saida) {
        String limpo = TextoSeguro.limparLinha(Redator.redigir(saida)).replaceAll("\\s+", " ");
        if (limpo.length() <= TAMANHO_DO_TRECHO) {
            return limpo;
        }
        return "…" + limpo.substring(limpo.length() - (TAMANHO_DO_TRECHO - 1));
    }
}
