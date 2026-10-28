package br.com.labdesktop.projetos;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Os arquivos de um projeto novo SEM o guia para IA: a única fonte desses textos no app. É o que o criador LOCAL ({@code CriadorLocal})
 * escreve no computador, e é, letra por letra, o que o agente do lab escreve quando recebe {@code "guia_ia": false} em
 * {@code POST /api/projetos/novo} ({@code arquivosSemGuiaIa} no código do agente; os testes dele e, aqui,
 * {@code GabaritoIgualAoDoAgenteTest} (que roda a função do agente empacotado no {@code node} e compara arquivo por arquivo) conferem os
 * mesmos textos): mexeu num, mexa no outro.
 *
 * <p>O que NÃO entra: {@code CLAUDE.md}, e qualquer frase sobre o Claude, o proxy de Docker ou o cloudflared do lab (o app fala com qualquer
 * servidor e com o computador local). O compose nasce como esqueleto ({@code services: {}}), a forma que o "Adicionar container" sabe
 * completar.
 */
public final class GabaritoDoProjeto {

    /** O nome do compose que o app escreve (e o primeiro que o agente procura). */
    public static final String ARQUIVO_COMPOSE = "docker-compose.yml";

    /** A linha que marca o esqueleto: o "Adicionar container" a troca por {@code services:} mais os serviços. */
    public static final String SERVICOS_VAZIO = "services: {}";

    static final String GITIGNORE = "node_modules/\ndist/\ncoverage/\n.env\n.env.*\n!.env.example\n*.log\n.DS_Store\nbackups/\n.pgdata/\n.minio/\n";

    private GabaritoDoProjeto() { }

    /**
     * Os cinco arquivos, na ordem em que são gravados (arquivo, conteúdo). O nome já passou por {@link NomeDoProjeto}.
     *
     * @param hoje a data de criação (injetada: o teste fixa)
     */
    public static Map<String, String> arquivos(String nome, LocalDate hoje) {
        if (!NomeDoProjeto.valido(nome)) {
            throw new IllegalArgumentException("Nome de projeto inválido.");
        }
        String data = hoje.toString();
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ARQUIVO_COMPOSE, composeVazio(nome));
        m.put(".gitignore", GITIGNORE);
        m.put(".env.example", "# nomes das variáveis (valores só no .env, que não vai para o git)\n");
        m.put("STATUS.md", "# STATUS — " + nome + "\n\nCriado em " + data + ". Nada construído ainda.\n");
        m.put("README.md", "# " + nome + "\n\nCriado em " + data + ". A rede `" + nome + "_default` já existe; os serviços entram no `docker-compose.yml`.\n"
            + "Veja `STATUS.md` para o estado.\n");
        return m;
    }

    /** O compose esqueleto (também o que a importação escreve num projeto que veio sem compose). */
    public static String composeVazio(String nome) {
        // entre aspas: um nome só de dígitos ou parecido com número/booleano (123, 1e3, 0x1f, true, null) virava inteiro ou booleano no YAML e quebrava o compose
        return "name: \"" + nome + "\"\n"
            + "# Esqueleto: acrescente os serviços abaixo (o botão \"Adicionar container\" do Lab Desktop escreve aqui).\n"
            + "# Convenções: banco, cache e filas só na rede default; segredos só no .env (nunca neste arquivo);\n"
            + "# portas no host só se precisar, e sempre em 127.0.0.1 (ex.: \"127.0.0.1:5440:5432\").\n"
            + SERVICOS_VAZIO + "\n";
    }

    /** Os rótulos e o nome da rede do compose, que o criador (e a importação) criam antes do primeiro {@code up}. */
    public static String redeDoProjeto(String nome) {
        return nome + "_default";
    }
}
