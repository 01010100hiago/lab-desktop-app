package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.instalador.GerenciadorDePacotes;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.instalador.InstaladorDeGit.Estado;
import br.com.labdesktop.instalador.InstaladorDeGit.Previa;
import br.com.labdesktop.instalador.InstaladorDeGit.Resultado;
import br.com.labdesktop.instalador.InstaladorDeGit.Situacao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * "Instalar o Git" no servidor: já instalado, instalável (mostra os comandos exatos e só roda depois do clique), sem sudo (o comando para o usuário, com Copiar, e "Já instalei, conferir de novo"),
 * sem gerenciador, falha, Parar que nunca prende. PNGs nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class DialogoInstalarGitTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final Estado SEM_GIT = new Estado(false, "");
    private static final Estado COM_GIT = new Estado(true, "git version 2.43.0");

    final AvisosGravados avisos = new AvisosGravados();
    final Clipboard area = new Clipboard("teste");
    final AtomicInteger mudou = new AtomicInteger();
    final List<String> chamadas = new ArrayList<>();
    volatile Previa previa;
    volatile Resultado resultado = new Resultado(true, COM_GIT, "O git foi instalado: git version 2.43.0.", "");
    volatile Estado estadoDaConferencia = SEM_GIT;
    volatile CountDownLatch segurar;

    @AfterEach
    void soltar() {
        if (segurar != null) {
            segurar.countDown();
        }
        Visual.tema(Tema.CLARO);
    }

    private static Previa pode() {
        return new Previa(SEM_GIT, Situacao.PODE_INSTALAR, GerenciadorDePacotes.APT_GET, "Debian GNU/Linux 13 (trixie)", false, true,
            List.of("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get update", "sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y git"), List.of());
    }

    private DialogoInstalarGit abrir(Previa p) {
        Visual.tema(Tema.CLARO);
        previa = p;
        DialogoInstalarGit.Motor motor = new DialogoInstalarGit.Motor() {
            @Override
            public Previa conferir() {
                chamadas.add("conferir");
                return previa;
            }

            @Override
            public Resultado rodar(Previa pv, InstaladorDeGit.Observador obs, Cancelamento cancelamento) throws InterruptedException {
                chamadas.add("instalar");
                obs.passo("Passo 1 de 2: " + pv.comandos().get(0));
                CountDownLatch l = segurar;
                if (l != null) {
                    l.await(15, TimeUnit.SECONDS);
                }
                return resultado;
            }

            @Override
            public Estado conferirDeNovo() {
                chamadas.add("conferir-de-novo");
                return estadoDaConferencia;
            }
        };
        DialogoInstalarGit d = Visual.naEdt(() -> new DialogoInstalarGit("lab", motor, new CopiadorDeTexto(() -> area), avisos, mudou::incrementAndGet));
        Espera.ate(PRAZO, () -> "lido: " + chamadas, () -> chamadas.contains("conferir"));
        Espera.ate(PRAZO, "aparece", () -> !ed(d::textoDaExplicacao).isEmpty() || !ed(d::textoDaSituacao).isEmpty() && !ed(d::textoDaSituacao).startsWith("Conferindo"));
        return d;
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    @Test
    void gitJaInstaladoDizEMostraAVersaoSemNadaParaFazer() {
        DialogoInstalarGit d = abrir(new Previa(COM_GIT, Situacao.JA_INSTALADO, null, "", false, false, List.of(), List.of()));
        assertTrue(ed(d::textoDaExplicacao).contains("já está instalado em lab: git version 2.43.0"), ed(d::textoDaExplicacao));
        assertFalse(ed(() -> d.botaoInstalar().isVisible()));
        assertEquals("Fechar", ed(() -> d.botaoFechar().getText()));
        CenarioDeCloudflare.fotografar(d, "dialogo-git-0-ja-instalado");
    }

    @Test
    void mostraOSistemaOGerenciadorEOsComandosExatosAntesDeRodar() {
        DialogoInstalarGit d = abrir(pode());
        String e = ed(d::textoDaExplicacao);
        assertTrue(e.contains("Debian GNU/Linux 13 (trixie)") && e.contains("apt-get") && e.contains("com sudo sem senha"), e);
        assertTrue(e.contains("Nada muda na configuração do git"));
        assertEquals("O app vai rodar", ed(d::tituloDosComandos));
        assertEquals("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get update\nsudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y git", ed(d::textoDosComandos));
        assertTrue(ed(() -> d.botaoInstalar().isVisible()));
        assertSame(d.botaoFechar(), d.botaoDoEnter(), "o Enter aciona o seguro");
        assertEquals(List.of("conferir"), chamadas, "abrir só lê");
        CenarioDeCloudflare.fotografar(d, "dialogo-git-1-confirmar");
    }

    @Test
    void instalarSoDepoisDoCliqueMostraOPassoEOResultado() throws Exception {
        segurar = new CountDownLatch(1);
        DialogoInstalarGit d = abrir(pode());
        Visual.naEdt(() -> d.botaoInstalar().doClick(0));
        Espera.ate(PRAZO, () -> "passo", () -> ed(d::textoDoProgresso).startsWith("Passo 1 de 2"));
        assertFalse(ed(() -> d.botaoInstalar().isEnabled()), "clique duplo não instala duas vezes");
        Visual.naEdt(() -> d.botaoInstalar().doClick(0));
        CenarioDeCloudflare.fotografar(d, "dialogo-git-2-instalando");
        segurar.countDown();
        Espera.ate(PRAZO, "resultado", () -> !ed(d::seloDoResultado).isEmpty());
        assertEquals("INSTALADO", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("git version 2.43.0"));
        assertEquals(1, chamadas.stream().filter("instalar"::equals).count());
        assertFalse(ed(() -> d.botaoInstalar().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-git-3-instalado");
        assertEquals(0, mudou.get());
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertEquals(1, mudou.get(), "ao fechar, quem abriu relê o estado do git");
    }

    @Test
    void falhaDaInstalacaoMostraOMotivoEOPassoEOferecePoderConferir() {
        resultado = new Resultado(false, SEM_GIT, "Outro instalador está rodando neste servidor (o gerenciador de pacotes está ocupado). Espere ele terminar e tente de novo.",
            "Passo 1 de 2: sudo -n env DEBIAN_FRONTEND=noninteractive apt-get update");
        DialogoInstalarGit d = abrir(pode());
        Visual.naEdt(() -> d.botaoInstalar().doClick(0));
        Espera.ate(PRAZO, "resultado", () -> !ed(d::seloDoResultado).isEmpty());
        assertEquals("NÃO INSTALOU", ed(d::seloDoResultado));
        assertTrue(ed(d::textoDoResultado).contains("Outro instalador está rodando"));
        assertTrue(ed(d::textoDoResultado).contains("O passo que falhou: Passo 1 de 2"));
        assertTrue(ed(() -> d.botaoConferirDeNovo().isVisible()));
        CenarioDeCloudflare.fotografar(d, "dialogo-git-4-falhou");
    }

    @Test
    void semRootNemSudoNaoTemBotaoInstalarMostraOComandoParaODonoComCopiar() throws Exception {
        Previa p = new Previa(SEM_GIT, Situacao.PRECISA_DO_DONO, GerenciadorDePacotes.APT_GET, "Debian GNU/Linux 13 (trixie)", false, false, List.of(),
            List.of("sudo env DEBIAN_FRONTEND=noninteractive apt-get update", "sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y git"));
        DialogoInstalarGit d = abrir(p);
        assertFalse(ed(() -> d.botaoInstalar().isVisible()), "o app não instala sem permissão");
        assertEquals("Rode no Terminal", ed(d::tituloDosComandos));
        assertTrue(ed(d::textoDaExplicacao).contains("não digita senha"), ed(d::textoDaExplicacao));
        assertTrue(ed(() -> d.botaoConferirDeNovo().isVisible()));
        assertEquals("Já instalei, conferir de novo", ed(() -> d.botaoConferirDeNovo().getText()));
        Visual.naEdt(() -> d.botaoCopiar().doClick(0));
        assertEquals(ed(d::textoDosComandos), area.getContents(null).getTransferData(DataFlavor.stringFlavor));
        CenarioDeCloudflare.fotografar(d, "dialogo-git-5-sem-sudo");
        // o usuário instalou no Terminal: conferir de novo acha o git
        estadoDaConferencia = COM_GIT;
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick(0));
        Espera.ate(PRAZO, "achou", () -> ed(d::seloDoResultado).equals("INSTALADO"));
        assertTrue(ed(d::textoDoResultado).contains("O git responde em lab"));
        assertEquals(List.of("conferir", "conferir-de-novo"), chamadas);
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertEquals(1, mudou.get());
    }

    @Test
    void conferirDeNovoSemGitAindaDizAindaNao() {
        Previa p = new Previa(SEM_GIT, Situacao.GERENCIADOR_NAO_ACHADO, null, "", false, false, List.of(), List.of());
        DialogoInstalarGit d = abrir(p);
        assertTrue(ed(d::textoDaExplicacao).contains("não sei como instalar por aqui"), ed(d::textoDaExplicacao));
        assertFalse(ed(() -> d.botaoInstalar().isVisible()));
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick(0));
        Espera.ate(PRAZO, "ainda não", () -> ed(d::seloDoResultado).equals("AINDA NÃO"));
        assertEquals(0, mudou.get());
        CenarioDeCloudflare.fotografar(d, "dialogo-git-6-sem-gerenciador");
    }

    @Test
    void pararNuncaPrendeESegundoCliqueFechaComOResultadoNosAvisos() {
        segurar = new CountDownLatch(1);
        DialogoInstalarGit d = abrir(pode());
        Visual.naEdt(() -> d.botaoInstalar().doClick(0));
        Espera.ate(PRAZO, "andando", () -> ed(d::ocupado));
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertEquals("Fechar mesmo assim", ed(() -> d.botaoFechar().getText()));
        assertFalse(ed(d::fechou));
        Visual.naEdt(() -> d.botaoFechar().doClick(0));
        assertTrue(ed(d::fechou));
        segurar.countDown();
        Espera.ate(PRAZO, () -> "aviso: " + avisos.textos(), () -> avisos.textos().stream().anyMatch(t -> t.contains("git version 2.43.0")));
        assertTrue(mudou.get() >= 1);
    }
}
