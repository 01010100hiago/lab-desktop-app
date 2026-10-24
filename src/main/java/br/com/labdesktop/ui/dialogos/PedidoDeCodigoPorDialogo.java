package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.util.Fundo;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * O {@link PedidoDeCodigo2fa} da interface (ARQUITETURA 10.1): abre o {@link DialogoCodigo2fa} na EDT por
 * {@code Fundo.naEdt} e espera o resultado num {@code CompletableFuture}. O {@code ClienteApi} chama {@code pedir}
 * numa thread VIRTUAL, então só ela espera; a EDT nunca bloqueia. Um pedido por servidor: se vários tópicos do
 * Sondador levarem 401 ao mesmo tempo, o {@code ClienteApi} junta todos num único pedido e, portanto, num único
 * diálogo.
 */
public final class PedidoDeCodigoPorDialogo implements PedidoDeCodigo2fa {

    private final Apresentador apresentador;

    public PedidoDeCodigoPorDialogo(Apresentador apresentador) {
        this.apresentador = apresentador;
    }

    @Override
    public Optional<String> pedir(String servidor, String aviso) {
        return pedir(servidor, aviso, 0);
    }

    @Override
    public Optional<String> pedir(String servidor, String aviso, int aguardeSegundos) {
        CompletableFuture<Optional<String>> resposta = new CompletableFuture<>();
        Fundo.naEdt(() -> {
            try {
                apresentador.mostrar(new DialogoCodigo2fa(servidor, aviso, aguardeSegundos, resposta::complete));
            } catch (RuntimeException e) {
                resposta.complete(Optional.empty()); // sem janela para mostrar: é como se o usuário cancelasse
            }
        });
        return resposta.join();
    }
}
