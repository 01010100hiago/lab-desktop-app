package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.CenarioDeSftp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Põe uma pasta de projeto de exemplo (a forma do lab) numa raiz de servidor de teste. */
final class CenarioDeSftpAjuda {

    private CenarioDeSftpAjuda() { }

    static void popular(Path raiz) throws IOException {
        String o = CenarioDeSftp.ORBITA.substring(1);
        Files.createDirectories(raiz.resolve(o + "/src/componentes"));
        Files.createDirectories(raiz.resolve(o + "/logs"));
        Files.createDirectories(raiz.resolve(o + "/docs"));
        Files.createDirectories(raiz.resolve(o + "/node_modules"));
        Files.createDirectories(raiz.resolve("srv/lab/projects/bazar"));
        Files.createDirectories(raiz.resolve("srv/lab/projects/vendas"));
        Files.writeString(raiz.resolve(o + "/README.md"), "# Órbita\n\nPainel de acompanhamento das entregas.\n");
        Files.writeString(raiz.resolve(o + "/docker-compose.yml"), "services:\n  web:\n    image: orbita-web:latest\n    ports:\n      - \"3100:3000\"\n    env_file: .env\n    restart: unless-stopped\n");
        Files.writeString(raiz.resolve(o + "/package.json"), "{\n  \"name\": \"orbita\",\n  \"version\": \"2.4.1\"\n}\n");
        Files.writeString(raiz.resolve(o + "/.env"), "PORTA=3000\nSEGREDO=trocar-isto\n");
        Files.writeString(raiz.resolve(o + "/.gitignore"), "node_modules\n.env\n");
        Files.writeString(raiz.resolve(o + "/Dockerfile"), "FROM node:20-alpine\nWORKDIR /app\nCOPY . .\nCMD [\"node\", \"src/servidor.js\"]\n");
        Files.writeString(raiz.resolve(o + "/src/servidor.js"), "const http = require('http');\nhttp.createServer((q, r) => r.end('ok')).listen(3000);\n");
        Files.writeString(raiz.resolve(o + "/src/componentes/cartao.js"), "export const cartao = (t) => `<div>${t}</div>`;\n");
        Files.writeString(raiz.resolve(o + "/logs/app.log"), "2026-10-24 17:55:01 INFO  no ar em 3000\n".repeat(40));
        Files.writeString(raiz.resolve(o + "/docs/arquitetura.md"), "# Arquitetura\n\nUm servidor, um banco.\n");
        Files.write(raiz.resolve(o + "/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13});
    }
}
