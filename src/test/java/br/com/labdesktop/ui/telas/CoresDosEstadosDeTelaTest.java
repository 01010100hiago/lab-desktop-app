package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
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
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Os estados de tela que o catálogo de cores não tinha. A tela "Gerenciar servidores" (os cartões com as ações), o servidor que perdeu a conexão (a
 * faixa de aviso com "Tentar agora"), o servidor sem agente (o aviso fixo e "Instalar agente"), o token que o agente recusa (a faixa com "Registrar token") e a aba Cloudflare
 * sem conta conectada. Cada estado no tema em que nasceu e com o tema trocado depois (a janela já montada).
 */
@ExtendWith(VerificaEdt.class)
class CoresDosEstadosDeTelaTest {

    private final AvisosGravados avisos = new AvisosGravados();

    private record Estado(String nome, Consumer<CenarioDeUi> preparar, Consumer<PainelPrincipal> mostrar) { }

    private static void esperarFora(CenarioDeUi c) {
        c.agente.fora = true;
        c.lab.conexao().verificarAgora();
        Espera.ate(Duration.ofSeconds(25), () -> "estado=" + c.lab.estado(), () -> c.lab.estado().conexao() != br.com.labdesktop.ssh.EstadoConexao.CONECTADO);
    }

    private List<Estado> estados(CenarioDeUi c) {
        List<Estado> l = new ArrayList<>();
        l.add(new Estado("gerenciar servidores", x -> { }, p -> p.barra().clicarEmGerenciar()));
        l.add(new Estado("servidor sem conexao com o agente (faixa Tentar agora)", CoresDosEstadosDeTelaTest::esperarFora, p -> p.mostrarResumo()));
        l.add(new Estado("servidor sem agente instalado (aviso fixo e Instalar agente)", x -> {
            x.agente.fora = true;
            x.agente.diagnostico = "ausente";
            x.lab.conexao().verificarAgora();
            Espera.ate(Duration.ofSeconds(25), () -> "situacao=" + x.lab.estado().situacaoDoAgente(), () -> x.lab.estado().situacaoDoAgente() != null && x.lab.estado().situacaoDoAgente().oferecerInstalar());
        }, p -> p.barra().clicarEmGerenciar()));
        l.add(new Estado("token recusado pelo agente (faixa Registrar token)", x -> {
            x.agente.recusaTodosOsTokens = true;
            x.lab.conexao().verificarAgora();
            Espera.dormir(1500);
        }, p -> p.mostrarResumo()));
        l.add(new Estado("aba Cloudflare sem conta conectada", x -> { }, p -> p.navegacao().mostrarServidor(c.lab, TelaServidor.Aba.CLOUDFLARE)));
        return l;
    }

    @Test
    void osEstadosDeTelaEnxergamSeNosDoisTemasMontadosETrocados() throws Exception {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            for (int i = 0; i < 5; i++) {
                Visual.tema(inicial);
                try (CenarioDeUi cenario = new CenarioDeUi()) {
                    Estado estado = estados(cenario).get(i);
                    estado.preparar().accept(cenario);
                    PainelPrincipal painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos)));
                    Visual.naEdt(() -> estado.mostrar().accept(painel));
                    Espera.dormir(500);
                    for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                        if (agora != inicial) {
                            Visual.tema(agora);
                            Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(painel));
                        }
                        List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(painel, 1440, 860));
                        String ctx = agora + (agora == inicial ? " montada" : " trocada de " + inicial) + " | " + estado.nome();
                        for (Medida m : medidas) {
                            todas.add(ctx + " | " + m);
                            if (m.falhou()) {
                                ruins.add(ctx + " | " + m);
                            }
                        }
                        if (agora == inicial) {
                            Visual.salvar(Visual.renderizar(painel, 1440, 700), "estado-" + estado.nome().replaceAll("[^a-z]+", "-") + "-" + agora.name().toLowerCase());
                        }
                    }
                    Visual.naEdt(painel::encerrar);
                }
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-estados-de-tela.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-estados-de-tela-ruins.txt"), ruins);
        assertTrue(todas.size() > 200, "poucas medidas: " + todas.size());
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }
}
