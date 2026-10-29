package br.com.labdesktop.git;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Transforma os bytes que o git escreveu em texto SEM quebrar: tenta UTF-8 de verdade e, se os bytes não são UTF-8 válido (um arquivo antigo em Latin-1, por exemplo), lê como Latin-1
 * (que aceita qualquer byte) e avisa, em vez de encher a tela de losangos ou lançar erro.
 */
public final class DecodificadorDeTexto {

    /** O texto e se foi preciso cair para Latin-1. */
    public record Texto(String texto, boolean outraCodificacao) { }

    private DecodificadorDeTexto() { }

    public static Texto decodificar(byte[] bytes) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return new Texto(s, false);
        } catch (CharacterCodingException e) {
            return new Texto(new String(bytes, StandardCharsets.ISO_8859_1), true);
        }
    }
}
