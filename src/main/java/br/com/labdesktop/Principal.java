package br.com.labdesktop;

import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.CofreEmUsoException;
import br.com.labdesktop.cofre.TravaDeInstancia;
import br.com.labdesktop.ui.GerenciadorTema;
import br.com.labdesktop.ui.PreferenciasAtuais;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.util.AparenciaDoSistema;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.ConfiguracaoDeLog;
import br.com.labdesktop.util.EdtWatchdog;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.SenhaDeTeste;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ponto de entrada (ARQUITETURA 4.4). Na ordem: as propriedades do macOS antes de qualquer classe do AWT, o log em
 * arquivo (antes do primeiro logger), a trava de instância (a segunda instância avisa e sai), as preferências, e então,
 * na EDT, o tema e o {@link Aplicacao}, que pede a senha-mestra e abre a janela.
 *
 * <p>{@code --pasta <dir>} troca a pasta de dados (desenvolvimento e testes: {@code ./gradlew run --args="--pasta
 * cofre-dev"}); caminho relativo vale a partir da pasta de trabalho.
 */
public final class Principal {

    private Principal() { }

    public static void main(String[] args) {
        // Antes de qualquer classe do AWT: no .app vêm como opções da JVM, isto cobre o `./gradlew run` quando alguém
        // rodar a classe direto (ARQUITETURA 4.4, passo 1).
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", Versao.NOME);

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(Versao.NOME + " precisa de uma tela (a JVM está em modo sem cabeça).");
            System.exit(3);
            return;
        }
        CaminhosApp caminhos;
        try {
            caminhos = CaminhosApp.deArgumentos(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        try {
            ConfiguracaoDeLog.configurar(caminhos);
        } catch (IOException e) {
            System.err.println("Não consegui preparar o arquivo de log: " + e.getMessage()); // o app abre mesmo assim
        }
        Logger log = LoggerFactory.getLogger(Principal.class);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("Erro não tratado na thread {}", t.getName(), e));

        // O domínio não importa Swing: é aqui que a EDT é ligada ao Fundo.
        Fundo.definirEdt(SwingUtilities::invokeLater);

        TravaDeInstancia trava;
        try {
            trava = TravaDeInstancia.adquirir(caminhos);
        } catch (CofreEmUsoException e) {
            avisoFatal("O " + Versao.NOME + " já está aberto. Esta segunda cópia vai fechar.");
            System.exit(1);
            return;
        } catch (IOException e) {
            log.error("Não consegui criar a pasta de dados", e);
            avisoFatal("Não consegui preparar a pasta de dados do app: " + e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(trava::close, "solta-trava"));

        // E/S de disco fora da EDT: tudo o que a janela precisa sobre o disco é lido aqui, na thread principal.
        Preferencias lidas = Preferencias.carregar(caminhos);
        boolean cofreExiste = CofreDeChaves.existe(caminhos);
        PreferenciasAtuais preferencias = new PreferenciasAtuais(caminhos, lidas);

        // Tudo o que daria um engasgo na EDT na partida sai daqui: fontes, biblioteca nativa do FlatLaf e a consulta de
        // claro ou escuro do macOS (um processo).
        Fundo.emFundo(Icones::preAquecer); // em paralelo com o resto da partida; o primeiro ícone na EDT custava 500 ms
        GerenciadorTema.preAquecer();
        boolean sistemaEscuro = preferencias.get().tema() == Preferencias.Tema.SISTEMA && AparenciaDoSistema.escuro();

        if (Boolean.getBoolean("labdesktop.dev")) {
            EdtWatchdog.emDesenvolvimento().iniciar();
        }
        log.info("{} {} iniciando (dados em {}).", Versao.NOME, Versao.atual(), caminhos.pastaDados());
        // Só desenvolvimento: conferido aqui, fora da EDT, porque faz E/S (ver SenhaDeTeste).
        Path arquivoDaSenhaDeTeste = null;
        if (SenhaDeTeste.pedida(args)) {
            StringBuilder motivo = new StringBuilder();
            arquivoDaSenhaDeTeste = SenhaDeTeste.arquivoSeguro(caminhos, motivo).orElse(null);
            if (arquivoDaSenhaDeTeste == null) {
                log.warn("{} ignorada: {}.", SenhaDeTeste.OPCAO, motivo);
            }
        }
        final Path arquivoDaSenhaDeTesteFinal = arquivoDaSenhaDeTeste;
        SwingUtilities.invokeLater(() -> {
            GerenciadorTema tema = new GerenciadorTema(preferencias.get().tema(), sistemaEscuro, true);
            Aplicacao app = new Aplicacao(caminhos, preferencias, tema, cofreExiste, () -> {
                trava.close();
                System.exit(0);
            });
            if (arquivoDaSenhaDeTesteFinal != null) {
                app.comSenhaDeTeste(arquivoDaSenhaDeTesteFinal);
            }
            app.iniciar();
        });
    }

    /** Antes de o tema existir: uma caixa simples, que não depende de nada do app. */
    private static void avisoFatal(String texto) {
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(null, texto, Versao.NOME, JOptionPane.WARNING_MESSAGE));
        } catch (Exception e) {
            System.err.println(texto);
        }
    }
}
