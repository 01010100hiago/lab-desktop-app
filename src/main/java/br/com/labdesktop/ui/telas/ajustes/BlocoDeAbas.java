package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.util.OrdemDeAbas;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.border.EmptyBorder;

/**
 * Uma barra de abas na tela Ajustes, Navegação: uma linha por aba, na ordem de agora, com a caixa "aparece na barra" e os botões Subir e Descer, mais "Restaurar ordem
 * padrão". Cada clique grava a preferência e as páginas abertas mudam na hora. Teclado: cada botão e cada caixa tem foco e nome; depois de mover, o foco fica no botão da
 * aba que se moveu. Regras que a tela garante (a conta é do {@link OrdemDeAbas}): a aba que está aberta não se esconde e sempre sobra pelo menos uma.
 */
final class BlocoDeAbas extends Painel {

    private static final long serialVersionUID = 1L;

    /** Uma aba que esta versão do app tem na barra: o id (o da preferência) e o nome que o usuário lê. */
    record AbaConhecida(String id, String rotulo) { }

    private final List<AbaConhecida> conhecidas;
    private final List<String> ids;
    private final transient Supplier<OrdemDeAbas> atual;
    private final transient Consumer<OrdemDeAbas> gravar;
    private final transient Supplier<String> abaAberta;
    private final JComponent linhas = Caixa.coluna(6);
    private final Botao restaurar = Botoes.secundario("Restaurar ordem padrão", "desfazer");
    private final Map<String, JCheckBox> caixas = new LinkedHashMap<>();
    private final Map<String, Botao> sobem = new LinkedHashMap<>();
    private final Map<String, Botao> descem = new LinkedHashMap<>();

    BlocoDeAbas(String nomeDaBarra, List<AbaConhecida> conhecidas, Supplier<OrdemDeAbas> atual, Consumer<OrdemDeAbas> gravar, Supplier<String> abaAberta) {
        super(new BorderLayout(0, 10));
        this.conhecidas = List.copyOf(conhecidas);
        this.ids = conhecidas.stream().map(AbaConhecida::id).toList();
        this.atual = atual;
        this.gravar = gravar;
        this.abaAberta = abaAberta;
        setOpaque(false);
        add(Paginas.cartao(linhas), BorderLayout.CENTER);
        restaurar.setToolTipText("Volta a ordem e as abas de " + nomeDaBarra + " ao que o app traz de fábrica");
        restaurar.addActionListener(e -> mudar(OrdemDeAbas.PADRAO, null, null));
        add(Caixa.linha(0, restaurar, Caixa.mola()), BorderLayout.SOUTH);
        getAccessibleContext().setAccessibleName("Abas de " + nomeDaBarra);
        atualizar();
    }

    private String rotuloDe(String id) {
        return conhecidas.stream().filter(a -> a.id().equals(id)).map(AbaConhecida::rotulo).findFirst().orElse(id);
    }

    /** Refaz as linhas com a preferência de agora. */
    void atualizar() {
        OrdemDeAbas o = atual.get();
        List<String> ordem = o.ordenar(ids);
        linhas.removeAll();
        caixas.clear();
        sobem.clear();
        descem.clear();
        String protegida = abaAberta.get();
        for (int i = 0; i < ordem.size(); i++) {
            String id = ordem.get(i);
            String rotulo = rotuloDe(id);
            boolean aparece = !o.oculta(id);
            JCheckBox caixa = new JCheckBox(rotulo, aparece);
            caixa.putClientProperty("html.disable", Boolean.TRUE);
            caixa.setOpaque(false);
            boolean podeEsconder = o.podeEsconder(ids, ids, id, protegida);
            caixa.setEnabled(!aparece || podeEsconder);
            if (aparece && !podeEsconder) {
                caixa.setToolTipText(id.equals(protegida) ? "Esta aba está aberta agora e não pode ser escondida" : "Precisa sobrar pelo menos uma aba na barra");
            } else {
                caixa.setToolTipText(aparece ? "Tira " + rotulo + " da barra (ela continua na lista, para voltar)" : "Põe " + rotulo + " de volta na barra");
            }
            caixa.getAccessibleContext().setAccessibleName(rotulo + (aparece ? ", aparece na barra" : ", escondida"));
            caixa.addActionListener(e -> mudar(caixa.isSelected() ? o.mostrando(id) : o.escondendo(id), null, null));
            Botao sobe = Botoes.icone("seta-cima", "Subir " + rotulo);
            Botao desce = Botoes.icone("seta-baixo", "Descer " + rotulo);
            sobe.setEnabled(i > 0);
            desce.setEnabled(i < ordem.size() - 1);
            sobe.addActionListener(e -> mudar(o.mover(ids, id, -1), id, "sobe"));
            desce.addActionListener(e -> mudar(o.mover(ids, id, 1), id, "desce"));
            JComponent linha = Caixa.linha(8, caixa, Caixa.mola(), sobe, desce);
            linha.setBorder(new EmptyBorder(2, 0, 2, 0));
            linhas.add(linha);
            caixas.put(id, caixa);
            sobem.put(id, sobe);
            descem.put(id, desce);
        }
        restaurar.setEnabled(!o.ehPadrao());
        linhas.revalidate();
        linhas.repaint();
    }

    private void mudar(OrdemDeAbas nova, String idParaFoco, String qual) {
        gravar.accept(nova);
        atualizar();
        if (idParaFoco != null) {
            Botao alvo = "sobe".equals(qual) ? sobem.get(idParaFoco) : descem.get(idParaFoco);
            if (alvo == null || !alvo.isEnabled()) {
                alvo = "sobe".equals(qual) ? descem.get(idParaFoco) : sobem.get(idParaFoco); // chegou na ponta: o foco vai para o botão do outro lado
            }
            if (alvo != null) {
                alvo.requestFocusInWindow();
            }
        }
    }

    // ---- para os testes ----

    /** Os nomes na ordem em que as linhas aparecem. */
    List<String> rotulosEmOrdem() {
        List<String> r = new ArrayList<>();
        caixas.keySet().forEach(id -> r.add(rotuloDe(id)));
        return r;
    }

    JCheckBox caixaDe(String id) {
        return caixas.get(id);
    }

    Botao botaoSubir(String id) {
        return sobem.get(id);
    }

    Botao botaoDescer(String id) {
        return descem.get(id);
    }

    Botao botaoRestaurar() {
        return restaurar;
    }
}
