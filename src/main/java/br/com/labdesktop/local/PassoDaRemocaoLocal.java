package br.com.labdesktop.local;

import br.com.labdesktop.instalador.Passo;

/** Os passos de "Remover laboratório local": só o agente, os dados dele, a imagem e a rede. Os projetos do usuário NÃO são tocados. */
public enum PassoDaRemocaoLocal implements Passo {
    CONTAINER("Parar e remover o agente"),
    DADOS("Apagar os dados do agente (token e 2FA)"),
    IMAGEM("Apagar a imagem do agente"),
    REDE("Remover a rede edge (se estiver vazia)");

    private final String rotulo;

    PassoDaRemocaoLocal(String rotulo) {
        this.rotulo = rotulo;
    }

    @Override
    public String rotulo() {
        return rotulo;
    }
}
