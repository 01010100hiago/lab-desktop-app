package br.com.labdesktop.transporte;

import br.com.labdesktop.cofre.Transporte;
import java.util.function.Consumer;

/**
 * Abre o caminho até o SSH de um servidor: no modo direto devolve o endereço e a porta dele; nos modos sem porta aberta sobe o túnel externo e devolve 127.0.0.1 e a porta do túnel
 *. Atrás de uma interface para os testes usarem túneis de mentira. Bloqueia (espera o túnel ficar pronto): fora da EDT.
 */
@FunctionalInterface
public interface AbridorDeDestino {

    /**
     * @param transporte o modo do servidor
     * @param host       o endereço do servidor (só vale no modo direto)
     * @param porta      a porta do SSH do servidor (só vale no modo direto)
     * @param aoCair     chamado, de qualquer thread, se o túnel externo terminar SOZINHO depois de pronto (nunca ao fechar de propósito); no modo direto nunca é chamado
     * @throws FalhaDoTunelException o túnel não subiu (programa ausente, login expirado, prazo...)
     * @throws InterruptedException  a thread foi interrompida enquanto esperava (o túnel que já tinha subido é encerrado)
     */
    DestinoSsh abrir(Transporte transporte, String host, int porta, Consumer<FalhaDoTunel> aoCair) throws FalhaDoTunelException, InterruptedException;

    /** Sem aviso de queda (para quem só usa o destino por um instante: sondagem, prova de chave). */
    default DestinoSsh abrir(Transporte transporte, String host, int porta) throws FalhaDoTunelException, InterruptedException {
        return abrir(transporte, host, porta, falha -> { });
    }

    /** Só sabe o modo direto: um transporte sem porta aberta recusa (usado por quem nunca tem túnel, e como padrão dos testes antigos). */
    AbridorDeDestino SO_DIRETO = (transporte, host, porta, aoCair) -> {
        if (transporte.ehTunel()) {
            throw new FalhaDoTunelException(new FalhaDoTunel(FalhaDoTunel.Tipo.NAO_INICIOU, "Este app não está preparado para o modo " + transporte.rotulo() + "."));
        }
        return DestinoSsh.direto(host, porta);
    };
}
