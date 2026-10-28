package br.com.labdesktop.local;

import java.io.IOException;

/**
 * Uma LEITURA do Docker saiu com status diferente de 0 (Docker parado, sem permissão, erro do comando): o estado é DESCONHECIDO. Nunca vale
 * como "não existe": tomar a falha por lista vazia faria o app dizer "removido" sem ter removido, ou criar o que já existe.
 */
final class LeituraDoDockerFalhouException extends IOException {

    private static final long serialVersionUID = 1L;

    LeituraDoDockerFalhouException(String mensagem) {
        super(mensagem);
    }
}
