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
 */
public record RecursosDoAgente(
    @JsonProperty("claude") Boolean claude,
    @JsonProperty("projetos") Boolean projetos,
    @JsonProperty("monitor") Boolean monitor) {

    /** O perfil {@code lab}: tudo. */
    public static final RecursosDoAgente TUDO = new RecursosDoAgente(true, true, true);
    /** O perfil {@code monitor}: só a leitura da máquina. */
    public static final RecursosDoAgente SO_MONITOR = new RecursosDoAgente(false, false, true);
}
