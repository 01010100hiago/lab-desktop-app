package br.com.labdesktop.local;

import br.com.labdesktop.instalador.Passo;

/** Os passos de "Criar meu laboratório local", na ordem. Todos idempotentes: cada um confere por leitura se já está feito. */
public enum PassoDoLaboratorio implements Passo {
    PASTA("Criar a pasta dos projetos"),
    REDE("Criar a rede edge do Docker"),
    IMAGEM("Construir a imagem do agente"),
    CONTAINER("Subir o agente em 127.0.0.1"),
    SONDAR("Conferir que o agente responde"),
    TOKEN("Registrar o token deste app");

    private final String rotulo;

    PassoDoLaboratorio(String rotulo) {
        this.rotulo = rotulo;
    }

    @Override
    public String rotulo() {
        return rotulo;
    }
}
