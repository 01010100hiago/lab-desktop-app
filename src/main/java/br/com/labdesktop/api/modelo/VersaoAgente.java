package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /api/versao} (CONTRATO-API 3.1): o teste de saúde. A conexão só vira CONECTADO depois de um 200 aqui.
 *
 * @param api        versão do contrato; o app avisa "agente incompatível" se for diferente de 1
 * @param painel     {@code process.version} do Node do agente
 * @param cliente    o nome do dispositivo da credencial usada; {@code null} sem credencial
 * @param totp       {@code "desligado"}, {@code "pendente"} ou {@code "ativo"}
 * @param perfil     {@code "lab"} ou {@code "monitor"}; {@code null} num agente antigo, que só existia no perfil do lab
 * @param recursos   o que o agente sabe fazer ({@link RecursosDoAgente}); {@code null} num agente antigo
 * @param agente     a versão do pacote do agente que o app instalou ({@code "0.1.0-abc1234"}); {@code null} no lab de produção
 */
public record VersaoAgente(
    @JsonProperty("api") Integer api,
    @JsonProperty("painel") String painel,
    @JsonProperty("exigeToken") Boolean exigeToken,
    @JsonProperty("cliente") String cliente,
    @JsonProperty("totp") String totp,
    @JsonProperty("perfil") String perfil,
    @JsonProperty("recursos") RecursosDoAgente recursos,
    @JsonProperty("agente") String agente) {

    /** A versão do contrato que este app fala. */
    public static final int API_SUPORTADA = 1;

    public VersaoAgente {
        painel = Textos.linha(painel);
        cliente = Textos.linha(cliente);
        totp = Textos.linha(totp);
        perfil = Textos.linha(perfil);
        agente = Textos.linha(agente);
    }

    /** Sem os campos de perfil (agente antigo, e os testes mais velhos). */
    public VersaoAgente(Integer api, String painel, Boolean exigeToken, String cliente, String totp) {
        this(api, painel, exigeToken, cliente, totp, null, null, null);
    }

    /** O perfil {@code monitor}: só leitura da máquina, instalado pelo app. */
    public boolean perfilMonitor() {
        return "monitor".equals(perfil);
    }

    /**
     * O servidor tem sessões do Claude? {@code null} quando o agente não diz (agente antigo): quem decide assume o comportamento de
     * antes (a presença de {@code sessao} no detalhe do projeto).
     */
    public Boolean temClaude() {
        return recursos == null ? null : recursos.claude();
    }

    /** O servidor tem projetos? Desconhecido vale como SIM: o app só esconde a aba Projetos quando o agente diz que não. */
    public boolean temProjetos() {
        return recursos == null || recursos.projetos() == null || recursos.projetos();
    }

    /** {@code false} se o agente fala outra versão do contrato (ou não disse). */
    public boolean compativel() {
        return api != null && api == API_SUPORTADA;
    }
}
