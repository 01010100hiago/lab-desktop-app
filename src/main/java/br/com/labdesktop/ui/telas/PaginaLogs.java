package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.Container;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.ModeloDeLogs.Filtrado;
import br.com.labdesktop.ui.telas.ModeloDeLogs.Preparado;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * A aba "Logs": escolher o container, quantas linhas (100, 300 ou 1000), Atualizar e a atualização a cada 5 s (opcional, só com
 * a aba visível), um filtro de texto com busca incremental e a contagem de ocorrências, a área monoespaçada com rolagem INTERNA
 * e "seguir o fim", e Copiar. A área cresce com a janela e rola por dentro: os logs nunca empurram o resto da página. O que a
 * tela desenha são as últimas N linhas, limpas ({@link ModeloDeLogs}); nunca HTML.
 *
 * <p>Abrir a aba, trocar o container ou as linhas e o botão Atualizar são leituras avulsas ({@code AcoesDoServidor.logs}); a
 * atualização automática é o tópico {@code LOGS(p,c,n)} (5 s) assinado só enquanto a caixa está marcada E a aba visível.
 */
final class PaginaLogs extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;
    static final int[] OPCOES_DE_LINHAS = {100, 300, 1000};
    private static final String CARTAO_TEXTO = "texto";
    private static final String CARTAO_MENSAGEM = "mensagem";

    private final JComboBox<String> containers = new JComboBox<>();
    private final JComboBox<Integer> linhas = new JComboBox<>(new Integer[] {OPCOES_DE_LINHAS[0], OPCOES_DE_LINHAS[1], OPCOES_DE_LINHAS[2]});
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    private final JCheckBox automatica = new JCheckBox("Atualizar a cada 5 s");
    private final JCheckBox seguir = new JCheckBox("Seguir o fim", true);
    private final JTextField filtro = Campos.busca(18, "Filtrar linhas…");
    private final Rotulo contagem = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Rotulo situacao = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(11.5f).truncavel();
    private final Botao copiar;
    private final AreaMonoespacada area = new AreaMonoespacada("Logs do container", false);
    private final JScrollPane rolagemDoTexto = AreaMonoespacada.emRolagem(area);
    private final Rotulo mensagem = new Rotulo().corDoToken(Tokens.LOGS_TEXTO2).tamanho(13f);
    private final CardLayout cartoes = new CardLayout();
    private final JPanel corpoDosLogs = new Painel(cartoes);

    private Preparado preparado = new Preparado(List.of(), 0, false);
    private Filtrado filtrado = new Filtrado("", 0, 0);
    private String containerEscolhido;
    private boolean atualizandoOCombo;
    private transient Inscricao automatico;
    private String chaveDoAutomatico = "";
    private int sequencia;
    private boolean carregou;
    private String erro;
    private boolean emCarga;

    PaginaLogs(ContextoDoProjeto ctx) {
        super(ctx);
        this.copiar = BotaoCopiar.criar("Copiar", () -> area.getText(), ctx.copiador, ctx.avisos);
        iniciar();
    }

    @Override
    boolean preencheAAltura() {
        return true;
    }

    @Override
    Set<String> topicosProprios() {
        return automatica.isSelected() && containerEscolhido != null
            ? Set.of(Topico.logs(ctx.projeto, containerEscolhido, linhasPedidas()).chave()) : Set.of();
    }

    @Override
    JComponent montarConteudo() {
        containers.setPrototypeDisplayValue("lab-claude-um-nome-bem-comprido-1");
        containers.getAccessibleContext().setAccessibleName("Container");
        containers.setToolTipText("De qual container mostrar os logs");
        linhas.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> lista, Object valor, int i, boolean sel,
                                                                    boolean foco) {
                return super.getListCellRendererComponent(lista, valor + " linhas", i, sel, foco);
            }
        });
        linhas.setSelectedItem(OPCOES_DE_LINHAS[1]);
        linhas.getAccessibleContext().setAccessibleName("Quantas linhas");
        linhas.setToolTipText("Quantas linhas do fim dos logs trazer");
        atualizar.setToolTipText("Lê os logs agora");
        automatica.setOpaque(false);
        automatica.setToolTipText("Lê os logs de novo a cada 5 segundos enquanto esta aba estiver na tela");
        seguir.setOpaque(false);
        seguir.setToolTipText("Mantém a rolagem no fim, onde ficam as linhas mais novas");
        filtro.setToolTipText("Mostra só as linhas que têm este texto");
        filtro.getAccessibleContext().setAccessibleName("Filtrar linhas");
        copiar.setToolTipText("Copia o que está na área de logs");

        containers.addActionListener(e -> {
            if (!atualizandoOCombo) {
                containerEscolhido = (String) containers.getSelectedItem();
                recarregar();
            }
        });
        linhas.addActionListener(e -> {
            if (!atualizandoOCombo) {
                recarregar();
            }
        });
        atualizar.addActionListener(e -> carregarAgora());
        automatica.addActionListener(e -> ajustarOAutomatico());
        seguir.addActionListener(e -> {
            if (seguir.isSelected()) {
                irParaOFim();
            }
        });
        filtro.getDocument().addDocumentListener(new br.com.labdesktop.ui.dialogos.SimplesOuvinteDeDocumento(this::refiltrar));

        containers.setMaximumSize(new Dimension(300, 30));
        containers.setPreferredSize(new Dimension(260, 30));
        linhas.setMaximumSize(new Dimension(130, 30));
        linhas.setPreferredSize(new Dimension(120, 30));
        JPanel linha1 = Caixa.linha(10, containers, linhas, atualizar, automatica, Caixa.mola(), copiar);
        JPanel linha2 = Caixa.linha(10, filtro, contagem, Caixa.mola(), situacao, seguir);
        filtro.setMaximumSize(new Dimension(260, 30));
        JPanel controles = Caixa.coluna(8, linha1, linha2);

        rolagemDoTexto.setPreferredSize(new Dimension(400, 220));
        rolagemDoTexto.setMinimumSize(new Dimension(100, 120));

        JPanel cartaoDaMensagem = new Painel(new java.awt.GridBagLayout()).fundo(Tokens.LOGS_BG).borda(Tokens.LOGS_BORDA);
        cartaoDaMensagem.add(mensagem);
        mensagem.setText("Escolha um container para ver os logs.");
        corpoDosLogs.add(cartaoDaMensagem, CARTAO_MENSAGEM);
        corpoDosLogs.add(rolagemDoTexto, CARTAO_TEXTO);
        cartoes.show(corpoDosLogs, CARTAO_MENSAGEM);

        JPanel conteudo = new Painel(new BorderLayout(0, 10));
        conteudo.add(controles, BorderLayout.NORTH);
        conteudo.add(corpoDosLogs, BorderLayout.CENTER);
        // as cores dos logs seguem o tema a cada pintura (a troca de tema repinta sozinha)
        return conteudo;
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------------

    @Override
    void aoAparecer() {
        if (ctx.detalhe != null) {
            carregarSeAindaNaoCarregou();
            ajustarOAutomatico();
        }
    }

    @Override
    void aoSumir() {
        sequencia++; // uma resposta que chegar depois de sumir não toca na tela
        automatico = soltar(automatico);
        chaveDoAutomatico = "";
    }

    @Override
    void atualizarConteudo() {
        List<String> nomes = ctx.detalhe.containers().stream().map(Container::nome).toList();
        boolean mudou = !nomes.equals(nomesDoCombo());
        if (mudou) {
            atualizandoOCombo = true;
            try {
                containers.removeAllItems();
                nomes.forEach(containers::addItem);
                if (containerEscolhido == null || !nomes.contains(containerEscolhido)) {
                    containerEscolhido = escolhaPadrao(ctx.detalhe.containers());
                }
                containers.setSelectedItem(containerEscolhido);
            } finally {
                atualizandoOCombo = false;
            }
        }
        boolean temContainers = !nomes.isEmpty();
        containers.setEnabled(temContainers);
        linhas.setEnabled(temContainers);
        atualizar.setEnabled(temContainers);
        automatica.setEnabled(temContainers);
        copiar.setEnabled(temContainers && !preparado.vazio());
        if (!temContainers) {
            mostrarMensagem(ctx.detalhe.composes().isEmpty()
                ? "Este projeto não tem containers: não há logs para ver."
                : "Este projeto ainda não tem containers. Os logs aparecem quando ele for iniciado.");
        } else if (!carregou && assinada()) {
            carregarSeAindaNaoCarregou();
        } else if (mudou && assinada()) {
            recarregar();
        }
        if (assinada()) {
            ajustarOAutomatico();
        }
    }

    /** Prefere o primeiro container que está rodando (é o que costuma ter o que ler); senão o primeiro. */
    static String escolhaPadrao(List<Container> lista) {
        return lista.stream().filter(Container::rodando).map(Container::nome).findFirst()
            .orElse(lista.isEmpty() ? null : lista.get(0).nome());
    }

    private List<String> nomesDoCombo() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < containers.getItemCount(); i++) {
            l.add(containers.getItemAt(i));
        }
        return l;
    }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    int linhasPedidas() {
        Object o = linhas.getSelectedItem();
        return o instanceof Integer n ? n : OPCOES_DE_LINHAS[1];
    }

    private void carregarSeAindaNaoCarregou() {
        if (!carregou && containerEscolhido != null) {
            carregarAgora();
        }
    }

    /** Container ou número de linhas mudou: lê de novo e, com a atualização automática, troca a assinatura. */
    private void recarregar() {
        carregou = false;
        carregarAgora();
        ajustarOAutomatico();
    }

    /** A leitura avulsa (abrir a aba, trocar de container ou de linhas, Atualizar). */
    void carregarAgora() {
        if (containerEscolhido == null || ctx.servidor == null) {
            return;
        }
        int minha = ++sequencia;
        String c = containerEscolhido;
        int n = linhasPedidas();
        emCarga = true;
        situacao.setText("Lendo…");
        atualizar.definirOcupado(true);
        Tarefas.rodar(() -> ctx.servidor.acoes().logs(ctx.projeto, c, n), texto -> {
            atualizar.definirOcupado(false);
            if (minha != sequencia) {
                return; // chegou depois de o usuário trocar de container ou sair da aba
            }
            emCarga = false;
            carregou = true;
            receber(texto, n);
        }, falha -> {
            atualizar.definirOcupado(false);
            if (minha != sequencia) {
                return;
            }
            emCarga = false;
            carregou = true;
            falhou(falha);
        });
    }

    /** Liga ou desliga o tópico de 5 s conforme a caixa e a aba visível (e troca a assinatura se o container ou as linhas mudaram). */
    private void ajustarOAutomatico() {
        boolean quer = automatica.isSelected() && assinada() && containerEscolhido != null && ctx.servidor != null;
        String chave = quer ? Topico.logs(ctx.projeto, containerEscolhido, linhasPedidas()).chave() : "";
        if (chave.equals(chaveDoAutomatico)) {
            return;
        }
        automatico = soltar(automatico);
        chaveDoAutomatico = chave;
        if (!quer) {
            return;
        }
        int n = linhasPedidas();
        automatico = ctx.servidor.assinar(Topico.logs(ctx.projeto, containerEscolhido, n), new OuvinteDeTopico<String>() {
            @Override
            public void aoAtualizar(String texto) {
                carregou = true;
                receber(texto, n);
            }

            @Override
            public void aoConfirmar() {
                if (erro == null && carregou) {
                    situacao.setText("Lido às " + Formato.fmtHora(ctx.agora(), ctx.fuso));
                }
            }

            @Override
            public void aoFalhar(ErroApi e) {
                if (!(e instanceof ErroApi.ErroRede)) {
                    falhou(e);
                }
            }
        });
    }

    /** Recebe o texto dos logs (na EDT). Público para os testes alimentarem a aba com dados fixos. */
    void receber(String bruto, int pedidas) {
        this.erro = null;
        this.preparado = ModeloDeLogs.preparar(bruto, pedidas);
        situacao.setText("Lido às " + Formato.fmtHora(ctx.agora(), ctx.fuso));
        refiltrar();
    }

    /** A leitura falhou: a área diz o que houve em palavras simples (e o Atualizar tenta de novo). */
    void falhou(Throwable falha) {
        this.erro = falha instanceof ErroApi e ? MensagensDeErro.daApi(e) : MensagensDeErro.de(falha);
        this.preparado = new Preparado(List.of(), 0, false);
        situacao.setText("");
        mostrarMensagem("Não consegui ler os logs: " + erro);
        contagem.setText("");
        copiar.setEnabled(false);
    }

    // ---------------------------------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------------------------------

    private void refiltrar() {
        if (erro != null) {
            return;
        }
        if (preparado.vazio()) {
            filtrado = new Filtrado("", 0, 0);
            mostrarMensagem("Este container ainda não escreveu nada nos logs.");
            contagem.setText("");
            copiar.setEnabled(false);
            return;
        }
        String termo = filtro.getText();
        filtrado = ModeloDeLogs.filtrar(preparado.linhas(), termo);
        boolean comTermo = !termo.strip().isEmpty();
        if (comTermo && filtrado.linhasQueCasam() == 0) {
            mostrarMensagem("Nenhuma linha tem “" + termo.strip() + "”.");
            contagem.setText("0 ocorrências");
            copiar.setEnabled(false);
            return;
        }
        contagem.setText(textoDaContagem(comTermo));
        contagem.setToolTipText(contagem.getText());
        copiar.setEnabled(true);
        cartoes.show(corpoDosLogs, CARTAO_TEXTO);
        area.setText(filtrado.texto());
        if (seguir.isSelected()) {
            irParaOFim();
        }
        // sem "seguir o fim" a rolagem fica onde o usuário deixou (a área nunca move o texto sozinha)
    }

    private String textoDaContagem(boolean comTermo) {
        String cortado = preparado.cortado() ? " (as últimas " + preparado.linhas().size() + " de " + Formato.fmtNum(preparado.linhasDoOriginal(), 0) + ")" : "";
        if (comTermo) {
            int o = filtrado.ocorrencias();
            return Formato.fmtNum(o, 0) + (o == 1 ? " ocorrência" : " ocorrências") + " em " + Formato.fmtNum(filtrado.linhasQueCasam(), 0)
                + (filtrado.linhasQueCasam() == 1 ? " linha" : " linhas");
        }
        int n = preparado.linhas().size();
        return n + (n == 1 ? " linha" : " linhas") + cortado;
    }

    private void mostrarMensagem(String texto) {
        mensagem.setText(texto);
        area.setText("");
        cartoes.show(corpoDosLogs, CARTAO_MENSAGEM);
    }

    private void irParaOFim() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar barra = rolagemDoTexto.getVerticalScrollBar();
            barra.setValue(barra.getMaximum());
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    JComboBox<String> comboDeContainers() {
        return containers;
    }

    JComboBox<Integer> comboDeLinhas() {
        return linhas;
    }

    Botao botaoAtualizar() {
        return atualizar;
    }

    JCheckBox caixaAutomatica() {
        return automatica;
    }

    JCheckBox caixaSeguir() {
        return seguir;
    }

    JTextField campoDoFiltro() {
        return filtro;
    }

    Botao botaoCopiar() {
        return copiar;
    }

    String textoDaArea() {
        return area.getText();
    }

    String textoDaContagem() {
        return contagem.getText();
    }

    String textoDaMensagem() {
        return mensagem.getText();
    }

    boolean mostraMensagem() {
        return mensagem.getParent() != null && mensagem.getParent().isVisible();
    }

    JScrollPane rolagemDosLogs() {
        return rolagemDoTexto;
    }

    boolean automaticoAssinado() {
        return automatico != null;
    }

    boolean carregando() {
        return emCarga;
    }

    String containerEscolhido() {
        return containerEscolhido;
    }

    Instant ultimaLeituraDosLogs() {
        return ctx.ultimaLeitura;
    }
}
