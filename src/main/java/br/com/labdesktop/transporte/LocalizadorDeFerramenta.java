package br.com.labdesktop.transporte;

import java.util.Optional;

/** Acha uma ferramenta neste computador (atrás de uma interface para os testes usarem um sistema de arquivos falso). */
@FunctionalInterface
public interface LocalizadorDeFerramenta {

    /** O caminho completo do programa (texto do sistema onde o app roda), ou vazio se não está instalado. */
    Optional<String> localizar(FerramentaExterna ferramenta);
}
