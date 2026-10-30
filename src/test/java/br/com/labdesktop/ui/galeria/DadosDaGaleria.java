package br.com.labdesktop.ui.galeria;

import br.com.labdesktop.git.ArquivoDoCommit;
import br.com.labdesktop.git.Branch;
import br.com.labdesktop.git.Commit;
import br.com.labdesktop.git.DetalheDoCommit;
import br.com.labdesktop.git.DiffDoArquivo;
import br.com.labdesktop.git.InterpretadorDeDiff;
import br.com.labdesktop.git.Pendencia;
import br.com.labdesktop.teste.LeitorDeGitFalso;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Os dados INVENTADOS das imagens da galeria do README: o projeto {@code loja-web} (detalhe, logs, processos, arquivos) e o histórico do Git (autores Ana Souza e Beto Lima, mensagens e
 * hashes de 40 caracteres escritos aqui, datas relativas ao relógio do teste). Nada vem de um servidor, de uma conta ou de um repositório de verdade.
 */
final class DadosDaGaleria {

    static final String PROJETO = "loja-web";
    static final String PASTA = "/srv/lab/projects/" + PROJETO;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(java.time.ZoneId.systemDefault());

    private DadosDaGaleria() { }

    private static String iso(Instant i) {
        return ISO.format(i);
    }

    // ---------------------------------------------------------------------------------------------
    // O projeto
    // ---------------------------------------------------------------------------------------------

    /** O que o agente diria de {@code GET /api/projetos/loja-web}. */
    static String detalheDoProjeto(Instant agora) {
        return detalheDoProjeto(agora, true);
    }

    /** {@code comGit} falso: o agente não vê Git nenhum na pasta do projeto ({@code "git": null}). */
    static String detalheDoProjeto(Instant agora, boolean comGit) {
        StringBuilder backups = new StringBuilder();
        long[] tamanhos = {43_200, 41_900, 40_600, 39_300, 38_000};
        for (int i = 0; i < tamanhos.length; i++) {
            Instant quando = backupDoDia(agora, i);
            backups.append(i > 0 ? "," : "").append("{\"arquivo\":\"").append(PROJETO).append('-').append(DIA.format(quando)).append(".sql.gz\",\"tamanho\":")
                .append(tamanhos[i]).append(",\"quando\":\"").append(iso(quando)).append("\"}");
        }
        String[][] containers = {
            {"loja-web-web-1", "running", "Up 3 hours", "127.0.0.1:3040->3000/tcp", "loja-web-web:latest", "1.4", "182452224", "12MB / 9MB", "23"},
            {"loja-web-api-1", "running", "Up 3 hours", "127.0.0.1:3041->4000/tcp", "loja-web-api:latest", "2.1", "164495360", "9MB / 7MB", "19"},
            {"loja-web-worker-1", "running", "Up 3 hours", "", "loja-web-worker:latest", "0.3", "96468992", "3MB / 2MB", "11"},
            {"loja-web-banco-1", "running", "Up 3 hours", "127.0.0.1:5440->5432/tcp", "postgres:16-alpine", "0.8", "143654912", "8MB / 11MB", "9"}};
        StringBuilder cs = new StringBuilder();
        for (int i = 0; i < containers.length; i++) {
            String[] c = containers[i];
            cs.append(i > 0 ? "," : "").append("{\"nome\":\"").append(c[0]).append("\",\"estado\":\"").append(c[1]).append("\",\"status\":\"").append(c[2])
                .append("\",\"portas\":\"").append(c[3]).append("\",\"imagem\":\"").append(c[4]).append("\",\"criado\":\"").append(iso(agora.minus(Duration.ofHours(3))))
                .append("\",\"cpu\":").append(c[5]).append(",\"mem\":").append(c[6]).append(",\"memLimite\":8228083597,\"net\":\"").append(c[7])
                .append("\",\"bloco\":\"4MB / 0B\",\"pids\":").append(c[8]).append('}');
        }
        return "{\"nome\":\"loja-web\","
            + "\"descricao\":\"Loja virtual de exemplo: vitrine, carrinho e pedidos.\","
            + "\"readme\":\"# loja-web\\n\\nLoja virtual de exemplo, usada só nas imagens do README.\\n\\n## Serviços\\n- `web` — vitrine e carrinho\\n- `api` — pedidos e estoque\\n"
            + "- `worker` — e-mails e notas fiscais em segundo plano\\n- `banco` — Postgres\\n\","
            + "\"status\":\"# STATUS — loja-web\\n\\n- Carrinho: pronto\\n- Cálculo do frete: em revisão\\n- Página de pedidos: em andamento\\n\","
            + "\"composes\":[{\"arquivo\":\"docker-compose.yml\",\"projetoCompose\":\"loja-web\"}],"
            + "\"containers\":[" + cs + "],"
            + "\"urls\":[\"lab-loja-web.example.com\"],"
            + "\"sessao\":{\"aberta\":false,\"container\":null,\"desde\":null,\"url\":\"/sessao/loja-web\"},"
            + "\"git\":" + (comGit ? "{\"branch\":\"main\",\"commit\":\"c4e91fa\",\"quando\":\"" + iso(agora.minus(Duration.ofHours(3))) + "\",\"sujo\":true}" : "null") + ","
            + "\"backups\":[" + backups + "],"
            + "\"tamanho\":418381824,\"mexido\":" + agora.minus(Duration.ofHours(3)).toEpochMilli() + ",\"acessoDocker\":true,"
            + "\"redes\":[\"loja-web_default\"],"
            + "\"arquivos\":[{\"nome\":\"app\",\"tipo\":\"pasta\"},{\"nome\":\"api\",\"tipo\":\"pasta\"},{\"nome\":\"docs\",\"tipo\":\"pasta\"},{\"nome\":\"README.md\",\"tipo\":\"arquivo\"},"
            + "{\"nome\":\"docker-compose.yml\",\"tipo\":\"arquivo\"},{\"nome\":\"package.json\",\"tipo\":\"arquivo\"}],"
            + "\"limite\":{\"teto_pct\":20,\"usado_pp\":1.96,\"estado\":\"ok\",\"liberado\":false,\"pausa\":null,\"zera_em\":\"" + iso(agora.plus(Duration.ofDays(5)))
            + "\",\"desde\":\"" + iso(agora.minus(Duration.ofDays(2))) + "\"}}";
    }

