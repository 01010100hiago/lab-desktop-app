package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.projetos.ComposeDoProjeto.Situacao;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.projetos.ModelosDeContainer.Parametros;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Os modelos de container e o acréscimo textual ao compose: valores conhecidos, e o compose do usuário nunca reescrito. */
class ComposeEModelosTest {

    private static final String ESQUELETO = GabaritoDoProjeto.composeVazio("demo");

    private static BlocoDeServico bloco(Modelo m) {
        return ModelosDeContainer.gerar(m, Parametros.padraoDe(m));
    }

    // ---------------------------------------------------------------------------------------------
    // a situação do arquivo
    // ---------------------------------------------------------------------------------------------

    @Test
    void esqueletoDoAppEDoAgenteSaoEsqueleto() {
        assertEquals(Situacao.ESQUELETO, ComposeDoProjeto.situacaoDe(ESQUELETO));
        assertEquals(Situacao.ESQUELETO, ComposeDoProjeto.situacaoDe("services: {}\n"));
        assertEquals(Situacao.ESQUELETO, ComposeDoProjeto.situacaoDe("name: x\nservices:\n"));
        assertEquals(Situacao.ESQUELETO, ComposeDoProjeto.situacaoDe("# oi\n\nservices: { }   # vazio\n"));
        // o esqueleto que o agente escreve COM guia (comentários a mais no fim) também é esqueleto
        assertEquals(Situacao.ESQUELETO, ComposeDoProjeto.situacaoDe("name: x\n# Esqueleto. Abra o Claude...\nservices: {}\n# networks:\n#   edge:\n#     external: true\n"));
    }

    @Test
    void semArquivoEAusente() {
        assertEquals(Situacao.AUSENTE, ComposeDoProjeto.situacaoDe(null));
    }

