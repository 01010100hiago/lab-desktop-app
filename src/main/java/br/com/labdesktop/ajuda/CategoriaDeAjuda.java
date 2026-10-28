package br.com.labdesktop.ajuda;

import java.util.List;

/** Uma categoria da Ajuda: um nome curto, por tarefa do usuário (não por tela do código), e os ids dos tópicos dela, na ordem. */
public record CategoriaDeAjuda(String nome, List<String> ids) { }
