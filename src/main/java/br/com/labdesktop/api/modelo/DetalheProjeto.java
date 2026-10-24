package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/projetos/:p} de um projeto comum (CONTRATO-API 3.8). Diferenças em relação à lista: traz
 * README e STATUS (até 6000 caracteres cada, TEXTO não confiável), containers completos, backups, arquivos da
 * raiz e redes; NÃO traz {@code login} (só a lista traz).
 */
public record DetalheProjeto(
    @JsonProperty("nome") String nome,
    @JsonProperty("descricao") String descricao,
    @JsonProperty("readme") String readme,
    @JsonProperty("status") String status,
    @JsonProperty("composes") List<Compose> composes,
    @JsonProperty("containers") List<Container> containers,
    @JsonProperty("urls") List<String> urls,
    @JsonProperty("sessao") SessaoProjeto sessao,
    @JsonProperty("git") GitInfo git,
    @JsonProperty("backups") List<Backup> backups,
    @JsonProperty("tamanho") Long tamanho,
    @JsonProperty("mexido") Long mexido,
    @JsonProperty("acessoDocker") Boolean acessoDocker,
    @JsonProperty("redes") List<String> redes,
    @JsonProperty("arquivos") List<Arquivo> arquivos,
    @JsonProperty("limite") Limite limite) implements Detalhe {

    public DetalheProjeto {
        nome = Textos.linha(nome);
        descricao = Textos.linha(descricao);
        readme = Textos.bloco(readme);
        status = Textos.bloco(status);
        composes = Textos.lista(composes);
        containers = Textos.lista(containers);
        urls = Textos.linhas(urls);
        backups = Textos.lista(backups);
        redes = Textos.linhas(redes);
        arquivos = Textos.lista(arquivos);
    }

    /** Um arquivo compose aceito e o nome do projeto compose. */
    public record Compose(
        @JsonProperty("arquivo") String arquivo,
        @JsonProperty("projetoCompose") String projetoCompose) {

        public Compose {
            arquivo = Textos.linha(arquivo);
            projetoCompose = Textos.linha(projetoCompose);
        }
    }

    /** Um backup do projeto (mais recente primeiro). */
    public record Backup(
        @JsonProperty("arquivo") String arquivo,
        @JsonProperty("tamanho") Long tamanho,
        @JsonProperty("quando") Instant quando) {

        public Backup {
            arquivo = Textos.linha(arquivo);
        }
    }

    /** Um item da raiz do projeto; {@code tipo} é {@code pasta} ou {@code arquivo}. */
    public record Arquivo(
        @JsonProperty("nome") String nome,
        @JsonProperty("tipo") String tipo) {

        public Arquivo {
            nome = Textos.linha(nome);
            tipo = Textos.linha(tipo);
        }
    }
}
