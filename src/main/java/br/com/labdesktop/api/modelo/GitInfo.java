package br.com.labdesktop.api.modelo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Estado git de um projeto (CONTRATO-API 3.7): {@code null} no projeto quando a pasta não é repositório. */
public record GitInfo(
    @JsonProperty("branch") String branch,
    @JsonProperty("commit") String commit,
    @JsonProperty("quando") Instant quando,
    @JsonProperty("sujo") Boolean sujo) {

    public GitInfo {
        branch = Textos.linha(branch);
        commit = Textos.linha(commit);
    }
}
