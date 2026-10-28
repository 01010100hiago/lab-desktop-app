package br.com.labdesktop.registro;

import java.io.IOException;

/**
 * Lê o registro geral de UM servidor. SOMENTE LEITURA, com comandos FIXOS. Bloqueia (SSH ou processo): nunca chamar da EDT. As falhas "esperadas" (sem permissão, sem registro) são
 * valores de {@link ResultadoDoRegistro}; só "não consegui nem rodar o comando" (sem conexão, prazo estourado) vira exceção, para o {@code Sondador} dobrar o intervalo.
 */
@FunctionalInterface
public interface LeitorDeRegistro {

    ResultadoDoRegistro ler() throws IOException, InterruptedException;
}
