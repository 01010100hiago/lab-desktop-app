package br.com.labdesktop.cloudflare;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * As regras de entrada de um túnel gerido pela API, no JSON da Cloudflare: acrescenta uma regra ANTES da regra final (a que não tem {@code hostname}) e tira
 * uma regra pelo endereço. Trabalha sobre uma CÓPIA da configuração inteira e só mexe na lista {@code ingress}: o resto (roteamento WARP, opções de
 * origem...) volta igual na gravação.
 *
 * <p>Diferente do {@code config.yml}, o JSON não guarda comentário, então a regra do app NÃO leva marca: quem é do app é decidido pelo registro de DNS
 * (o comentário {@code lab-desktop:}). Por isso {@link #remover} recebe o serviço que o app espera encontrar e só tira a regra se for esse.
 */
public final class RegraDaApi {

    private RegraDaApi() { }

    /** Por que o app não mexe na configuração. */
    public static final class Problema extends Exception {
        private static final long serialVersionUID = 1L;

        public Problema(String mensagem) {
            super(mensagem);
        }
    }

    /** A configuração nova e o que dizer sobre ela. */
    public record Edicao(ObjectNode config, boolean jaEstava, List<String> avisos) {
        public Edicao {
            avisos = List.copyOf(avisos);
        }
    }

    private static ArrayNode ingressDe(ObjectNode config) throws Problema {
        JsonNode i = config.get("ingress");
        if (i == null || i.isNull()) {
            return null;
        }
        if (!i.isArray()) {
            throw new Problema("A lista de regras (ingress) do túnel tem uma forma que o app não entende.");
        }
        return (ArrayNode) i;
    }

    private static boolean ehFinal(JsonNode regra) {
        return !regra.hasNonNull("hostname");
    }

    /** As regras como linhas simples ({@code hostname -> serviço}, e a final como {@code (todo o resto) -> serviço}), para mostrar a diferença. */
    public static List<String> linhas(ObjectNode config) {
        List<String> l = new ArrayList<>();
        JsonNode ingress = config == null ? null : config.get("ingress");
        if (ingress != null && ingress.isArray()) {
            for (JsonNode r : ingress) {
                String h = r.path("hostname").asText("");
                l.add((h.isEmpty() ? "(todo o resto)" : h) + " -> " + r.path("service").asText(""));
            }
        }
        return l;
    }

    /** O serviço da regra deste endereço, ou vazio. */
    public static Optional<String> servicoDe(ObjectNode config, String hostname) {
        JsonNode ingress = config == null ? null : config.get("ingress");
        if (ingress != null && ingress.isArray()) {
            for (JsonNode r : ingress) {
                if (r.path("hostname").asText("").equalsIgnoreCase(hostname)) {
                    return Optional.of(r.path("service").asText(""));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Acrescenta {@code hostname -> servico} antes da regra final. Se a regra já existe com o MESMO serviço, {@link Edicao#jaEstava()} (idempotente); com outro
     * serviço, {@link Problema}.
     */
    public static Edicao acrescentar(ObjectNode original, String hostname, String servico) throws Problema {
        String h = hostname.toLowerCase(Locale.ROOT);
        ObjectNode config = original.deepCopy();
        ArrayNode ingress = ingressDe(config);
        List<String> avisos = new ArrayList<>();
        if (ingress == null) {
            ingress = config.putArray("ingress");
        }
        for (JsonNode r : ingress) {
            if (r.path("hostname").asText("").equalsIgnoreCase(h)) {
                if (r.path("service").asText("").equals(servico)) {
                    return new Edicao(original.deepCopy(), true, List.of());
                }
                throw new Problema("O endereço " + h + " já está nas regras do túnel e aponta para " + r.path("service").asText("outro lugar") + ". Escolha outro nome.");
            }
        }
        ObjectNode nova = Json.mapper().createObjectNode();
        nova.put("hostname", h);
        nova.put("service", servico);
        int ultima = ingress.size() - 1;
        boolean temFinal = !ingress.isEmpty() && ehFinal(ingress.get(ultima));
        // a Cloudflare usa a PRIMEIRA regra que casa: uma curinga (*.dominio) ou só de caminho do usuário antes da final pegaria o endereço novo. A nova entra antes dela.
        int sombra = -1;
        for (int i = 0; i < (temFinal ? ultima : ingress.size()); i++) {
            JsonNode r = ingress.get(i);
            String rh = r.path("hostname").asText("");
            if (rh.isEmpty() || (rh.contains("*") && ConfigYmlDoCloudflared.casaComCuringa(rh, h))) {
                sombra = i;
                break;
            }
        }
        if (ingress.isEmpty()) {
            ingress.add(nova);
            ingress.add(Json.mapper().createObjectNode().put("service", "http_status:404"));
            avisos.add("O túnel não tinha regras: o app acrescentou a regra e a regra final (http_status:404), que a Cloudflare exige.");
        } else {
            if (!temFinal) {
                ingress.add(Json.mapper().createObjectNode().put("service", "http_status:404"));
                avisos.add("O túnel não tinha a regra final (http_status:404), que a Cloudflare exige: o app a acrescentou depois da nova regra.");
            }
            if (sombra >= 0) {
                JsonNode r = ingress.get(sombra);
                ingress.insert(sombra, nova);
                avisos.add("Atenção: " + (r.path("hostname").asText("").isEmpty() ? "uma regra só de caminho (sem endereço) do túnel" : "a regra curinga " + r.path("hostname").asText(""))
                    + " (" + r.path("service").asText("sem serviço") + ") vem antes e também casaria com " + h + ", e a Cloudflare usa a primeira regra que casa. Por isso a regra nova "
                    + "entra ANTES dela; as outras continuam como estavam.");
            } else if (temFinal) {
                ingress.insert(ultima, nova);
            } else {
                ingress.insert(ingress.size() - 1, nova);
            }
        }
        return new Edicao(config, false, avisos);
    }

    /** Tira a regra do endereço, SÓ se o serviço dela é {@code servicoEsperado}. {@code null} no retorno: não havia regra. */
    public static ObjectNode remover(ObjectNode original, String hostname, String servicoEsperado) throws Problema {
        ObjectNode config = original.deepCopy();
        ArrayNode ingress = ingressDe(config);
        if (ingress == null) {
            return null;
        }
        for (int i = 0; i < ingress.size(); i++) {
            JsonNode r = ingress.get(i);
            if (!ehFinal(r) && r.path("hostname").asText("").equalsIgnoreCase(hostname)) {
                if (servicoEsperado != null && !r.path("service").asText("").equals(servicoEsperado)) {
                    throw new Problema("A regra de " + hostname + " no túnel aponta para " + r.path("service").asText("outro lugar") + ", e não para o que este app criou (" + servicoEsperado
                        + "). O app não a remove: tire-a à mão se quiser.");
                }
                ingress.remove(i);
                return config;
            }
        }
        return null;
    }
}