    @Test
    void qualquerOutraCoisaECompleto() {
        List<String> complexos = List.of(
            "services:\n  web:\n    image: nginx\n",
            "name: x\nservices:\n  web:\n    image: nginx\nvolumes:\n  d:\n",
            "services: {}\nvolumes:\n  d:\n",
            "services: {}\nnetworks:\n  edge:\n    external: true\n",
            "x-comum: &c\n  restart: always\nservices: {}\n",
            "services: {}\r\n",
            "services:\t{}\n",
            "version: '3'\nservices: {}\n",
            "services: {}\nservices: {}\n",
            "name: a\nname: b\nservices: {}\n",
            "\ufeffservices: {}\n",
            "services: {a: {image: x}}\n",
            "  services: {}\n",
            "");
        for (String c : complexos) {
            assertEquals(Situacao.COMPLEXO, ComposeDoProjeto.situacaoDe(c), c);
            assertEquals(Optional.empty(), ComposeDoProjeto.acrescentar(c, "x", bloco(Modelo.REDIS)), "nunca reescreve: " + c);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // o acréscimo
    // ---------------------------------------------------------------------------------------------

    @Test
    void redisNoEsqueletoValorConhecido() {
        String novo = ComposeDoProjeto.acrescentar(ESQUELETO, "demo", bloco(Modelo.REDIS)).orElseThrow();
        assertEquals("name: demo\n"
            + "# Esqueleto: acrescente os serviços abaixo (o botão \"Adicionar container\" do Lab Desktop escreve aqui).\n"
            + "# Convenções: banco, cache e filas só na rede default; segredos só no .env (nunca neste arquivo);\n"
            + "# portas no host só se precisar, e sempre em 127.0.0.1 (ex.: \"127.0.0.1:5440:5432\").\n"
            + "services:\n"
            + "  redis:\n"
            + "    image: redis:7-alpine\n"
            + "    command: [\"redis-server\", \"--appendonly\", \"yes\"]\n"
            + "    volumes:\n"
            + "      - redis_dados:/data\n"
            + "    networks:\n"
            + "      - default\n"
            + "    restart: unless-stopped\n"
            + "\n"
            + "volumes:\n"
            + "  redis_dados:\n", novo);
        assertEquals(Situacao.COMPLEXO, ComposeDoProjeto.situacaoDe(novo), "depois de escrito já não é esqueleto");
    }

    @Test
    void comentariosENameFicamComoEstavam() {
        String original = "# meu comentário\nname: demo   # nome\n\n# outro\nservices: {}\n# fim\n";
        String novo = ComposeDoProjeto.acrescentar(original, "demo", ModelosDeContainer.gerar(Modelo.IMAGEM, new Parametros("servico", "nginx:alpine", null, null))).orElseThrow();
        assertTrue(novo.startsWith("# meu comentário\nname: demo   # nome\n\n# outro\nservices:\n  servico:\n    image: nginx:alpine\n"), novo);
        assertTrue(novo.contains("    restart: unless-stopped\n# fim\n"), novo);
        assertFalse(novo.contains("volumes:"), "imagem sem volume não cria a seção volumes");
    }

    @Test
    void semArquivoNasceDoEsqueleto() {
        String novo = ComposeDoProjeto.acrescentar(null, "demo", bloco(Modelo.POSTGRES)).orElseThrow();
        assertTrue(novo.startsWith("name: demo\n# Esqueleto"), novo);
        assertTrue(novo.contains("services:\n  postgres:\n"), novo);
        assertTrue(novo.endsWith("volumes:\n  postgres_dados:\n"), novo);
    }

    // ---------------------------------------------------------------------------------------------
    // os modelos
    // ---------------------------------------------------------------------------------------------

    @Test
    void postgresTemVolumeNomeadoSenhaNoEnvESemPortaPublicadaPorPadrao() {
        BlocoDeServico b = bloco(Modelo.POSTGRES);
        String texto = String.join("\n", b.linhasDoServico());
        assertTrue(texto.contains("image: postgres:17-alpine"));
        assertTrue(texto.contains("postgres_dados:/var/lib/postgresql/data"));
        assertTrue(texto.contains("POSTGRES_PASSWORD: \"${POSTGRES_PASSWORD:?defina POSTGRES_PASSWORD no .env}\""), "só referencia a variável");
        assertFalse(texto.contains("ports:"), "banco sem porta por padrão: os outros serviços o acham pelo nome");
        assertTrue(texto.contains("restart: unless-stopped"));
        assertTrue(texto.contains("- default"));
        assertEquals(List.of("  postgres_dados:"), b.linhasDeVolumes());
        assertEquals(List.of("POSTGRES_PASSWORD"), b.variaveis().stream().map(BlocoDeServico.Variavel::nome).toList());
        assertFalse(texto.matches("(?s).*PASSWORD: [\"']?[A-Za-z0-9]{8,}[\"']?\\s.*"), "nenhuma senha escrita no compose");
    }

    @Test
    void portaSoEmLoopbackEQuandoPedida() {
        BlocoDeServico pg = ModelosDeContainer.gerar(Modelo.POSTGRES, new Parametros("db", null, 5440, 5432));
        assertTrue(pg.linhasDoServico().contains("      - \"127.0.0.1:5440:5432\""));
        assertEquals("  db:", pg.linhasDoServico().get(0));
        BlocoDeServico node = bloco(Modelo.NODE);
        assertTrue(node.linhasDoServico().contains("      - \"127.0.0.1:3000:3000\""), "a aplicação Node abre a 3000, em 127.0.0.1");
        for (Modelo m : Modelo.values()) {
            for (String linha : ModelosDeContainer.gerar(m, new Parametros("s", "nginx:alpine", 8080, 80)).linhasDoServico()) {
                if (linha.contains("\"") && linha.matches(".*[0-9]+:[0-9]+\".*")) {
                    assertTrue(linha.contains("\"127.0.0.1:"), "porta fora do loopback: " + linha);
                }
            }
        }
    }

    @Test
    void aplicacaoNodeUsaAPastaDoProjetoENenhumSegredo() {
        String t = String.join("\n", bloco(Modelo.NODE).linhasDoServico());
        assertTrue(t.contains("image: node:22-alpine"));
        assertTrue(t.contains("working_dir: /app"));
        assertTrue(t.contains("- ./:/app"));
        assertTrue(t.contains("NODE_ENV: \"${NODE_ENV:-development}\""));
        assertTrue(bloco(Modelo.NODE).variaveis().isEmpty());
        assertFalse(bloco(Modelo.NODE).temVolumes());
    }

    @Test
    void imagemPorNomeValidaOFormato() {
        for (String ok : List.of("nginx", "nginx:alpine", "ghcr.io/usuario/app:1.2", "usuario/app", "registro.example.com:5000/usuario/app:v1",
            "postgres@sha256:" + "a".repeat(64), "mcr.microsoft.com/dotnet/aspnet:8.0")) {
            assertEquals(List.of(), ModelosDeContainer.conferir(Modelo.IMAGEM, new Parametros("s", ok, null, null)), ok);
        }
        for (String ruim : List.of("", "Nginx", "nginx alpine", "nginx;ls", "-x", "nginx:", "a/b/../c", "$(x)", "nginx\nimage: x", "x".repeat(250), "usuario//app", "nginx:al pine")) {
            assertFalse(ModelosDeContainer.conferir(Modelo.IMAGEM, new Parametros("s", ruim, null, null)).isEmpty(), "devia recusar: " + ruim);
        }
    }

    @Test
    void servicoEPortasSaoConferidos() {
        assertFalse(ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("Redis", null, null, null)).isEmpty());
        assertFalse(ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("a b", null, null, null)).isEmpty());
        assertFalse(ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("x".repeat(32), null, null, null)).isEmpty());
        assertEquals(List.of(), ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("x".repeat(31), null, null, null)));
        assertFalse(ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("r", null, 0, 6379)).isEmpty());
        assertFalse(ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("r", null, 70000, 6379)).isEmpty());
        assertFalse(ModelosDeContainer.conferir(Modelo.REDIS, new Parametros("r", null, 6379, null)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ModelosDeContainer.gerar(Modelo.REDIS, new Parametros("R", null, null, null)));
    }

    @Test
    void textoParaCopiarTrazAsTresPartesESemValorDeSenha() {
        String t = bloco(Modelo.POSTGRES).textoParaCopiar();
        assertTrue(t.contains("# 1) Em \"services:\""));
        assertTrue(t.contains("# 2) Em \"volumes:\""));
        assertTrue(t.contains("# 3) No arquivo .env"));
        assertTrue(t.contains("POSTGRES_PASSWORD=escolha-uma-senha-forte"));
        String r = bloco(Modelo.REDIS).textoParaCopiar();
        assertFalse(r.contains(".env"));
        String n = bloco(Modelo.NODE).textoParaCopiar();
        assertFalse(n.contains("# 2) Em \"volumes:\""), "Node não tem seção volumes");
    }
}
