package br.com.labdesktop.ui;

import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.telas.ajustes.ControleDeAjustes;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.util.InformacoesDoApp;
import br.com.labdesktop.util.Preferencias;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.function.UnaryOperator;

/** O {@link ControleDeAjustes} de verdade: liga a tela Ajustes ao tema, às preferências, ao painel principal e aos dados do app. */
public final class AjustesDoApp implements ControleDeAjustes {

    private final GerenciadorTema tema;
    private final PreferenciasAtuais preferencias;
    private final PainelPrincipal painel;
    private final CopiadorDeTexto copiador;
    private final Notificacoes avisos;
    private final java.util.function.Supplier<java.util.List<br.com.labdesktop.ajuda.AtalhoDoMenu>> atalhos;

    public AjustesDoApp(GerenciadorTema tema, PreferenciasAtuais preferencias, PainelPrincipal painel, CopiadorDeTexto copiador, Notificacoes avisos) {
        this(tema, preferencias, painel, copiador, avisos, java.util.List::of);
    }

    /** @param atalhos os atalhos do menu de agora (o {@code MenuApp} os dá); vazio onde não há menu (testes simples) */
    public AjustesDoApp(GerenciadorTema tema, PreferenciasAtuais preferencias, PainelPrincipal painel, CopiadorDeTexto copiador, Notificacoes avisos,
                        java.util.function.Supplier<java.util.List<br.com.labdesktop.ajuda.AtalhoDoMenu>> atalhos) {
        this.atalhos = atalhos;
        this.tema = tema;
        this.preferencias = preferencias;
        this.painel = painel;
        this.copiador = copiador;
        this.avisos = avisos;
    }

    /** Troca o tamanho do texto de toda a interface (100, 115 ou 130 %) na hora e lembra. Serve ao menu Visualizar e aos Ajustes. */
    static void aplicarTamanhoDoTexto(GerenciadorTema tema, PreferenciasAtuais preferencias, int porcento) {
        Fontes.definirPercentual(porcento);
        tema.reaplicarFontes();
        int atual = Fontes.percentual();
        preferencias.atualizar(p -> p.comEscalaTexto(atual));
    }

    @Override
    public Preferencias preferencias() {
        return preferencias.get();
    }

    @Override
    public void atualizarPreferencias(UnaryOperator<Preferencias> mudanca) {
        preferencias.atualizar(mudanca);
    }

    @Override
    public void trocarTema(Tema novo, Runnable depois) {
        // "Sistema" espera a resposta do computador antes de valer (sem piscar no tema errado); a preferência é gravada quando a escolha já está na tela
        tema.escolher(novo, () -> {
            preferencias.atualizar(p -> p.comTema(novo));
            depois.run();
        });
    }

    @Override
    public boolean sistemaEstaEscuro() {
        return tema.sistemaEstaEscuro();
    }

    @Override
    public boolean temaEscuroAgora() {
        return tema.escuroAgora();
    }

    @Override
    public void aoMudarOTema(Runnable ouvinte) {
        tema.aoMudar(ouvinte);
    }

    @Override
    public void mudarTamanhoDoTexto(int porcento) {
        aplicarTamanhoDoTexto(tema, preferencias, porcento);
    }

    @Override
    public int tamanhoDoTexto() {
        return Fontes.percentual();
    }

    @Override
    public void aoMudarAsAbas() {
        painel.navegacao().reaplicarAbas(preferencias.get());
    }

    @Override
    public String abaAbertaDoServidor() {
        return painel.abaAbertaDoServidor();
    }

    @Override
    public String abaAbertaDoProjeto() {
        return painel.abaAbertaDoProjeto();
    }

    @Override
    public java.util.List<br.com.labdesktop.ajuda.AtalhoDoMenu> atalhosDoMenu() {
        return atalhos.get();
    }

    @Override
    public boolean irPara(br.com.labdesktop.ajuda.Destino destino) {
        return switch (destino) {
            case SERVIDORES -> {
                painel.mostrarServidores();
                yield true;
            }
            case RESUMO -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.RESUMO);
            case SEGURANCA -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.SEGURANCA);
            case CONTAINERS -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.CONTAINERS);
            case PROCESSOS -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.PROCESSOS);
            case MAQUINA -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.MAQUINA);
            case PROJETOS -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.PROJETOS);
            case ARQUIVOS -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.ARQUIVOS);
            case TERMINAL -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.TERMINAL);
            case CLOUDFLARE -> painel.irParaAba(br.com.labdesktop.ui.telas.TelaServidor.Aba.CLOUDFLARE);
            case AJUSTES_APARENCIA -> abrirAjustes(br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao.APARENCIA);
            case AJUSTES_NAVEGACAO -> abrirAjustes(br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao.NAVEGACAO);
            case AJUSTES_TERMINAL -> abrirAjustes(br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao.TERMINAL);
            case AJUSTES_SOBRE -> abrirAjustes(br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao.SOBRE);
        };
    }

    private boolean abrirAjustes(br.com.labdesktop.ui.telas.ajustes.TelaAjustes.Secao secao) {
        painel.mostrarAjustes(secao);
        return true;
    }

    @Override
    public InformacoesDoApp informacoes() {
        return InformacoesDoApp.coletar(preferencias.caminhos());
    }

    @Override
    public CopiadorDeTexto copiador() {
        return copiador;
    }

    @Override
    public Notificacoes avisos() {
        return avisos;
    }
}
