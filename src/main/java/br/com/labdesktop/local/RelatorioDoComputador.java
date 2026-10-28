package br.com.labdesktop.local;

import java.util.List;

/**
 * O resultado de "Preparar este computador" (só leitura).
 *
 * @param resultados     uma entrada por verificação, na ordem da tela
 * @param sistema        o sistema deste computador
 * @param dockerPronto   o Docker responde agora (é o que libera "Criar meu laboratório local")
 * @param portaLivreSugerida uma porta livre perto da pedida (a mesma, se estava livre); 0 se não achou
 */
public record RelatorioDoComputador(List<ResultadoDoComputador> resultados, SistemaOperacional sistema, boolean dockerPronto, int portaLivreSugerida) {

    public RelatorioDoComputador {
        resultados = List.copyOf(resultados);
    }

    /** Algum problema impede criar o laboratório. */
    public boolean bloqueado() {
        return resultados.stream().anyMatch(ResultadoDoComputador::bloqueia);
    }

    public List<ResultadoDoComputador> problemas() {
        return resultados.stream().filter(ResultadoDoComputador::bloqueia).toList();
    }

    public ResultadoDoComputador de(ItemDoComputador item) {
        return resultados.stream().filter(r -> r.item() == item).findFirst().orElse(null);
    }

    /** Tudo certo para "Criar meu laboratório local". */
    public boolean pronto() {
        return !bloqueado() && dockerPronto;
    }
}
