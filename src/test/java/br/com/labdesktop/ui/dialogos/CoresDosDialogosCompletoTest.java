package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.InfoChave.Origem;
import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O catálogo de cores dos diálogos tinha 11 dos 29 diálogos do app. Este tem os que faltavam (o assistente de servidor, novo projeto, publicar, o
 * agente, preparar o computador, a senha-mestra, o relatório...), cada um no estado em que abre, com o botão do Enter ligado a um painel de janela, nos dois temas, montado e
 * trocado de tema. O {@code CoresDosDialogosTest} segue com os 15 que já tinha; os dois juntos cobrem todo diálogo que se monta sem servidor. ({@code DialogoEscolherPasta} é
 * do pacote dos arquivos e tem o teste dele lá.)
 */
@ExtendWith(VerificaEdt.class)
class CoresDosDialogosCompletoTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private final AvisosGravados avisos = new AvisosGravados();

    private CopiadorDeTexto copiador() {
        return new CopiadorDeTexto(() -> new Clipboard("teste"));
    }

    private static JsonNode json(String texto) {
        try {
            return br.com.labdesktop.util.Json.mapper().readTree(texto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Supplier<Dialogo>> catalogo() {
        Map<String, Supplier<Dialogo>> m = new LinkedHashMap<>();
        m.put("confirmar sair do projeto (nao perguntar de novo)", () -> DialogoConfirmar.comNaoPerguntarDeNovo("Sair de app-loja?", "alerta",
            "Este projeto tem um terminal aberto. Se sair da página dele agora, o terminal fecha e o que estiver rodando nele para. A conversa do Claude continua no servidor.",
            "Sair do projeto", true, "Não perguntar de novo", "Para voltar a ser perguntado: Ajustes, Terminal.", d -> { }));
        m.put("2fa do servidor", () -> new Configurar2fa("lab", br.com.labdesktop.cofre.Estado2fa.DESLIGADO, DubleDeInterface.de(br.com.labdesktop.servidor.OperacoesDe2fa.class), avisos));
        m.put("adicionar container", () -> new DialogoAdicionarContainer("demo", DubleDeInterface.de(DialogoAdicionarContainer.Motor.class), () -> { }, () -> { }, avisos, copiador()));
        m.put("conectar cloudflare", () -> new DialogoConectarCloudflare(DubleDeInterface.de(DialogoConectarCloudflare.Motor.class), avisos, copiador(), () -> { }));
        m.put("desligar senha", () -> new DialogoDesligarSenha("lab", DubleDeInterface.de(DialogoDesligarSenha.Motor.class), copiador(), avisos, () -> { }));
        m.put("novo projeto", () -> new DialogoNovoProjeto(DubleDeInterface.de(DialogoNovoProjeto.Motor.class), avisos, copiador(), c -> { }));
        m.put("publicar", () -> new DialogoPublicar(DubleDeInterface.de(DialogoPublicar.Motor.class), avisos, copiador(), () -> { }));
        m.put("remover endereco", () -> new DialogoRemoverEndereco("lab-x.example.com", DubleDeInterface.de(DialogoRemoverEndereco.Motor.class), avisos, () -> { }));
        m.put("preparar este computador", () -> new DialogoPrepararComputador(DialogoPrepararComputador.Acao.PREPARAR,
            new DialogoPrepararComputador.Alvo("Este computador", 7070, "/Users/x/projetos", "painel-lab", "0.1.0", false),
            DubleDeInterface.de(DialogoPrepararComputador.Motor.class), () -> { }, avisos, copiador()));
        m.put("remover laboratorio local", () -> new DialogoPrepararComputador(DialogoPrepararComputador.Acao.REMOVER,
            new DialogoPrepararComputador.Alvo("Este computador", 7070, "/Users/x/projetos", "painel-lab", "0.1.0", false),
            DubleDeInterface.de(DialogoPrepararComputador.Motor.class), () -> { }, avisos, copiador()));
        m.put("registro do servidor", () -> new DialogoRegistro("lab", null, copiador(), avisos, () -> FUSO));
        m.put("editar servidor", () -> new DialogoEditarServidor(ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", "k1"), dados -> null, avisos));
        m.put("editar servidor (com acesso)", () -> new DialogoEditarServidor(ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", "k1"), dados -> null, avisos,
            new DialogoEditarServidor.Acesso("lab", "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg", "ssh-ed25519 AAAAexemplo lab", () -> { }), copiador()));
        m.put("instalar a chave com a senha", () -> new DialogoInstalarChave(new DialogoInstalarChave.Alvo("lab", "labuser", "192.0.2.5", 2222,
            List.of(new br.com.labdesktop.cofre.Pin("ssh-ed25519", "SHA256:mH3s1p2VwY+BAVOJ7MAG7JIZttP/NSiO9T4w4F165f0")), "lab",
            "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg", "ssh-ed25519 AAAAexemplo lab"), (senha, progresso) -> null, copiador(), avisos));
        m.put("remover servidor", () -> new DialogoRemoverServidor("lab", "labuser", true, List.of("outro"), Origem.GERADA_PELO_APP, opcoes -> null, avisos));
        m.put("remover servidor local", () -> DialogoRemoverServidor.paraEsteComputador("Este computador", false, opcoes -> null, avisos));
        m.put("limite excedido (teto)", () -> new DialogoLimiteExcedido("app-loja", conflito("{\"erro\":\"Ainda acima do limite\",\"usado_pp\":22.1,\"teto_pct\":20,\"pct_conta\":93.0,\"freio_pct\":null}"),
            new Limite(20, 22.1, "pausado", false, null, AGORA.plusSeconds(3 * 24 * 3600), null), AGORA, FUSO, e -> { }));
        m.put("limite excedido (freio)", () -> new DialogoLimiteExcedido("app-loja", conflito("{\"erro\":\"x\",\"usado_pp\":3,\"teto_pct\":20,\"pct_conta\":93.0,\"freio_pct\":90}"), null, AGORA, FUSO, e -> { }));
        m.put("conflito de transferencia", () -> new DialogoConflitoDeTransferencia(new br.com.labdesktop.arquivos.FilaDeTransferencias.Conflito(
            br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo.ENVIO, "a.txt", "/x/a.txt", 10, AGORA, 12, AGORA), FUSO, d -> { }));
        m.put("relatorio de tendencias", () -> new DialogoRelatorio(j -> br.com.labdesktop.tendencias.DadosDeTendencia.deHistorico(j, br.com.labdesktop.teste.HistoricoSintetico.dados(j, AGORA, 0), AGORA),
            br.com.labdesktop.tendencias.JanelaDeTempo.UM_DIA,
            br.com.labdesktop.tendencias.DadosDeTendencia.deHistorico(br.com.labdesktop.tendencias.JanelaDeTempo.UM_DIA,
                br.com.labdesktop.teste.HistoricoSintetico.dados(br.com.labdesktop.tendencias.JanelaDeTempo.UM_DIA, AGORA, 0), AGORA),
            true, "lab", FUSO, () -> AGORA, DubleDeInterface.de(br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal.class), (destino, bytes) -> { }, copiador(), avisos));
        m.put("senha-mestra (criar)", () -> new DialogoSenhaMestra(true, DubleDeInterface.de(DialogoSenhaMestra.Abridor.class), new br.com.labdesktop.cofre.AtrasoDeSenha(), () -> AGORA, r -> { }));
        m.put("senha-mestra (desbloquear)", () -> new DialogoSenhaMestra(false, DubleDeInterface.de(DialogoSenhaMestra.Abridor.class), new br.com.labdesktop.cofre.AtrasoDeSenha(), () -> AGORA, r -> { }));
        return m;
    }

    @org.junit.jupiter.api.io.TempDir
    Path pasta;

    /** O assistente de servidor (três passos): precisa de um cofre de verdade, num diretório temporário. */
    @Test
    void oAssistenteDeAdicionarServidorEnxergaSeNosDoisTemasMontadoETrocado() throws Exception {
        List<String> ruins = new ArrayList<>();
        int medidas = 0;
        try (br.com.labdesktop.cofre.CofreDeChaves cofre = br.com.labdesktop.cofre.CofreDeChaves.criar(
            br.com.labdesktop.util.CaminhosApp.de(pasta.resolve("dados"), pasta.resolve("logs")), "uma-senha-mestra-de-teste".toCharArray(), br.com.labdesktop.cofre.ParametrosKdf.MINIMO)) {
            var gerenciador = new br.com.labdesktop.servidor.GerenciadorServidores(cofre, br.com.labdesktop.api.PedidoDeCodigo2fa.NUNCA, br.com.labdesktop.ssh.ConfigSsh.rapida());
            try {
                var assistente = new br.com.labdesktop.servidor.AssistenteDeServidor(cofre, gerenciador, br.com.labdesktop.ssh.ConfigSsh.rapida());
                for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
                    for (boolean primeiro : new boolean[] {true, false}) {
                        Visual.tema(inicial);
                        Dialogo d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, primeiro, avisos, s -> { }));
                        JRootPane raiz = Visual.naEdt(() -> naJanela(d));
                        int h = Visual.alturaPreferida(d.raiz(), 620) + 20;
                        for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                            if (agora != inicial) {
                                Visual.tema(agora);
                                Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(raiz));
                            }
                            for (Medida med : Visual.naEdt(() -> AuditoriaDeCores.auditar(raiz, 620, h, true))) {
                                medidas++;
                                if (med.falhou()) {
                                    ruins.add(agora + " (de " + inicial + ", primeiro=" + primeiro + ") | " + med);
                                }
                            }
                        }
                    }
                }
            } finally {
                gerenciador.fechar().join();
            }
        }
        assertTrue(medidas > 50, "poucas medidas: " + medidas);
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite:\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }

    private static br.com.labdesktop.api.ErroApi.Conflito conflito(String texto) {
        JsonNode corpo = json(texto);
        return new br.com.labdesktop.api.ErroApi.Conflito(corpo, corpo.path("erro").asText(""));
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
    void osDialogosQueFaltavamEnxergamSeNosDoisTemasMontadosETrocados() throws Exception {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            for (var e : catalogo().entrySet()) {
                Visual.tema(inicial);
                Dialogo d = Visual.naEdt(() -> e.getValue().get());
                JRootPane raiz = Visual.naEdt(() -> naJanela(d));
                int w = Math.max(560, Visual.naEdt(() -> d.raiz().getPreferredSize().width)); // a janela é do tamanho do conteúdo (pack)
                int h = Visual.alturaPreferida(d.raiz(), w) + 20;
                for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                    if (agora != inicial) {
                        Visual.tema(agora);
                        Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(raiz));
                    }
                    List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(raiz, w, h, true));
                    String ctx = agora + (agora == inicial ? " montado" : " trocado de " + inicial) + " | " + e.getKey();
                    for (Medida med : medidas) {
                        todas.add(ctx + " | " + med);
                        if (med.falhou()) {
                            ruins.add(ctx + " | " + med);
                        }
                    }
                    if (agora == inicial) {
                        Visual.salvar(Visual.renderizar(d.raiz(), w, h), "dialogo-" + e.getKey().replaceAll("[^a-z0-9]+", "-") + "-" + agora.name().toLowerCase());
                    }
                }
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-dialogos-completo.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-dialogos-completo-ruins.txt"), ruins);
        assertTrue(todas.size() > 400, "poucas medidas: " + todas.size());
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }
}
