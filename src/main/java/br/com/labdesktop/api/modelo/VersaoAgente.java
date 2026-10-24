package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /api/versao} (CONTRATO-API 3.1): o teste de saúde. A conexão só vira CONECTADO depois de um 200 aqui.
 *
 * @param api        versão do contrato; o app avisa "agente incompatível" se for diferente de 1
 * @param painel     {@code process.version} do Node do agente
 * @param cliente    o nome do dispositivo da credencial usada; {@code null} sem credencial
 * @param totp       {@code "desligado"}, {@code "pendente"} ou {@code "ativo"}
 */
public record VersaoAgente(
    @JsonProperty("api") Integer api,
    @JsonProperty("painel") String painel,
    @JsonProperty("exigeToken") Boolean exigeToken,
    @JsonProperty("cliente") String cliente,
    @JsonProperty("totp") String totp) {

    /** A versão do contrato que este app fala. */
    public static final int API_SUPORTADA = 1;

    public VersaoAgente {
        painel = Textos.linha(painel);
        cliente = Textos.linha(cliente);
        totp = Textos.linha(totp);
    }

    /** {@code false} se o agente fala outra versão do contrato (ou não disse). */
    public boolean compativel() {
        return api != null && api == API_SUPORTADA;
    }
}
