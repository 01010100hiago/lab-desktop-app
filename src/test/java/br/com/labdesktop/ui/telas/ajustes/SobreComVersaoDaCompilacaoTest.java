package br.com.labdesktop.ui.telas.ajustes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.InformacoesDoApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A tela Sobre mostra a versão, o commit e a data da compilação, e o "Copiar informações" leva tudo isso sem pasta de usuário, nome de usuário nem endereço IP. */
@ExtendWith(VerificaEdt.class)
class SobreComVersaoDaCompilacaoTest {

    private static final Pattern ENDERECO_IP = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");

    private static InformacoesDoApp info(String commit) {
        return new InformacoesDoApp("Lab Desktop", "0.1.320", commit, "2026-10-30", "0.1.0-dadf957", "dadf957f1f824b16fa1876f74848e33ab4c4a5ae",
            "21.0.12 (Homebrew)", "Mac OS X 15.6", "aarch64", "~/Library/Application Support/LabDesktop");
    }

    private static PainelSobre painel(InformacoesDoApp info, Clipboard area) {
        return Visual.naEdt(() -> new PainelSobre(info, new CopiadorDeTexto(() -> area), new AvisosGravados()));
    }

    private static String textoVisivel(Component c) {
        StringBuilder b = new StringBuilder();
        Visual.naEdt(() -> juntar(c, b));
        return b.toString();
    }

    private static void juntar(Component c, StringBuilder b) {
        if (c instanceof JTextComponent t) {
            b.append(t.getText()).append('\n');
        } else if (c instanceof JLabel l && l.getText() != null) {
            b.append(l.getText()).append('\n');
        } else if (c instanceof AbstractButton a && a.getText() != null) {
            b.append(a.getText()).append('\n');
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                juntar(f, b);
            }
        }
    }

    @Test
    void mostraAVersaoOCommitEADataDaCompilacaoEOCommitAlteradoSemEsconder() {
        Visual.tema(Tema.CLARO);
        for (String commit : new String[] {"a207ad4", "a207ad4-alterado", "desconhecido"}) {
            String texto = textoVisivel(painel(info(commit), new Clipboard("t")));
            assertTrue(texto.contains("Versão do app") && texto.contains("0.1.320"), texto);
            assertTrue(texto.contains("Commit") && texto.contains(commit), commit + "\n" + texto);
            assertTrue(texto.contains("Data da compilação") && texto.contains("2026-10-30"), texto);
        }
    }

    @Test
    void copiarInformacoesLevaVersaoCommitDataJavaESistema() throws Exception {
        Visual.tema(Tema.CLARO);
        Clipboard area = new Clipboard("t");
        PainelSobre p = painel(info("a207ad4-alterado"), area);
        Visual.naEdt(() -> p.botaoCopiarInformacoes().doClick());
        String copiado = (String) area.getData(DataFlavor.stringFlavor);
        assertTrue(copiado.startsWith("Lab Desktop 0.1.320\n"), copiado);
        assertTrue(copiado.contains("Commit: a207ad4-alterado"), copiado);
        assertTrue(copiado.contains("Compilado em: 2026-10-30"), copiado);
        assertTrue(copiado.contains("Java: 21.0.12 (Homebrew)"), copiado);
        assertTrue(copiado.contains("Sistema: Mac OS X 15.6 (aarch64)"), copiado);
        assertTrue(copiado.contains("Agente empacotado: 0.1.0-dadf957"), copiado);
    }

    @Test
    void aCopiaDoAppDeVerdadeNaoVazaPastaDeUsuarioNomeDeUsuarioNemIp() throws Exception {
        Visual.tema(Tema.CLARO);
        String casa = System.getProperty("user.home");
        String usuario = System.getProperty("user.name");
        // a pasta de dados fica SOB a pasta do usuário, o pior caso para o vazamento
        InformacoesDoApp real = InformacoesDoApp.coletar(CaminhosApp.comPasta(Path.of(casa, "Library", "Application Support", "LabDesktop")));
        Clipboard area = new Clipboard("t");
        PainelSobre p = painel(real, area);
        Visual.naEdt(() -> p.botaoCopiarInformacoes().doClick());
        String copiado = (String) area.getData(DataFlavor.stringFlavor);
        assertTrue(copiado.contains("Commit: ") && copiado.contains("Compilado em: ") && copiado.contains("Java: ") && copiado.contains("Sistema: "), copiado);
        assertFalse(copiado.contains(casa), "a pasta do usuário não sai: " + copiado);
        if (usuario != null && usuario.length() >= 3) {
            assertFalse(copiado.toLowerCase().contains(usuario.toLowerCase()), "o nome do usuário não sai: " + copiado);
        }
        // a versão do Java pode ter quatro números separados por ponto: tira a linha dela antes de procurar um endereço IP
        String semJava = copiado.lines().filter(l -> !l.startsWith("Java: ")).collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(ENDERECO_IP.matcher(semJava).find(), "nenhum endereço IP: " + copiado);
        assertFalse(copiado.contains("/Users/") || copiado.contains("\\Users\\") || copiado.contains("/home/"), copiado);
        assertEquals(real.comoTexto(), copiado);
    }

    @Test
    void geraOsPngsDaTelaSobreNosDoisTemas() {
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            PainelSobre p = painel(info("a207ad4-alterado"), new Clipboard("t"));
            Visual.salvar(Visual.renderizar(p, 1000, Visual.alturaPreferida(p, 1000)), "pacote-u-sobre-" + t.name().toLowerCase());
        }
    }
}
