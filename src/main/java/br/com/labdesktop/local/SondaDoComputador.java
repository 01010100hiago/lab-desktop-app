package br.com.labdesktop.local;

import java.util.Optional;

/**
 * Os fatos deste computador que as verificações precisam e que NÃO vêm de rodar um programa (disco, memória, arquitetura, porta, o que existe
 * em disco). É uma interface para os testes simularem pouco disco, pouca memória, porta ocupada e arquitetura estranha sem tocar na máquina.
 */
public interface SondaDoComputador {

    /** O programa (ex.: {@code docker}, {@code brew}, {@code winget}) existe neste computador? O caminho dele, se sim. */
    Optional<String> localizarPrograma(String nome);

    /** Existe este arquivo ou pasta? */
    boolean existe(String caminho);

    /** Bytes livres no disco da pasta (ou do primeiro ancestral que existe); {@code -1} se não deu para ver. */
    long bytesLivres(String pasta);

    /** Memória física total, em bytes; {@code -1} se não deu para ver. */
    long memoriaTotalBytes();

    /** {@code arm64}, {@code amd64} ou o texto cru do sistema. */
    String arquitetura();

    /** "macOS 26.6", "Windows 11"...: só para a frase da tela. */
    String descricaoDoSistema();

    /** A porta está livre para o Docker publicar em 127.0.0.1 (nada escuta nela, em nenhum endereço)? */
    boolean portaLivre(int porta);

    /** O nome do usuário do sistema (para a frase de permissão no Linux). */
    String nomeDoUsuario();
}
