package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComboBox;

/**
 * "Para qual projeto?": a pergunta de uma ação que é do servidor mas age num projeto (Adicionar domínio, da aba Cloudflare). Uma lista dos projetos, o primeiro já escolhido, e o botão
 * leva o verbo da ação. Só pergunta: nada é escrito aqui. Fechar a janela é cancelar.
 */
public final class DialogoEscolherProjeto extends Dialogo {

    private final Consumer<String> aoEscolher;
    private final JComboBox<String> lista = new JComboBox<>();
    private final Botao continuar = Botoes.primario("Continuar", "avancar");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);

    public DialogoEscolherProjeto(List<String> projetos, Consumer<String> aoEscolher) {
        super("Para qual projeto?");
        this.aoEscolher = aoEscolher;
        projetos.forEach(lista::addItem);
        lista.getAccessibleContext().setAccessibleName("Projeto");
        lista.setName("projetos");
        conteudo(Caixa.coluna(12, cabecalho("externo", "Adicionar domínio"), paragrafo("Escolha o projeto que vai ganhar o endereço novo na internet."),
            Caixa.linha(8, new Rotulo("Projeto").corDoToken(Tokens.TEXTO2).tamanho(12.5f), lista, Caixa.mola()), rodape(null, cancelar, continuar)));
        botaoPadrao(continuar);
        cancelar.addActionListener(e -> fechar());
        continuar.addActionListener(e -> {
            Object escolhido = lista.getSelectedItem();
            fechar();
            if (escolhido != null) {
                aoEscolher.accept(escolhido.toString());
            }
        });
    }

    public JComboBox<String> lista() {
        return lista;
    }

    public Botao botaoContinuar() {
        return continuar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }
}
