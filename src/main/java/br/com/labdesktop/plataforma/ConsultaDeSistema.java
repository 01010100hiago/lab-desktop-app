package br.com.labdesktop.plataforma;

import java.time.Duration;
import java.util.List;

/**
 * Uma pergunta CURTA ao sistema (uma leitura: o tema claro ou escuro, uma configuração), feita por UM programa com os argumentos em LISTA (nunca texto para um shell). A tomada existe
 * para os testes darem as respostas de um Windows, de um Mac ou de um Linux sem rodar nada: em produção é {@link ConsultaPorProcesso}.
 */
@FunctionalInterface
public interface ConsultaDeSistema {

    /**
     * @param status o código de saída; {@code -1} se o programa não existe, não respondeu no prazo ou falhou
     * @param saida  a saída padrão e a de erro juntas, no máximo alguns KB
     */
    record Resposta(int status, String saida) {

        public static Resposta falhou() {
            return new Resposta(-1, "");
        }

        public boolean ok() {
            return status == 0;
        }
    }

    /** Roda e devolve. NUNCA lança: quem pergunta trata a falha como "não sei". Bloqueia: fora da EDT. */
    Resposta perguntar(List<String> comando, Duration prazo);
}
