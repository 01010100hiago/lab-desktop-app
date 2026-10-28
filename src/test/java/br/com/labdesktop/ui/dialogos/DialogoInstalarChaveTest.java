package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.acesso.InstaladorDeChave;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.EtapaDoPrimeiroAcesso;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha.Motivo;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.SemTransbordo;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * "Instalar a chave com a senha" pelos controles do diálogo, com um motor de mentira: cada estado (pedindo a senha, trabalhando, senha errada, host que mudou, sem
 * impressão digital, pronto), a senha que sai do campo e é zerada, Esc que não fecha no meio e PNGs nos dois temas. O fluxo de verdade (servidor SSH, cofre, prova) está em
 * {@code ReparoDaChaveRecusadaTest} e {@code ChaveRecusadaNaJanelaTest}.
 */
@ExtendWith(VerificaEdt.class)
class DialogoInstalarChaveTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final String SENHA = "senha-do-servidor-Zk4!";
    private static final Pin PIN = new Pin("ssh-ed25519", "SHA256:mH3s1p2VwY+BAVOJ7MAG7JIZttP/NSiO9T4w4F165f0");
    private static final DialogoInstalarChave.Alvo ALVO = new DialogoInstalarChave.Alvo("lab", "labuser", "192.0.2.5", 2222, List.of(PIN), "lab",
        "SHA256:5MGLihSpPv/h6+y5NnTZfr2nnOaG8vJ9pEy1z3+jIQg", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExemploDeChavePublicaParaTeste lab-do-mac");

    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("teste");
    private final List<char[]> senhasRecebidas = new ArrayList<>();

    @AfterEach
    void desmontar() {
        Visual.tema(Tema.CLARO);
    }

    private DialogoInstalarChave abrir(DialogoInstalarChave.Alvo alvo, DialogoInstalarChave.Motor motor) {
        return Visual.naEdt(() -> new DialogoInstalarChave(alvo, (senha, progresso) -> {
            senhasRecebidas.add(senha);
            return motor.reparar(senha, progresso);
        }, new CopiadorDeTexto(() -> area), avisos));
    }

    private static DialogoInstalarChave.Motor falhando(Motivo motivo, String mensagem) {
        return (senha, progresso) -> {
            throw new FalhaDeEntradaPorSenha(motivo, mensagem);
        };
    }

    private static void digitarEClicar(DialogoInstalarChave d) {
        Visual.naEdt(() -> d.campoDaSenha().setText(SENHA));
        Visual.naEdt(() -> d.botaoInstalar().doClick());
    }

    private static void semTransbordo(DialogoInstalarChave d) {
        for (int largura : new int[] {560, 1100}) {
            Visual.alturaPreferida(d.raiz(), largura);
            List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(d.raiz()));
            assertTrue(achados.isEmpty(), "transborda em " + largura + " px: " + achados);
        }
        Visual.alturaPreferida(d.raiz(), 560);
    }

    private interface Cenario {
        DialogoInstalarChave montar();
    }

    /** O mesmo cenário nos dois temas (o diálogo é montado DEPOIS de escolher o tema, como o app faz), com o PNG de cada um. */
    private void nosDoisTemas(String nome, Cenario cenario) {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoInstalarChave d = cenario.montar();
            semTransbordo(d);
            Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "dialogo-instalar-chave-" + nome + "-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);
    }

    @Test
    void pedeASenhaMostrandoOServidorAImpressaoGuardadaEAChaveQueSeraInstalada() {
        nosDoisTemas("1-pedindo-a-senha", () -> abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.ADICIONADA));
        DialogoInstalarChave d = abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.ADICIONADA);
        String textos = Visual.naEdt(() -> textosDe(d.raiz()));
        assertTrue(textos.contains("labuser@192.0.2.5:2222"), textos);
        assertTrue(textos.contains(PIN.impressao()), "a impressão digital do servidor que o usuário confirmou: " + textos);
        assertTrue(textos.contains(ALVO.impressaoDaChave()), "a impressão digital da chave que será instalada: " + textos);
        assertTrue(textos.contains(DialogoInstalarChave.TEXTO_DA_IMPRESSAO), textos);
        assertEquals("Senha do usuário labuser no servidor (usada só agora, não fica guardada)", Visual.naEdt(d::rotuloDoCampo));
        assertTrue(Visual.naEdt(() -> d.botaoInstalar().isEnabled() && d.botaoInstalar().isVisible()));
        assertFalse(Visual.naEdt(d::parado));
        assertEquals(d.botaoInstalar(), d.botaoDoEnter());
        assertEquals("Instalar a chave com a senha", d.titulo());
        assertTrue(senhasRecebidas.isEmpty(), "abrir não manda nada a ninguém");
    }

    @Test
    void copiarAChavePublicaPoeALinhaDaChaveNaAreaDeTransferencia() throws Exception {
        DialogoInstalarChave d = abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.ADICIONADA);
        Visual.naEdt(() -> d.botaoCopiarChave().doClick());
        assertEquals(ALVO.chavePublica(), area.getData(DataFlavor.stringFlavor));
    }

    @Test
    void senhaVaziaNaoChamaOMotorEDizOQueFazer() {
        DialogoInstalarChave d = abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.ADICIONADA);
        Visual.naEdt(() -> d.botaoInstalar().doClick());
        assertEquals("Digite a senha do usuário labuser no servidor.", Visual.naEdt(d::textoDoErro));
        assertTrue(senhasRecebidas.isEmpty());
    }

    @Test
    void enquantoTrabalhaMostraAsEtapasNaoFechaComEscESenhaSaiDoCampoEFicaZeradaDepois() throws Exception {
        CountDownLatch segurar = new CountDownLatch(1);
        nosDoisTemas("2-trabalhando", () -> {
            DialogoInstalarChave d = abrir(ALVO, (s, p) -> {
                p.accept(EtapaDoPrimeiroAcesso.ENTRANDO);
                p.accept(EtapaDoPrimeiroAcesso.INSTALANDO);
                segurar.await(PRAZO.toSeconds(), TimeUnit.SECONDS);
                return InstaladorDeChave.Resultado.ADICIONADA;
            });
            digitarEClicar(d);
            Espera.ate(PRAZO, "o diálogo ocupado com a etapa", () -> Visual.naEdt(d::ocupado) && Visual.naEdt(d::andamento).startsWith("Instalando"));
            assertEquals(0, Visual.naEdt(() -> d.campoDaSenha().getPassword().length), "a senha saiu do campo no clique");
            assertFalse(Visual.naEdt(() -> d.botaoCancelar().isEnabled()));
            return d;
        });
        segurar.countDown();

        CountDownLatch segurarDeNovo = new CountDownLatch(1);
        DialogoInstalarChave d = abrir(ALVO, (s, p) -> {
            p.accept(EtapaDoPrimeiroAcesso.ENTRANDO);
            segurarDeNovo.await(PRAZO.toSeconds(), TimeUnit.SECONDS);
            return InstaladorDeChave.Resultado.ADICIONADA;
        });
        int antes = senhasRecebidas.size();
        digitarEClicar(d);
        Espera.ate(PRAZO, "ocupado", () -> Visual.naEdt(d::ocupado));
        Visual.naEdt(() -> d.aoEsc());
        assertFalse(Visual.naEdt(d::jaFechouParaTeste), "Esc não fecha no meio da operação");
        assertTrue(avisos.textos().stream().anyMatch(t -> t.startsWith("Espere terminar")), avisos.textos().toString());
        segurarDeNovo.countDown();
        Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(d::pronto));
        char[] entregue = senhasRecebidas.get(antes);
        for (char c : entregue) {
            assertEquals('\0', c, "o motor devolveu e a senha ficou zerada");
        }
    }

    @Test
    void senhaErradaMostraAFraseApagaOCampoEDeixaDigitarDeNovo() {
        nosDoisTemas("3-senha-errada", () -> {
            DialogoInstalarChave d = abrir(ALVO, falhando(Motivo.SENHA_INCORRETA,
                "O servidor não aceitou esta senha. Confira o usuário e digite a senha de novo. Restam 2 tentativas: o servidor pode bloquear o seu endereço depois de várias senhas erradas."));
            digitarEClicar(d);
            Espera.ate(PRAZO, "o erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
            assertTrue(Visual.naEdt(d::textoDoErro).startsWith("O servidor não aceitou esta senha"));
            assertEquals(0, Visual.naEdt(() -> d.campoDaSenha().getPassword().length));
            assertFalse(Visual.naEdt(d::parado), "senha errada deixa tentar de novo");
            assertTrue(Visual.naEdt(() -> d.botaoInstalar().isEnabled() && d.botaoInstalar().isVisible()));
            return d;
        });
    }

    @Test
    void hostQueMudouParaOFluxoEOferecaSoFechar() {
        nosDoisTemas("4-servidor-mudou", () -> {
            DialogoInstalarChave d = abrir(ALVO, falhando(Motivo.HOST_MUDOU, AssistenteDeServidor.TEXTO_DO_HOST_QUE_MUDOU));
            digitarEClicar(d);
            Espera.ate(PRAZO, "parou", () -> Visual.naEdt(d::parado));
            assertTrue(Visual.naEdt(d::textoDoErro).contains("A senha NÃO foi enviada"));
            assertFalse(Visual.naEdt(() -> d.botaoInstalar().isVisible()), "não há como tentar de novo daqui");
            assertTrue(Visual.naEdt(() -> d.botaoFechar().isVisible()));
            assertFalse(Visual.naEdt(() -> d.campoDaSenha().isEnabled()));
            assertEquals(d.botaoFechar(), d.botaoDoEnter());
            return d;
        });
    }

    @Test
    void semImpressaoDigitalConfirmadaNemDeixaDigitarASenha() {
        DialogoInstalarChave.Alvo semPin = new DialogoInstalarChave.Alvo("lab", "labuser", "192.0.2.5", 2222, List.of(), "lab", ALVO.impressaoDaChave(), ALVO.chavePublica());
        nosDoisTemas("5-sem-impressao", () -> {
            DialogoInstalarChave d = abrir(semPin, (s, p) -> {
                throw new AssertionError("não deve chamar o motor");
            });
            assertTrue(Visual.naEdt(d::parado));
            assertTrue(Visual.naEdt(d::textoDoErro).contains("Conferir a impressão do servidor"));
            assertFalse(Visual.naEdt(() -> d.campoDaSenha().isEnabled()));
            assertTrue(Visual.naEdt(() -> textosDe(d.raiz())).contains("sem impressão digital confirmada"));
            return d;
        });
        assertTrue(senhasRecebidas.isEmpty());
    }

    @Test
    void servidorSemSenhaParaEMostraAFraseDeEntregarAChaveAQuemAdministra() {
        DialogoInstalarChave d = abrir(ALVO, falhando(Motivo.SERVIDOR_SEM_SENHA, AssistenteDeServidor.TEXTO_SERVIDOR_SEM_SENHA));
        digitarEClicar(d);
        Espera.ate(PRAZO, "parou", () -> Visual.naEdt(d::parado));
        assertEquals(AssistenteDeServidor.TEXTO_SERVIDOR_SEM_SENHA, Visual.naEdt(d::textoDoErro));
        assertTrue(Visual.naEdt(() -> d.botaoCopiarChave().isVisible()), "a chave pública continua à mão para entregar ao administrador");
    }

    @Test
    void pausaDoFreioDeixaTentarDepois() {
        DialogoInstalarChave d = abrir(ALVO, falhando(Motivo.MUITAS_TENTATIVAS, "Espere 8 minutos antes de tentar de novo: o servidor pode bloquear o seu endereço depois de várias senhas erradas."));
        digitarEClicar(d);
        Espera.ate(PRAZO, "o erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertFalse(Visual.naEdt(d::parado));
        assertTrue(Visual.naEdt(() -> d.botaoInstalar().isEnabled()));
    }

    @Test
    void prontoNaoPerguntaDeDesligarASenhaSoDizOndeSeFaz() {
        nosDoisTemas("6-pronto", () -> {
            DialogoInstalarChave d = abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.ADICIONADA);
            digitarEClicar(d);
            Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(d::pronto));
            String fim = Visual.naEdt(d::textoDoFim);
            assertTrue(fim.contains(DialogoInstalarChave.TEXTO_DE_PRONTO), fim);
            assertTrue(fim.contains("Para desligar o login por senha, use Servidor > Segurança."), fim);
            assertEquals("CHAVE INSTALADA", Visual.naEdt(d::seloDoFim));
            assertFalse(Visual.naEdt(() -> d.botaoInstalar().isVisible()));
            assertTrue(Visual.naEdt(() -> d.botaoFechar().isVisible()));
            assertFalse(Visual.naEdt(() -> textosDe(d.raiz())).contains("Desligar a senha"), "o diálogo não oferece desligar a senha");
            return d;
        });
        assertTrue(avisos.textos().stream().anyMatch(t -> t.startsWith("Chave instalada em lab")), avisos.textos().toString());
    }

    @Test
    void chaveQueJaEstavaNoServidorDizIssoEAvisaDaLinhaInutil() {
        DialogoInstalarChave d = abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.JA_EXISTIA);
        digitarEClicar(d);
        Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(d::pronto));
        assertTrue(Visual.naEdt(d::textoDoFim).contains(DialogoInstalarChave.TEXTO_DE_JA_ESTAVA));
        DialogoInstalarChave e = abrir(ALVO, (s, p) -> InstaladorDeChave.Resultado.ADICIONADA_APESAR_DE_LINHA_INUTIL);
        digitarEClicar(e);
        Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(e::pronto));
        assertTrue(avisos.textos().stream().anyMatch(t -> t.contains("só comentada ou com uma restrição")), avisos.textos().toString());
    }

    @Test
    void oDialogoNuncaGuardaASenhaEmNenhumTextoDaTela() {
        DialogoInstalarChave d = abrir(ALVO, (s, p) -> {
            throw new FalhaDeEntradaPorSenha(Motivo.SENHA_INCORRETA, "O servidor não aceitou esta senha.");
        });
        digitarEClicar(d);
        Espera.ate(PRAZO, "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        String tudo = Visual.naEdt(() -> textosDe(d.raiz())) + d.rotuloDoCampo() + d.andamento();
        assertFalse(tudo.contains(SENHA), "nenhum texto da tela tem a senha");
    }

    private static String textosDe(java.awt.Component c) {
        if (c == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t && !(c instanceof javax.swing.JPasswordField)) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                if (f.isVisible()) {
                    sb.append(textosDe(f));
                }
            }
        }
        return sb.toString();
    }
}
