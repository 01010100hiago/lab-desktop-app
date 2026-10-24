package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Um item de {@code projetos[]} em {@code GET /api/projetos} (CONTRATO-API 3.7). A sessão raiz ({@code lab}) NÃO
 * aparece aqui: o app a busca em {@code /api/projetos/lab}.
 *
 * @param mexido        epoch em milissegundos
 * @param tamanho       bytes da pasta; {@code null} até o primeiro {@code du}
 * @param sessaoClaude  BOOLEANO aqui (no detalhe da raiz o campo de mesmo nome é um objeto)
 * @param sessaoInfo    {@code null} se não há sessão ou ainda não calculou
 * @param login         {@code null} se não há login pendente
 * @param cpu           percentual somado dos containers rodando; {@code null} se nenhum leu
 * @param mem           bytes somados dos containers rodando
 * @param acessoDocker  {@code false} com {@code .lab-sem-docker}
 * @param backups       quantidade de arquivos em backups/
 */
public record Projeto(
    @JsonProperty("nome") String nome,
    @JsonProperty("descricao") String descricao,
    @JsonProperty("composes") List<String> composes,
    @JsonProperty("containers") List<Container> containers,
    @JsonProperty("rodando") Boolean rodando,
    @JsonProperty("urls") List<String> urls,
    @JsonProperty("tamanho") Long tamanho,
    @JsonProperty("mexido") Long mexido,
    @JsonProperty("sessaoClaude") Boolean sessaoClaude,
    @JsonProperty("sessaoInfo") InfoSessao sessaoInfo,
    @JsonProperty("login") LoginPendente login,
    @JsonProperty("cpu") Double cpu,
    @JsonProperty("mem") Long mem,
    @JsonProperty("acessoDocker") Boolean acessoDocker,
    @JsonProperty("backups") Integer backups,
    @JsonProperty("tokens") ResumoTokens tokens,
    @JsonProperty("limite") Limite limite,
    @JsonProperty("git") GitInfo git) {

    public Projeto {
        nome = Textos.linha(nome);
        descricao = Textos.linha(descricao);
        composes = Textos.linhas(composes);
        containers = Textos.lista(containers);
        urls = Textos.linhas(urls);
    }

    /** O estado exibido: sem compose, nunca subiu, no ar, parcial ou parado (regra do front). */
    public EstadoProjeto estado() {
        if (composes.isEmpty()) {
            return EstadoProjeto.SEM_COMPOSE;
        }
        if (containers.isEmpty()) {
            return EstadoProjeto.NUNCA_SUBIU;
        }
        long rodandoAgora = containers.stream().filter(Container::rodando).count();
        if (rodandoAgora == containers.size()) {
            return EstadoProjeto.NO_AR;
        }
        return rodandoAgora > 0 ? EstadoProjeto.PARCIAL : EstadoProjeto.PARADO;
    }

    public boolean temSessaoClaude() {
        return Boolean.TRUE.equals(sessaoClaude);
    }

    public boolean pausado() {
        return limite != null && limite.pausado();
    }

    public boolean temLoginPendente() {
        return login != null && login.url() != null;
    }
}
