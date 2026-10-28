package br.com.labdesktop.instalador;

/** O que o instalador vai fazer: instalar o agente pela primeira vez, ou instalar a versão nova do pacote por cima (mantendo dados e token). */
public enum ModoDaInstalacao {
    INSTALAR("Instalar o agente"),
    ATUALIZAR("Atualizar o agente");

    private final String rotulo;

    ModoDaInstalacao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
