package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.EntradaRemota;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.dialogos.Dialogo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;

/**
 * "Mover para…": escolher a pasta de destino numa árvore de pastas do servidor (a mesma carga preguiçosa da árvore da tela,
 * limitada à raiz do modo). O botão que confirma diz o destino ("Mover para src") e só habilita com uma pasta escolhida
 * diferente da de origem.
 */
final class DialogoEscolherPasta extends Dialogo {

    private final ArvoreDePastas arvore;
    private final Consumer<String> aoEscolher;
    private final Rotulo escolhida = new Rotulo("Nenhuma pasta escolhida.").corDoToken(Tokens.TEXTO2).truncavel();
    private final Botao mover = Botoes.primario("Mover para…", "mover");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final String origem;
    private boolean decidiu;
    private String atual;

    /**
     * @param oQue       "3 itens", "relatorio.md": o que vai ser movido
     * @param origem      a pasta onde os itens estão agora (não pode ser o destino)
     * @param raiz        o caminho e o nome da raiz da árvore
     * @param carregador  lê as subpastas (o mesmo do gerenciador)
     * @param aoEscolher  recebe o caminho escolhido
     */
    DialogoEscolherPasta(String oQue, String origem, String raiz, String nomeDaRaiz, ArvoreDePastas.Carregador carregador,
                         Consumer<String> aoEscolher) {
        super("Mover para…");
        this.origem = origem;
        this.aoEscolher = aoEscolher;
        this.arvore = new ArvoreDePastas(new ArvoreDePastas.Carregador() {
            @Override
            public void carregar(String caminho, Consumer<List<EntradaRemota>> aoChegar, Consumer<ErroSftp> aoFalhar) {
                carregador.carregar(caminho, aoChegar, aoFalhar);
            }

            @Override
            public void escolheuPasta(String caminho) {
                escolher(caminho);
            }
        });
        arvore.definirRaiz(raiz, nomeDaRaiz);
        JScrollPane rolagem = new JScrollPane(arvore);
        rolagem.setBorder(BorderFactory.createLineBorder(Tokens.cor(Tokens.BORDA)));
        rolagem.setPreferredSize(new Dimension(420, 260));
        JComponent corpo = Caixa.coluna(12, cabecalho("mover", "Mover para…"), paragrafo("Escolha a pasta para onde mover " + TextoSeguro.limparLinha(oQue) + "."),
            rolagem, escolhida, rodape(null, cancelar, mover));
        conteudo(corpo);
        botaoPadrao(mover);
        mover.setEnabled(false);
        mover.addActionListener(e -> confirmar());
        cancelar.addActionListener(e -> aoCancelar());
    }

    private void escolher(String caminho) {
        this.atual = caminho;
        boolean serve = !caminho.equals(origem);
        mover.setEnabled(serve);
        String nome = br.com.labdesktop.arquivos.CaminhoRemoto.nome(caminho);
        mover.setText("Mover para " + (nome.isEmpty() ? "/" : TextoSeguro.limparLinha(nome)));
        escolhida.setText(serve ? "Pasta escolhida: " + caminho : "Os itens já estão nesta pasta.");
        reajustar();
    }

    ArvoreDePastas arvore() {
        return arvore;
    }

    Botao botaoMover() {
        return mover;
    }

    Botao botaoCancelar() {
        return cancelar;
    }

    String caminhoEscolhido() {
        return atual;
    }

    /** Escolhe por programa (os testes); como um clique na árvore. */
    void escolherPorPrograma(String caminho) {
        escolher(caminho);
    }

    private void confirmar() {
        if (decidiu || atual == null || atual.equals(origem)) {
            return;
        }
        decidiu = true;
        fechar();
        aoEscolher.accept(atual);
    }

    @Override
    protected void aoCancelar() {
        decidiu = true;
        fechar();
    }
}
