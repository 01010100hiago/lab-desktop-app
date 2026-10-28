package br.com.labdesktop.arquivos;

import br.com.labdesktop.util.CaminhosApp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * O que o gerenciador de arquivos precisa do Mac, entregue pronto pela aplicação: a memória dos últimos caminhos e a pasta das
 * cópias de segurança. Um pacote só para não aumentar a lista de parâmetros de cada tela entre a janela e a aba.
 *
 * @param memoria          último caminho por servidor e por projeto, e a raiz dos projetos
 * @param pastaDeBackups   onde ficam as cópias de segurança locais dos arquivos editados
 * @param pastasDoApp      as pastas do próprio app neste computador (dados com o cofre, logs): o gerenciador de arquivos de "Este computador" as
 *                         trata como intocáveis, com tudo o que há dentro
 */
public record ServicosDeArquivos(MemoriaDeCaminhos memoria, Path pastaDeBackups, java.util.List<Path> pastasDoApp) {

    public ServicosDeArquivos {
        pastasDoApp = pastasDoApp == null ? java.util.List.of() : java.util.List.copyOf(pastasDoApp);
    }

    /** Sem pastas do app a proteger (telas montadas sem o app inteiro, testes). */
    public ServicosDeArquivos(MemoriaDeCaminhos memoria, Path pastaDeBackups) {
        this(memoria, pastaDeBackups, java.util.List.of());
    }

    /** Os de produção: tudo na pasta de dados do app. */
    public static ServicosDeArquivos de(CaminhosApp caminhos) {
        return new ServicosDeArquivos(MemoriaDeCaminhos.doArquivo(caminhos.arquivoMemoriaDeArquivos()),
            caminhos.pastaBackupsDeArquivos(), java.util.List.of(caminhos.pastaDados(), caminhos.pastaLogs()));
    }

    /** A pasta onde o seletor de "Baixar" começa: Downloads, na pasta do usuário, ou a própria pasta do usuário se não houver Downloads. */
    public static Path pastaDeDownloadsPadrao() {
        Path casa = Path.of(System.getProperty("user.home"));
        Path downloads = casa.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : casa;
    }

    /** Sem pasta de dados (telas montadas sem o app inteiro, testes): memória volátil e backups numa pasta temporária. */
    public static synchronized ServicosDeArquivos descartaveis() {
        if (temporaria == null) {
            try {
                temporaria = Files.createTempDirectory("lab-desktop-backups-arquivos");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return new ServicosDeArquivos(MemoriaDeCaminhos.emMemoria(), temporaria);
    }

    private static Path temporaria;
}
