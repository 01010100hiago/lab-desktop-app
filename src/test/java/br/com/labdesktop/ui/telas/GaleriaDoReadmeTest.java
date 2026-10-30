package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.time.Duration;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As imagens da galeria do README que mostram a JANELA INTEIRA com um servidor vivo (o SSH embutido e o agente de mentira, com dados inventados): o Resumo do servidor
 * e a aba Processos, nos dois temas, dentro da moldura de janela do macOS ({@link MolduraDeJanela}). Saem em {@code build/galeria/}; as outras imagens da galeria
 * (diálogos, Ajustes, Ajuda, lista de servidores) saem dos testes que já as montavam.
 */
@ExtendWith(VerificaEdt.class)
class GaleriaDoReadmeTest {

    private static final Duration PRAZO = Duration.ofSeconds(40);

    @Test
    void resumoEProcessosNaJanelaInteira() throws Exception {
        Visual.tema(Tema.CLARO);
        AvisosGravados avisos = new AvisosGravados();
        try (CenarioDeUi cenario = new CenarioDeUi(br.com.labdesktop.api.PedidoDeCodigo2fa.NUNCA, false)) {
            // o nome do servidor na imagem é "servidor-demo"; o gerenciador nasce depois da edição e já lê o nome novo
            var cfg = cenario.cofre.servidor(br.com.labdesktop.teste.CofreFalso.ID).orElseThrow();
            cenario.cofre.alterarServidor(br.com.labdesktop.teste.CofreFalso.ID, c -> c.editar("servidor-demo", br.com.labdesktop.cofre.Transporte.DIRETO, cfg.host(), cfg.porta(), cfg.usuario(), cfg.portaAgente())
                .comPin(cenario.ssh.pin()));
            var gerenciador = new br.com.labdesktop.servidor.GerenciadorServidores(cenario.cofre, br.com.labdesktop.api.PedidoDeCodigo2fa.NUNCA, br.com.labdesktop.ssh.ConfigSsh.rapida());
            var lab = gerenciador.servidor(br.com.labdesktop.teste.CofreFalso.ID).orElseThrow();
            cenario.agente.perfil = "monitor"; // o agente que o app instala (o do lab de fora faria o servidor aparecer como protegido)
            cenario.agente.historico = true;
            // o registro do servidor que aparece na imagem: só endereços de documentação (192.0.2.x) e usuários inventados
            cenario.agente.saidaDoJournal = "2026-10-28T11:55:01-0300 lab sshd[1203]: Accepted publickey for ana from 192.0.2.9 port 51234 ssh2\n"
                + "2026-10-28T11:56:10-0300 lab kernel: usb 1-1: new high-speed USB device number 5\n"
                + "2026-10-28T11:58:00-0300 lab dockerd[900]: level=warning msg=\"Your kernel does not support swap limit capabilities\"\n"
                + "2026-10-28T11:59:30-0300 lab sshd[1250]: Failed password for invalid user admin from 192.0.2.50 port 4000 ssh2\n";
            cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
            lab.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == br.com.labdesktop.ssh.EstadoConexao.CONECTADO);
            PainelPrincipal painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));

            Visual.naEdt(() -> painel.navegacao().mostrarServidor(lab, TelaServidor.Aba.RESUMO));
            TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(br.com.labdesktop.teste.CofreFalso.ID));
            Visual.naEdt(() -> ts.pagina(TelaServidor.Aba.RESUMO).assinar()); // sem janela de verdade ninguém "aparece": a página assina os tópicos à mão
            PaginaResumo resumo = (PaginaResumo) ts.pagina(TelaServidor.Aba.RESUMO);
            Visual.naEdt(() -> resumo.caixaDoRegistro().painel().forcarVisivelParaTeste(true));
            Visual.naEdt(() -> resumo.blocoDeTendencias().botaoDaJanela(br.com.labdesktop.tendencias.JanelaDeTempo.UM_DIA).doClick());
            Espera.ate(PRAZO, () -> "o Resumo com os gráficos e o registro: " + textosDe(painel).replace('\n', '|'), () -> {
                String t = textosDe(painel);
                return t.contains("Tendências") && t.contains("Registro do servidor") && t.contains("Precisa de atenção") && !t.contains("Carregando") && !t.contains("lendo") && !t.contains("sem dado ainda");
            });
            Visual.galeriaNosDoisTemas(painel, 1440, 860, "resumo", "Lab Desktop", MolduraDeJanela.Tipo.JANELA);

            Visual.naEdt(() -> resumo.caixaDoRegistro().painel().forcarVisivelParaTeste(false));
            Visual.naEdt(() -> ts.pagina(TelaServidor.Aba.RESUMO).soltar());
            Visual.naEdt(() -> painel.navegacao().mostrarServidor(lab, TelaServidor.Aba.PROCESSOS));
            Visual.naEdt(() -> ts.pagina(TelaServidor.Aba.PROCESSOS).assinar());
            Espera.ate(PRAZO, () -> "a aba Processos com a lista: " + textosDe(painel).replace('\n', '|'), () -> {
                String t = textosDe(painel);
                return t.contains("Processos") && !t.contains("Carregando");
            });
            Visual.galeriaNosDoisTemas(painel, 1440, 860, "processos", "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
            assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("build", "galeria", "resumo-claro.png")));
            Visual.naEdt(painel::encerrar);
            gerenciador.fechar().join();
        }
    }

    private static String textosDe(Component c) {
        return Visual.naEdt(() -> {
            StringBuilder sb = new StringBuilder();
            coletar(c, sb);
            return sb.toString();
        });
    }

    private static void coletar(Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof JTextComponent t && t.getText() != null) {
            sb.append(t.getText()).append('\n');
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletar(f, sb);
            }
        }
    }
}
