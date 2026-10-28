package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.MocksDoFront;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O cronômetro das trocas de página (sem suposição): quanto a EDT fica ocupada em cada troca de aba do servidor, ao abrir a página de um projeto e em cada aba
 * dele, medido com {@code System.nanoTime} em volta da troca E da pintura da tela de 1440 por 860 (o que a janela faria). Fica em {@code build/tempos-das-telas.txt}.
 */
@ExtendWith(VerificaEdt.class)
class TemposDasTelasTest {

    private final List<String> linhas = new ArrayList<>();

    private <T> T medir(String o, Supplier<T> acao) {
        long t0 = System.nanoTime();
        T r = acao.get();
        linhas.add(String.format("%7.1f ms  %s", (System.nanoTime() - t0) / 1e6, o));
        return r;
    }

    @Test
    void cronometroDasTrocasDeAbaEDaPaginaDoProjeto() throws Exception {
        Visual.tema(Tema.CLARO);
        AvisosGravados avisos = new AvisosGravados();
        try (CenarioDeUi cenario = new CenarioDeUi()) {
            cenario.agente.detalhes.put("app-loja", MocksDoFront.ler("projetos/app-loja.json"));
            PainelPrincipal painel = medir("construir o painel principal (barra, navegação e a página do servidor)",
                () -> Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos))));
            medir("primeira pintura da janela (1440x860)", () -> Visual.renderizar(painel, 1440, 860));
            TelaServidor ts = Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
            for (TelaServidor.Aba aba : TelaServidor.Aba.values()) {
                medir("aba do servidor " + aba + ": trocar", () -> Visual.naEdt(() -> {
                    painel.navegacao().mostrarServidor(cenario.lab, aba);
                    return 0;
                }));
                medir("aba do servidor " + aba + ": pintar", () -> Visual.renderizar(painel, 1440, 860));
            }
            Visual.naEdt(() -> painel.mostrarProjetos());
            Visual.naEdt(() -> ts.projetos().assinar());
            Espera.ate(Duration.ofSeconds(20), "lista", () -> Visual.naEdt(() -> ts.projetos().nomesExibidos()).contains("app-loja"));
            medir("lista de projetos com dados: pintar", () -> Visual.renderizar(painel, 1440, 860));
            medir("abrir a página do projeto app-loja (clique na linha): criar", () -> Visual.naEdt(() -> {
                painel.navegacao().abrirProjeto(cenario.lab, "app-loja");
                return 0;
            }));
            medir("abrir a página do projeto app-loja: pintar", () -> Visual.renderizar(painel, 1440, 860));
            TelaProjeto tp = Visual.naEdt(() -> ts.projetoAberto());
            Visual.naEdt(tp::assinar);
            for (TelaProjeto.Aba aba : TelaProjeto.Aba.values()) {
                medir("aba do projeto " + aba + ": trocar", () -> Visual.naEdt(() -> {
                    tp.mostrarAba(aba);
                    return 0;
                }));
                medir("aba do projeto " + aba + ": pintar", () -> Visual.renderizar(painel, 1440, 860));
            }
            medir("terminal do servidor: abrir uma aba nova (cria o JediTerm)", () -> Visual.naEdt(() -> ts.terminais().novoTerminal()));
            medir("terminal do servidor: pintar", () -> Visual.renderizar(painel, 1440, 860));
            Visual.naEdt(painel::encerrar);
        }
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/tempos-das-telas.txt"), linhas);
        linhas.forEach(System.out::println);
        assertTrue(!linhas.isEmpty());
    }
}
