package br.com.labdesktop.local;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** A {@link SondaDoComputador} de verdade: lê o disco, a JVM e tenta abrir a porta. Só leitura. */
public final class SondaReal implements SondaDoComputador {

    private final LocalizadorDeProgramas localizador;

    public SondaReal(LocalizadorDeProgramas localizador) {
        this.localizador = localizador;
    }

    @Override
    public Optional<String> localizarPrograma(String nome) {
        return localizador.localizar(nome).map(Path::toString);
    }

    @Override
    public boolean existe(String caminho) {
        try {
            return Files.exists(Path.of(caminho));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long bytesLivres(String pasta) {
        try {
            Path p = Path.of(pasta).toAbsolutePath();
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            return p == null ? -1 : Files.getFileStore(p).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    @Override
    public long memoriaTotalBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (RuntimeException e) {
            // sem a extensão da JVM
        }
        return -1;
    }

    @Override
    public String arquitetura() {
        return normalizarArquitetura(System.getProperty("os.arch"));
    }

    static String normalizarArquitetura(String osArch) {
        String a = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "aarch64", "arm64" -> "arm64";
            case "x86_64", "amd64" -> "amd64";
            default -> a.isEmpty() ? "desconhecida" : a;
        };
    }

    @Override
    public String descricaoDoSistema() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version");
    }

    /**
     * Livre = não dá para conectar em 127.0.0.1:porta E dá para abrir a porta em TODOS os endereços e só no loopback. O AirPlay do macOS escuta em
     * 7000 e 5000 em todos os endereços: abrir só o loopback poderia "passar" e o Docker falhar depois.
     */
    @Override
    public boolean portaLivre(int porta) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), porta), 300);
            return false; // alguém atendeu
        } catch (IOException e) {
            // ninguém atendeu: confere se dá para abrir
        }
        try (ServerSocket todos = new ServerSocket()) {
            todos.setReuseAddress(false);
            todos.bind(new java.net.InetSocketAddress(porta));
        } catch (IOException e) {
            return false;
        }
        try (ServerSocket loop = new ServerSocket()) {
            loop.setReuseAddress(false);
            loop.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), porta));
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public String nomeDoUsuario() {
        return System.getProperty("user.name", "");
    }
}
