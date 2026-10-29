package br.com.labdesktop.git;

/**
 * O que o usuário decidiu na janela "Criar repositório Git".
 *
 * @param conferencia   a conferência que a janela mostrou
 * @param nome          o nome de quem assina, quando o git ainda não sabia (senão {@code null})
 * @param email         o e-mail de quem assina, quando o git ainda não sabia (senão {@code null})
 * @param incluirGrandes incluir os arquivos acima do limite de tamanho
 */
public record PedidoDeCriacao(ConferenciaDoRepositorio conferencia, String nome, String email, boolean incluirGrandes) { }
