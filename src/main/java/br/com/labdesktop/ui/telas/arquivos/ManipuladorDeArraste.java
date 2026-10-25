package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.EntradaRemota;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.TransferHandler;

/**
 * Arrastar e soltar na tabela e na árvore, de duas origens:
 * <ul>
 *   <li><b>arquivos do Finder</b> ({@code javaFileListFlavor}): viram um ENVIO para a pasta sobre a qual foram soltos (a linha
 *       de uma pasta, o nó da árvore) ou, soltos num espaço vazio da tabela, para a pasta atual;</li>
 *   <li><b>itens do próprio servidor</b> (arrastados da tabela ou da árvore): viram um MOVER para a pasta de destino.</li>
 * </ul>
 * Só vale com as ações de escrita ligadas. O que sai da tabela para fora do app não leva nem o caminho em texto: o único formato
 * exportado é o interno ({@link #ENTRADAS}), que outro programa não lê.
 */
final class ManipuladorDeArraste extends TransferHandler {

    private static final long serialVersionUID = 1L;

    /** O formato interno: uma lista de {@link EntradaRemota} dentro desta JVM. */
    static final DataFlavor ENTRADAS = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.util.List", "Itens do servidor");

    /** Quem age. */
    interface Alvo {
        boolean escritaLiberada();

        /** A pasta do servidor sob o ponto de soltura, ou a atual se o ponto é um espaço vazio; {@code null} se não vale soltar ali. */
        String pastaDeSoltura(TransferSupport suporte);

        List<EntradaRemota> selecionadasParaArrastar();

        void soltarArquivosLocais(List<Path> arquivos, String pasta);

        void soltarEntradas(List<EntradaRemota> entradas, String pasta);
    }

    private final Alvo alvo;

    ManipuladorDeArraste(Alvo alvo) {
        this.alvo = alvo;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return alvo.escritaLiberada() ? MOVE : NONE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        List<EntradaRemota> sel = alvo.selecionadasParaArrastar();
        if (sel.isEmpty() || !alvo.escritaLiberada()) {
            return null;
        }
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {ENTRADAS};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor f) {
                return ENTRADAS.equals(f);
            }

            @Override
            public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) {
                    throw new UnsupportedFlavorException(f);
                }
                return sel;
            }
        };
    }

    @Override
    public boolean canImport(TransferSupport s) {
        if (!alvo.escritaLiberada()) {
            return false;
        }
        boolean arquivos = s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        boolean entradas = s.isDataFlavorSupported(ENTRADAS);
        if (!arquivos && !entradas) {
            return false;
        }
        return alvo.pastaDeSoltura(s) != null;
    }

    @Override
    public boolean importData(TransferSupport s) {
        if (!canImport(s)) {
            return false;
        }
        String pasta = alvo.pastaDeSoltura(s);
        try {
            if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> arquivos = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> caminhos = new ArrayList<>();
                for (File f : arquivos) {
                    caminhos.add(f.toPath());
                }
                alvo.soltarArquivosLocais(caminhos, pasta);
                return true;
            }
            @SuppressWarnings("unchecked")
            List<EntradaRemota> entradas = (List<EntradaRemota>) s.getTransferable().getTransferData(ENTRADAS);
            alvo.soltarEntradas(entradas, pasta);
            return true;
        } catch (UnsupportedFlavorException | java.io.IOException | ClassCastException e) {
            return false;
        }
    }
}
