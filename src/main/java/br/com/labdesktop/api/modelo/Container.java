package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Um container de projeto. A lista de projetos (CONTRATO-API 3.7) traz só nome, estado, status, portas, cpu e
 * mem; o detalhe (3.8) traz também os demais campos. Tudo anulável: o agente devolve {@code null} sem leitura.
 *
 * @param cpu       percentual (12,5 = 12,5 %); a soma de containers pode passar de 100
 * @param mem       bytes (NUNCA texto: o mock do front usa texto, o agente real usa número)
 * @param memLimite bytes
 * @param portas    texto do Docker ({@code 127.0.0.1:5440->5432/tcp, ...})
 * @param status    texto do Docker ({@code Up 3 hours})
 */
public record Container(
    @JsonProperty("nome") String nome,
    @JsonProperty("estado") String estado,
    @JsonProperty("status") String status,
    @JsonProperty("portas") String portas,
    @JsonProperty("imagem") String imagem,
    @JsonProperty("criado") Instant criado,
    @JsonProperty("cpu") Double cpu,
    @JsonProperty("mem") Long mem,
    @JsonProperty("memLimite") Long memLimite,
    @JsonProperty("net") String net,
    @JsonProperty("bloco") String bloco,
    @JsonProperty("pids") Integer pids) {

    public Container {
        nome = Textos.linha(nome);
        estado = Textos.linha(estado);
        status = Textos.linha(status);
        portas = Textos.linha(portas);
        imagem = Textos.linha(imagem);
        net = Textos.linha(net);
        bloco = Textos.linha(bloco);
    }

    public boolean rodando() {
        return "running".equals(estado);
    }
}
