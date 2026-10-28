package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.EditorRemoto.Aberto;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

/**
 * O editor de UM arquivo de texto do servidor: fonte monoespaçada, números de linha, realce de sintaxe com a paleta neutra do
 * app, busca (Cmd+F), indicação de modificado (um asterisco no nome), Salvar (Cmd+S), "Desfazer a última edição" (volta à cópia
 * local anterior à última gravação), Recarregar e Fechar. A parte de rede (salvar com conflito, backup, sudo) é do gerenciador,
 * que recebe os pedidos por {@link Acoes}; aqui só se edita e se mostra.
 *
 * <p>Esc fecha a barra de busca e NUNCA limpa o texto nem sai da página: o editor se declara responsável pelo Esc
 * ({@link GerenciadorDeArquivos#PROPRIEDADE_ESC}) e a página do projeto, que usa Esc para voltar à lista, respeita isso.
 */
final class EditorDeTexto extends Painel {

    private static final long serialVersionUID = 1L;

    /** O que o editor pede a quem o controla. */
    interface Acoes {
        void salvar();

        void recarregar();

        void desfazerUltimaEdicao();

        void fechar();

        /** O estado de modificado mudou (a aba mostra o asterisco). */
        void modificadoMudou(boolean modificado);
    }

    private final Acoes acoes;
    private final boolean somenteLeitura;
    private final AreaDeEdicao area = new AreaDeEdicao();
    private final RTextScrollPane rolagem;
    private final Rotulo nome = new Rotulo().negrito();
    private final Rotulo pasta = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12.5f).truncavel();
    private final Rotulo posicao = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao salvar = Botoes.miniPrimario("Salvar", "salvar");
    private final Botao desfazer = Botoes.mini("Desfazer a última edição", "desfazer");
    private final Botao recarregar = Botoes.mini("Recarregar", "atualizar");
    private final Botao buscar = Botoes.mini("Buscar", "busca");
    private final Botao fechar = Botoes.mini("Fechar", "fechar");
    private final TextoCorrido aviso = new TextoCorrido("", 600).tamanho(12.5f);
    private final Painel faixaDeAviso = new Painel(new BorderLayout());
    private final Painel barraDeBusca = new Painel(new BorderLayout(8, 0));
    private final JTextField campoDeBusca = Campos.texto(22, "Buscar neste arquivo");
    private final JCheckBox maiusculas = new JCheckBox("Diferenciar maiúsculas");
    private final Rotulo resultadoDaBusca = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao proximo = Botoes.mini("Próximo", "avancar");
    private final Botao anterior = Botoes.mini("Anterior", "voltar");
    private final Botao fecharBusca = Botoes.fantasma("Fechar a busca", null);
    private final SearchContext contexto = new SearchContext();

    private Aberto aberto;
    private String textoBase = "";
    private boolean modificado;
    private boolean carregando;

    @SuppressWarnings("this-escape")
    EditorDeTexto(Aberto aberto, boolean somenteLeitura, Acoes acoes) {
        super(new BorderLayout(0, 8));
        this.aberto = aberto;
        this.acoes = acoes;
        this.somenteLeitura = somenteLeitura;
        putClientProperty(GerenciadorDeArquivos.PROPRIEDADE_ESC, Boolean.TRUE);
        setName("editor-" + aberto.nome());
        area.setEditable(!somenteLeitura);
        Fontes.aplicar(area, Fontes.MONO, Font.PLAIN, 12.5f);
        area.setSyntaxEditingStyle(EstilosDeSintaxe.paraArquivo(aberto.nome()));
        area.getAccessibleContext().setAccessibleName("Conteúdo de " + TextoSeguro.limparLinha(aberto.nome()));
        rolagem = new RTextScrollPane(area, true);
        rolagem.setLineNumbersEnabled(true);
        rolagem.setFoldIndicatorEnabled(false);
        rolagem.setBorder(BorderFactory.createLineBorder(Tokens.cor(Tokens.BORDA)));
        area.aoMudarAparencia(a -> aplicarCoresDaCalha());
        aplicarCoresDaCalha();

        nome.setText(TextoSeguro.limparLinha(aberto.nome()));
        nome.setToolTipText(TextoSeguro.limparLinha(aberto.caminho()));
        String dir = br.com.labdesktop.arquivos.CaminhoRemoto.pai(aberto.caminho());
        pasta.setText("em " + TextoSeguro.limparLinha(dir));
        pasta.setToolTipText(TextoSeguro.limparLinha(aberto.caminho()));
        salvar.setToolTipText("Grava no servidor (" + br.com.labdesktop.ui.componentes.Teclas.nome() + "+S)");
        desfazer.setToolTipText("Volta ao conteúdo que o arquivo tinha antes da última vez que você salvou aqui");
        recarregar.setToolTipText("Lê o arquivo de novo do servidor (descarta o que você digitou e não salvou)");
        buscar.setToolTipText("Procura neste arquivo (" + br.com.labdesktop.ui.componentes.Teclas.nome() + "+F)");
        fechar.setToolTipText("Fecha o editor");
        desfazer.setEnabled(false);
        salvar.setEnabled(false);
        JPanel botoes = Caixa.linha(8, salvar, desfazer, recarregar, buscar, Caixa.mola(), fechar);
        JPanel cabecalho = new Painel(new BorderLayout(0, 6));
        JPanel linhaDoNome = Caixa.linha(10, nome, pasta, Caixa.mola(), posicao);
        cabecalho.add(linhaDoNome, BorderLayout.NORTH);
        cabecalho.add(botoes, BorderLayout.CENTER);
        faixaDeAviso.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        faixaDeAviso.add(aviso, BorderLayout.CENTER);
        faixaDeAviso.setVisible(false);
        cabecalho.add(faixaDeAviso, BorderLayout.SOUTH);
        add(cabecalho, BorderLayout.NORTH);
        add(rolagem, BorderLayout.CENTER);

        maiusculas.putClientProperty("html.disable", Boolean.TRUE);
        resultadoDaBusca.setPreferredSize(new java.awt.Dimension(120, resultadoDaBusca.getPreferredSize().height));
        JPanel opcoes = Caixa.linha(8, anterior, proximo, maiusculas, resultadoDaBusca, fecharBusca);
        barraDeBusca.add(campoDeBusca, BorderLayout.CENTER);
        barraDeBusca.add(opcoes, BorderLayout.EAST);
        barraDeBusca.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        barraDeBusca.setVisible(false);
        add(barraDeBusca, BorderLayout.SOUTH);

        if (somenteLeitura) {
            salvar.setVisible(false);
            desfazer.setVisible(false);
            mostrarAviso("Somente leitura nesta versão: dá para ler e procurar, mas salvar fica desligado.", Gravidade.NEUTRO);
        } else if (aberto.quebrasMistas()) {
            mostrarAviso("Este arquivo mistura quebras de linha de tipos diferentes. Ao salvar, elas serão unificadas.", Gravidade.ATENCAO);
        }
        ligarEventos();
        definirConteudo(aberto, aberto.texto());
        getAccessibleContext().setAccessibleName("Editor de " + TextoSeguro.limparLinha(aberto.nome()));
    }

    // ---------------------------------------------------------------------------------------------
    // Conteúdo
    // ---------------------------------------------------------------------------------------------

    /** Põe um conteúdo novo (abriu, recarregou, salvou, desfez): não conta como modificado e zera o "desfazer" da digitação. */
    void definirConteudo(Aberto novo, String texto) {
        this.aberto = novo;
        carregando = true;
        try {
            this.textoBase = texto;
            area.setText(texto);
            area.setCaretPosition(0);
            area.discardAllEdits();
        } finally {
            carregando = false;
        }
        atualizarModificado();
        atualizarPosicao();
    }

    /**
     * Depois de gravar: o que foi gravado passa a ser o texto salvo. Sem trocar o texto do editor, para o cursor ficar onde está e
     * para o que foi digitado enquanto a gravação rodava continuar contando como alteração.
     */
    void marcarComoSalvo(Aberto novo, String textoSalvo) {
        this.aberto = novo;
        this.textoBase = textoSalvo;
        atualizarModificado();
    }

    String texto() {
        return area.getText();
    }

    Aberto aberto() {
        return aberto;
    }

    boolean modificado() {
        return modificado;
    }

    private void atualizarModificado() {
        boolean novo = !area.getText().equals(textoBase);
        boolean mudou = novo != modificado;
        modificado = novo;
        nome.setText(TextoSeguro.limparLinha(aberto.nome()) + (modificado ? " *" : ""));
        salvar.setEnabled(!somenteLeitura && modificado);
        if (mudou) {
            acoes.modificadoMudou(modificado);
        }
    }

    /** Liga ou desliga "Desfazer a última edição" (há cópia local anterior). */
    void definirPodeDesfazer(boolean pode) {
        desfazer.setEnabled(pode && !somenteLeitura);
    }

    /** Um aviso em cima do texto (conflito, quebra mista, erro de gravação), com ícone e palavras. */
    void mostrarAviso(String texto, Gravidade gravidade) {
        aviso.setText(texto);
        aviso.corDoToken(gravidade.tokenDoTexto());
        faixaDeAviso.fundo(gravidade.tokenDoFundo()).borda(gravidade == Gravidade.NEUTRO ? Tokens.BORDA : gravidade.tokenDaBorda()).raio(8);
        faixaDeAviso.setVisible(!texto.isEmpty());
        revalidate();
    }

    void limparAviso() {
        mostrarAviso("", Gravidade.NEUTRO);
    }

    String textoDoAviso() {
        return aviso.getText();
    }

    private void aplicarCoresDaCalha() {
        var calha = rolagem.getGutter();
        calha.setBackground(Tokens.cor(Tokens.BG2));
        calha.setLineNumberColor(Tokens.cor(Tokens.TEXTO3));
        calha.setBorderColor(Tokens.cor(Tokens.BORDA));
        calha.setLineNumberFont(area.getFont());
        rolagem.setBorder(BorderFactory.createLineBorder(Tokens.cor(Tokens.BORDA)));
    }

    // ---------------------------------------------------------------------------------------------
    // Eventos e teclas
    // ---------------------------------------------------------------------------------------------

    private void ligarEventos() {
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                aoMudarTexto();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                aoMudarTexto();
            }

            @Override
            public void changedUpdate(DocumentEvent e) { }
        });
        area.addCaretListener(e -> atualizarPosicao());
        salvar.addActionListener(e -> acoes.salvar());
        desfazer.addActionListener(e -> acoes.desfazerUltimaEdicao());
        recarregar.addActionListener(e -> acoes.recarregar());
        fechar.addActionListener(e -> acoes.fechar());
        buscar.addActionListener(e -> abrirBusca());
        fecharBusca.addActionListener(e -> fecharBusca());
        proximo.addActionListener(e -> procurar(true));
        anterior.addActionListener(e -> procurar(false));
        maiusculas.addActionListener(e -> procurar(true));
        campoDeBusca.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                procurarAPartirDoInicioDaSelecao();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                procurarAPartirDoInicioDaSelecao();
            }

            @Override
            public void changedUpdate(DocumentEvent e) { }
        });

        int cmd = br.com.labdesktop.ui.componentes.Teclas.comando();
        liga(this, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_S, cmd), "salvar-arquivo", () -> {
            if (salvar.isEnabled()) {
                acoes.salvar();
            }
        });
        liga(this, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyStroke.getKeyStroke(KeyEvent.VK_F, cmd), "buscar-no-arquivo", this::abrirBusca);
        liga(area, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "fechar-busca", this::fecharBuscaSeAberta);
        liga(campoDeBusca, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "fechar-busca", this::fecharBusca);
        liga(campoDeBusca, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "proximo", () -> procurar(true));
        liga(campoDeBusca, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            "anterior", () -> procurar(false));
        liga(area, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_G, cmd), "proximo-resultado", () -> procurar(true));
    }

    private static void liga(JComponent c, int condicao, KeyStroke tecla, String nome, Runnable acao) {
        c.getInputMap(condicao).put(tecla, nome);
        c.getActionMap().put(nome, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                acao.run();
            }
        });
    }

    private void aoMudarTexto() {
        if (carregando) {
            return;
        }
        atualizarModificado();
    }

    private void atualizarPosicao() {
        try {
            int pos = area.getCaretPosition();
            int linha = area.getLineOfOffset(pos);
            int coluna = pos - area.getLineStartOffset(linha);
            posicao.setText("Linha " + (linha + 1) + ", coluna " + (coluna + 1));
        } catch (BadLocationException e) {
            posicao.setText("");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Busca
    // ---------------------------------------------------------------------------------------------

    void abrirBusca() {
        barraDeBusca.setVisible(true);
        String selecionado = area.getSelectedText();
        if (selecionado != null && !selecionado.isEmpty() && !selecionado.contains("\n")) {
            campoDeBusca.setText(selecionado);
        }
        campoDeBusca.requestFocusInWindow();
        campoDeBusca.selectAll();
        revalidate();
        repaint();
    }

    void fecharBusca() {
        barraDeBusca.setVisible(false);
        resultadoDaBusca.setText("");
        SearchEngine.markAll(area, new SearchContext(""));
        area.requestFocusInWindow();
        revalidate();
        repaint();
    }

    private void fecharBuscaSeAberta() {
        if (barraDeBusca.isVisible()) {
            fecharBusca();
        }
    }

    boolean buscaAberta() {
        return barraDeBusca.isVisible();
    }

    JTextField campoDeBusca() {
        return campoDeBusca;
    }

    /** Ao digitar, procura a partir do começo da seleção (a busca "anda" com o que se digita). */
    private void procurarAPartirDoInicioDaSelecao() {
        int inicio = area.getSelectionStart();
        area.setCaretPosition(Math.min(inicio, area.getDocument().getLength()));
        procurar(true);
    }

    String resultadoDaBusca() {
        return resultadoDaBusca.getText();
    }

    /** Procura o texto do campo; mostra "Não achei" ou quantas vezes aparece. */
    boolean procurar(boolean paraFrente) {
        String t = campoDeBusca.getText();
        if (t.isEmpty()) {
            resultadoDaBusca.setText("");
            SearchEngine.markAll(area, new SearchContext(""));
            return false;
        }
        contexto.setSearchFor(t);
        contexto.setMatchCase(maiusculas.isSelected());
        contexto.setSearchForward(paraFrente);
        contexto.setSearchWrap(true);
        contexto.setMarkAll(true);
        SearchResult r = SearchEngine.find(area, contexto);
        if (r.wasFound()) {
            int n = r.getMarkedCount();
            resultadoDaBusca.setText(n > 0 ? n + (n == 1 ? " resultado" : " resultados") : "Achei");
            resultadoDaBusca.corDoToken(Tokens.TEXTO2);
        } else {
            resultadoDaBusca.setText("Não achei");
            resultadoDaBusca.corDoToken(Tokens.ATENCAO);
        }
        return r.wasFound();
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes e para o gerenciador
    // ---------------------------------------------------------------------------------------------

    AreaDeEdicao area() {
        return area;
    }

    Botao botaoSalvar() {
        return salvar;
    }

    Botao botaoDesfazer() {
        return desfazer;
    }

    Botao botaoRecarregar() {
        return recarregar;
    }

    Botao botaoBuscar() {
        return buscar;
    }

    Botao botaoFechar() {
        return fechar;
    }

    String textoDoNome() {
        return nome.getText();
    }

    String textoDaPosicao() {
        return posicao.getText();
    }

    RTextScrollPane rolagem() {
        return rolagem;
    }

    boolean somenteLeitura() {
        return somenteLeitura;
    }
}
