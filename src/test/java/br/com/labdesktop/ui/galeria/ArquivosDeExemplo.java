package br.com.labdesktop.ui.galeria;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A pasta de um projeto de exemplo no disco do servidor de teste (o SFTP a mostra como {@code /srv/lab/projects/loja-web}): só arquivos inventados. */
final class ArquivosDeExemplo {

    static final String PASTA = "/srv/lab/projects/loja-web";

    private ArquivosDeExemplo() { }

    static void popular(Path raiz) throws IOException {
        Path projetos = raiz.resolve("srv/lab/projects");
        for (String outro : new String[] {"painel-vendas", "app-loja", "api-pedidos", "blog"}) {
            Files.createDirectories(projetos.resolve(outro));
        }
        Path p = projetos.resolve("loja-web");
        for (String pasta : new String[] {"src", "public", "tests", "docs", "logs"}) {
            Files.createDirectories(p.resolve(pasta));
        }
        Files.writeString(p.resolve("src/servidor.js"), "const http = require('http');\nhttp.createServer((q, r) => r.end('ok')).listen(3000);\n");
        escrever(p.resolve("README.md"), "# loja-web\n\nLoja de exemplo: catálogo, carrinho e pedidos.\n", 2_800, 3);
        escrever(p.resolve("Dockerfile"), "FROM node:22-alpine\nWORKDIR /app\nCOPY . .\nCMD [\"node\", \"src/servidor.js\"]\n", 240, 9);
        escrever(p.resolve("docker-compose.yml"), "services:\n  web:\n    build: .\n    ports:\n      - \"3000:3000\"\n  db:\n    image: postgres:16-alpine\n", 910, 1);
        escrever(p.resolve("package.json"), "{\n  \"name\": \"loja-web\",\n  \"version\": \"1.8.2\"\n}\n", 1_350, 5);
        escrever(p.resolve("package-lock.json"), "{\n  \"lockfileVersion\": 3\n}\n", 86_400, 12);
        escrever(p.resolve(".env.example"), "PORTA=3000\nBANCO_URL=postgres://usuario:senha@db/loja\n", 120, 30);
        escrever(p.resolve(".gitignore"), "node_modules\n.env\nlogs\n", 40, 30);
        escrever(p.resolve("logs/app.log"), "no ar em 3000\n", 52_000, 0);
        Instant agora = Instant.now();
        for (String pasta : new String[] {"src", "public", "tests", "docs", "logs"}) {
            tempo(p.resolve(pasta), agora.minus(pasta.length() + 1L, ChronoUnit.DAYS));
        }
    }

    /** Escreve o texto e completa com comentários até o tamanho pedido; a data é de {@code dias} dias atrás. */
    private static void escrever(Path arquivo, String texto, int bytes, int dias) throws IOException {
        StringBuilder sb = new StringBuilder(texto);
        while (sb.length() < bytes) {
            sb.append(sb.length() % 2 == 0 ? "\n" : " ");
        }
        Files.writeString(arquivo, sb.substring(0, Math.max(bytes, texto.length())));
        tempo(arquivo, Instant.now().minus(dias, ChronoUnit.DAYS).minus(dias * 37L, ChronoUnit.MINUTES));
    }

    private static void tempo(Path p, Instant quando) throws IOException {
        Files.setLastModifiedTime(p, FileTime.from(quando));
    }
}
