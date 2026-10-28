package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As cores de todo controle das telas principais (as abas do servidor e as abas da página de um projeto), medidas nos pixels com um servidor VIVO de
 * mentira ({@link AuditoriaDeCores}), nos dois temas: com a janela montada no tema e com a janela JÁ montada e o tema trocado (o caso real).
 */
@ExtendWith(VerificaEdt.class)
class CoresDasTelasTest {

    private static final Tema[] TEMAS = {Tema.CLARO, Tema.ESCURO};

    private static List<String> relatar(String contexto, List<Medida> medidas) {
        List<String> ruins = new ArrayList<>();
        for (Medida m : medidas) {
            if (m.falhou()) {
                ruins.add(contexto + " | " + m);
            }
        }
        return ruins;
    }

    @Test
    void todoControleDasTelasEnxergaSeNosDoisTemasMontadoETrocado() throws Exception {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        AvisosGravados avisos = new AvisosGravados();
        for (Tema inicial : TEMAS) {
            Visual.tema(inicial);
            try (CenarioDeUi cenario = new CenarioDeUi()) {
                cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
                PainelPrincipal painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
                TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
                Visual.naEdt(() -> painel.mostrarProjetos());
                Visual.naEdt(() -> ts.projetos().assinar());
                Espera.ate(Duration.ofSeconds(20), "lista", () -> Visual.naEdt(() -> ts.projetos().nomesExibidos()).contains("app-loja"));
                for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                    if (agora != inicial) {
                        Visual.tema(agora);
                        Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(painel));
                    }
                    String modo = agora == inicial ? "montada-no-tema" : "trocada-de-" + inicial;
                    for (TelaServidor.Aba aba : TelaServidor.Aba.values()) {
                        Visual.naEdt(() -> painel.navegacao().mostrarServidor(cenario.lab, aba));
                        var pagina = Visual.naEdt(() -> ts.pagina(aba));
                        if (pagina != null) {
                            Visual.naEdt(pagina::assinar);
                        }
                        Espera.dormir(300);
                        List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(painel, 1440, 860));
                        String ctx = agora + " | " + modo + " | aba " + aba;
                        medidas.forEach(m -> todas.add(ctx + " | " + m));
                        ruins.addAll(relatar(ctx, medidas));
                    }
                    Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "app-loja"));
                    TelaProjeto tp = Visual.naEdt(() -> ts.projetoAberto());
                    Visual.naEdt(tp::assinar);
                    for (TelaProjeto.Aba aba : TelaProjeto.Aba.values()) {
                        Visual.naEdt(() -> tp.mostrarAba(aba));
                        Visual.naEdt(() -> {
                            if (tp.pagina(aba) != null) {
                                tp.pagina(aba).assinar();
                            }
                        });
                        Espera.dormir(400);
                        List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(painel, 1440, 860));
                        String ctx = agora + " | " + modo + " | projeto " + aba;
                        medidas.forEach(m -> todas.add(ctx + " | " + m));
                        ruins.addAll(relatar(ctx, medidas));
                    }
                    Visual.naEdt(() -> ts.voltarParaALista());
                }
                Visual.naEdt(painel::encerrar);
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-telas.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-telas-ruins.txt"), ruins);
        assertTrue(todas.size() > 3000, "poucas medidas: " + todas.size());
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }
}
