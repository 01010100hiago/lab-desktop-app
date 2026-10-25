package br.com.labdesktop.arquivos;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decide se um arquivo abre no editor: texto é o que não tem byte nulo nos primeiros 8 KB e é UTF-8 válido de ponta a ponta.
 * Também descobre a quebra de linha do arquivo (o {@code JTextArea} guarda tudo com {@code \n}; ao salvar, a quebra original
 * volta, para um arquivo do Windows não virar do Unix só porque o usuário corrigiu uma vírgula).
 */
public final class DetectorDeTexto {

    /** Maior arquivo que o editor abre. */
    public static final int LIMITE = 1024 * 1024;
    private static final int AMOSTRA = 8 * 1024;

    private DetectorDeTexto() { }

    /**
     * O texto de um arquivo, pronto para o editor.
     *
     * @param texto          o conteúdo, com {@code \n} em todas as quebras
     * @param quebraDeLinha  a quebra a devolver ao salvar: {@code "\n"} ou {@code "\r\n"}
     * @param quebrasMistas  o arquivo misturava quebras (as duas, ou {@code \r} sozinho): ao salvar elas serão unificadas
     */
    public record Texto(String texto, String quebraDeLinha, boolean quebrasMistas) { }

    /** @return o texto, ou {@code null} se não é texto (binário, ou não é UTF-8) */
    public static Texto detectar(byte[] bytes) {
        int n = Math.min(bytes.length, AMOSTRA);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        if (s.indexOf('\0') >= 0) {
            return null; // um NUL depois dos 8 KB: também não é texto
        }
        int crlf = 0;
        int lf = 0;
        int cr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        boolean mistas = cr > 0 || (crlf > 0 && lf > 0);
        String quebra = crlf > lf && crlf >= cr ? "\r\n" : "\n";
        String unificado = (crlf > 0 || cr > 0) ? s.replace("\r\n", "\n").replace('\r', '\n') : s;
        return new Texto(unificado, quebra, mistas);
    }

    /** O texto do editor de volta para o que se grava: as quebras originais, UTF-8. */
    public static byte[] paraDisco(String textoDoEditor, String quebraDeLinha) {
        String s = "\r\n".equals(quebraDeLinha) ? textoDoEditor.replace("\n", "\r\n") : textoDoEditor;
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
