package br.com.labdesktop.ui.telas;

/**
 * As frases fixas dos botões que escrevem no servidor e ficam atrás da trava das ações ({@code labdesktop.acoes}). Em vez de SUMIR na versão padrão, o botão aparece
 * DESLIGADO com esta explicação (o usuário não acha o que não aparece: foi o que aconteceu com "Publicar na internet"). A Ajuda repete a mesma frase.
 */
final class TextosDeAcao {

    /** A explicação do botão desligado pela trava. */
    static final String TRAVA = "As ações de escrita estão desligadas nesta versão do app.";

    /** A explicação curta de uma linha, abaixo de uma lista ou de um botão. */
    static final String TRAVA_COMPLETA = TRAVA + " Sem elas o app só mostra e lê: encerrar, reiniciar, publicar e remover ficam desligados.";

    private TextosDeAcao() { }
}
