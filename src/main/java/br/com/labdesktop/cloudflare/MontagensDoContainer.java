package br.com.labdesktop.cloudflare;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Optional;

/**
 * O que o {@code docker inspect} do container do cloudflared diz: se ele existe, se está rodando e ONDE ele enxerga o {@code config.yml} do servidor (o
 * caminho de dentro do container é outro, e o {@code ingress validate} precisa dele). Lê o JSON com Jackson; nada aqui roda comando.
 */
public record MontagensDoContainer(boolean existe, boolean rodando, String imagem, Optional<String> caminhoDoConfigNoContainer) {

    /** O container não existe (ou o JSON não é de um container). */
    public static final MontagensDoContainer NAO_EXISTE = new MontagensDoContainer(false, false, "", Optional.empty());

    /**
     * @param json              a saída de {@code docker inspect <container>} (uma lista com um objeto)
     * @param caminhoDoConfigNoServidor o {@code config.yml} no servidor, como o app o conhece
     */
    public static MontagensDoContainer ler(String json, String caminhoDoConfigNoServidor) {
        JsonNode raiz;
        try {
            raiz = Json.mapper().readTree(json == null ? "" : json);
        } catch (IOException e) {
            return NAO_EXISTE;
        }
        JsonNode c = raiz != null && raiz.isArray() && !raiz.isEmpty() ? raiz.get(0) : raiz;
        if (c == null || !c.isObject() || c.path("State").isMissingNode()) {
            return NAO_EXISTE;
        }
        boolean rodando = c.path("State").path("Running").asBoolean(false);
        String imagem = c.path("Config").path("Image").asText("");
        String melhor = null;
        int melhorTamanho = -1;
        for (JsonNode m : c.path("Mounts")) {
            String origem = m.path("Source").asText("");
            String destino = m.path("Destination").asText("");
            if (origem.isEmpty() || destino.isEmpty()) {
                continue;
            }
            String doConfig = null;
            if (origem.equals(caminhoDoConfigNoServidor)) {
                doConfig = destino; // o arquivo montado direto
            } else if (caminhoDoConfigNoServidor.startsWith(origem.endsWith("/") ? origem : origem + "/")) {
                String resto = caminhoDoConfigNoServidor.substring(origem.endsWith("/") ? origem.length() : origem.length() + 1);
                doConfig = (destino.endsWith("/") ? destino : destino + "/") + resto; // uma pasta montada que contém o arquivo
            }
            if (doConfig != null && origem.length() > melhorTamanho && ValidadorDeNomes.caminhoSeguroValido(doConfig)) {
                melhor = doConfig;
                melhorTamanho = origem.length();
            }
        }
        return new MontagensDoContainer(true, rodando, imagem, Optional.ofNullable(melhor));
    }
}
