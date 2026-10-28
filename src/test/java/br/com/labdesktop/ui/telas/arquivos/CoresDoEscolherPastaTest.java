package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** O diálogo "Mover para…" (é do pacote dos arquivos, fora do catálogo dos outros diálogos) nos dois temas, montado e trocado, com a borda dele lida do token. */
@ExtendWith(VerificaEdt.class)
class CoresDoEscolherPastaTest {

    @Test
    void oDialogoMoverParaEnxergaSeNosDoisTemasMontadoETrocado() {
        List<String> ruins = new ArrayList<>();
        int medidas = 0;
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(inicial);
            DialogoEscolherPasta d = Visual.naEdt(() -> new DialogoEscolherPasta("3 itens", "/srv/a", "/srv", "srv", new ArvoreDePastas.Carregador() {
                @Override
                public void carregar(String caminho, Consumer<List<br.com.labdesktop.arquivos.EntradaRemota>> aoChegar, Consumer<br.com.labdesktop.arquivos.ErroSftp> aoFalhar) {
                    aoChegar.accept(List.of());
                }

                @Override
                public void escolheuPasta(String caminho) { }
            }, c -> { }));
            JRootPane raiz = Visual.naEdt(() -> {
                JRootPane r = new JRootPane();
                r.setContentPane(d.raiz());
                if (d.botaoDoEnter() != null) {
                    r.setDefaultButton(d.botaoDoEnter());
                }
                return r;
            });
            int w = Math.max(560, Visual.naEdt(() -> d.raiz().getPreferredSize().width));
            int h = Visual.alturaPreferida(d.raiz(), w) + 20;
            for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                if (agora != inicial) {
                    Visual.tema(agora);
                    Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(raiz));
                }
                for (Medida m : Visual.naEdt(() -> AuditoriaDeCores.auditar(raiz, w, h, true))) {
                    medidas++;
                    if (m.falhou()) {
                        ruins.add(agora + " (de " + inicial + ") | " + m);
                    }
                }
            }
        }
        assertTrue(medidas > 20, "poucas medidas: " + medidas);
        assertTrue(ruins.isEmpty(), String.join("\n", ruins));
    }
}
