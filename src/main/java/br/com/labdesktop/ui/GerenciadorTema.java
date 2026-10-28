package br.com.labdesktop.ui;

import br.com.labdesktop.util.AparenciaDoSistema;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Preferencias.Tema;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.swing.UIManager;

/**
 * O tema do app (ARQUITETURA 8.2): {@code FlatMacLightLaf} e {@code FlatMacDarkLaf} no Mac, {@code FlatLightLaf} e {@code FlatDarkLaf} no Windows e no Linux ({@link EstiloDeLaf}),
 * sempre com os MESMOS tokens do painel web registrados por {@code FlatLaf.registerCustomDefaultsSource("br.com.labdesktop.ui.tema")}, fontes Inter e
 * JetBrains Mono. A escolha é CLARO, ESCURO ou SISTEMA; o sistema é consultado na abertura e ao ganhar foco
 * ({@link #conferirSistema()}), e a troca vale sem reiniciar.
 *
 * <p>Tudo aqui roda na EDT, exceto {@link #conferirSistema()}, que só dispara a consulta (um processo) numa thread
 * de fundo e volta à EDT para aplicar. Os componentes próprios leem as cores na hora de pintar
 * ({@code Tokens.cor}), então nada precisa ser refeito quando o tema muda.
 */
public final class GerenciadorTema {

    /** O pacote de recursos com os {@code FlatLightLaf.properties}, {@code FlatDarkLaf.properties} (tokens, valem para os dois estilos) e {@code FlatMac*Laf.properties} (só o que é do Mac) do app. */
    public static final String PACOTE_DO_TEMA = "br.com.labdesktop.ui.tema";

    private static boolean preparado;

