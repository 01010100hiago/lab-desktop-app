package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.OperacoesDe2fa;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.SegredoTotp;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.teste.AgendadorFalso;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiaTemporaria;
import br.com.labdesktop.util.Preferencias.Tema;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import javax.swing.ImageIcon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** "Configurar 2FA": QR de ida e volta, confirmação do código, desativar e o segredo sempre zerado ao fechar. */
@ExtendWith(VerificaEdt.class)
class Configurar2faTest {

    private static final String SEGREDO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/Lab%20Desktop:lab?secret=" + SEGREDO + "&issuer=Lab%20Desktop";

    /** Um servidor de mentira para o diálogo. */
    private static class Falso implements OperacoesDe2fa {
        volatile String totp = "desligado";
        volatile boolean versaoFalha;
        volatile String codigoCerto = "123456";
        final List<String> chamadas = new ArrayList<>();
        final List<SegredoTotp> segredos = new ArrayList<>();
        volatile CountDownLatch seguraOIniciar;

        @Override
        public VersaoAgente consultarVersao() throws ErroApi {
            chamadas.add("versao");
            if (versaoFalha) {
                throw new ErroApi.ErroRede("fora", null);
            }
            return new VersaoAgente(1, "v22", false, "mac", totp);
        }

        @Override
        public SegredoTotp iniciarConfiguracao2fa() throws ErroProvisionamento {
            chamadas.add("iniciar");
            if (seguraOIniciar != null) {
                try {
                    seguraOIniciar.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            SegredoTotp s = SegredoTotp.de(SEGREDO, URI);
            segredos.add(s);
            return s;
        }

        @Override
        public Optional<Instant> confirmarConfiguracao2fa(String codigo) throws ErroProvisionamento {
            chamadas.add("confirmar:" + codigo);
            if (!codigoCerto.equals(codigo)) {
                throw new ErroProvisionamento(ErroProvisionamento.TOTP_INCORRETO, "Código incorreto ou já usado.");
            }
            totp = "ativo";
            return Optional.of(Instant.parse("2026-11-23T12:00:00Z"));
        }

        @Override
        public void desativar2fa(String codigo) throws ErroProvisionamento {
            chamadas.add("desativar:" + codigo);
            if (!codigoCerto.equals(codigo)) {
                throw new ErroProvisionamento(ErroProvisionamento.TOTP_INCORRETO, "Código incorreto ou já usado.");
            }
            totp = "desligado";
        }
    }

    private final Falso servidor = new Falso();
    private final AvisosGravados avisos = new AvisosGravados();

    private Configurar2fa abrir(Estado2fa dica) {
        Configurar2fa c = Visual.naEdt(() -> new Configurar2fa("lab", dica, servidor, avisos));
        Visual.naEdt(c::carregar);
        return c;
    }

    /** A área de transferência e o relógio de mentira da cópia da chave. */
    private final Clipboard area = new Clipboard("teste");
    private final AgendadorFalso relogio = new AgendadorFalso();

    private Configurar2fa abrirComCopiaFalsa() {
        CopiaTemporaria copia = new CopiaTemporaria(() -> area, relogio, Duration.ofSeconds(30));
        Configurar2fa c = Visual.naEdt(() -> new Configurar2fa("lab", Estado2fa.DESLIGADO, servidor, avisos, copia));
        Visual.naEdt(c::carregar);
        return c;
    }

    private Configurar2fa abrirNoQrComCopiaFalsa() {
        Configurar2fa c = abrirComCopiaFalsa();
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        esperarCartao(c, "qr");
        return c;
    }

    private String naArea() throws Exception {
        var t = area.getContents(null);
        return t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor) ? null : (String) t.getTransferData(DataFlavor.stringFlavor);
    }

    private static void esvaziarEdt() throws Exception {
        java.awt.EventQueue.invokeAndWait(() -> { });
    }

    private static void esperarCartao(Configurar2fa c, String cartao) {
        Espera.ate(Duration.ofSeconds(10), () -> "cartão " + cartao + " (está em " + Visual.naEdt(c::cartaoAtual) + ")",
            () -> cartao.equals(Visual.naEdt(c::cartaoAtual)));
    }

