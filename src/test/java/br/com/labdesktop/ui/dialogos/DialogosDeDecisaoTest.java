package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoLimiteExcedido.Escolha;
import br.com.labdesktop.util.Preferencias.Tema;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Os diálogos que o usuário decide: confiar no host, definir limite, "ainda estoura", reabrir, registrar token. */
@ExtendWith(VerificaEdt.class)
class DialogosDeDecisaoTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private final AvisosGravados avisos = new AvisosGravados();

    // ------------------------------------------------------------------ confiar no host

    private static SondagemDeHost.Resultado sonda() throws Exception {
        KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        return new SondagemDeHost.Resultado("ssh-ed25519", KeyUtils.getFingerPrint(par.getPublic()), par.getPublic(),
            "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub");
    }

    @Test
    void confiarNoHostSoHabilitaDepoisDeMarcarQueConferiu() throws Exception {
        Visual.tema(Tema.CLARO);
        List<Boolean> decisoes = new ArrayList<>();
        DialogoConfiarHost d = Visual.naEdt(() -> {
            try {
                return new DialogoConfiarHost("lab", "192.0.2.5:2222", sonda(), avisos, decisoes::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertFalse(Visual.naEdt(() -> d.botaoConfiar().isEnabled()));
        Visual.naEdt(() -> d.botaoConfiar().doClick());
        assertTrue(decisoes.isEmpty(), "sem conferir, o clique não faz nada");

        Visual.naEdt(() -> d.caixaDeConferencia().doClick());
        assertTrue(Visual.naEdt(() -> d.botaoConfiar().isEnabled()));
        Visual.naEdt(() -> d.botaoConfiar().doClick());
        assertEquals(List.of(true), decisoes);
        assertTrue(d.sondagem().impressao().startsWith("SHA256:"));
    }

    @Test
    void confiarNoHostExplicaEmPalavrasSimplesEDaOComandoDeConferirNumaLinhaCopiavel() throws Exception {
        Visual.tema(Tema.CLARO);
        java.awt.datatransfer.Clipboard area = new java.awt.datatransfer.Clipboard("teste");
        SondagemDeHost.Resultado sonda = sonda();
        DialogoConfiarHost d = Visual.naEdt(() -> new DialogoConfiarHost("lab", "192.0.2.5:2222", sonda, avisos, x -> { },
            new br.com.labdesktop.ui.componentes.CopiadorDeTexto(() -> area)));

        String explicacao = Visual.naEdt(d::textoDaExplicacao);
        assertTrue(explicacao.contains("identifica o servidor"), explicacao);
        assertTrue(explicacao.contains("só é pedida agora"), explicacao);
        assertTrue(explicacao.contains("Se um dia ela mudar, o app recusa conectar, por segurança"), explicacao);
        assertEquals("Confere, confiar neste servidor", Visual.naEdt(() -> d.botaoConfiar().getText()));
        assertEquals("Não confere, cancelar", Visual.naEdt(() -> d.botaoCancelar().getText()));
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", Visual.naEdt(d::comandoMostrado));
        assertFalse(Visual.naEdt(d::comandoMostrado).contains("\n"), "uma linha só, para copiar de uma vez");

        Visual.naEdt(() -> d.botaoCopiarComando().doClick());
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", area.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
        assertEquals("Copiado", Visual.naEdt(() -> d.botaoCopiarComando().getText()));
        Visual.naEdt(() -> d.botaoCopiarImpressao().doClick());
        assertEquals(sonda.impressao(), area.getData(java.awt.datatransfer.DataFlavor.stringFlavor));
        assertTrue(avisos.disse("info", "Copiado"));
    }

    @Test
    void cancelarOuFecharNaoConfiaENaoGravaNada() throws Exception {
        Visual.tema(Tema.CLARO);
        List<Boolean> decisoes = new ArrayList<>();
        DialogoConfiarHost d = Visual.naEdt(() -> {
            try {
                return new DialogoConfiarHost("lab", "h:22", sonda(), avisos, decisoes::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Visual.naEdt(() -> d.caixaDeConferencia().doClick());
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        Visual.naEdt(() -> d.aoCancelar());
        assertEquals(List.of(false), decisoes, "mesmo com a caixa marcada, cancelar é não confiar, e só uma vez");
    }

    @Test
    void desenhaOHostNosDoisTemas() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoConfiarHost d = Visual.naEdt(() -> {
                try {
                    return new DialogoConfiarHost("lab", "192.0.2.5:2222", sonda(), avisos, x -> { });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Visual.salvar(Visual.renderizar(d.raiz(), 540, Visual.alturaPreferida(d.raiz(), 540)), "dialogo-confiar-host-" + tema.name().toLowerCase());
        }
    }

    // ------------------------------------------------------------------ definir limite

    private static Limite limite(Integer teto, Double usado, boolean liberado) {
        return new Limite(teto, usado, "ok", liberado, null, null, null);
    }

    @Test
    void definirLimiteValidaInteiroDe1a100EEnviaOTeto() {
        Visual.tema(Tema.CLARO);
        AtomicReference<Object> enviado = new AtomicReference<>("nada");
        List<Integer> salvos = new ArrayList<>();
        DialogoLimite d = Visual.naEdt(() -> new DialogoLimite("app-loja", limite(20, 14.0, false),
            teto -> enviado.set(teto == null ? "removido" : teto), salvos::add));

        assertEquals("20", Visual.naEdt(() -> d.campoDoValor().getText()), "vem preenchido com o teto de hoje");
        for (String ruim : new String[] {"", "0", "101", "12,5", "abc", "-3"}) {
            Visual.naEdt(() -> d.campoDoValor().setText(ruim));
            Visual.naEdt(() -> d.botaoSalvar().doClick());
            assertTrue(Visual.naEdt(d::textoDoErro).contains("inteiro de 1 a 100"), "'" + ruim + "': " + Visual.naEdt(d::textoDoErro));
        }
        assertEquals("nada", enviado.get(), "nada foi enviado com valor inválido");

        Visual.naEdt(() -> d.campoDoValor().setText("25"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "salvar", () -> !salvos.isEmpty());
        assertEquals(25, enviado.get());
        assertEquals(List.of(25), salvos);
    }

    @Test
    void semLimiteRemoveOTetoEADicaAvisaDoUso() {
        Visual.tema(Tema.CLARO);
        AtomicReference<Object> enviado = new AtomicReference<>("nada");
        List<Integer> salvos = new ArrayList<>();
        DialogoLimite d = Visual.naEdt(() -> new DialogoLimite("app-loja", limite(20, 14.0, false),
            teto -> enviado.set(teto == null ? "removido" : teto), salvos::add));
        assertTrue(Visual.naEdt(d::textoDaDica).contains("Uso estimado agora: 14% da semana · teto atual: 20%."), Visual.naEdt(d::textoDaDica));

        Visual.naEdt(() -> d.campoDoValor().setText("10"));
        assertTrue(Visual.naEdt(d::textoDaDica).contains("já está abaixo do uso"), "10 contra 14 de uso: a sessão pausaria");
        Visual.naEdt(() -> d.campoDoValor().setText("30"));
        assertFalse(Visual.naEdt(d::textoDaDica).contains("já está abaixo do uso"));

        Visual.naEdt(() -> d.caixaSemLimite().doClick());
        assertFalse(Visual.naEdt(() -> d.campoDoValor().isEnabled()));
        assertTrue(Visual.naEdt(d::textoDaDica).contains("só pausa pelo freio global"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "salvar", () -> !salvos.isEmpty());
        assertEquals("removido", enviado.get());
        assertEquals(java.util.Collections.singletonList(null), salvos);
    }

    @Test
    void erroDoAgenteApareceDentroDoDialogoQueContinuaAberto() {
        Visual.tema(Tema.CLARO);
        List<Integer> salvos = new ArrayList<>();
        DialogoLimite d = Visual.naEdt(() -> new DialogoLimite("app-loja", null, teto -> {
            throw new ErroApi.ServidorErro(400, null, "Informe teto_pct entre 1 e 100.");
        }, salvos::add));
        Visual.naEdt(() -> d.campoDoValor().setText("20"));
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertEquals("Informe teto_pct entre 1 e 100.", Visual.naEdt(d::textoDoErro));
        assertTrue(salvos.isEmpty());
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "dá para corrigir e salvar de novo");
        assertFalse(d.jaFechou());
    }

    @Test
    void ofereceOLimiteDaSessaoRaizComONomeAmigavel() {
        Visual.tema(Tema.CLARO);
        DialogoLimite d = Visual.naEdt(() -> new DialogoLimite("lab", null, teto -> { }, t -> { }));
        assertEquals("Definir limite", d.titulo());
        Visual.salvar(Visual.renderizar(d.raiz(), 520, Visual.alturaPreferida(d.raiz(), 520)), "dialogo-definir-limite");
    }

    // ------------------------------------------------------------------ "ainda acima do limite"

    private static Conflito conflito(String json) throws Exception {
        JsonNode corpo = br.com.labdesktop.util.Json.mapper().readTree(json);
        return new Conflito(corpo, corpo.path("erro").asText(""));
    }

    private DialogoLimiteExcedido excedido(String json, Limite l, List<Escolha> escolhas) throws Exception {
        Conflito c = conflito(json);
        return Visual.naEdt(() -> new DialogoLimiteExcedido("app-loja", c, l, AGORA, FUSO, escolhas::add));
    }

    @Test
    void estouroPeloTetoOferecAumentarELiberar() throws Exception {
        Visual.tema(Tema.CLARO);
        List<Escolha> escolhas = new ArrayList<>();
        DialogoLimiteExcedido d = excedido("{\"erro\":\"Ainda acima do limite\",\"usado_pp\":22.1,\"teto_pct\":20,\"pct_conta\":93.0,\"freio_pct\":null}",
            new Limite(20, 22.1, "pausado", false, null, AGORA.plusSeconds(3 * 24 * 3600), null), escolhas);
        assertEquals("Consumiu 22% de 20%", d.tituloDoTexto());
        assertTrue(d.textoDoCorpo().contains("app-loja já usou 22% da semana (estimado) e o teto é 20%."), d.textoDoCorpo());
        assertTrue(d.textoDoCorpo().contains("Aumente o limite, ou libere o projeto"), d.textoDoCorpo());
        assertTrue(d.textoDoCorpo().contains("zera em 3 d"), d.textoDoCorpo());
        assertTrue(d.ofereceAumentar());
        assertTrue(Visual.naEdt(() -> d.botaoAumentar().isVisible()));
        Visual.naEdt(() -> d.botaoAumentar().doClick());
        assertEquals(List.of(Escolha.AUMENTAR), escolhas);
        Visual.salvar(Visual.renderizar(d.raiz(), 520, Visual.alturaPreferida(d.raiz(), 520)), "dialogo-limite-excedido");
    }

    @Test
    void estouroSoPeloFreioNaoOferecAumentarOLimite() throws Exception {
        Visual.tema(Tema.CLARO);
        List<Escolha> escolhas = new ArrayList<>();
        DialogoLimiteExcedido d = excedido("{\"erro\":\"x\",\"usado_pp\":3,\"teto_pct\":20,\"pct_conta\":93.0,\"freio_pct\":90}", null, escolhas);
        assertEquals("Freio global ativo", d.tituloDoTexto());
        assertTrue(d.textoDoCorpo().contains("A conta está em 93% da semana e o freio global pausa as sessões em 90%."), d.textoDoCorpo());
        assertFalse(d.ofereceAumentar());
        assertFalse(Visual.naEdt(() -> d.botaoAumentar().isVisible()));
        Visual.naEdt(() -> d.botaoLiberar().doClick());
        assertEquals(List.of(Escolha.LIBERAR), escolhas);
    }

    @Test
    void tetoEFreioJuntosDizemOsDoisEOMesmoTextoSemNumerosUsaAMensagemDoAgente() throws Exception {
        Visual.tema(Tema.CLARO);
        DialogoLimiteExcedido ambos = excedido("{\"erro\":\"x\",\"usado_pp\":25,\"teto_pct\":20,\"pct_conta\":95.0,\"freio_pct\":90}", null, new ArrayList<>());
        assertTrue(ambos.textoDoCorpo().contains("já usou 25% da semana"));
        assertTrue(ambos.textoDoCorpo().contains("freio global pausa as sessões em 90%"));
        assertEquals("Consumiu 25% de 20%", ambos.tituloDoTexto());
        DialogoLimiteExcedido sem = excedido("{\"erro\":\"O projeto ainda estoura.\"}", null, new ArrayList<>());
        assertEquals("Ainda acima do limite", sem.tituloDoTexto());
        assertTrue(sem.textoDoCorpo().startsWith("O projeto ainda estoura."), sem.textoDoCorpo());
    }

    @Test
    void cancelarEOEscEntregamCancelarUmaVezSo() throws Exception {
        Visual.tema(Tema.CLARO);
        List<Escolha> escolhas = new ArrayList<>();
        DialogoLimiteExcedido d = excedido("{\"erro\":\"x\",\"usado_pp\":25,\"teto_pct\":20}", null, escolhas);
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        Visual.naEdt(() -> d.aoCancelar());
        assertEquals(List.of(Escolha.CANCELAR), escolhas);
    }

    // ------------------------------------------------------------------ confirmar (reabrir)

    @Test
    void confirmarEntregaSimOuNaoUmaVez() {
        Visual.tema(Tema.CLARO);
        List<Boolean> respostas = new ArrayList<>();
        DialogoConfirmar sim = Visual.naEdt(() -> new DialogoConfirmar("Reabrir sessão do Claude", "atualizar", "texto", "Reabrir", false, respostas::add));
        Visual.naEdt(() -> sim.botaoConfirmar().doClick());
        Visual.naEdt(() -> sim.botaoCancelar().doClick());
        DialogoConfirmar nao = Visual.naEdt(() -> new DialogoConfirmar("Reabrir", "atualizar", "texto", "Reabrir", true, respostas::add));
        Visual.naEdt(() -> nao.aoCancelar());
        assertEquals(List.of(true, false), respostas);
    }

    // ------------------------------------------------------------------ registrar token

    @Test
    void registrarTokenValidaONomeDoDispositivoEChamaARegistro() {
        Visual.tema(Tema.CLARO);
        List<String> nomes = new ArrayList<>();
        List<String> substituicoes = new ArrayList<>();
        boolean[] concluiu = {false};
        DialogoRegistrarToken d = Visual.naEdt(() -> new DialogoRegistrarToken("lab", "mac-de-teste", false,
            nomes::add, substituicoes::add, () -> concluiu[0] = true, avisos));
        assertEquals("mac-de-teste", Visual.naEdt(() -> d.campoDoNome().getText()));
        assertTrue(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        for (String ruim : new String[] {"", "Mac Do Usuário", "mac_x", "-x".repeat(30)}) {
            Visual.naEdt(() -> d.campoDoNome().setText(ruim));
            assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "'" + ruim + "' não é um nome de dispositivo");
        }
        Visual.naEdt(() -> d.campoDoNome().setText("meu-mac"));
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "registro", () -> concluiu[0]);
        assertEquals(List.of("meu-mac"), nomes);
        assertTrue(substituicoes.isEmpty());
        assertTrue(avisos.disse("ok", "Token registrado em lab"));
    }

    @Test
    void nomeJaUsadoOferecSubstituirEOCaminhoDeSubstituirRevogaOAntigo() {
        Visual.tema(Tema.CLARO);
        List<String> substituicoes = new ArrayList<>();
        boolean[] concluiu = {false};
        DialogoRegistrarToken d = Visual.naEdt(() -> new DialogoRegistrarToken("lab", "mac", false, nome -> {
            throw new ErroProvisionamento(ErroProvisionamento.TOKEN_CONFLITO, "Já existe um token com esse nome.");
        }, substituicoes::add, () -> concluiu[0] = true, avisos));
        assertFalse(Visual.naEdt(() -> d.caixaDeSubstituicao().isVisible()));
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "conflito", () -> Visual.naEdt(() -> d.caixaDeSubstituicao().isVisible()));
        assertTrue(Visual.naEdt(d::textoDoErro).contains("Já existe um token"), Visual.naEdt(d::textoDoErro));
        assertFalse(Visual.naEdt(() -> d.caixaDeSubstituicao().isSelected()), "revogar o antigo só com o OK explícito do usuário");

        Visual.naEdt(() -> d.caixaDeSubstituicao().doClick());
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "substituição", () -> concluiu[0]);
        assertEquals(List.of("mac"), substituicoes);
    }

    @Test
    void tokenRecusadoAbreJaEmModoSubstituir() {
        Visual.tema(Tema.CLARO);
        List<String> substituicoes = new ArrayList<>();
        boolean[] concluiu = {false};
        DialogoRegistrarToken d = Visual.naEdt(() -> new DialogoRegistrarToken("lab", "mac-de-teste", true,
            nome -> { throw new IllegalStateException("não deveria registrar"); }, substituicoes::add, () -> concluiu[0] = true, avisos));
        assertEquals("Substituir o token", d.titulo());
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "substituição", () -> concluiu[0]);
        assertEquals(List.of("mac-de-teste"), substituicoes);
        assertNotNull(d.raiz());
    }
}
