package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Os textos do esqueleto sem guia para IA, valores conhecidos. O MESMO texto está nos testes do agente (o que o agente escreve
 * com {@code guia_ia: false}): se mudar aqui, mude lá.
 */
class GabaritoDoProjetoTest {

    private static final LocalDate DATA = LocalDate.of(2026, 10, 28);

    @Test
    void cincoArquivosNaOrdemEmQueSaoGravados() {
        Map<String, String> m = GabaritoDoProjeto.arquivos("meu-app", DATA);
        assertEquals(List.of("docker-compose.yml", ".gitignore", ".env.example", "STATUS.md", "README.md"), List.copyOf(m.keySet()));
        assertFalse(m.containsKey("CLAUDE.md"));
    }

    @Test
    void composeEsqueleto() {
        assertEquals("name: meu-app\n"
            + "# Esqueleto: acrescente os serviços abaixo (o botão \"Adicionar container\" do Lab Desktop escreve aqui).\n"
            + "# Convenções: banco, cache e filas só na rede default; segredos só no .env (nunca neste arquivo);\n"
            + "# portas no host só se precisar, e sempre em 127.0.0.1 (ex.: \"127.0.0.1:5440:5432\").\n"
            + "services: {}\n", GabaritoDoProjeto.arquivos("meu-app", DATA).get("docker-compose.yml"));
        assertEquals(ComposeDoProjeto.Situacao.ESQUELETO, ComposeDoProjeto.situacaoDe(GabaritoDoProjeto.composeVazio("x")));
    }

    @Test
    void demaisArquivos() {
        Map<String, String> m = GabaritoDoProjeto.arquivos("meu-app", DATA);
        assertEquals("node_modules/\ndist/\ncoverage/\n.env\n.env.*\n!.env.example\n*.log\n.DS_Store\nbackups/\n.pgdata/\n.minio/\n", m.get(".gitignore"));
        assertEquals("# nomes das variáveis (valores só no .env, que não vai para o git)\n", m.get(".env.example"));
        assertEquals("# STATUS — meu-app\n\nCriado em 2026-10-28. Nada construído ainda.\n", m.get("STATUS.md"));
        assertEquals("# meu-app\n\nCriado em 2026-10-28. A rede `meu-app_default` já existe; os serviços entram no `docker-compose.yml`.\n"
            + "Veja `STATUS.md` para o estado.\n", m.get("README.md"));
    }

    @Test
    void nadaFalaDoClaudeNemDoLab() {
        for (String texto : GabaritoDoProjeto.arquivos("meu-app", DATA).values()) {
            String t = texto.toLowerCase();
            assertFalse(t.contains("claude"), texto);
            assertFalse(t.contains("cloudflared") || t.contains("/srv/lab") || t.contains("proxy"), texto);
        }
    }

    @Test
    void nomeInvalidoNaoGeraNada() {
        assertThrows(IllegalArgumentException.class, () -> GabaritoDoProjeto.arquivos("../x", DATA));
        assertThrows(IllegalArgumentException.class, () -> GabaritoDoProjeto.arquivos("abc-", DATA));
        assertTrue(GabaritoDoProjeto.redeDoProjeto("meu-app").equals("meu-app_default"));
    }
}
