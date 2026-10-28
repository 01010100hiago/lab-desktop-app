package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.ComposeComEdge;
import br.com.labdesktop.instalador.GerenciadorDePacotes;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.AvaliadorDeProcessos;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.IdentidadeDoProcesso;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As cores dos botões dos diálogos de Processos e Backups (Encerrar e Reiniciar, Instalar o Git, o assistente Adicionar domínio e "Para qual projeto?"), pela auditoria de cores:
 * medidas nos pixels, com o botão do Enter ligado a um painel de janela, nos dois temas e trocando o tema com o diálogo já montado.
 */
@ExtendWith(VerificaEdt.class)
class CoresDosDialogosDeProcessosTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private final AvisosGravados avisos = new AvisosGravados();

    private static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private DialogoProcesso processo(ProcessoAlvo alvo, Resultado resposta) {
        Oferta oferta = AvaliadorDeProcessos.avaliar(alvo, AmbienteDeProcessos.servidor("labuser", Set.of()));
        DialogoProcesso.Motor m = new DialogoProcesso.Motor() {
            @Override
            public Previa preparar() {
                return new Previa(alvo, oferta, oferta.via() == br.com.labdesktop.processos.Via.CONTAINER ? null
                    : new IdentidadeDoProcesso(alvo.pid(), "1000", "Tue Oct 28 12:00:00 2026", alvo.nome()), null);
            }

            @Override
            public Resultado encerrar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
                return resposta;
            }

            @Override
            public Resultado forcar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
                return resposta;
            }

            @Override
            public Resultado reiniciar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
                return resposta;
            }
        };
        DialogoProcesso d = ed(() -> new DialogoProcesso(alvo, oferta, br.com.labdesktop.processos.Acao.ENCERRAR, "origem", "Forçar (SIGKILL)", m,
            new CopiadorDeTexto(() -> new Clipboard("t")), avisos, () -> { }));
        Espera.ate(PRAZO, "lido", () -> !ed(d::fatos).isEmpty());
        return d;
    }

    private DialogoInstalarGit git(InstaladorDeGit.Previa previa) {
        DialogoInstalarGit.Motor m = new DialogoInstalarGit.Motor() {
            @Override
            public InstaladorDeGit.Previa conferir() {
                return previa;
            }

            @Override
            public InstaladorDeGit.Resultado rodar(InstaladorDeGit.Previa p, InstaladorDeGit.Observador o, Cancelamento c) {
                return new InstaladorDeGit.Resultado(true, new InstaladorDeGit.Estado(true, "git version 2.43.0"), "ok", "");
            }

            @Override
            public InstaladorDeGit.Estado conferirDeNovo() {
                return new InstaladorDeGit.Estado(false, "");
            }
        };
        DialogoInstalarGit d = ed(() -> new DialogoInstalarGit("lab", m, new CopiadorDeTexto(() -> new Clipboard("t")), avisos, () -> { }));
        Espera.ate(PRAZO, "lido", () -> !ed(d::textoDaExplicacao).isEmpty());
        return d;
    }

    private DialogoAdicionarDominio dominio(int passos) {
        DialogoAdicionarDominio.Motor m = new DialogoAdicionarDominio.Motor() {
            @Override
            public String projeto() {
                return "demo";
            }

            @Override
            public Optional<DialogoAdicionarDominio.Conexao> conexao() {
                return Optional.of(new DialogoAdicionarDominio.Conexao("Conta de Teste", "example.com", "site", "lab"));
            }

            @Override
            public void conectar(Runnable depois) { }

            @Override
            public List<ComposeComEdge.Servico> servicos() {
                return List.of(new ComposeComEdge.Servico("app", null, false, 3000));
            }

            @Override
            public boolean podeRecriar() {
                return true;
            }
        };
        DialogoAdicionarDominio d = ed(() -> new DialogoAdicionarDominio(m, null, p -> { }, () -> { }));
        for (int i = 0; i < passos; i++) {
            Visual.naEdt(() -> d.botaoContinuar().doClick(0));
        }
        if (passos == 3) {
            Espera.ate(PRAZO, "serviços", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
        }
        return d;
    }

    private Map<String, Supplier<Dialogo>> catalogo() {
        ProcessoAlvo node = new ProcessoAlvo(3000, "node", "labuser", null, null);
        Map<String, Supplier<Dialogo>> m = new LinkedHashMap<>();
        m.put("processo: confirmar", () -> processo(node, Resultado.de(Desfecho.ENCERRADO, "fechou")));
        m.put("processo: container", () -> processo(new ProcessoAlvo(3100, "node", "node", "app-loja-web", "app-loja"), Resultado.de(Desfecho.REINICIADO, "reiniciou")));
        m.put("processo: sem permissão", () -> processo(new ProcessoAlvo(3200, "postgres", "postgres", null, null), Resultado.de(Desfecho.ENCERRADO, "x")));
        m.put("processo: ainda rodando", () -> {
            DialogoProcesso d = processo(node, Resultado.de(Desfecho.AINDA_RODANDO, "continua rodando"));
            Visual.naEdt(() -> d.botaoEncerrar().doClick(0));
            Espera.ate(PRAZO, "resultado", () -> !ed(d::seloDoResultado).isEmpty());
            return d;
        });
        m.put("git: pode instalar", () -> git(new InstaladorDeGit.Previa(new InstaladorDeGit.Estado(false, ""), InstaladorDeGit.Situacao.PODE_INSTALAR, GerenciadorDePacotes.APT_GET,
            "Debian", false, true, List.of("sudo -n apt-get install -y git"), List.of())));
        m.put("git: precisa do usuário", () -> git(new InstaladorDeGit.Previa(new InstaladorDeGit.Estado(false, ""), InstaladorDeGit.Situacao.PRECISA_DO_DONO, GerenciadorDePacotes.APT_GET,
            "Debian", false, false, List.of(), List.of("sudo apt-get install -y git"))));
        m.put("git: já instalado", () -> git(new InstaladorDeGit.Previa(new InstaladorDeGit.Estado(true, "git version 2.43.0"), InstaladorDeGit.Situacao.JA_INSTALADO, null, "", false, false,
            List.of(), List.of())));
        m.put("domínio: passo 1", () -> dominio(0));
        m.put("domínio: passo 2", () -> dominio(1));
        m.put("domínio: passo 3", () -> dominio(2));
        m.put("domínio: passo 4", () -> dominio(3));
        m.put("escolher projeto", () -> ed(() -> new DialogoEscolherProjeto(List.of("alfa", "demo"), p -> { })));
        return m;
    }

    private static JRootPane naJanela(Dialogo d) {
        JRootPane raiz = new JRootPane();
        raiz.setContentPane(d.raiz());
        if (d.botaoDoEnter() != null) {
            raiz.setDefaultButton(d.botaoDoEnter());
        }
        return raiz;
    }

    @Test
    void todoBotaoDosDialogosNovosEnxergaSeNosDoisTemasMontadoETrocado() throws Exception {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            for (var e : catalogo().entrySet()) {
                Visual.tema(inicial);
                Dialogo d = e.getValue().get();
                JRootPane raiz = Visual.naEdt(() -> naJanela(d));
                int h = Visual.alturaPreferida(d.raiz(), 600) + 20;
                for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                    if (agora != inicial) {
                        Visual.tema(agora);
                        Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(raiz));
                    }
                    List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(raiz, 600, h, true));
                    String ctx = agora + (agora == inicial ? " montado" : " trocado de " + inicial) + " | " + e.getKey();
                    for (Medida med : medidas) {
                        todas.add(ctx + " | " + med);
                        if (med.falhou()) {
                            ruins.add(ctx + " | " + med);
                        }
                    }
                }
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-dialogos-pacote-j.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-dialogos-pacote-j-ruins.txt"), ruins);
        assertTrue(todas.size() > 200, "poucas medidas: " + todas.size());
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
        Visual.tema(Tema.CLARO);
    }
}
