package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * O campo {@code recursos} de {@code GET /api/versao} (CONTRATO-API 3.1): o que o agente deste servidor sabe fazer. O perfil
 * {@code lab} tem tudo ligado; o perfil {@code monitor} só tem {@code monitor}. O app usa isto no lugar de adivinhar pelo que o
 * detalhe do projeto traz: esconde o Claude quando {@code claude} é falso e a aba Projetos quando {@code projetos} é falso.
 *
 * <p>Cada campo é anulável: um agente que manda só alguns deles deixa os outros "desconhecidos", e desconhecido nunca esconde
 * nada (o app assume o comportamento de antes).
 *
 * @param claude   o servidor abre sessões do Claude (e tem consumo e limites do plano)
 * @param projetos o servidor tem projetos (pasta de projetos, compose, backups)
 * @param monitor  o servidor responde as leituras de máquina (CPU, memória, disco, processos, containers)
 * @param guiaIa   o {@code POST /api/projetos/novo} entende {@code "guia_ia": false}: só então o app mostra a caixa "Incluir guia para o
 *                 Claude" (um agente de antes ignoraria o {@code false} e criaria o {@code CLAUDE.md} mesmo assim); {@code null} = não diz
 * @param historico o agente grava o histórico de métricas e responde {@code GET /api/servidor/historico}: só então o app chama essa rota; sem isto
 *                 (agente de antes) ele usa os 10 minutos de {@code /api/servidor}; {@code null} = não diz = não tem
 */
public record RecursosDoAgente(
    @JsonProperty("claude") Boolean claude,
    @JsonProperty("projetos") Boolean projetos,
    @JsonProperty("monitor") Boolean monitor,
    @JsonProperty("guia_ia") Boolean guiaIa,
    @JsonProperty("historico") Boolean historico) {

    /** Sem o campo {@code guia_ia} (agente mais antigo, e os testes mais velhos). */
    public RecursosDoAgente(Boolean claude, Boolean projetos, Boolean monitor) {
        this(claude, projetos, monitor, null, null);
    }

    /** Sem o campo {@code historico} (agente mais antigo). */
    public RecursosDoAgente(Boolean claude, Boolean projetos, Boolean monitor, Boolean guiaIa) {
        this(claude, projetos, monitor, guiaIa, null);
    }

    /** O perfil {@code lab}: tudo. */
    public static final RecursosDoAgente TUDO = new RecursosDoAgente(true, true, true);
    /** O perfil {@code monitor}: só a leitura da máquina. */
    public static final RecursosDoAgente SO_MONITOR = new RecursosDoAgente(false, false, true);
    /** O perfil {@code lab} de hoje, que entende {@code guia_ia} (o mais antigo é {@link #TUDO}, que não diz). */
    public static final RecursosDoAgente TUDO_COM_GUIA_IA = new RecursosDoAgente(true, true, true, true);
    /** O perfil {@code lab} de hoje, que também grava e serve o histórico de métricas. */
    public static final RecursosDoAgente TUDO_COM_HISTORICO = new RecursosDoAgente(true, true, true, true, true);
    /** O perfil {@code monitor} de hoje, com o histórico de métricas. */
    public static final RecursosDoAgente SO_MONITOR_COM_HISTORICO = new RecursosDoAgente(false, false, true, false, true);
}
