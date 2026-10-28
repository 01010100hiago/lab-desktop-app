package br.com.labdesktop.local;

import java.util.List;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * O resultado de "Preparar este computador" (só leitura).
 *
 * @param resultados     uma entrada por verificação, na ordem da tela
 * @param sistema        o sistema deste computador
 * @param dockerPronto   o Docker responde agora (é o que libera "Criar meu laboratório local")
 * @param portaLivreSugerida uma porta livre perto da pedida (a mesma, se estava livre ou se é a do laboratório do app); 0 se não achou
 * @param laboratorio    o que já existe deste laboratório neste computador (o container do app)
 */
public record RelatorioDoComputador(List<ResultadoDoComputador> resultados, SistemaOperacional sistema, boolean dockerPronto, int portaLivreSugerida,
                                    LaboratorioDoApp laboratorio) {

    /**
     * O container do agente que o app criou, se existe.
     *
     * @param existe         há um container com o nome do agente, criado pelo app
     * @param rodando        ele está rodando
     * @param portaPublicada a porta que ele publica em 127.0.0.1 (0 se nenhuma)
     * @param versao         a versão do pacote que o criou
     */
    public record LaboratorioDoApp(boolean existe, boolean rodando, int portaPublicada, String versao) {
        public static final LaboratorioDoApp NENHUM = new LaboratorioDoApp(false, false, 0, "");
    }

    public RelatorioDoComputador {
        resultados = List.copyOf(resultados);
        laboratorio = laboratorio == null ? LaboratorioDoApp.NENHUM : laboratorio;
    }

    public RelatorioDoComputador(List<ResultadoDoComputador> resultados, SistemaOperacional sistema, boolean dockerPronto, int portaLivreSugerida) {
        this(resultados, sistema, dockerPronto, portaLivreSugerida, LaboratorioDoApp.NENHUM);
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
