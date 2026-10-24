package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.telas.FluxosDeProjeto;
import br.com.labdesktop.ui.telas.TelaProjetos;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.CardLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A área de conteúdo da janela (ARQUITETURA 4.2): uma {@link TelaProjetos} por servidor e a {@link TelaServidores},
 * uma de cada vez. Cada tela de projetos assina o servidor ao ficar visível e solta ao esconder, então só a que está
 * na tela consulta o agente. Sem nenhum servidor cadastrado, mostra a tela de servidores (com o convite para adicionar
 * o primeiro).
 */
public final class Navegacao extends Painel {

    private static final long serialVersionUID = 1L;
    private static final String SERVIDORES = "servidores";
    private static final String PREFIXO_DO_SERVIDOR = "servidor:";

    private final GerenciadorServidores gerenciador;
    private final AcoesDaInterface acoes;
    private final Navegador navegador;
    private final Notificacoes avisos;
    private final Apresentador apresentador;
    private final CardLayout cartoes = new CardLayout();
    private final Map<String, TelaProjetos> telas = new LinkedHashMap<>();
    private final TelaServidores telaServidores;
    private final Inscricao inscricaoDaLista;
    private final java.util.List<Consumer<String>> ouvintes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String atual = SERVIDORES;

    public Navegacao(GerenciadorServidores gerenciador, AcoesDaInterface acoes, Navegador navegador, Notificacoes avisos,
                     Apresentador apresentador) {
        super();
        setLayout(cartoes);
        fundo(Tokens.BG);
        this.gerenciador = gerenciador;
        this.acoes = acoes;
        this.navegador = navegador;
        this.avisos = avisos;
        this.apresentador = apresentador;
        this.telaServidores = new TelaServidores(gerenciador, acoes);
        add(telaServidores, SERVIDORES);
        cartoes.show(this, SERVIDORES);
        inscricaoDaLista = gerenciador.observarLista(this::aoMudarALista);
    }

    private TelaProjetos garantirTela(ServidorLab lab) {
        return telas.computeIfAbsent(lab.id(), id -> {
            FluxosDeProjeto fluxos = new FluxosDeProjeto(lab, avisos, apresentador, FluxosDeProjeto.liberadasPorPropriedade());
            TelaProjetos t = new TelaProjetos(lab, fluxos, navegador, acoes);
            add(t, PREFIXO_DO_SERVIDOR + id);
            return t;
        });
    }

    private void aoMudarALista(List<ServidorLab> labs) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ServidorLab lab : labs) {
            ids.add(lab.id());
            garantirTela(lab);
        }
        for (java.util.Iterator<Map.Entry<String, TelaProjetos>> it = telas.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TelaProjetos> par = it.next();
            if (!ids.contains(par.getKey())) {
                par.getValue().soltar();
                remove(par.getValue());
                it.remove();
            }
        }
        if (atual.startsWith(PREFIXO_DO_SERVIDOR) && !telas.containsKey(atual.substring(PREFIXO_DO_SERVIDOR.length()))) {
            mostrarServidores(); // o servidor que estava na tela saiu
        }
        revalidate();
        repaint();
    }

    /** Mostra a lista de projetos de um servidor. */
    public void mostrarServidor(ServidorLab lab) {
        garantirTela(lab);
        atual = PREFIXO_DO_SERVIDOR + lab.id();
        cartoes.show(this, atual);
        ouvintes.forEach(o -> o.accept(atual));
    }

    /** Mostra a tela de servidores. */
    public void mostrarServidores() {
        atual = SERVIDORES;
        cartoes.show(this, SERVIDORES);
        ouvintes.forEach(o -> o.accept(atual));
    }

    /** O cartão que está na tela: {@code "servidores"} ou {@code "servidor:<id>"}. */
    public String atual() {
        return atual;
    }

    /** O servidor cuja lista está na tela, ou {@code null} na tela de servidores. */
    public ServidorLab servidorAtual() {
        if (!atual.startsWith(PREFIXO_DO_SERVIDOR)) {
            return null;
        }
        return gerenciador.servidor(atual.substring(PREFIXO_DO_SERVIDOR.length())).orElse(null);
    }

    /** Avisa (na EDT) toda vez que a tela mostrada muda. */
    public void aoMudar(Consumer<String> ouvinte) {
        ouvintes.add(ouvinte);
    }

    public TelaProjetos telaDe(String servidorId) {
        return telas.get(servidorId);
    }

    public TelaServidores telaDeServidores() {
        return telaServidores;
    }

    /** Solta tudo (a janela fechou ou o cofre foi bloqueado). */
    public void encerrar() {
        inscricaoDaLista.close();
        telas.values().forEach(TelaProjetos::soltar);
        telas.clear();
        telaServidores.encerrar();
    }
}