    /** O backup noturno de {@code i} dias atrás: sempre às 03:00:01 (UTC); o de "hoje" só conta se a hora já passou. */
    private static Instant backupDoDia(Instant agora, int i) {
        // 03:00:01 no horário do computador, o mesmo que a coluna "Quando" mostra (o nome do arquivo e a hora da lista batem em qualquer fuso)
        Instant hoje = agora.atZone(java.time.ZoneId.systemDefault()).truncatedTo(java.time.temporal.ChronoUnit.DAYS).plusSeconds(3 * 3600 + 1).toInstant();
        return (hoje.isAfter(agora) ? hoje.minus(Duration.ofDays(1)) : hoje).minus(Duration.ofDays(i));
    }

    /** Os logs do container: linhas de um servidor web de loja, com alguns avisos e um erro, uma por poucos segundos. */
    static String logs(Instant agora) {
        String[] modelos = {
            "GET /produtos 200 14ms", "GET /produtos/42 200 9ms", "POST /carrinho 201 31ms", "GET /carrinho 200 8ms", "GET /saude 200 1ms",
            "POST /pedidos 201 88ms", "GET /pedidos/1187 200 22ms", "estoque: produto 42 com 12 unidades", "frete: cep 01001-000 calculado em 41ms",
            "cache: acerto em produtos:lista", "GET /produtos?categoria=camisetas 200 17ms", "GET /favicon.ico 304 1ms"};
        StringBuilder sb = new StringBuilder();
        int total = 140;
        for (int i = 0; i < total; i++) {
            Instant quando = agora.minusSeconds((long) (total - i) * 7);
            String texto = modelos[(i * 5) % modelos.length];
            if (i == total - 32) {
                texto = "WARN  estoque baixo: produto 17 com 2 unidades";
            } else if (i == total - 19) {
                texto = "ERROR falha ao enviar o e-mail do pedido 1187: tempo esgotado";
            } else if (i == total - 18) {
                texto = "WARN  nova tentativa de envio do pedido 1187 em 30 s";
            }
            sb.append(ISO.format(quando).replace("Z", "000000Z")).append(' ').append(texto).append('\n');
        }
        return sb.toString();
    }

