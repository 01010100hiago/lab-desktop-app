package br.com.labdesktop.cloudflare;

/** Os passos de publicar e de remover um endereço, na ordem em que a tela os mostra. */
public enum Etapa {
    COMPOSE("Compose do projeto: nome do container e rede edge"),
    REGRA("Regra no túnel"),
    CONFERIR_E_REINICIAR("Conferir as regras e reiniciar o cloudflared"),
    DNS("Registro de DNS"),
    PROJETO("Recriar o projeto para entrar na rede edge"),
    TESTE("Testar o endereço"),
    REMOVER_REGRA("Tirar a regra do túnel"),
    REMOVER_DNS("Apagar o registro de DNS");

    private final String rotulo;

    Etapa(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
