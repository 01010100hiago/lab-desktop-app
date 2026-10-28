package br.com.labdesktop;

import br.com.labdesktop.cofre.CofreEmUsoException;
import br.com.labdesktop.cofre.TravaDeInstancia;
import br.com.labdesktop.ui.JanelaDeAbertura;
import br.com.labdesktop.util.CaminhosApp;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Os caminhos de falha da partida, separados do {@code Principal} para serem provados sem {@code System.exit} nem janela: a segunda cópia do app, a pasta de dados que não abre
 * e um erro antes da primeira janela. A regra: a janelinha de abertura NUNCA fica por cima de um aviso nem sobrevive a uma falha. Ela só nasce depois de a
 * trava de instância estar na mão, e tudo o que mata a partida a fecha primeiro.
 */
public final class PartidaDoApp {

    private PartidaDoApp() { }

    /**
     * Pega a trava de instância. Se outra cópia do app já a tem, ou a pasta de dados não abre, fecha a janelinha de abertura (se houver), mostra o aviso e devolve vazio
     * (quem chamou sai). {@code aviso} é quem mostra a frase ao usuário (uma caixa de diálogo, de verdade; nos testes, um registro).
     */
    public static Optional<TravaDeInstancia> adquirirOuAvisar(CaminhosApp caminhos, Consumer<String> aviso, Consumer<IOException> registrarFalha) {
        try {
            return Optional.of(TravaDeInstancia.adquirir(caminhos));
        } catch (CofreEmUsoException e) {
            JanelaDeAbertura.fecharAAtual();
            aviso.accept("O " + Versao.NOME + " já está aberto. Esta segunda cópia vai fechar.");
            return Optional.empty();
        } catch (IOException e) {
            JanelaDeAbertura.fecharAAtual();
            registrarFalha.accept(e);
            aviso.accept("Não consegui preparar a pasta de dados do app: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Roda o início do app e, se ele falhar antes da primeira janela, fecha a janelinha antes de a falha seguir adiante (nunca fica presa). */
    public static void protegendo(Runnable inicio) {
        try {
            inicio.run();
        } catch (RuntimeException | Error e) {
            JanelaDeAbertura.fecharAAtual();
            throw e;
        }
    }
}
