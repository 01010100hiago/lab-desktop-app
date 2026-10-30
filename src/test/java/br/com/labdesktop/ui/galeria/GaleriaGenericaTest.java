package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.tendencias.JanelaDeTempo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.PaginaResumo;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * As imagens de galeria com o visual genérico do Windows e do Linux ({@code FlatLightLaf} e {@code FlatDarkLaf}): o Resumo de um servidor e a tela Servidores. A moldura
 * da janela é a do macOS mesmo assim (é só enquadramento). Rode com {@code ./gradlew test -Plabdesktop.laf=generico --tests '...GaleriaGenericaTest'}: as imagens
 * {@code resumo-generico} e {@code servidores-generico} só saem nesse modo. No modo normal o teste monta as mesmas telas, confere o conteúdo e não grava nada.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaGenericaTest {

    @TempDir
    Path tmp;

    @Test
    void resumoGenerico() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp, new CenaDoServidor.Opcoes().perfil("monitor"))) {
            c.sshd = SegurancaDeExemplo.SSHD_SO_CHAVE; // um Resumo sem o alarme do login por senha: o SSH só aceita chave
            c.loginsNoRegistro = false; // só as tentativas que falharam: o Resumo mostra "Atenção" e não um alarme por login legítimo
            c.cenario.agente.saidaDoJournal = "2026-10-28T11:55:01-0300 servidor-demo sshd[1203]: Accepted publickey for ana from 192.0.2.9 port 51234 ssh2\n"
                + "2026-10-28T11:56:10-0300 servidor-demo kernel: usb 1-1: new high-speed USB device number 5\n"
                + "2026-10-28T11:58:00-0300 servidor-demo dockerd[900]: level=warning msg=\"Your kernel does not support swap limit capabilities\"\n"
                + "2026-10-28T11:59:30-0300 servidor-demo sshd[1250]: Failed password for invalid user admin from 192.0.2.50 port 4000 ssh2\n";
            c.abrir(Aba.RESUMO);
            TelaServidor ts = c.tela();
            PaginaResumo resumo = (PaginaResumo) ts.pagina(Aba.RESUMO);
            Object caixa = Visual.naEdt(() -> AcessoPorReflexao.chamar(resumo, "caixaDoRegistro"));
            Object painelDoRegistro = Visual.naEdt(() -> AcessoPorReflexao.chamar(caixa, "painel"));
            Visual.naEdt(() -> AcessoPorReflexao.chamar(painelDoRegistro, "forcarVisivelParaTeste", true)); // sem janela de verdade ninguém "aparece"
            Visual.naEdt(() -> AcessoPorReflexao.chamar(resumo, "escolherJanelaParaTeste", JanelaDeTempo.UM_DIA));
            Espera.ate(CenaDoServidor.PRAZO, () -> "o Resumo com os gráficos e o registro: " + c.textos().replace('\n', '|'), () -> {
                String t = c.textos();
                return t.contains("Tendências") && t.contains("Registro do servidor") && t.contains("Precisa de atenção") && !t.contains("Carregando") && !t.contains("lendo")
                    && !t.contains("sem dado ainda");
            });
            assertTrue(c.textos().contains("Resumo"), "a aba Resumo");
            c.fotografar("resumo-generico");
        }
    }

    @Test
    void servidoresGenerico() throws Exception {
        try (CenaDeServidores c = new CenaDeServidores(tmp)) {
            c.abrirServidores();
            c.fotografar("servidores-generico");
        }
    }
}
