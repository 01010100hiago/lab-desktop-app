package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.acesso.DesligadorDeSenha;
import br.com.labdesktop.acesso.DesligadorDeSenha.Passo;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.teste.SshdFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * "Desligar a senha": lê ao abrir (nada escreve), mostra o que fará, os riscos e o comando de reverter, só habilita o botão depois do nome do servidor digitado,
 * mostra os passos e o resultado real, e nunca prende nem escreve antes da hora. PNGs nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class DialogoDesligarSenhaTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final String CARIMBO = "20261028-153005";

    private SshdFalso sshd;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard areaDeTransferencia = new Clipboard("teste");
    private final AtomicInteger aoAgir = new AtomicInteger();
    private volatile FalhaDeEntradaPorSenha falhaDaProva;
    private volatile CountDownLatch segurar;

    @BeforeEach
    void montar() {
        Visual.tema(Tema.CLARO);
        sshd = new SshdFalso(CARIMBO);
    }

    @AfterEach
    void desmontar() {
        if (segurar != null) {
            segurar.countDown();
        }
        Visual.tema(Tema.CLARO);
    }

    private DialogoDesligarSenha abrir() {
        DesligadorDeSenha d = new DesligadorDeSenha(sshd, () -> {
            if (segurar != null) {
                try {
                    segurar.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (falhaDaProva != null) {
                throw falhaDaProva;
            }
        }, () -> LocalDateTime.of(2026, 10, 28, 15, 30, 5), () -> java.util.List.of("publickey"), SshdFalso.USUARIO, Duration.ZERO);
        DialogoDesligarSenha.Motor motor = new DialogoDesligarSenha.Motor() {
            @Override
            public DesligadorDeSenha.Avaliacao avaliar() throws Exception {
                return d.avaliar();
            }

            @Override
            public DesligadorDeSenha.Resultado desligar(DesligadorDeSenha.Avaliacao a, DesligadorDeSenha.Observador o) {
                return d.desligar(a, o);
            }
        };
        DialogoDesligarSenha dialogo = Visual.naEdt(() -> new DialogoDesligarSenha("lab", motor, new CopiadorDeTexto(() -> areaDeTransferencia), avisos, aoAgir::incrementAndGet));
        Espera.ate(PRAZO, "a leitura terminou", () -> Visual.naEdt(() -> dialogo.avaliacao() != null || !dialogo.textoDoBloqueio().isEmpty()));
        return dialogo;
    }

    private static void png(DialogoDesligarSenha d, String nome) {
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), nome);
    }

    /** Roda o cenário uma vez em cada tema, montando o diálogo DEPOIS de escolher o tema (como o app faz), e grava o PNG de cada um. */
    private void nosDoisTemas(String nome, java.util.function.Consumer<SshdFalso> preparar, java.util.function.Consumer<DialogoDesligarSenha> cenario) {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            sshd = new SshdFalso(CARIMBO);
            preparar.accept(sshd);
            DialogoDesligarSenha d = abrir();
            cenario.accept(d);
            semTransbordo(d);
            png(d, nome + "-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);
    }

    private static void semTransbordo(DialogoDesligarSenha d) {
        Visual.alturaPreferida(d.raiz(), 560);
        List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(d.raiz()));
        assertTrue(achados.isEmpty(), "transborda: " + achados);
    }

    private String copiado() throws Exception {
        return (String) areaDeTransferencia.getData(DataFlavor.stringFlavor);
    }

    @Test
    void aoAbrirSoLeEMostraOQueFaraOsRiscosEOComandoDeReverterSemEscreverNada() throws Exception {
        DialogoDesligarSenha d = abrir();

        assertEquals(List.of(), sshd.escritas, "abrir a janela só lê");
        assertTrue(d.avaliacao().podeDesligar());
        String faz = Visual.naEdt(d::textoDoQueFazMostrado);
        assertTrue(faz.contains("cópia datada") && faz.contains("PasswordAuthentication no") && faz.contains("sshd -t") && faz.contains("sem reiniciar"), faz);
        assertTrue(faz.contains("só com a chave") && faz.contains("desfaz tudo sozinho"), faz);
        String riscos = Visual.naEdt(d::textoDosRiscosMostrado);
        assertTrue(riscos.contains("entram neste servidor por senha deixam de conseguir entrar"), riscos);
        assertTrue(riscos.contains("perder a chave deste app") && riscos.contains("painel do provedor ou ao lado do servidor"), riscos);
        assertTrue(riscos.contains("NÃO liga a senha de novo sozinho"), riscos);
        assertTrue(riscos.contains("cloud-init"), riscos);
        String achados = Visual.naEdt(d::textoDoEncontrado);
        assertTrue(achados.contains("Login por senha agora: ligado"), achados);
        assertTrue(Visual.naEdt(d::comandoDeReverterMostrado).contains("rm " + ComandosDoAcesso.ARQUIVO_PROPRIO), Visual.naEdt(d::comandoDeReverterMostrado));
        String tela = Visual.naEdt(() -> TextosDeTela.de(d.raiz()));
        assertTrue(tela.contains("Para confirmar, digite o nome do servidor: lab"), tela);
        assertTrue(tela.contains("Desligar a senha em lab"), tela);
        semTransbordo(d);
        nosDoisTemas("desligar-senha-previa", x -> { }, outro -> { });
    }

    @Test
    void oBotaoSoHabilitaDepoisDoNomeDoServidorDigitadoEOEnterNaoAcionaOQueMudaOServidor() throws Exception {
        DialogoDesligarSenha d = abrir();
        assertFalse(Visual.naEdt(() -> d.botaoDesligar().isEnabled()), "sem o nome digitado o botão fica desligado");
        assertEquals(Visual.naEdt(d::botaoFechar), Visual.naEdt(d::botaoDoEnter), "o Enter aciona Cancelar, o botão seguro");

        Visual.naEdt(() -> d.campoDaConfirmacao().setText("outro"));
        assertFalse(Visual.naEdt(() -> d.botaoDesligar().isEnabled()));
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("Lab"));
        assertFalse(Visual.naEdt(() -> d.botaoDesligar().isEnabled()), "o nome é conferido por inteiro, com as mesmas letras");
        Visual.naEdt(() -> d.botaoDesligar().doClick());
        assertEquals(List.of(), sshd.escritas, "um clique num botão desligado não escreve nada");

        Visual.naEdt(() -> d.campoDaConfirmacao().setText("lab"));
        assertTrue(Visual.naEdt(() -> d.botaoDesligar().isEnabled()));
        assertEquals(List.of(), sshd.escritas, "digitar o nome também não escreve: só o clique");
    }

    @Test
    void depoisDoCliqueMostraOsPassosEOResultadoRealComOComandoDeReverter() throws Exception {
        DialogoDesligarSenha d = abrir();
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("lab"));
        Visual.naEdt(() -> d.botaoDesligar().doClick());
        Espera.ate(PRAZO, () -> "resultado (selo='" + Visual.naEdt(d::seloDoResultado) + "')", () -> !Visual.naEdt(d::seloDoResultado).isEmpty());

        assertEquals("SENHA DESLIGADA", Visual.naEdt(d::seloDoResultado));
        assertEquals("Login por senha: desligado. O servidor só oferece a chave numa conexão nova.", Visual.naEdt(d::textoDoResultado));
        for (Passo p : Passo.values()) {
            assertTrue(Visual.naEdt(d::linhasDosPassos).get(p).startsWith(p.rotulo()), p.toString());
        }
        assertTrue(Visual.naEdt(d::linhasDosPassos).get(Passo.CONFERIR).contains("passwordauthentication no"), Visual.naEdt(d::linhasDosPassos).toString());
        assertTrue(Visual.naEdt(d::textoDosDetalhes).contains("cloud-init"), Visual.naEdt(d::textoDosDetalhes));
        assertEquals(ComandosDoAcesso.comandoParaReverter(), Visual.naEdt(d::comandoDeReverterMostrado));
        Visual.naEdt(() -> d.botaoCopiarComandoDeReverter().doClick());
        assertEquals(ComandosDoAcesso.comandoParaReverter(), copiado());
        assertFalse(Visual.naEdt(() -> d.botaoDesligar().isVisible()), "não oferece desligar de novo");
        assertEquals("Fechar", Visual.naEdt(() -> d.botaoFechar().getText()));
        assertTrue(sshd.temArquivoNosso());
        semTransbordo(d);
        nosDoisTemas("desligar-senha-resultado", x -> { }, outro -> {
            Visual.naEdt(() -> outro.campoDaConfirmacao().setText("lab"));
            Visual.naEdt(() -> outro.botaoDesligar().doClick());
            Espera.ate(PRAZO, "resultado", () -> !Visual.naEdt(outro::seloDoResultado).isEmpty());
        });
        Visual.naEdt(() -> d.botaoFechar().doClick());
        assertTrue(Visual.naEdt(d::fechou));
        assertEquals(1, aoAgir.get(), "avisa quem abriu (o assistente conclui) depois de o usuário ter agido");
    }

    @Test
    void provaQueFalhaMostraQueODesfezESemTransbordo() throws Exception {
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "O servidor não aceitou a chave.");
        DialogoDesligarSenha d = abrir();
        Visual.naEdt(() -> d.campoDaConfirmacao().setText("lab"));
        Visual.naEdt(() -> d.botaoDesligar().doClick());
        Espera.ate(PRAZO, "resultado", () -> !Visual.naEdt(d::seloDoResultado).isEmpty());

        assertEquals("DESFEITO", Visual.naEdt(d::seloDoResultado));
        assertTrue(Visual.naEdt(d::textoDoResultado).contains("DESFEZ"), Visual.naEdt(d::textoDoResultado));
        assertTrue(Visual.naEdt(d::linhasDosPassos).get(Passo.PROVAR).startsWith(Passo.PROVAR.rotulo() + " — A chave não entrou."), Visual.naEdt(d::linhasDosPassos).toString());
        assertFalse(sshd.temArquivoNosso(), "o arquivo foi apagado");
        semTransbordo(d);
        nosDoisTemas("desligar-senha-desfeito", x -> { }, outro -> {
            Visual.naEdt(() -> outro.campoDaConfirmacao().setText("lab"));
            Visual.naEdt(() -> outro.botaoDesligar().doClick());
            Espera.ate(PRAZO, "resultado", () -> !Visual.naEdt(outro::seloDoResultado).isEmpty());
        });
    }

    @Test
    void semPermissaoNaoTentaMostraOComandoParaODonoRodarEPodeCopiar() throws Exception {
        sshd.sudo = false;
        DialogoDesligarSenha d = abrir();

        assertEquals(List.of(ComandosDoAcesso.lerPermissoes()), sshd.comandos, "só perguntou a permissão");
        assertTrue(Visual.naEdt(d::textoDoBloqueio).contains("nunca pede a senha de sudo"), Visual.naEdt(d::textoDoBloqueio));
        assertEquals(ComandosDoAcesso.comandoParaOsDonoRodar(), Visual.naEdt(d::comandoParaODonoRodar));
        assertFalse(Visual.naEdt(() -> d.botaoDesligar().isVisible()), "não há botão que escreve");
        assertEquals("Fechar", Visual.naEdt(() -> d.botaoFechar().getText()));
        Visual.naEdt(() -> d.botaoCopiarComandoManual().doClick());
        assertEquals(ComandosDoAcesso.comandoParaOsDonoRodar(), copiado());
        assertTrue(Visual.naEdt(d::comandoDeReverterMostrado).contains("rm "), "o comando de reverter também aparece");
        semTransbordo(d);
        Visual.naEdt(() -> d.botaoFechar().doClick());
        assertEquals(List.of(), sshd.escritas);
        nosDoisTemas("desligar-senha-sem-permissao", x -> x.sudo = false, outro -> { });
    }

    @Test
    void arquivoQueVemAntesMostraOMotivoENaoOfereceODesligar() throws Exception {
        sshd.pasta.put("01-provedor.conf", "PasswordAuthentication yes\n");
        DialogoDesligarSenha d = abrir();
        assertTrue(Visual.naEdt(d::textoDoBloqueio).contains("01-provedor.conf"), Visual.naEdt(d::textoDoBloqueio));
        assertFalse(Visual.naEdt(() -> d.botaoDesligar().isVisible()));
        assertTrue(Visual.naEdt(d::textoDoEncontrado).contains("01-provedor.conf"), "relata o que encontrou: " + Visual.naEdt(d::textoDoEncontrado));
        semTransbordo(d);
        assertEquals(List.of(), sshd.escritas);
        nosDoisTemas("desligar-senha-arquivo-anterior", x -> x.pasta.put("01-provedor.conf", "PasswordAuthentication yes\n"), outro -> { });
    }

    @Test
    void osBotoesEnxergamSeNosDoisTemasNaPreviaEnoResultado() throws Exception {
        List<String> ruins = new java.util.ArrayList<>();
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            for (boolean comResultado : new boolean[] {false, true}) {
                Visual.tema(tema);
                sshd = new SshdFalso(CARIMBO);
                DialogoDesligarSenha d = abrir();
                if (comResultado) {
                    Visual.naEdt(() -> d.campoDaConfirmacao().setText("lab"));
                    Visual.naEdt(() -> d.botaoDesligar().doClick());
                    Espera.ate(PRAZO, "resultado", () -> !Visual.naEdt(d::seloDoResultado).isEmpty());
                }
                javax.swing.JRootPane raiz = Visual.naEdt(() -> {
                    javax.swing.JRootPane r = new javax.swing.JRootPane();
                    r.setContentPane(d.raiz());
                    r.setDefaultButton(d.botaoDoEnter());
                    return r;
                });
                int h = Visual.alturaPreferida(d.raiz(), 560) + 20;
                for (var m : Visual.naEdt(() -> br.com.labdesktop.ui.AuditoriaDeCores.auditar(raiz, 560, h, true))) {
                    if (m.falhou()) {
                        ruins.add(tema + (comResultado ? " resultado" : " prévia") + " | " + m);
                    }
                }
            }
        }
        assertTrue(ruins.isEmpty(), "botões com pouco contraste:\n" + String.join("\n", ruins));
    }

    @Test
    void cancelarSemTerAgidoFechaSemAvisarEEscNaoFechaNoMeioDoTrabalho() throws Exception {
        DialogoDesligarSenha d = abrir();
        Visual.naEdt(() -> d.botaoFechar().doClick());
        assertTrue(Visual.naEdt(d::fechou));
        assertEquals(0, aoAgir.get(), "quem só olhou e cancelou não conclui nada");
        assertEquals(List.of(), sshd.escritas);

        segurar = new CountDownLatch(1);
        DialogoDesligarSenha segundo = abrir();
        Visual.naEdt(() -> segundo.campoDaConfirmacao().setText("lab"));
        Visual.naEdt(() -> segundo.botaoDesligar().doClick());
        Espera.ate(PRAZO, "andando", () -> Visual.naEdt(segundo::ocupado) && sshd.recargas >= 1);
        assertFalse(Visual.naEdt(() -> segundo.botaoFechar().isEnabled()), "o botão não deixa fechar no meio");
        Visual.naEdt(() -> segundo.botaoFechar().doClick());
        assertFalse(Visual.naEdt(segundo::fechou));
        segurar.countDown();
        Espera.ate(PRAZO, "resultado", () -> !Visual.naEdt(segundo::seloDoResultado).isEmpty());
        assertEquals("SENHA DESLIGADA", Visual.naEdt(segundo::seloDoResultado));
    }
}
