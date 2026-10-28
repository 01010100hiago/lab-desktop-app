package br.com.labdesktop.instalador;

/**
 * Os passos da instalação, na ordem. Todos idempotentes: cada um confere, por LEITURA, se já está feito e então não repete (é o que
 * faz "Tentar de novo" retomar do passo que falhou e a segunda execução não mudar nada).
 */
public enum PassoDeInstalacao implements Passo {
    DOCKER("Instalar o Docker (só se faltar)"),
    ENVIAR_PACOTE("Enviar o pacote do agente e conferir o SHA-256"),
    CONSTRUIR_IMAGEM("Construir a imagem do agente no servidor"),
    SUBIR_CONTAINER("Subir o container do agente"),
    SONDAR("Conferir que o agente responde"),
    REGISTRAR_TOKEN("Registrar o token deste computador");

    private final String rotulo;

    PassoDeInstalacao(String rotulo) {
        this.rotulo = rotulo;
    }

    @Override
    public String rotulo() {
        return rotulo;
    }
}
