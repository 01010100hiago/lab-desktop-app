package br.com.labdesktop.transporte;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;

/**
 * Por que o túnel de um transporte sem porta aberta não subiu, ou caiu. A {@code mensagem} é texto simples em pt-BR, já passado pelo {@link Redator} e sem quebra de
 * linha; o {@code textoCopiavel} é o que o usuário pode copiar (o comando de instalação, o comando de login da AWS ou o endereço de login da Cloudflare), sempre TEXTO: o app nunca o
 * executa nem abre sozinho. Tudo o que veio da saída do programa passa pelo {@code Redator}, é truncado e tratado como texto.
 *
 * @param tipo           o que deu errado, em categorias que a tela sabe tratar
 * @param mensagem       a frase para o usuário
 * @param textoCopiavel  o texto a copiar (comando ou endereço), ou {@code null}
 */
public record FalhaDoTunel(Tipo tipo, String mensagem, String textoCopiavel) {

    /** O limite do texto copiável (um comando, uma lista curta de passos, ou um endereço de login). */
    static final int MAXIMO_DO_TEXTO_COPIAVEL = 800;

    /** As categorias. */
    public enum Tipo {
        /** O programa (aws, plugin do Session Manager ou cloudflared) não está neste computador. */
        FERRAMENTA_AUSENTE,
        /** O login da AWS expirou (ou não existe): falta {@code aws sso login}. */
        LOGIN_AWS_EXPIRADO,
        /** A instância não está ligada ao Session Manager ({@code TargetNotConnected}). */
        INSTANCIA_SEM_SSM,
        /** A Cloudflare pede login no navegador; o endereço vem como texto. */
        CLOUDFLARE_PEDE_LOGIN,
        /** O túnel não ficou pronto no prazo. */
        PRAZO_ESTOURADO,
        /** O programa terminou sozinho. */
        SAIU,
        /** O programa nem chegou a iniciar. */
        NAO_INICIOU,
        /** A porta local sorteada foi tomada por outro programa (o app tenta de novo com outra). */
        PORTA_OCUPADA
    }

    public FalhaDoTunel {
        if (tipo == null) {
            throw new IllegalArgumentException("tipo");
        }
        mensagem = TextoSeguro.limparLinha(Redator.redigir(mensagem));
        if (textoCopiavel != null) {
            String limpo = TextoSeguro.limpar(Redator.redigir(textoCopiavel)).strip();
            textoCopiavel = limpo.isEmpty() ? null : TextoSeguro.truncar(limpo, MAXIMO_DO_TEXTO_COPIAVEL);
        }
    }

    public FalhaDoTunel(Tipo tipo, String mensagem) {
        this(tipo, mensagem, null);
    }
}
