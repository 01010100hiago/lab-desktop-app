package br.com.labdesktop.ajuda;

/**
 * Um atalho de teclado do menu do app, como a Ajuda o cita: a chave estável ({@code "ajustes"}, {@code "aba-3"}), o que ele faz e a tecla escrita para o usuário
 * ({@code "Cmd+,"}). A lista sai do próprio menu, nunca é digitada: a Ajuda não fica velha quando um atalho muda.
 */
public record AtalhoDoMenu(String chave, String descricao, String tecla) { }
