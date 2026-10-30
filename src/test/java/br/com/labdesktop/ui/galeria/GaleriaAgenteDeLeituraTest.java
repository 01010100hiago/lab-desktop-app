package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * As imagens de galeria de um servidor que tem só o agente de monitoramento que o app instala: ele só lê a máquina, então não há aba Projetos, nem sessões do Claude, nem
 * "Claude" em lugar nenhum; o Resumo mostra os seis gráficos de tendência (10 minutos e 1 hora) e as abas Máquina e Containers mostram o que o agente lê. Os dados são os
 * do servidor de exemplo ("servidor-demo", inventados). Saem em {@code build/galeria/servidor-agente-leitura-*}.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaAgenteDeLeituraTest {

    @TempDir
    Path tmp;

    private static void semClaude(CenaDoServidor c) {
        // a aba Máquina sempre tem a linha das versões das sessões (aparece sem valor quando o agente não informa a imagem): é a única citação permitida
        String t = c.textos().replace("Claude" + " Code (sessões)", "");
        assertFalse(t.toLowerCase().contains("claude"), "um agente de leitura não tem Claude: " + t);
        TelaServidor ts = c.tela();
        assertFalse(Visual.naEdt(() -> ts.abas().visivel(Aba.PROJETOS.id())), "o agente diz projetos: false, então a aba Projetos some");
    }

    private static void resumoNaJanela(CenaDoServidor c, JanelaDeTempo janela) {
        TelaServidor ts = c.tela();
        PaginaResumo resumo = (PaginaResumo) ts.pagina(Aba.RESUMO);
        Object caixa = Visual.naEdt(() -> AcessoPorReflexao.chamar(resumo, "caixaDoRegistro"));
        Object painelDoRegistro = Visual.naEdt(() -> AcessoPorReflexao.chamar(caixa, "painel"));
        Visual.naEdt(() -> AcessoPorReflexao.chamar(painelDoRegistro, "forcarVisivelParaTeste", true)); // sem janela de verdade ninguém "aparece"
        Espera.ate(CenaDoServidor.PRAZO, () -> "o Resumo com os gráficos: " + c.textos().replace('\n', '|'), () -> {
            Visual.naEdt(() -> AcessoPorReflexao.chamar(resumo, "escolherJanelaParaTeste", janela)); // o seletor só liga quando o agente diz que guarda o histórico
            String t = c.textos();
            return t.contains("Tendências") && t.contains(janela.rotuloLongo()) && !t.contains("Carregando") && !t.contains("lendo") && !t.contains("sem dado ainda");
        });
    }

    private CenaDoServidor cena() throws Exception {
        CenaDoServidor c = new CenaDoServidor(tmp, new CenaDoServidor.Opcoes().agenteDeLeitura());
        c.sshd = SegurancaDeExemplo.SSHD_SO_CHAVE;
        c.loginsNoRegistro = false;
        return c;
    }

    @Test
    void resumoDe10Minutos() throws Exception {
        try (CenaDoServidor c = cena()) {
            c.abrir(Aba.RESUMO);
            resumoNaJanela(c, JanelaDeTempo.DEZ_MINUTOS);
            semClaude(c);
            assertTrue(c.textos().contains("CPU") && c.textos().contains("Memória"), c.textos());
            c.fotografar("servidor-agente-leitura-resumo-10min");
        }
    }

    @Test
    void resumoDe1Hora() throws Exception {
        try (CenaDoServidor c = cena()) {
            c.abrir(Aba.RESUMO);
            resumoNaJanela(c, JanelaDeTempo.UMA_HORA);
            semClaude(c);
            c.fotografar("servidor-agente-leitura-resumo-1h");
        }
    }

    @Test
    void maquina() throws Exception {
        try (CenaDoServidor c = cena()) {
            c.abrir(Aba.MAQUINA);
            c.esperarTextos("a aba Máquina lida pelo agente de leitura", "Git");
            semClaude(c);
            c.fotografar("servidor-agente-leitura-maquina");
        }
    }

    @Test
    void containers() throws Exception {
        try (CenaDoServidor c = cena()) {
            c.abrir(Aba.CONTAINERS);
            c.esperarTextos("a aba Containers sem sessões do Claude", "Containers", "Rodando");
            semClaude(c);
            c.fotografar("servidor-agente-leitura-containers");
        }
    }
}
