package br.com.labdesktop.git;

import br.com.labdesktop.arquivos.Cancelamento;

/**
 * Tudo o que o app ESCREVE no git de um projeto, separado de {@link LeitorDeGit} de propósito: ler não precisa da trava das ações ({@code labdesktop.acoes}), escrever precisa, e quem lê nunca
 * recebe uma destas operações. Na Fase 1 só existe criar o repositório. A Fase 2 (aprovar e mesclar, reverter, publicar no GitHub com o {@code gh}) acrescentará métodos AQUI, cada um atrás da mesma trava
 * e da mesma confirmação com o nome do servidor; nenhum deles existe ainda.
 */
public interface OperacoesDeGit {

    /** Quem acompanha a criação ao vivo: uma frase por passo. Chamado de thread de fundo. */
    @FunctionalInterface
    interface Observador {
        void passo(String frase);

        Observador NENHUM = frase -> { };
    }

    /** A conferência que a janela mostra antes de qualquer escrita no projeto. Só lê (e usa uma pasta temporária FORA do projeto). */
    ConferenciaDoRepositorio conferirCriacao() throws InterruptedException;

    /** Cria o repositório: só depois do clique. Nunca apaga o {@code .git}: se falhar no meio, o resultado diz o que fez e o comando para desfazer. */
    ResultadoDaCriacao criarRepositorio(PedidoDeCriacao pedido, Observador observador, Cancelamento cancelamento) throws InterruptedException;
}
