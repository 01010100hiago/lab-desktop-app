package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cofre.AtrasoDeSenha;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.CofreCorrompidoException;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.SenhaIncorretaException;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Criar e destravar o cofre: força mínima, aviso de que não há recuperação, atraso e a senha sempre zerada. */
@ExtendWith(VerificaEdt.class)
class DialogoSenhaMestraTest {

    @TempDir
    Path tmp;

    private final List<Optional<CofreDeChaves>> decisoes = new ArrayList<>();
    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-24T12:00:00Z"));

    /** Um abridor que registra a senha recebida e delega ao cofre de verdade (Argon2id mínimo). */
    private class AbridorDeTeste implements DialogoSenhaMestra.Abridor {
        final List<char[]> recebidas = new ArrayList<>();
        final List<char[]> copiasNoMomento = new ArrayList<>();
        CompletableFuture<CofreDeChaves> proximo;
        boolean falharComSenhaIncorreta;
        final CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));

        @Override
        public CompletableFuture<CofreDeChaves> criar(char[] senha) {
            recebidas.add(senha);
            copiasNoMomento.add(senha.clone());
            if (proximo != null) {
                return proximo;
            }
            try {
                return CompletableFuture.completedFuture(CofreDeChaves.criar(caminhos, senha.clone(), ParametrosKdf.MINIMO));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<CofreDeChaves> abrir(char[] senha) {
            recebidas.add(senha);
            copiasNoMomento.add(senha.clone());
            if (falharComSenhaIncorreta) {
                return CompletableFuture.failedFuture(new SenhaIncorretaException());
            }
            try {
                return CompletableFuture.completedFuture(CofreDeChaves.abrir(caminhos, senha.clone(), ParametrosKdf.MINIMO));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private DialogoSenhaMestra dialogo(boolean criar, AbridorDeTeste abridor, AtrasoDeSenha atraso) {
        return Visual.naEdt(() -> new DialogoSenhaMestra(criar, abridor, atraso, agora::get, decisoes::add));
    }

    private static void digitar(DialogoSenhaMestra d, String senha, String confirmacao) {
        Visual.naEdt(() -> {
            d.campoDaSenha().setText(senha);
            d.campoDeConfirmacao().setText(confirmacao);
        });
    }

    private static void clicar(DialogoSenhaMestra d) {
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
    }

    @Test
    void criarMostraAForcaEmTextoERecusaSenhaFracaOuDiferente() {
        Visual.tema(Tema.CLARO);
        AbridorDeTeste abridor = new AbridorDeTeste();
        DialogoSenhaMestra d = dialogo(true, abridor, new AtrasoDeSenha());

        digitar(d, "curta", "curta");
        assertTrue(Visual.naEdt(d::textoDaForca).contains("faltam 7"), Visual.naEdt(d::textoDaForca));
        clicar(d);
        assertTrue(Visual.naEdt(d::textoDoErro).contains("pelo menos 12"), Visual.naEdt(d::textoDoErro));

        digitar(d, "aaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaa");
        clicar(d);
        assertTrue(Visual.naEdt(d::textoDoErro).contains("previsível"), Visual.naEdt(d::textoDoErro));

        digitar(d, "cavalo bateria grampo correto", "cavalo bateria grampo errado");
        assertTrue(Visual.naEdt(d::textoDaForca).startsWith("Forte"), Visual.naEdt(d::textoDaForca));
        clicar(d);
        assertEquals("As duas senhas não são iguais.", Visual.naEdt(d::textoDoErro));
        assertTrue(abridor.recebidas.isEmpty(), "nada foi enviado ao KDF com senha recusada");
        assertTrue(decisoes.isEmpty());
    }

    /** O achado: 12 caracteres sem sequência liberavam o botão, mesmo sendo "Password1234". */
    @Test
    void senhaDeDicionarioNaoCriaOCofreMesmoComTamanhoENumero() {
        Visual.tema(Tema.CLARO);
        AbridorDeTeste abridor = new AbridorDeTeste();
        DialogoSenhaMestra d = dialogo(true, abridor, new AtrasoDeSenha());

        for (String comum : new String[] {"Password1234", "iloveyou1234", "Corinthians10", "Passw0rd!!!!!", "Password12345678"}) {
            digitar(d, comum, comum);
            assertTrue(Visual.naEdt(d::textoDaForca).contains("comum"), comum + ": " + Visual.naEdt(d::textoDaForca));
            clicar(d);
            assertTrue(Visual.naEdt(d::textoDoErro).contains("comum"), comum + ": " + Visual.naEdt(d::textoDoErro));
        }
        digitar(d, "k9#Vd2!xQm@7", "k9#Vd2!xQm@7"); // sorteada, mas com 12 caracteres
        clicar(d);
        assertTrue(Visual.naEdt(d::textoDoErro).contains("16 caracteres"), Visual.naEdt(d::textoDoErro));
        assertTrue(abridor.recebidas.isEmpty(), "nada foi enviado ao KDF");
        assertTrue(decisoes.isEmpty());
    }

    @Test
    void criarComSenhaBoaEntregaOCofreEZeraAsSenhas() throws Exception {
        Visual.tema(Tema.CLARO);
        AbridorDeTeste abridor = new AbridorDeTeste();
        DialogoSenhaMestra d = dialogo(true, abridor, new AtrasoDeSenha());
        digitar(d, "cavalo bateria grampo correto", "cavalo bateria grampo correto");
        clicar(d);

        Espera.ate(Duration.ofSeconds(10), () -> "decisão", () -> !decisoes.isEmpty());
        CofreDeChaves cofre = decisoes.get(0).orElseThrow();
        assertTrue(cofre.aberto());
        assertArrayEquals("cavalo bateria grampo correto".toCharArray(), abridor.copiasNoMomento.get(0));
        assertTrue(Zeragem.todoZero(new String(abridor.recebidas.get(0)).isEmpty() ? new byte[0] : toBytes(abridor.recebidas.get(0))),
            "o array que o diálogo entregou foi zerado logo depois");
        assertEquals("", new String(Visual.naEdt(() -> d.campoDaSenha().getPassword())), "o campo foi limpo");
        assertEquals("", new String(Visual.naEdt(() -> d.campoDeConfirmacao().getPassword())));
        cofre.bloquear();
    }

    private static byte[] toBytes(char[] chars) {
        byte[] b = new byte[chars.length];
        for (int i = 0; i < chars.length; i++) {
            b[i] = (byte) chars[i];
        }
        return b;
    }

    @Test
    void senhaIncorretaMostraAMensagemEAplicaOAtrasoCrescente() throws Exception {
        Visual.tema(Tema.CLARO);
        AbridorDeTeste abridor = new AbridorDeTeste();
        abridor.falharComSenhaIncorreta = true;
        AtrasoDeSenha atraso = new AtrasoDeSenha();
        DialogoSenhaMestra d = dialogo(false, abridor, atraso);

        for (int i = 0; i < 3; i++) {
            digitar(d, "senha-errada-qualquer", "");
            final int tentativas = i + 1;
            clicar(d);
            Espera.ate(Duration.ofSeconds(5), () -> "tentativa " + tentativas, () -> abridor.recebidas.size() == tentativas);
            Espera.ate(Duration.ofSeconds(5), () -> "terminar", () -> !Visual.naEdt(d::estaOcupado));
            if (i < 2) {
                assertEquals("Senha incorreta.", Visual.naEdt(d::textoDoErro), "as duas primeiras falhas não esperam");
            }
        }
        // 3ª falha: atraso de 1 s
        assertTrue(Visual.naEdt(d::textoDoErro).startsWith("Aguarde"), Visual.naEdt(d::textoDoErro));
        digitar(d, "outra-senha-qualquer", "");
        assertFalse(Visual.naEdt(() -> d.botaoPrincipal().isEnabled()), "durante o atraso o botão fica desabilitado");
        agora.set(agora.get().plusSeconds(5));
        digitar(d, "outra-senha-qualquer!", "");
        assertTrue(Visual.naEdt(() -> d.botaoPrincipal().isEnabled()), "passado o atraso volta a habilitar");
        assertEquals(3, atraso.falhasSeguidas());
    }

    @Test
    void arquivoCorrompidoDizQueEstaIlegivelENaoSenhaIncorreta() {
        Visual.tema(Tema.CLARO);
        AbridorDeTeste abridor = new AbridorDeTeste() {
            @Override
            public CompletableFuture<CofreDeChaves> abrir(char[] senha) {
                return CompletableFuture.failedFuture(new CofreCorrompidoException("O cofre não pôde ser lido."));
            }
        };
        DialogoSenhaMestra d = dialogo(false, abridor, new AtrasoDeSenha());
        digitar(d, "alguma-senha-boa", "");
        clicar(d);
        Espera.ate(Duration.ofSeconds(5), () -> "mensagem", () -> Visual.naEdt(d::textoDoErro).contains("ilegível"));
    }

    @Test
    void sairEntregaVazioEOCofreQueChegarDepoisEBloqueado() throws Exception {
        Visual.tema(Tema.CLARO);
        AbridorDeTeste abridor = new AbridorDeTeste();
        abridor.proximo = new CompletableFuture<>(); // o Argon2id "ainda está rodando"
        DialogoSenhaMestra d = dialogo(true, abridor, new AtrasoDeSenha());
        digitar(d, "cavalo bateria grampo correto", "cavalo bateria grampo correto");
        clicar(d);
        assertTrue(Visual.naEdt(d::estaOcupado));

        Visual.naEdt(() -> d.botaoSair().doClick());
        assertEquals(List.of(Optional.empty()), decisoes);

        CofreDeChaves tarde = CofreDeChaves.criar(abridor.caminhos, "cavalo bateria grampo correto".toCharArray(), ParametrosKdf.MINIMO);
        abridor.proximo.complete(tarde);
        Espera.ate(Duration.ofSeconds(5), () -> "cofre tardio bloqueado", () -> !tarde.aberto());
        assertEquals(1, decisoes.size());
    }

    @Test
    void desenhaCriarEAbrirNosDoisTemas() {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoSenhaMestra criar = dialogo(true, new AbridorDeTeste(), new AtrasoDeSenha());
            digitar(criar, "cavalo bateria grampo", "cavalo bateria grampo");
            BufferedImage a = Visual.renderizar(criar.raiz(), 520, Visual.alturaPreferida(criar.raiz(), 520));
            Visual.salvar(a, "dialogo-criar-cofre-" + tema.name().toLowerCase());
            assertNotNull(a);

            AtrasoDeSenha atraso = new AtrasoDeSenha();
            for (int i = 0; i < 4; i++) {
                atraso.registrarFalha(agora.get());
            }
            DialogoSenhaMestra abrir = dialogo(false, new AbridorDeTeste(), atraso);
            digitar(abrir, "x", "");
            BufferedImage b = Visual.renderizar(abrir.raiz(), 520, Visual.alturaPreferida(abrir.raiz(), 520));
            Visual.salvar(b, "dialogo-abrir-cofre-" + tema.name().toLowerCase());
        }
    }
}
