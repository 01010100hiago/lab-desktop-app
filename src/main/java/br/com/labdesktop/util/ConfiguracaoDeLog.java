package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Liga o arquivo de log do {@code slf4j-simple} (ARQUITETURA 4.4 passo 2 e 11.3).
 *
 * <p>Precisa rodar ANTES de qualquer logger ser criado: o {@code slf4j-simple} lê as propriedades uma vez.
 * O arquivo é criado já com 0600 e a pasta com 0700, porque o log pode citar nome de servidor e de projeto.
 * O {@code slf4j-simple} não rotaciona: passando de {@link #TAMANHO_PARA_ROTACIONAR} o arquivo vira
 * {@code .1} na partida (uma geração só). Com {@code -Dlabdesktop.debug=true} liga também o log de requisições
 * ({@link LogDeRequisicoes}).
 */
public final class ConfiguracaoDeLog {

    /** 2 MiB, como no ARQUITETURA 11.3. */
    public static final long TAMANHO_PARA_ROTACIONAR = 2L * 1024 * 1024;

    private ConfiguracaoDeLog() { }

    /** Prepara a pasta e o arquivo e aponta o {@code slf4j-simple} para ele. */
    public static void configurar(CaminhosApp caminhos) throws IOException {
        // PRIMEIRA linha, antes de qualquer E/S ou logger: o slf4j-simple lê o logFile uma vez, quando o primeiro logger nasce. Se a pasta falhar, o arquivo simplesmente não é criado
        // (o slf4j-simple cai para a tela), mas o caminho certo já está valendo.
        Path log = caminhos.arquivoLog();
        System.setProperty("org.slf4j.simpleLogger.logFile", log.toString());
        caminhos.prepararPastaDeLogs();
        rotacionarSeGrande(log);
        if (!Files.exists(log)) {
            Permissoes.criarArquivoPrivado(log);
        } else {
            Permissoes.corrigirArquivoSeAberto(log);
        }
        LogDeRequisicoes.ligarSeSolicitado();
    }

    static void rotacionarSeGrande(Path log) throws IOException {
        if (Files.exists(log) && Files.size(log) > TAMANHO_PARA_ROTACIONAR) {
            Path antigo = log.resolveSibling(log.getFileName() + ".1");
            Files.move(log, antigo, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
