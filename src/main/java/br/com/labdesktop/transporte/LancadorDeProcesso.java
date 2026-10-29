package br.com.labdesktop.transporte;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Lança um programa externo por LISTA de argumentos (nunca por um shell). Em produção é o {@link LancadorReal}; nos testes é um lançador de mentira.
 */
public interface LancadorDeProcesso {

    /**
     * @param argumentos       o programa (já com o caminho completo) e os argumentos, cada um um item; nada de linha de comando
     * @param ambienteExtra    variáveis a ACRESCENTAR ao ambiente do processo filho (por exemplo o {@code PATH} com a pasta do plugin do Session Manager); vazio se nada
     * @throws IOException     o sistema não conseguiu iniciar o programa
     */
    ProcessoExterno lancar(List<String> argumentos, Map<String, String> ambienteExtra) throws IOException;
}
