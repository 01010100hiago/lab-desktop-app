package br.com.labdesktop.ui.telas;

import br.com.labdesktop.cloudflare.EnderecoDoApp;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * As linhas dos endereços públicos que o app criou (a mesma nas abas "Cloudflare" do servidor e "Internet" do projeto): o endereço em letra de código, o projeto e para onde o túnel
 * manda, um selo com o estado da regra do túnel (texto sempre junto, nunca só cor) e os botões Abrir e Remover. Só desenha o que recebe; quem clica é de quem a monta.
 */
final class ListaDeEnderecos extends Painel {

    private static final long serialVersionUID = 1L;

    private final boolean mostrarProjeto;
    private final boolean comRemover;
    private final transient Consumer<String> aoAbrir;
    private final transient Consumer<String> aoRemover;
    private final List<Botao> botoesDeRemover = new ArrayList<>();
    private final List<Botao> botoesDeAbrir = new ArrayList<>();
    private final List<String> hostnames = new ArrayList<>();

    ListaDeEnderecos(boolean mostrarProjeto, boolean comRemover, Consumer<String> aoAbrir, Consumer<String> aoRemover) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        this.mostrarProjeto = mostrarProjeto;
        this.comRemover = comRemover;
        this.aoAbrir = aoAbrir;
        this.aoRemover = aoRemover;
        setName("lista-de-enderecos");
    }

    void definir(List<EnderecoDoApp> enderecos) {
        removeAll();
        botoesDeRemover.clear();
        botoesDeAbrir.clear();
        hostnames.clear();
        boolean primeiro = true;
        for (EnderecoDoApp e : enderecos) {
            JComponent linha = linha(e, primeiro);
            linha.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(linha);
            primeiro = false;
        }
        revalidate();
        repaint();
    }

    private JComponent linha(EnderecoDoApp e, boolean primeira) {
        Rotulo host = new Rotulo(e.hostname()).mono(13f).negrito().truncavel();
        host.setToolTipText(e.url());
        List<String> partes = new ArrayList<>();
        if (mostrarProjeto && !e.projeto().isEmpty()) {
            partes.add("projeto " + e.projeto());
        }
        if (!e.servico().isEmpty()) {
            partes.add("vai para " + e.servico());
        }
        Rotulo detalhe = new Rotulo(String.join("  ·  ", partes)).corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
        JPanel texto = Caixa.coluna(2, host, detalhe);
        Selo selo = switch (e.regra()) {
            case SIM -> new Selo(e.temDns() ? "NO TÚNEL" : "SÓ A REGRA", e.temDns() ? Gravidade.OK : Gravidade.ATENCAO);
            case NAO -> new Selo("SEM REGRA NO TÚNEL", Gravidade.ATENCAO);
            case NAO_CONFERIDA -> new Selo("REGRA NÃO CONFERIDA", Gravidade.NEUTRO);
        };
        Botao abrir = Botoes.mini("Abrir", "externo");
        abrir.setToolTipText("Abre " + e.url() + " no navegador");
        abrir.getAccessibleContext().setAccessibleName("Abrir " + e.hostname());
        abrir.addActionListener(x -> aoAbrir.accept(e.hostname()));
        botoesDeAbrir.add(abrir);
        hostnames.add(e.hostname());
        JPanel direita = Caixa.linha(8, selo, abrir);
        if (comRemover) {
            Botao remover = Botoes.miniPerigo("Remover…", "lixeira");
            remover.setToolTipText("Tira a regra do túnel e o registro de DNS que o app criou (pede confirmação)");
            remover.getAccessibleContext().setAccessibleName("Remover " + e.hostname());
            remover.addActionListener(x -> aoRemover.accept(e.hostname()));
            botoesDeRemover.add(remover);
            direita.add(remover);
        }
        JPanel l = new Painel(new BorderLayout(12, 0)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        l.setBorder(BorderFactory.createEmptyBorder(primeira ? 0 : 8, 0, 8, 0));
        l.add(texto, BorderLayout.CENTER);
        l.add(direita, BorderLayout.EAST);
        l.setName("endereco-" + e.hostname());
        return l;
    }

    List<String> hostnames() {
        return List.copyOf(hostnames);
    }

    List<Botao> botoesDeRemover() {
        return List.copyOf(botoesDeRemover);
    }

    List<Botao> botoesDeAbrir() {
        return List.copyOf(botoesDeAbrir);
    }
}