    private Tema escolha;
    private boolean sistemaEscuro;
    private boolean aplicado;
    private boolean escuroAplicado;
    private final boolean animar;
    private final EstiloDeLaf estilo;
    private final List<Runnable> ouvintes = new CopyOnWriteArrayList<>();
    private BooleanSupplier consultaDoSistema = AparenciaDoSistema::escuro;

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
        this(escolha, sistemaEscuroInicial, animar, EstiloDeLaf.padrao());
    }

    /** Com o estilo do LaF dado (os testes provam o do Windows e o do Mac no mesmo computador). */
    public GerenciadorTema(Tema escolha, boolean sistemaEscuroInicial, boolean animar, EstiloDeLaf estilo) {
        this.estilo = estilo;
        this.escolha = escolha == null ? Tema.SISTEMA : escolha;
        this.sistemaEscuro = sistemaEscuroInicial;
        this.animar = animar;
    }

    /** Troca quem diz como o sistema está (um processo, rodado fora da EDT): os testes põem uma resposta que controlam, em vez de rodar o comando do sistema. */
    public GerenciadorTema comConsultaDoSistema(BooleanSupplier consulta) {
        this.consultaDoSistema = consulta;
        return this;
    }

    /**
     * Esquenta o que o primeiro {@code setup} custaria na EDT: registra o pacote do tema e as fontes e carrega a
     * biblioteca nativa do FlatLaf para o macOS (o {@code System.load} do dylib leva mais de 500 ms, e na EDT travaria a
     * primeira janela). Chame da thread principal, antes de abrir a EDT.
     */
    public static void preAquecer() {
        preparar();
        if (EstiloDeLaf.padrao() != EstiloDeLaf.MAC) {
            return; // só o Mac tem o dylib que demora; o do Windows (FlatLaf) só é carregado se houver decoração própria de janela, que o app não usa
        }
        try {
            com.formdev.flatlaf.ui.FlatNativeMacLibrary.isLoaded();
        } catch (LinkageError | RuntimeException e) {
            // sem o dylib: o tema funciona do mesmo jeito, só sem o recurso nativo
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

    /** O estilo do LaF que este gerenciador instala. */
    public EstiloDeLaf estilo() {
        return estilo;
    }

    /** O tema escolhido (não o efetivo: {@code SISTEMA} continua {@code SISTEMA}). */
    public Tema escolha() {
        return escolha;
    }

    /** {@code true} se o tema que está na tela é o escuro. */
    public boolean escuroAgora() {
        return aplicado ? escuroAplicado : efetivoEscuro();
    }

    /** Como o sistema (macOS, Windows) está agora, pelo que foi lido por último ({@link #conferirSistema()}): é o que "Sistema" segue. */
    public boolean sistemaEstaEscuro() {
        return sistemaEscuro;
    }

    /** Troca a escolha e aplica, sem consultar o sistema (para escolha de claro ou escuro, ou quando o sistema acabou de ser lido). Na EDT. */
    public void definirEscolha(Tema nova) {
        this.escolha = nova == null ? Tema.SISTEMA : nova;
        aplicar(efetivoEscuro());
    }

    /**
     * A escolha do usuário na tela de Ajustes. Para "Sistema" o claro ou escuro do computador é lido ANTES (num processo, fora da EDT) e só então a escolha vale, já com o
     * tema certo: aplicar a escolha com o valor velho do sistema e corrigir depois fazia o app piscar no tema errado. Claro e escuro valem na hora.
     * {@code depois} roda na EDT, depois de a escolha estar aplicada (é onde quem chamou grava a preferência e atualiza a tela). Chame na EDT.
     */
    public void escolher(Tema nova, Runnable depois) {
        Tema pedida = nova == null ? Tema.SISTEMA : nova;
        if (pedida != Tema.SISTEMA) {
            definirEscolha(pedida);
            depois.run();
            return;
        }
        Fundo.emFundoComResultado(consultaDoSistema::getAsBoolean).whenComplete((escuro, erro) -> Fundo.naEdt(() -> {
            if (erro == null) {
                this.sistemaEscuro = escuro; // antes de aplicar a escolha: o tema já nasce certo e a frase "o sistema está usando" nunca fica errada (sem avisar ninguém: o depois() atualiza a tela)
            }
            definirEscolha(pedida);
            depois.run();
        }));
    }

    /** O sistema mudou de claro para escuro (ou o contrário). Só muda o tema com a escolha em {@code SISTEMA}; com outra, só guarda (a frase dos Ajustes usa). Na EDT. */
    public void definirSistemaEscuro(boolean escuro) {
        registrarSistema(escuro);
        if (escolha == Tema.SISTEMA) {
            aplicar(escuro);
        }
    }

    private void registrarSistema(boolean escuro) {
        boolean mudou = escuro != sistemaEscuro;
        this.sistemaEscuro = escuro;
        if (mudou && escolha != Tema.SISTEMA) {
            ouvintes.forEach(Runnable::run); // o tema não mudou, mas quem mostra "o sistema está usando..." precisa saber (com Sistema o aplicar() avisa)
        }
    }

    /**
     * Pergunta ao sistema (num processo, fora da EDT) e guarda na EDT: com a escolha em {@code SISTEMA} o tema acompanha. Chame na abertura e quando a janela ganha
     * foco; é barato se nada mudou. Consulta mesmo com claro ou escuro escolhido, para a frase dos Ajustes ("agora o sistema está usando...") e a próxima escolha de Sistema
     * já saberem a resposta.
     */
    public void conferirSistema() {
        Fundo.emFundoComResultado(consultaDoSistema::getAsBoolean)
            .thenAccept(escuro -> Fundo.naEdt(() -> definirSistemaEscuro(escuro)));
    }

    /**
     * O usuário mudou o tamanho do texto (Visualizar, "Aumentar texto"): refaz as fontes dos componentes padrão e avisa quem
     * guarda imagem ou medida. As fontes dos componentes do app já foram refeitas por {@code Fontes.definirPercentual}. Na EDT.
     */
    public void reaplicarFontes() {
        if (!aplicado) {
            return;
        }
        Fontes.instalarBase();
        FlatLaf.updateUI();
        ouvintes.forEach(Runnable::run);
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
        if (estilo == EstiloDeLaf.MAC) {
            if (escuro) {
                FlatMacDarkLaf.setup();
            } else {
                FlatMacLightLaf.setup();
            }
        } else if (escuro) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        Fontes.instalarBase(); // o texto base (14, com o fator do usuário) vale para todo componente padrão
        FlatLaf.updateUI();
        if (trocando) {
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
        aplicado = true;
        escuroAplicado = escuro;
        ouvintes.forEach(Runnable::run);
    }
}