    @Test
    void desligadoOferecConfigurarEOQrDecodificaDeVoltaNaMesmaUri() throws Exception {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        esperarCartao(c, "qr");

        assertTrue(Visual.naEdt(c::mostraQr));
        assertEquals("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ", Visual.naEdt(c::textoDaChave), "chave em grupos de 4, para digitar à mão");
        BufferedImage imagem = Visual.naEdt(() -> (BufferedImage) ((ImageIcon) iconeDoQr(c)).getImage());
        var lido = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(imagem))));
        assertEquals(URI, lido.getText(), "o QR na tela é a URI do agente, byte a byte");
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(c.raiz()));
            Visual.salvar(Visual.renderizar(c.raiz(), 520, Visual.alturaPreferida(c.raiz(), 520)), "dialogo-configurar-2fa-qr-" + tema.name().toLowerCase());
        }
    }

    private static javax.swing.Icon iconeDoQr(Configurar2fa c) {
        return findIcon(c.raiz());
    }

    private static javax.swing.Icon findIcon(java.awt.Component raiz) {
        if (raiz instanceof javax.swing.JLabel l && l.getIcon() instanceof ImageIcon i && i.getIconWidth() >= 200) {
            return i;
        }
        if (raiz instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                javax.swing.Icon r = findIcon(f);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    @Test
    void confirmarComOCodigoCertoAtivaMostraAValidadeEZeraOSegredo() {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        esperarCartao(c, "qr");
        SegredoTotp segredo = servidor.segredos.get(0);
        assertFalse(segredo.estaZerado());

        assertFalse(Visual.naEdt(() -> c.botaoConfirmar().isEnabled()));
        Visual.naEdt(() -> c.campoDeConfirmacao().setText("123 456"));
        assertTrue(Visual.naEdt(() -> c.botaoConfirmar().isEnabled()));
        Visual.naEdt(() -> c.botaoConfirmar().doClick());
        esperarCartao(c, "pronto");

        assertTrue(servidor.chamadas.contains("confirmar:123456"));
        assertTrue(Visual.naEdt(c::textoPronto).contains("30 dias"), Visual.naEdt(c::textoPronto));
        assertTrue(segredo.estaZerado(), "ativado: o segredo sai da memória e da tela");
        assertNull(Visual.naEdt(c::segredoEmMemoria));
        assertFalse(Visual.naEdt(c::mostraQr));
        assertEquals("", Visual.naEdt(c::textoDaChave));
        assertTrue(avisos.disse("ok", "2FA ativado em lab"));
    }

    @Test
    void codigoErradoMostraOMotivoENaoApagaOSegredoParaTentarDeNovo() {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        esperarCartao(c, "qr");
        Visual.naEdt(() -> c.campoDeConfirmacao().setText("000000"));
        Visual.naEdt(() -> c.botaoConfirmar().doClick());
        Espera.ate(Duration.ofSeconds(10), () -> "erro", () -> !Visual.naEdt(c::textoDoErroDeConfirmar).isEmpty());
        assertTrue(Visual.naEdt(c::textoDoErroDeConfirmar).contains("hora do celular"), Visual.naEdt(c::textoDoErroDeConfirmar));
        assertEquals("qr", Visual.naEdt(c::cartaoAtual));
        assertFalse(servidor.segredos.get(0).estaZerado(), "ainda dá para escanear e tentar de novo");
        assertEquals("", Visual.naEdt(() -> c.campoDeConfirmacao().getText()));
    }

    @Test
    void fecharAntesDeConfirmarZeraOSegredoEODoisFechamentosNaoDaoErro() {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        esperarCartao(c, "qr");
        SegredoTotp segredo = servidor.segredos.get(0);

        Visual.naEdt(() -> c.aoCancelar());
        assertTrue(segredo.estaZerado(), "fechou sem confirmar: o 2FA fica pendente e o segredo some");
        assertNull(Visual.naEdt(c::segredoEmMemoria));
        Visual.naEdt(() -> c.aoCancelar());
        assertFalse(servidor.chamadas.stream().anyMatch(x -> x.startsWith("confirmar")));
    }

    @Test
    void seODonoFechaEnquantoOSshResponde_osegredoQueChegarDepoisEZerado() throws Exception {
        Visual.tema(Tema.CLARO);
        servidor.seguraOIniciar = new CountDownLatch(1);
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        Espera.ate(Duration.ofSeconds(5), () -> "iniciar chamado", () -> servidor.chamadas.contains("iniciar"));
        Visual.naEdt(() -> c.aoCancelar());
        servidor.seguraOIniciar.countDown();
        Espera.ate(Duration.ofSeconds(5), () -> "segredo gerado", () -> servidor.segredos.size() == 1);
        Espera.ate(Duration.ofSeconds(5), () -> "segredo zerado", () -> servidor.segredos.get(0).estaZerado());
        assertFalse(Visual.naEdt(c::mostraQr));
    }

    @Test
    void copiarAChaveDizQueExpiraETiraDaAreaDeTransferenciaEm30Segundos() throws Exception {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrirNoQrComCopiaFalsa();

        Visual.naEdt(() -> c.botaoCopiar().doClick());
        assertEquals(SEGREDO, naArea(), "o botão Copiar põe a chave na área de transferência");
        assertTrue(avisos.disse("info", "30 s"), "o aviso diz que a cópia expira: " + avisos.todos);
        assertTrue(Visual.naEdt(() -> c.botaoCopiar().getToolTipText()).contains("30 s"));

        relogio.avancar(Duration.ofSeconds(31));
        esvaziarEdt();
        assertEquals("", naArea(), "passados 30 s a chave não está mais lá");
        assertEquals("qr", Visual.naEdt(c::cartaoAtual), "o diálogo continua aberto");
    }

    @Test
    void fecharODialogoTiraAChaveCopiadaDaAreaDeTransferencia() throws Exception {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrirNoQrComCopiaFalsa();
        Visual.naEdt(() -> c.botaoCopiar().doClick());
        assertEquals(SEGREDO, naArea());

        Visual.naEdt(() -> c.aoCancelar());

        assertEquals("", naArea(), "fechou: a chave sai da área de transferência junto com o segredo da memória");
        assertTrue(servidor.segredos.get(0).estaZerado());
    }

    @Test
    void confirmarOCodigoTiraAChaveCopiadaMesmoComODialogoAindaAberto() throws Exception {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrirNoQrComCopiaFalsa();
        Visual.naEdt(() -> c.botaoCopiar().doClick());
        Visual.naEdt(() -> c.campoDeConfirmacao().setText("123456"));
        Visual.naEdt(() -> c.botaoConfirmar().doClick());
        esperarCartao(c, "pronto");

        assertEquals("", naArea(), "2FA ativo: a semente não tem mais o que fazer na área de transferência");
    }

    @Test
    void naoApagaOQueODonoCopiouDepoisDaChave() throws Exception {
        Visual.tema(Tema.CLARO);
        Configurar2fa c = abrirNoQrComCopiaFalsa();
        Visual.naEdt(() -> c.botaoCopiar().doClick());
        area.setContents(new StringSelection("a lista de compras do usuário"), null);

        relogio.avancar(Duration.ofSeconds(31));
        esvaziarEdt();
        Visual.naEdt(() -> c.aoCancelar());

        assertEquals("a lista de compras do usuário", naArea());
    }

    @Test
    void semCopiarNadaFecharNaoTocaNaAreaDeTransferencia() throws Exception {
        Visual.tema(Tema.CLARO);
        area.setContents(new StringSelection("texto do usuário"), null);
        Configurar2fa c = abrirNoQrComCopiaFalsa();

        Visual.naEdt(() -> c.aoCancelar());

        assertEquals("texto do usuário", naArea());
    }

    @Test
    void ativoOferecDesativarEExigeUmCodigoValido() {
        Visual.tema(Tema.CLARO);
        servidor.totp = "ativo";
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ativo");
        assertFalse(Visual.naEdt(() -> c.botaoDesativar().isEnabled()));

        Visual.naEdt(() -> c.campoParaDesativar().setText("999999"));
        Visual.naEdt(() -> c.botaoDesativar().doClick());
        Espera.ate(Duration.ofSeconds(10), () -> "erro", () -> !Visual.naEdt(c::textoDoErroDeDesativar).isEmpty());
        assertEquals("ativo", servidor.totp, "código errado: nada muda");

        Visual.naEdt(() -> c.campoParaDesativar().setText("123 456"));
        Visual.naEdt(() -> c.botaoDesativar().doClick());
        Espera.ate(Duration.ofSeconds(10), () -> "desativado", () -> "desligado".equals(servidor.totp));
        Espera.ate(Duration.ofSeconds(5), () -> "aviso", () -> avisos.disse("ok", "2FA desativado em lab"));
    }

    @Test
    void pendenteOferecConfigurarDeNovoENaoFalaEmDesativar() {
        Visual.tema(Tema.CLARO);
        servidor.totp = "pendente";
        Configurar2fa c = abrir(Estado2fa.DESLIGADO);
        esperarCartao(c, "ligar");
        assertTrue(Visual.naEdt(() -> c.raiz().isVisible()));
    }

    @Test
    void seOAgenteNaoRespondeUsaOUltimoEstadoConhecido() {
        Visual.tema(Tema.CLARO);
        servidor.versaoFalha = true;
        Configurar2fa c = abrir(Estado2fa.ATIVO); // a dica do cofre diz "ativo": por exemplo, aguardando o código
        esperarCartao(c, "ativo");
        servidor.versaoFalha = true;
        Configurar2fa d = abrir(Estado2fa.DESLIGADO);
        esperarCartao(d, "ligar");
    }

    @Test
    void erroAoGerarOSegredoAparecaNoCartao() {
        Visual.tema(Tema.CLARO);
        Falso quebrado = new Falso() {
            @Override
            public SegredoTotp iniciarConfiguracao2fa() throws ErroProvisionamento {
                throw new ErroProvisionamento(ErroProvisionamento.SEM_SESSAO, "sem sessão");
            }
        };
        Configurar2fa c = Visual.naEdt(() -> new Configurar2fa("lab", Estado2fa.DESLIGADO, quebrado, avisos));
        Visual.naEdt(c::carregar);
        esperarCartao(c, "ligar");
        Visual.naEdt(() -> c.botaoConfigurar().doClick());
        Espera.ate(Duration.ofSeconds(10), () -> "erro", () -> !Visual.naEdt(c::textoDoErroDeLigar).isEmpty());
        assertTrue(Visual.naEdt(c::textoDoErroDeLigar).contains("Sem conexão SSH"), Visual.naEdt(c::textoDoErroDeLigar));
        assertTrue(Visual.naEdt(() -> c.botaoConfigurar().isEnabled()), "o botão volta ao normal para tentar de novo");
    }
}
