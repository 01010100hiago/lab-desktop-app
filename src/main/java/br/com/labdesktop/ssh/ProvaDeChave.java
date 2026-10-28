package br.com.labdesktop.ssh;

/**
 * "Provar antes de confiar": abre uma conexão NOVA, só com a chave do cofre, e roda um comando inocente. É a única forma de saber que o
 * servidor realmente aceita a chave (e que desligar a senha não vai trancar o usuário para fora). Uma interface para o desligador de senha e o
 * assistente poderem ser testados sem rede.
 */
@FunctionalInterface
public interface ProvaDeChave {

    /**
     * @throws FalhaDeEntradaPorSenha o servidor não aceitou a chave, o host mudou ou não houve conexão
     * @throws InterruptedException   a thread foi interrompida
     */
    void provar() throws FalhaDeEntradaPorSenha, InterruptedException;
}
