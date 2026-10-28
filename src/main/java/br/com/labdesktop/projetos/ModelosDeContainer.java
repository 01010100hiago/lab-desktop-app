package br.com.labdesktop.projetos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Os modelos de "Adicionar container": Aplicação Node, Banco Postgres, Redis e Imagem do Docker Hub (ou GHCR) por nome. Cada um vira um
 * {@link BlocoDeServico} com as convenções do lab: o serviço entra na rede {@code default}, {@code restart: unless-stopped}, porta SÓ em
 * {@code 127.0.0.1} (e só se o usuário pediu, menos na aplicação Node), volume nomeado para o que guarda dados e as variáveis no {@code .env}
 * (o compose só as referencia). O modelo "Publicar na internet" fica para o pacote do Cloudflare.
 */
public final class ModelosDeContainer {

    /** Os modelos, na ordem da tela. */
    public enum Modelo {
        NODE("Aplicação Node", "Roda o seu código com Node (a pasta do projeto vira /app, e o container roda como o usuário \"node\", de número 1000, para o npm install não "
            + "deixar arquivos do administrador na sua pasta; se o seu usuário não for o 1000, ajuste a linha user:) e abre uma porta neste computador.", "app", 3000, 3000),
        POSTGRES("Banco Postgres", "Um Postgres com os dados num volume nomeado (não se perdem ao recriar o container) e a senha no .env.",
            "postgres", null, 5432),
        REDIS("Redis", "Um Redis com os dados num volume nomeado. Os outros serviços o acham pelo nome, dentro da rede do projeto.", "redis", null, 6379),
        IMAGEM("Imagem do Docker por nome", "Qualquer imagem do Docker Hub ou do GHCR (por exemplo nginx:alpine ou ghcr.io/usuario/app:1.2).", "servico",
            null, null);

        private final String rotulo;
        private final String descricao;
        private final String servicoPadrao;
        private final Integer portaPadraoNoComputador;
        private final Integer portaPadraoNoContainer;

        Modelo(String rotulo, String descricao, String servicoPadrao, Integer portaNoComputador, Integer portaNoContainer) {
            this.rotulo = rotulo;
            this.descricao = descricao;
            this.servicoPadrao = servicoPadrao;
            this.portaPadraoNoComputador = portaNoComputador;
            this.portaPadraoNoContainer = portaNoContainer;
        }

        public String rotulo() {
            return rotulo;
        }

        public String descricao() {
            return descricao;
        }

        public String servicoPadrao() {
            return servicoPadrao;
        }

        /** A porta que o modelo abre neste computador sem o usuário pedir, ou {@code null} (só a aplicação Node abre uma). */
        public Integer portaPadraoNoComputador() {
            return portaPadraoNoComputador;
        }

        /** A porta do container que o modelo conhece, ou {@code null} (a imagem por nome: o usuário diz). */
        public Integer portaPadraoNoContainer() {
            return portaPadraoNoContainer;
        }
    }

    /**
     * O que o usuário escolhe.
     *
     * @param servico          o nome do serviço no compose
     * @param imagem           só para {@link Modelo#IMAGEM}
     * @param portaNoComputador a porta aberta em 127.0.0.1 deste computador ({@code null}: não abre porta)
     * @param portaNoContainer  a porta de dentro do container (obrigatória se há porta no computador)
     */
    public record Parametros(String servico, String imagem, Integer portaNoComputador, Integer portaNoContainer) {

        /** Os valores que o modelo propõe. */
        public static Parametros padraoDe(Modelo m) {
            return new Parametros(m.servicoPadrao(), m == Modelo.IMAGEM ? "" : null, m.portaPadraoNoComputador(), m.portaPadraoNoContainer());
        }
    }

