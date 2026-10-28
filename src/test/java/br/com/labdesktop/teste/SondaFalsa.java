package br.com.labdesktop.teste;

import br.com.labdesktop.local.SondaDoComputador;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Os fatos do computador, programados pelo teste: disco, memória, arquitetura, portas ocupadas e o que existe em disco. */
public final class SondaFalsa implements SondaDoComputador {

    public long bytesLivres = 120L * 1024 * 1024 * 1024;
    public long memoriaTotal = 16L * 1024 * 1024 * 1024;
    public String arquitetura = "arm64";
    public String sistema = "macOS 26.6";
    public String usuario = "ana";
    public final Set<Integer> portasOcupadas = new HashSet<>();
    public final Set<String> programas = new HashSet<>(Set.of("brew", "git"));
    public final Set<String> caminhos = new HashSet<>();

    @Override
    public Optional<String> localizarPrograma(String nome) {
        return programas.contains(nome) ? Optional.of("/usr/local/bin/" + nome) : Optional.empty();
    }

    @Override
    public boolean existe(String caminho) {
        return caminhos.contains(caminho);
    }

    @Override
    public long bytesLivres(String pasta) {
        return bytesLivres;
    }

    @Override
    public long memoriaTotalBytes() {
        return memoriaTotal;
    }

    @Override
    public String arquitetura() {
        return arquitetura;
    }

    @Override
    public String descricaoDoSistema() {
        return sistema;
    }

    @Override
    public boolean portaLivre(int porta) {
        return !portasOcupadas.contains(porta);
    }

    @Override
    public String nomeDoUsuario() {
        return usuario;
    }
}