    /** {@code GET /api/servidor}: o do mock do front com a lista de processos trocada pelos do projeto (e dois do servidor). */
    static String servidorComProcessos(String baseDoMock) {
        try {
            ObjectNode raiz = (ObjectNode) Json.mapper().readTree(baseDoMock);
            ArrayNode processos = raiz.putArray("processos");
            Object[][] linhas = {
                {4120, "node", "1000", 18.4, 164495360L, "loja-web-api-1"},
                {4088, "node", "1000", 9.7, 182452224L, "loja-web-web-1"},
                {812, "dockerd", "root", 5.3, 210763776L, null},
                {3951, "postgres", "999", 6.2, 143654912L, "loja-web-banco-1"},
                {4190, "node", "1000", 3.1, 96468992L, "loja-web-worker-1"},
                {2011, "cloudflared", "root", 1.2, 55050240L, null},
                {1, "systemd", "root", 0.1, 10485760L, null}};
            for (Object[] l : linhas) {
                ObjectNode p = processos.addObject();
                p.put("pid", (Integer) l[0]);
                p.put("nome", (String) l[1]);
                p.put("usuario", (String) l[2]);
                p.put("cpu", (Double) l[3]);
                p.put("mem", (Long) l[4]);
                if (l[5] == null) {
                    p.putNull("container");
                } else {
                    p.put("container", (String) l[5]);
                }
            }
            return Json.mapper().writeValueAsString(raiz);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code GET /api/servidor/detalhe}: o sintético com os processos dizendo de qual projeto são (o que a aba Processos do projeto usa para filtrar). */
    static String detalheDoServidorComProcessos(String base) {
        try {
            ObjectNode raiz = (ObjectNode) Json.mapper().readTree(base);
            ArrayNode processos = raiz.putArray("processos");
            Object[][] linhas = {
                {4120, "node", "1000", "loja-web-api-1", "loja-web"}, {4088, "node", "1000", "loja-web-web-1", "loja-web"},
                {3951, "postgres", "999", "loja-web-banco-1", "loja-web"}, {4190, "node", "1000", "loja-web-worker-1", "loja-web"},
                {812, "dockerd", "root", null, "(servidor)"}};
            for (Object[] l : linhas) {
                ObjectNode p = processos.addObject();
                p.put("pid", (Integer) l[0]);
                p.put("nome", (String) l[1]);
                p.put("usuario", (String) l[2]);
                if (l[3] == null) {
                    p.putNull("container");
                } else {
                    p.put("container", (String) l[3]);
                }
                p.put("projeto", (String) l[4]);
            }
            return Json.mapper().writeValueAsString(raiz);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code GET /api/limites}: o do mock do front com o consumo do plano em níveis calmos e nenhum projeto pausado (a barra lateral mostra o percentual da semana). */
    static String limites(String baseDoMock) {
        try {
            ObjectNode raiz = (ObjectNode) Json.mapper().readTree(baseDoMock);
            ((ObjectNode) raiz.get("fonte")).put("projeto", PROJETO);
            ((ObjectNode) raiz.get("cinco_horas")).put("usado_pct", 18);
            ((ObjectNode) raiz.get("semana")).put("usado_pct", 34);
            ((ObjectNode) raiz.get("freio")).put("ativo", false);
            raiz.putArray("pausados");
            ObjectNode porProjeto = raiz.putObject("por_projeto");
            ObjectNode loja = porProjeto.putObject(PROJETO);
            loja.put("usado_pp", 1.96);
            loja.put("teto_pct", 20);
            loja.put("estado", "ok");
            return Json.mapper().writeValueAsString(raiz);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A pasta do projeto no "servidor" (a raiz do SFTP do teste): a forma que o lab tem, com arquivos de uma loja de exemplo. */
    static void criarArquivosDoProjeto(Path raizDoServidor, Instant agora) throws IOException {
        Path p = raizDoServidor.resolve(PASTA.substring(1));
        for (String pasta : List.of("src/rotas", "src/componentes", "public/imagens", "docs", "scripts", "testes", "node_modules")) {
            Files.createDirectories(p.resolve(pasta));
        }
        escrever(p, "README.md", "# loja-web\n\nLoja virtual de exemplo.\n\n## Como rodar\n\n```\ndocker compose up -d\n```\n", atras(agora, 9, 340));
        escrever(p, "docker-compose.yml", "services:\n  web:\n    image: loja-web-web:latest\n    ports:\n      - \"3040:3000\"\n    restart: unless-stopped\n"
            + "  api:\n    image: loja-web-api:latest\n    ports:\n      - \"3041:4000\"\n    restart: unless-stopped\n", atras(agora, 4, 95));
        escrever(p, "package.json", "{\n  \"name\": \"loja-web\",\n  \"version\": \"1.8.0\",\n  \"scripts\": { \"start\": \"node src/servidor.js\" }\n}\n", atras(agora, 6, 710));
        escrever(p, "Dockerfile", "FROM node:22-alpine\nWORKDIR /app\nCOPY . .\nCMD [\"node\", \"src/servidor.js\"]\n", atras(agora, 30, 15));
        escrever(p, ".env.example", "PORTA=3000\nBANCO_URL=postgres://usuario@banco/loja\n", atras(agora, 30, 15));
        escrever(p, ".gitignore", "node_modules\n.env\nlogs\n", atras(agora, 30, 15));
        escrever(p, "src/servidor.js", "const http = require('http');\n\n// sobe a vitrine na porta do ambiente\nconst porta = Number(process.env.PORTA) || 3000;\n"
            + "http.createServer((req, res) => {\n  res.end('ok');\n}).listen(porta);\n", atras(agora, 1, 395));
        escrever(p, "src/rotas/pedidos.js", "exports.listar = (req, res) => res.json([]);\n", atras(agora, 0, 305));
        escrever(p, "src/rotas/frete.js", "exports.calcular = (cep) => (cep.startsWith('0') ? 12.5 : 19.9);\n", atras(agora, 0, 190));
        escrever(p, "src/componentes/carrinho.js", "export const carrinho = (itens) => itens.length;\n", atras(agora, 2, 640));
        escrever(p, "docs/arquitetura.md", "# Arquitetura\n\nVitrine, API, fila e banco.\n", atras(agora, 12, 215));
        escrever(p, "scripts/backup.sh", "#!/bin/sh\npg_dump loja | gzip > backups/loja.sql.gz\n", atras(agora, 20, 480));
        escrever(p, "testes/frete.test.js", "test('frete', () => {});\n", atras(agora, 3, 770));
        Files.write(p.resolve("public/imagens/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1});
        Files.setLastModifiedTime(p.resolve("public/imagens/logo.png"), java.nio.file.attribute.FileTime.from(atras(agora, 40, 250)));
        int n = 0;
        for (String pasta : List.of("src", "docs", "scripts", "testes", "public", "node_modules")) {
            Files.setLastModifiedTime(p.resolve(pasta), java.nio.file.attribute.FileTime.from(atras(agora, 1 + n, 330 + 77 * n)));
            n++;
        }
        Files.createDirectories(raizDoServidor.resolve("srv/lab/projects/painel-vendas"));
        Files.createDirectories(raizDoServidor.resolve("srv/lab/projects/api-pedidos"));
    }

    private static Instant atras(Instant agora, int dias, int minutos) {
        return agora.minus(Duration.ofDays(dias)).minus(Duration.ofMinutes(minutos));
    }

    private static void escrever(Path base, String nome, String texto, Instant quando) throws IOException {
        Path arquivo = base.resolve(nome);
        Files.createDirectories(arquivo.getParent());
        Files.writeString(arquivo, texto, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(arquivo, java.nio.file.attribute.FileTime.from(quando));
    }

    // ---------------------------------------------------------------------------------------------
    // O Git
    // ---------------------------------------------------------------------------------------------

    private static final String[] HASHES = {
        "c4e91fa7b30d28e65a914f0c3d7be2a1086f95d4", "8a02d5e1f7c64b93a0e8d12f5b6c47390a1de8f2", "e17b6c40a9d3582f1c7ea04b95d6f83a2c0714be",
        "5d3f9a82c1e07b46d8a5f290e3b17c64a98d0f13", "b91e4d07a6c25f38e0d19b7a4c8f62d35e1a09c7", "2f6a8c13d5e90b74a1c38e6f0d92b5a7c4e18f60",
        "73c0e5b9a2d146f8e07a3c91b5d84f2a6e09c1d3", "d84a1f67c0e3925b8a6d14f3e7c09b52a1d8e6f4", "0a9d7e35c8b1f4602e5a97d3c1b8f40a6e2d9c75",
        "6e2b0f94d1a8c37e5b0f62a9d4c18e73b5a0f9d2", "f1c58a3e07d9b4620a8e5c1d3f97b6a40e2c85d1", "39b7d0e4a6f1c8532e9a0b7d5c4f163a8e0d2b96"};

    private static final String[] AUTORES = {"Ana Souza", "Beto Lima"};
    private static final String[] EMAILS = {"ana@example.com", "beto@example.com"};
    private static final String[] ASSUNTOS = {
        "Corrige o cálculo do frete", "Adiciona a página de pedidos", "Ajusta o total do carrinho", "Atualiza as dependências do projeto",
        "Mostra o estoque na página do produto", "Troca a imagem da vitrine", "Adiciona o botão de repetir pedido", "Corrige o título da página inicial",
        "Cria a rota de pedidos da API", "Melhora a mensagem de CEP inválido", "Adiciona testes do frete", "Primeira versão da loja"};

    private static final String[] CORPOS = {
        "O frete passa a vir da tabela por região do CEP e do peso do pedido. Um CEP fora da área de entrega gera uma mensagem clara.",
        "A lista de pedidos mostra número, data e total. A paginação fica para a próxima mudança.",
        "O total do carrinho agora soma preço vezes quantidade de cada item.",
        "Sobe as versões do Node e do Postgres da imagem e refaz o arquivo de dependências.",
        "A página do produto avisa quando restam poucas unidades no estoque.",
        "Troca a imagem principal da vitrine por uma versão mais leve.",
        "Quem já comprou pode repetir o pedido com um clique.",
        "O título da página inicial estava com o nome antigo da loja.",
        "Cria a rota que lista os pedidos de um cliente.",
        "A mensagem de CEP inválido passa a dizer o formato esperado.",
        "Cobre os cálculos de frete com testes automáticos.",
        "Estrutura inicial: vitrine, carrinho e API de pedidos."};

    /** Um commit inventado: o {@code i}-ésimo da lista (0 é o mais novo), com data relativa a {@code agora}. */
    static Commit commit(int i, Instant agora) {
        String hash = HASHES[i % HASHES.length];
        String pai = HASHES[(i + 1) % HASHES.length];
        long minutos = switch (i) {
            case 0 -> 190;
            case 1 -> 60 * 8;
            case 2 -> 60 * 26;
            default -> 60L * 24 * (i + 1);
        };
        return new Commit(hash, hash.substring(0, 7), AUTORES[i % 2], EMAILS[i % 2], agora.minus(Duration.ofMinutes(minutos)), List.of(pai), ASSUNTOS[i % ASSUNTOS.length], 1 + i % 3);
    }

    static List<Commit> commits(int n, Instant agora, int deslocamento) {
        List<Commit> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(commit(i + deslocamento, agora));
        }
        return l;
    }

    /** Um leitor de mentira com {@code n} commits inventados, branches e o detalhe de cada commit (arquivos mudados). */
    static LeitorDeGitFalso leitorComHistorico(int n, Instant agora, int alteracoes) {
        return leitorComHistorico(n, agora, alteracoes, 0);
    }

    /** Como {@link #leitorComHistorico(int, Instant, int)}, mas começando do commit {@code deslocamento} da história (repositórios diferentes têm commits diferentes). */
    static LeitorDeGitFalso leitorComHistorico(int n, Instant agora, int alteracoes, int deslocamento) {
        LeitorDeGitFalso l = new LeitorDeGitFalso();
        l.commits = commits(n, agora, deslocamento);
        l.resumo = new br.com.labdesktop.git.ResultadoDoGit.Ok<>(LeitorDeGitFalso.comGit("main", l.commits.get(0), alteracoes));
        for (int k = 0; k < l.commits.size(); k++) {
            Commit c = l.commits.get(k);
            String corpo = c.assunto() + "\n\n" + CORPOS[(k + deslocamento) % CORPOS.length];
            l.detalhes.put(c.hash(), LeitorDeGitFalso.detalhe(c, corpo,
                new ArquivoDoCommit("src/rotas/frete.js", null, 10, 4), new ArquivoDoCommit("testes/frete.test.js", null, 14, 0),
                new ArquivoDoCommit("public/imagens/logo.png", null, -1, -1), new ArquivoDoCommit("docs/arquitetura.md", "docs/estrutura.md", 2, 1)));
        }
        l.branches = List.of(new Branch("main", true, "c4e91fa"), new Branch("feature/pagina-de-pedidos", false, "8a02d5e"), new Branch("correcao/frete", false, "e17b6c4"));
        l.diffs = chave -> new br.com.labdesktop.git.ResultadoDoGit.Ok<>(chave.startsWith("pendente:") ? diffDeAlteracao() : diffDoFrete());
        return l;
    }

    /** O diff do cálculo do frete, com dois trechos: contexto, linhas tiradas (-) e acrescentadas (+). */
    static DiffDoArquivo diffDoFrete() {
        return InterpretadorDeDiff.interpretar("diff --git a/src/rotas/frete.js b/src/rotas/frete.js\n"
            + "index 3b18e5c..9f2a7d1 100644\n"
            + "--- a/src/rotas/frete.js\n"
            + "+++ b/src/rotas/frete.js\n"
            + "@@ -1,9 +1,15 @@\n"
            + " const TABELA = require('./tabela-de-frete');\n"
            + " \n"
            + "-exports.calcular = (cep) => {\n"
            + "-  return 10;\n"
            + "-};\n"
            + "+exports.calcular = (cep, peso) => {\n"
            + "+  const regiao = cep.slice(0, 1);\n"
            + "+  const base = TABELA[regiao];\n"
            + "+  if (base === undefined) {\n"
            + "+    throw new Error('CEP fora da área de entrega');\n"
            + "+  }\n"
            + "+  return base + peso * 0.8;\n"
            + "+};\n"
            + " \n"
            + " exports.prazo = (cep) => (cep.startsWith('0') ? 2 : 5);\n"
            + "@@ -22,4 +28,5 @@ exports.prazo = (cep) => (cep.startsWith('0') ? 2 : 5);\n"
            + " module.exports = {\n"
            + "   calcular: exports.calcular,\n"
            + "-  prazo: exports.prazo\n"
            + "+  prazo: exports.prazo,\n"
            + "+  tabela: TABELA\n"
            + " };\n", false);
    }

    private static DiffDoArquivo diffDeAlteracao() {
        return InterpretadorDeDiff.interpretar("diff --git a/src/componentes/carrinho.js b/src/componentes/carrinho.js\n"
            + "--- a/src/componentes/carrinho.js\n"
            + "+++ b/src/componentes/carrinho.js\n"
            + "@@ -1,4 +1,8 @@\n"
            + "-export const carrinho = (itens) => itens.length;\n"
            + "+export const carrinho = (itens) => ({\n"
            + "+  quantidade: itens.length,\n"
            + "+  total: itens.reduce((soma, item) => soma + item.preco * item.quantidade, 0),\n"
            + "+});\n"
            + " \n"
            + " export default carrinho;\n", false);
    }

    /** As alterações não commitadas de exemplo (sem nenhum arquivo de segredo). */
    static List<Pendencia> alteracoes() {
        return List.of(
            new Pendencia("src/componentes/carrinho.js", null, Pendencia.Tipo.MODIFICADO, false, true),
            new Pendencia("src/rotas/pedidos.js", null, Pendencia.Tipo.MODIFICADO, true, true),
            new Pendencia("docs/frete.md", null, Pendencia.Tipo.NAO_RASTREADO, false, true),
            new Pendencia("testes/pedidos.test.js", null, Pendencia.Tipo.ADICIONADO, true, false));
    }
}
