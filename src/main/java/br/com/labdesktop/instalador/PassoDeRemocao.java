package br.com.labdesktop.instalador;

/** Os passos de "Remover agente": só o que o instalador criou; o Docker fica. */
public enum PassoDeRemocao implements Passo {
    CONTAINER("Parar e remover o container do agente"),
    DADOS("Apagar os dados do agente (token e 2FA)"),
    IMAGEM("Apagar a imagem do agente"),
    PASTA("Apagar a pasta do instalador");

    private final String rotulo;

    PassoDeRemocao(String rotulo) {
        this.rotulo = rotulo;
    }

    @Override
    public String rotulo() {
        return rotulo;
    }
}
