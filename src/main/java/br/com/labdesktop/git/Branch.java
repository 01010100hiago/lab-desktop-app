package br.com.labdesktop.git;

/**
 * Um branch local.
 *
 * @param nome  o nome
 * @param atual é o branch em que a pasta está agora
 * @param curto o hash curto da ponta
 */
public record Branch(String nome, boolean atual, String curto) { }
