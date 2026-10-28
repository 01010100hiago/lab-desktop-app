import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Confere a imagem gerada pelo jpackage (o {@code .app} do Mac, a pasta do Windows ou do Linux): acha o Java enxuto e os jars DENTRO dela e roda
 * {@code br.com.labdesktop.Principal --autoteste} (e {@code --versao}) com ESSE runtime. Se faltar um módulo do jlink ou um nativo, o autoteste diz qual.
 *
 * <p>Uso: {@code java scripts/ConferirEmpacotamento.java <pasta-da-imagem>} (a que contém {@code Lab Desktop.app} ou {@code Lab Desktop/}). Sai com 0 se tudo passou.
 */
public class ConferirEmpacotamento {

    public static void main(String[] args) throws Exception {
        Path imagem = Path.of(args[0]).toAbsolutePath();
        String so = System.getProperty("os.name").toLowerCase();
        boolean mac = so.contains("mac");
        boolean windows = so.contains("win");
        Path raiz;
        Path java;
        Path app;
        if (mac) {
            raiz = imagem.resolve("Lab Desktop.app/Contents");
            java = raiz.resolve("runtime/Contents/Home/bin/java");
            app = raiz.resolve("app");
        } else if (windows) {
            raiz = imagem.resolve("Lab Desktop");
            java = raiz.resolve("runtime/bin/java.exe");
            app = raiz.resolve("app");
        } else {
            raiz = imagem.resolve("Lab Desktop");
            java = raiz.resolve("lib/runtime/bin/java");
            app = raiz.resolve("lib/app");
        }
        for (Path p : List.of(raiz, java, app.resolve("lab-desktop.jar"))) {
            if (!Files.exists(p)) {
                System.err.println("FALTA na imagem: " + p);
                System.exit(2);
            }
        }
        // o launcher e o arquivo de configuração do jpackage existem, e a configuração leva as opções certas para este sistema
        Path cfg;
        try (Stream<Path> s = Files.list(app)) {
            cfg = s.filter(p -> p.getFileName().toString().endsWith(".cfg")).findFirst().orElse(null);
        }
        if (cfg == null) {
            System.err.println("FALTA o arquivo .cfg do launcher em " + app);
            System.exit(2);
        }
        String cfgTexto = Files.readString(cfg, StandardCharsets.UTF_8);
        boolean temApple = cfgTexto.contains("apple.");
        if (mac != temApple) {
            System.err.println("O .cfg " + (temApple ? "leva" : "não leva") + " opções do Mac em um sistema que " + (mac ? "é" : "não é") + " o Mac:\n" + cfgTexto);
            System.exit(2);
        }
        System.out.println("Imagem: " + raiz);
        System.out.println("Java da imagem: " + java);
        String sep = windows ? ";" : ":";
        List<String> cp = new ArrayList<>();
        try (Stream<Path> s = Files.list(app)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(p -> cp.add(p.toString()));
        }
        String classpath = String.join(sep, cp);
        int saida = rodar(java, classpath, "--versao");
        if (saida == 0) {
            saida = rodar(java, classpath, "--autoteste");
        }
        System.exit(saida);
    }

    static int rodar(Path java, String classpath, String opcao) throws IOException, InterruptedException {
        List<String> cmd = List.of(java.toString(), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-cp", classpath, "br.com.labdesktop.Principal", opcao);
        System.out.println("$ java ... Principal " + opcao);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            System.err.println("TEMPO ESGOTADO em " + opcao);
            return 3;
        }
        return p.exitValue();
    }
}
