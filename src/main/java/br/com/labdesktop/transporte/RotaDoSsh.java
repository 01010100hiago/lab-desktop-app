package br.com.labdesktop.transporte;

import br.com.labdesktop.cofre.Transporte;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * "Como chegar ao SSH deste servidor": o modo, o endereço e a porta (que só valem no direto) e quem abre o caminho. É o que a sondagem do host, a prova da chave, a entrada com senha, a
 * sonda de métodos e a {@code ConexaoSsh} recebem no lugar de um {@code host:porta} solto: cada uma abre o destino no momento de conectar e o fecha ao terminar.
 */
public record RotaDoSsh(Transporte transporte, String host, int porta, AbridorDeDestino abridor) {

    public RotaDoSsh {
        Objects.requireNonNull(transporte, "transporte");
        Objects.requireNonNull(abridor, "abridor");
    }

    /** O SSH direto num endereço e porta (o modo padrão). */
    public static RotaDoSsh direta(String host, int porta) {
        return new RotaDoSsh(Transporte.DIRETO, host, porta, AbridorDeDestino.SO_DIRETO);
    }

    public boolean ehTunel() {
        return transporte.ehTunel();
    }

    /** Abre o destino. Quem recebe é responsável por fechá-lo. */
    public DestinoSsh abrir(Consumer<FalhaDoTunel> aoCair) throws FalhaDoTunelException, InterruptedException {
        return abridor.abrir(transporte, host, porta, aoCair);
    }

    public DestinoSsh abrir() throws FalhaDoTunelException, InterruptedException {
        return abrir(falha -> { });
    }

    /** Para as frases de erro e o log: o endereço no direto; o modo e o destino nos outros. */
    public String descricao() {
        return ehTunel() ? transporte.rotulo() + " " + transporte.destinoEmTexto() : host + ":" + porta;
    }
}
