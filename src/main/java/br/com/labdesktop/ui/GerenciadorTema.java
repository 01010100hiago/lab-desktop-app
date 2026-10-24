package br.com.labdesktop.ui;

import br.com.labdesktop.util.AparenciaDoSistema;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Preferencias.Tema;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.UIManager;

/**
 * O tema do app (ARQUITETURA 8.2): {@code FlatMacLightLaf} e {@code FlatMacDarkLaf} com os tokens do painel web
 * registrados por {@code FlatLaf.registerCustomDefaultsSource("br.com.labdesktop.ui.tema")}, fontes Inter e
 * JetBrains Mono. A escolha é CLARO, ESCURO ou SISTEMA; o sistema é consultado na abertura e ao ganhar foco
 * ({@link #conferirSistema()}), e a troca vale sem reiniciar.
 *
 * <p>Tudo aqui roda na EDT, exceto {@link #conferirSistema()}, que só dispara a consulta (um processo) numa thread
 * de fundo e volta à EDT para aplicar. Os componentes próprios leem as cores na hora de pintar
 * ({@code Tokens.cor}), então nada precisa ser refeito quando o tema muda.
 */
public final class GerenciadorTema {

    /** O pacote de recursos com os {@code FlatMacLightLaf.properties} e {@code FlatMacDarkLaf.properties} do app. */
    public static final String PACOTE_DO_TEMA = "br.com.labdesktop.ui.tema";

    private static boolean preparado;

    private Tema escolha;
    private boolean sistemaEscuro;
    private boolean aplicado;
    private boolean escuroAplicado;
    private final boolean animar;
    private final List<Runnable> ouvintes = new CopyOnWriteArrayList<>();

    /**
     * @param escolha tema pedido (claro, escuro ou o do sistema)
     * @param animar  {@code true} em produção: a troca esmaece. Os testes (sem janela) passam {@code false}.
     */
    public GerenciadorTema(Tema escolha, boolean animar) {
        this(escolha, false, animar);
    }

    /**
     * @param sistemaEscuroInicial como o macOS está AGORA (consultado fora da EDT, na partida): o tema já nasce certo,
     *                             sem instalar o claro e trocar logo em seguida (dois {@code setup} e um clarão).
     */
    public GerenciadorTema(Tema escolha, boolean sistemaEscuroInicial, boolean animar) {
        this.escolha = escolha == null ? Tema.SISTEMA : escolha;
        this.sistemaEscuro = sistemaEscuroInicial;
        this.animar = animar;
    }

    /**
     * Esquenta o que o primeiro {@code setup} custaria na EDT: registra o pacote do tema e as fontes e carrega a
     * biblioteca nativa do FlatLaf para o macOS (o {@code System.load} do dylib leva mais de 500 ms, e na EDT travaria a
     * primeira janela). Chame da thread principal, antes de abrir a EDT.
     */
    public static void preAquecer() {
        preparar();
        try {
            com.formdev.flatlaf.ui.FlatNativeMacLibrary.isLoaded();
        } catch (LinkageError | RuntimeException e) {
            // fora do macOS ou sem o dylib: o tema funciona do mesmo jeito, só sem o recurso nativo
        }
    }

    /**
     * Registra a fonte dos tokens e as fontes da interface. Uma vez por JVM (o {@code FlatLaf} guarda isso em
     * estado estático). Precisa vir ANTES do primeiro {@code setup}.
     */
    public static synchronized void preparar() {
        if (preparado) {
            return;
        }
        FlatLaf.registerCustomDefaultsSource(PACOTE_DO_TEMA);
        FlatInterFont.install();
        FlatJetBrainsMonoFont.install();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
        // JOptionPane e afins vêm em inglês; o app só os usa de reserva, mas em pt-BR.
        UIManager.put("OptionPane.yesButtonText", "Sim");
        UIManager.put("OptionPane.noButtonText", "Não");
        UIManager.put("OptionPane.cancelButtonText", "Cancelar");
        UIManager.put("OptionPane.okButtonText", "OK");
        preparado = true;
    }

    /** Instala o tema da escolha atual. Chame na EDT, depois de {@link #preparar()}. */
    public void instalar() {
        preparar();
        aplicar(efetivoEscuro());
    }

    /** O tema escolhido (não o efetivo: {@code SISTEMA} continua {@code SISTEMA}). */
    public Tema escolha() {
        return escolha;
    }

    /** {@code true} se o tema que está na tela é o escuro. */
    public boolean escuroAgora() {
        return aplicado ? escuroAplicado : efetivoEscuro();
    }

    /** Troca a escolha e aplica. Na EDT. */
    public void definirEscolha(Tema nova) {
        this.escolha = nova == null ? Tema.SISTEMA : nova;
        aplicar(efetivoEscuro());
    }

    /** O sistema mudou de claro para escuro (ou o contrário); só tem efeito com a escolha em {@code SISTEMA}. Na EDT. */
    public void definirSistemaEscuro(boolean escuro) {
        this.sistemaEscuro = escuro;
        if (escolha == Tema.SISTEMA) {
            aplicar(escuro);
        }
    }

    /**
     * Pergunta ao sistema (num processo, fora da EDT) e aplica na EDT. Chame na abertura e quando a janela ganha
     * foco; é barato se nada mudou.
     */
    public void conferirSistema() {
        if (escolha != Tema.SISTEMA) {
            return;
        }
        Fundo.emFundoComResultado(AparenciaDoSistema::escuro)
            .thenAccept(escuro -> Fundo.naEdt(() -> definirSistemaEscuro(escuro)));
    }

    /** Pede para ser avisado depois de cada troca de tema (por exemplo, para gráficos que guardam imagem). */
    public void aoMudar(Runnable ouvinte) {
        ouvintes.add(ouvinte);
    }

    private boolean efetivoEscuro() {
        return switch (escolha) {
            case ESCURO -> true;
            case CLARO -> false;
            case SISTEMA -> sistemaEscuro;
        };
    }

    private void aplicar(boolean escuro) {
        if (aplicado && escuro == escuroAplicado) {
            return;
        }
        boolean trocando = aplicado && animar;
        if (trocando) {
            FlatAnimatedLafChange.showSnapshot();
        }
        if (escuro) {
            FlatMacDarkLaf.setup();
        } else {
            FlatMacLightLaf.setup();
        }
        FlatLaf.updateUI();
        if (trocando) {
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
        aplicado = true;
        escuroAplicado = escuro;
        ouvintes.forEach(Runnable::run);
    }
}