    static final Pattern SERVICO = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,30}$");
    private static final Pattern IMAGEM_POR_NOME = Pattern.compile("^(?:[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?/)?(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*"
        + "[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?$");

    private ModelosDeContainer() { }

    /** Os problemas dos parâmetros em frases simples; lista vazia se estão certos. */
    public static List<String> conferir(Modelo modelo, Parametros p) {
        List<String> problemas = new ArrayList<>();
        if (p.servico() == null || !SERVICO.matcher(p.servico()).matches()) {
            problemas.add("O nome do serviço usa letras minúsculas, números, hífen ou sublinhado (até 31 caracteres), começando por letra ou número.");
        }
        if (modelo == Modelo.IMAGEM) {
            String i = p.imagem() == null ? "" : p.imagem();
            if (i.isEmpty()) {
                problemas.add("Escreva o nome da imagem (por exemplo nginx:alpine).");
            } else if (i.length() > 200 || !IMAGEM_POR_NOME.matcher(i).matches()) {
                problemas.add("Esse nome de imagem não vale. Use o formato nome:versão, como nginx:alpine ou ghcr.io/usuario/app:1.2 (minúsculas, sem espaço).");
            }
        }
        if (p.portaNoComputador() != null) {
            if (p.portaNoComputador() < 1 || p.portaNoComputador() > 65535) {
                problemas.add("A porta deste computador vai de 1 a 65535.");
            }
            if (p.portaNoContainer() == null || p.portaNoContainer() < 1 || p.portaNoContainer() > 65535) {
                problemas.add("Diga a porta de dentro do container (de 1 a 65535).");
            }
        }
        return problemas;
    }

    /** Gera o bloco do modelo. {@link IllegalArgumentException} se {@link #conferir} acharia problema. */
    public static BlocoDeServico gerar(Modelo modelo, Parametros p) {
        List<String> problemas = conferir(modelo, p);
        if (!problemas.isEmpty()) {
            throw new IllegalArgumentException(problemas.get(0));
        }
        String s = p.servico();
        List<String> l = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        List<BlocoDeServico.Variavel> variaveis = new ArrayList<>();
        l.add("  " + s + ":");
        switch (modelo) {
            case NODE -> {
                l.add("    image: node:22-alpine");
                l.add("    working_dir: /app");
                l.add("    user: \"node\"");
                l.add("    command: [\"sh\", \"-c\", \"npm install && npm start\"]");
                l.add("    environment:");
                l.add("      NODE_ENV: \"${NODE_ENV:-development}\"");
                l.add("    volumes:");
                l.add("      - ./:/app");
            }
            case POSTGRES -> {
                l.add("    image: postgres:17-alpine");
                l.add("    environment:");
                l.add("      POSTGRES_USER: \"${POSTGRES_USER:-app}\"");
                l.add("      POSTGRES_PASSWORD: \"${POSTGRES_PASSWORD:?defina POSTGRES_PASSWORD no .env}\"");
                l.add("      POSTGRES_DB: \"${POSTGRES_DB:-app}\"");
                l.add("    volumes:");
                l.add("      - " + s + "_dados:/var/lib/postgresql/data");
                volumes.add("  " + s + "_dados:");
                variaveis.add(new BlocoDeServico.Variavel("POSTGRES_PASSWORD", BlocoDeServico.Variavel.Tipo.SENHA_ALEATORIA));
            }
            case REDIS -> {
                l.add("    image: redis:7-alpine");
                l.add("    command: [\"redis-server\", \"--appendonly\", \"yes\"]");
                l.add("    volumes:");
                l.add("      - " + s + "_dados:/data");
                volumes.add("  " + s + "_dados:");
            }
            case IMAGEM -> l.add("    image: " + p.imagem());
        }
        if (p.portaNoComputador() != null) {
            l.add("    ports:");
            l.add("      - \"127.0.0.1:" + p.portaNoComputador() + ":" + p.portaNoContainer() + "\"");
        }
        l.add("    networks:");
        l.add("      - default");
        l.add("    restart: unless-stopped");
        return new BlocoDeServico(s, modelo.rotulo(), l, volumes, variaveis);
    }
}
