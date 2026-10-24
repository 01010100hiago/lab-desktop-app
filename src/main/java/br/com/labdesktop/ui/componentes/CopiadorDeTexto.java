package br.com.labdesktop.ui.componentes;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copia um texto COMUM (a chave pública, um comando, a impressão digital de um servidor) para a área de transferência.
 * Segredo NÃO passa por aqui: para a chave do 2FA existe {@link CopiaTemporaria}, que a tira de lá depois.
 *
 * <p>Existe separado do botão para os testes trocarem a área de transferência do sistema (que não existe sem janela) por
 * uma de mentira. A área só é tocada na hora de copiar.
 */
public final class CopiadorDeTexto {

    private static final Logger LOG = LoggerFactory.getLogger(CopiadorDeTexto.class);

    private final Supplier<Clipboard> area;

    /** @param area de onde tirar a área de transferência, na hora de copiar */
    public CopiadorDeTexto(Supplier<Clipboard> area) {
        this.area = area;
    }

    /** O de produção: a área de transferência do sistema. */
    public static CopiadorDeTexto doSistema() {
        return new CopiadorDeTexto(() -> Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    /** @return {@code false} se a área de transferência não deixou (nada foi copiado) */
    public boolean copiar(String texto) {
        try {
            area.get().setContents(new StringSelection(texto), null);
            return true;
        } catch (IllegalStateException | HeadlessException e) {
            LOG.warn("Não consegui usar a área de transferência: {}", e.toString());
            return false;
        }
    }
}
