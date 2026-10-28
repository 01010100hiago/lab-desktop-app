package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.dialogos.DialogoSenhaMestra;
import br.com.labdesktop.util.AparenciaDoSistema;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O cronômetro da partida (sem suposição), no que dá para medir SEM tela: o que o {@code Principal} faz antes da primeira janela, passo a passo, e a
 * subida da JVM até o {@code main} (processo novo com {@code --versao}). O que só existe com tela (o AWT do macOS acordando) fica de fora e é medido pelo
 * próprio app a cada abertura ({@code MedidaDaPartida}). Roda isolado para pegar a JVM fria: {@code ./gradlew test --tests '*TemposDaPartidaTest'}. Saída em
 * {@code build/tempos-da-partida.txt}.
 */
class TemposDaPartidaTest {

    private final List<String> linhas = new ArrayList<>();

    private <T> T medir(String o, Supplier<T> acao) {
        long t0 = System.nanoTime();
        T r = acao.get();
        linhas.add(String.format("%7.1f ms  %s", (System.nanoTime() - t0) / 1e6, o));
        return r;
    }

    @Test
    void cronometroDaPartida(@TempDir Path tmp) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // 1) subida da JVM até o main, num processo novo, com o classpath do teste
        String binJava = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long[] subidas = new long[3];
        for (int i = 0; i < subidas.length; i++) {
            long t0 = System.nanoTime();
            Process p = new ProcessBuilder(binJava, "-cp", System.getProperty("java.class.path"), "br.com.labdesktop.Principal", "--versao")
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            subidas[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        linhas.add("JVM nova até imprimir a versão (--versao), 3 vezes: " + subidas[0] + ", " + subidas[1] + ", " + subidas[2] + " ms");
        // 2) o que o Principal faz antes da primeira janela
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        medir("preferências lidas (sem arquivo)", () -> Preferencias.carregar(caminhos));
        medir("Icones.preAquecer (em fundo na partida)", () -> {
            Icones.preAquecer();
            return 0;
        });
        medir("GerenciadorTema.preAquecer (registra tema e fontes, carrega o nativo do FlatLaf no Mac)", () -> {
            GerenciadorTema.preAquecer();
            return 0;
        });
        medir("AparenciaDoSistema.escuro (um processo no Mac)", AparenciaDoSistema::escuro);
        GerenciadorTema tema = new GerenciadorTema(Tema.SISTEMA, false, false);
        medir("instalar o tema na EDT", () -> {
            Visual.naEdt(tema::instalar);
            return 0;
        });
        DialogoSenhaMestra d = medir("montar o diálogo da senha-mestra", () -> Visual.naEdt(() -> new DialogoSenhaMestra(false, null, new br.com.labdesktop.cofre.AtrasoDeSenha(), java.time.Instant::now, r -> { })));
        medir("pintar o diálogo da senha-mestra", () -> Visual.renderizar(d.raiz(), 460, Visual.alturaPreferida(d.raiz(), 460)));
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/tempos-da-partida.txt"), linhas);
        linhas.forEach(System.out::println);
        assertTrue(!linhas.isEmpty());
    }
}
