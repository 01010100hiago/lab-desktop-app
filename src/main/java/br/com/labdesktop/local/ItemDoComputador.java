package br.com.labdesktop.local;

/** O que "Preparar este computador" confere, na ordem da tela. */
public enum ItemDoComputador {
    SISTEMA("Sistema"),
    ARQUITETURA("Processador"),
    DOCKER("Docker"),
    COMPOSE("Docker Compose"),
    DISCO("Espaço em disco"),
    MEMORIA("Memória"),
    VIRTUALIZACAO("Virtualização e WSL 2"),
    PORTA("Porta do agente"),
    GIT("git");

    private final String rotulo;

    ItemDoComputador(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
