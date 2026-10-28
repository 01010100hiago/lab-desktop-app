package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.registro.FiltroDeRegistro;
import br.com.labdesktop.registro.LinhaDeRegistro;
import br.com.labdesktop.registro.ResultadoDoRegistro;
import br.com.labdesktop.registro.Severidade;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * O registro geral do servidor numa lista que se lê, filtra, pausa e copia: o miolo da caixa "Registro do servidor" do Resumo ({@code compacto}) e da janela "Ver tudo".
 *
 * <p><b>Ordem: as mais NOVAS em cima</b>, sempre (na caixa, na janela, no que se copia): o que o usuário quer ver é o que aconteceu agora, sem rolar, e a lista que se atualiza a cada 15 s
 * não pula de lugar. Cada linha é {@code HH:mm:ss  origem  mensagem}, com a severidade em PALAVRA e ícone (Info, Aviso, Erro: nunca só a cor). Tudo é texto puro (nada de HTML).
 *
 * <p>Assina o registro só enquanto está visível E não está em pausa ({@code HierarchyListener}); pausar solta a assinatura (nenhuma chamada), e "Atualizar" lê uma vez
 * mesmo em pausa. Seleção de várias linhas e cópia (Cmd/Ctrl+C e o menu de contexto). Nada de rede, disco ou processo aqui: só recebe o que o {@code Sondador} entrega, na EDT.
 */
public final class PainelDoRegistro extends Painel {

    private static final long serialVersionUID = 1L;
    private static final int ALTURA_DA_LINHA = 22;
    private static final String CARTAO_LISTA = "lista";
    private static final String CARTAO_MENSAGEM = "mensagem";

    /** De onde vêm os resultados (o servidor de verdade, ou nada nos testes que alimentam o painel à mão). */
    public interface Fonte {
        /**
         * @param repetirUltimo {@code true} entrega logo o último registro guardado (a tela que aparece); {@code false} só o que for lido DEPOIS, e já PEDE essa leitura
         *                      (a leitura avulsa do "Atualizar" em pausa: o guardado seria a resposta velha a um pedido novo)
         */
        Inscricao assinar(OuvinteDeTopico<ResultadoDoRegistro> ouvinte, boolean repetirUltimo);

        void atualizarAgora();
    }

    private final boolean compacto;
    private final transient Fonte fonte;
    private final transient CopiadorDeTexto copiador;
    private final transient Notificacoes avisos;
    private final transient Supplier<ZoneId> fuso;
    private transient Supplier<Instant> relogio = Instant::now;

    private final JTextField campoDoFiltro = Campos.busca(14, "Filtrar por texto");
    private final Map<FiltroDeRegistro.Nivel, JToggleButton> botoesDeNivel = new LinkedHashMap<>();
    private final Botao pausar = Botoes.icone("pausa", "Pausar: para de atualizar o registro (nenhuma leitura é feita)");
    private final Botao atualizar = Botoes.icone("atualizar", "Atualizar: lê o registro agora");
    private final Botao verTudo = Botoes.mini("Ver tudo", "abrir");
    private final DefaultListModel<LinhaDeRegistro> modelo = new DefaultListModel<>();
    private final JList<LinhaDeRegistro> lista = new JList<>(modelo);
    private final JScrollPane rolagem;
    private final CardLayout cartoes = new CardLayout();
    private final JPanel corpo = new Painel(cartoes);
    private final Esqueleto esqueleto = new Esqueleto();
    private final Rotulo tituloDaMensagem = new Rotulo().negrito().tamanho(13f);
    private final TextoCorrido textoDaMensagem = new TextoCorrido("", 420).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final javax.swing.JTextArea comando = new javax.swing.JTextArea();
    private final Botao copiarComando;
    private final JScrollPane rolagemDaMensagem;
    private final JPanel blocoDoComando = new Painel(new BorderLayout(0, 4));
    private final Rotulo status = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();

    private transient Inscricao inscricao;
    /** Só os testes: sem janela de verdade o painel nunca está visível; eles dizem se está. */
    private Boolean visivelForcado;
    private ResultadoDoRegistro ultimo;
    private boolean falhouDepois;
    private boolean pausado;
    private boolean leituraAvulsa;
    private Instant lidoEm;
    private FiltroDeRegistro filtro = FiltroDeRegistro.NENHUM;
    private List<LinhaDeRegistro> visiveis = List.of();
    private Runnable aoVerTudo = () -> { };

    public PainelDoRegistro(boolean compacto, Fonte fonte, CopiadorDeTexto copiador, Notificacoes avisos, Supplier<ZoneId> fuso) {
        super(new BorderLayout(0, 6));
        this.compacto = compacto;
        this.fonte = fonte;
        this.copiador = copiador;
        this.avisos = avisos;
        this.fuso = fuso;
        setName("painel-do-registro");

        // ---- filtro e botões ----
        ButtonGroup grupo = new ButtonGroup();
        List<Component> nivel = new ArrayList<>();
        for (FiltroDeRegistro.Nivel n : FiltroDeRegistro.Nivel.values()) {
            JToggleButton b = Botoes.filtro(n.rotulo(), n == FiltroDeRegistro.Nivel.TUDO ? "Todas as linhas"
                : n == FiltroDeRegistro.Nivel.AVISOS ? "Avisos e erros" : "Só os erros");
            b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 8; minimumHeight: 26; margin: 1,9,1,9; selectedBackground: $Lab.destaqueSuave; "
                + "selectedForeground: $Lab.destaque; pressedBackground: $Lab.destaqueSuave");
            b.setName("nivel-" + n.rotulo());
            grupo.add(b);
            botoesDeNivel.put(n, b);
            nivel.add(b);
            b.addActionListener(e -> definirFiltro(new FiltroDeRegistro(campoDoFiltro.getText(), n)));
        }
        botoesDeNivel.get(FiltroDeRegistro.Nivel.TUDO).setSelected(true);
        campoDoFiltro.setName("filtro-do-registro");
        campoDoFiltro.getAccessibleContext().setAccessibleName("Filtrar o registro por texto");
        campoDoFiltro.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                aoDigitar();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                aoDigitar();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                aoDigitar();
            }
        });
        pausar.setName("pausar-registro");
        pausar.addActionListener(e -> alternarPausa());
        atualizar.setName("atualizar-registro");
        atualizar.addActionListener(e -> lerAgora());
        verTudo.setToolTipText("Abre o registro numa janela maior");
        verTudo.setName("ver-tudo-do-registro");
        verTudo.addActionListener(e -> aoVerTudo.run());
        verTudo.setVisible(compacto);

        campoDoFiltro.setPreferredSize(new Dimension(150, campoDoFiltro.getPreferredSize().height));
        campoDoFiltro.setMinimumSize(new Dimension(70, campoDoFiltro.getPreferredSize().height));
        JPanel filtros = Caixa.linha(6, campoDoFiltro);
        for (Component c : nivel) {
            filtros.add(c);
        }
        filtros.add(Caixa.mola());
        filtros.add(pausar);
        filtros.add(atualizar);
        filtros.add(verTudo);
        add(filtros, BorderLayout.NORTH);

        // ---- a lista ----
        lista.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        lista.setCellRenderer(new Desenho());
        lista.setFixedCellHeight(ALTURA_DA_LINHA);
        lista.setOpaque(false);
        lista.setName("lista-do-registro");
        lista.getAccessibleContext().setAccessibleName("Linhas do registro do servidor");
        lista.putClientProperty("html.disable", Boolean.TRUE);
        lista.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, br.com.labdesktop.ui.componentes.Teclas.comando()), "copiar-linhas");
        lista.getActionMap().put("copiar-linhas", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copiarSelecionadas();
            }
        });
        lista.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                aoClicar(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                aoClicar(e);
            }
        });
        rolagem = new JScrollPane(lista, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rolagem.setBorder(BorderFactory.createEmptyBorder());
        rolagem.setOpaque(false);
        rolagem.getViewport().setOpaque(false);
        rolagem.getVerticalScrollBar().setUnitIncrement(ALTURA_DA_LINHA);
        rolagem.setName("rolagem-do-registro");

        // ---- as mensagens (vazio, sem permissão, indisponível, erro) ----
        comando.setEditable(false);
        comando.setLineWrap(true);
        comando.setWrapStyleWord(false);
        comando.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        comando.setOpaque(true);
        comando.putClientProperty("html.disable", Boolean.TRUE);
        Fontes.aplicar(comando, Fontes.MONO, Font.PLAIN, 12.5f);
        comando.setName("comando-de-acesso");
        copiarComando = BotaoCopiar.criar("Copiar comando", comando::getText, copiador, avisos);
        blocoDoComando.add(comando, BorderLayout.CENTER);
        blocoDoComando.add(Caixa.linha(0, copiarComando, Caixa.mola()), BorderLayout.SOUTH);
        blocoDoComando.setVisible(false);
        JPanel mensagem = new Painel();
        mensagem.setLayout(new javax.swing.BoxLayout(mensagem, javax.swing.BoxLayout.Y_AXIS));
        mensagem.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        for (JComponent c : new JComponent[] {tituloDaMensagem, textoDaMensagem, blocoDoComando}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            mensagem.add(c);
            mensagem.add(javax.swing.Box.createRigidArea(new Dimension(0, 6)));
        }
        rolagemDaMensagem = new JScrollPane(mensagem, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rolagemDaMensagem.setBorder(BorderFactory.createEmptyBorder());
        rolagemDaMensagem.setOpaque(false);
        rolagemDaMensagem.getViewport().setOpaque(false);

        corpo.add(rolagem, CARTAO_LISTA);
        corpo.add(rolagemDaMensagem, CARTAO_MENSAGEM);
        corpo.add(esqueleto, "esqueleto");
        add(corpo, BorderLayout.CENTER);
        status.setName("situacao-do-registro");
        if (!compacto) {
            add(status, BorderLayout.SOUTH); // na caixa do Resumo o status vai para o título (economiza uma linha); na janela grande fica embaixo
        }

        corpo.setMinimumSize(new Dimension(100, ALTURA_DA_LINHA * (compacto ? 3 : 8)));
        corpo.setPreferredSize(new Dimension(300, ALTURA_DA_LINHA * (compacto ? 3 : 14)));
        cartoes.show(corpo, "esqueleto");
        mostrarStatus();
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    assinar();
                } else {
                    soltar();
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Assinatura
    // ---------------------------------------------------------------------------------------------

    private void assinar() {
        if (fonte == null || inscricao != null || (pausado && !leituraAvulsa)) {
            return;
        }
        // Na leitura avulsa (Atualizar em pausa) o guardado NÃO volta: só vale o resultado que veio depois do pedido.
        inscricao = fonte.assinar(new OuvinteDeTopico<ResultadoDoRegistro>() {
            @Override
            public void aoAtualizar(ResultadoDoRegistro valor) {
                aoReceber(valor);
            }

            @Override
            public void aoConfirmar() {
                lidoEm = relogio.get();
                falhouDepois = false;
                mostrarStatus();
                terminarLeituraAvulsa();
            }

            @Override
            public void aoFalhar(ErroApi erro) {
                aoFalharLeitura();
            }
        }, !leituraAvulsa);
    }

    /** Só para os testes: faz o que o {@code HierarchyListener} faria quando a aba aparece ({@code true}) ou some ({@code false}); sem janela de verdade o painel nunca está visível. */
    void forcarVisivelParaTeste(boolean visivel) {
        visivelForcado = visivel;
        if (visivel) {
            assinar();
        } else {
            soltar();
        }
    }

    private boolean visivel() {
        return visivelForcado != null ? visivelForcado : isShowing();
    }

    private void soltar() {
        if (inscricao != null) {
            inscricao.close();
            inscricao = null;
        }
    }

    /** Está assinando (visível e sem pausa, ou numa leitura avulsa)? Para os testes. */
    boolean assinada() {
        return inscricao != null;
    }

    private void alternarPausa() {
        pausado = !pausado;
        leituraAvulsa = false;
        String dica = pausado ? "Continuar: volta a atualizar o registro a cada 15 s" : "Pausar: para de atualizar o registro (nenhuma leitura é feita)";
        pausar.setIcon(Icones.de(pausado ? "play" : "pausa", 14));
        pausar.setToolTipText(dica);
        pausar.getAccessibleContext().setAccessibleName(dica);
        if (pausado) {
            soltar();
        } else if (visivel()) {
            assinar();
        }
        mostrarStatus();
    }

    /** "Atualizar": lê agora. Em pausa, lê UMA vez (assina e solta logo que a resposta chega). */
    private void lerAgora() {
        if (fonte == null) {
            return;
        }
        if (pausado) {
            leituraAvulsa = true;
            if (inscricao == null && visivel()) {
                assinar(); // assinar SEM o valor guardado já pede uma leitura nova: pedir de novo daria duas leituras para um clique
                return;
            }
        }
        fonte.atualizarAgora();
    }

    private void terminarLeituraAvulsa() {
        if (pausado && leituraAvulsa) {
            leituraAvulsa = false;
            soltar();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Resultados (na EDT)
    // ---------------------------------------------------------------------------------------------

    /** Um resultado novo chegou (o Sondador, ou o teste). */
    void aoReceber(ResultadoDoRegistro novo) {
        this.ultimo = novo;
        this.falhouDepois = false;
        this.lidoEm = relogio.get();
        desenhar();
        terminarLeituraAvulsa();
    }

    /** A leitura falhou (sem conexão, prazo): o que está na tela fica, com o aviso. */
    void aoFalharLeitura() {
        this.falhouDepois = true;
        desenhar();
        terminarLeituraAvulsa();
    }

    private void aoDigitar() {
        definirFiltro(new FiltroDeRegistro(campoDoFiltro.getText(), filtro.nivel()));
    }

    private void definirFiltro(FiltroDeRegistro novo) {
        this.filtro = novo;
        desenhar();
    }

    private void desenhar() {
        if (ultimo instanceof ResultadoDoRegistro.Lido l) {
            List<LinhaDeRegistro> passam = filtro.aplicar(l.linhas());
            atualizarLista(passam);
            if (l.linhas().isEmpty()) {
                mensagem("Nenhuma linha no registro.", "O servidor não escreveu nada no registro que o app lê (" + l.fonte() + ").", null);
            } else if (passam.isEmpty()) {
                mensagem("Nenhuma linha passa pelo filtro.", "Mude o texto ou o nível (Tudo, Avisos, Erros) para ver as " + l.linhas().size() + " linhas lidas.", null);
            } else {
                cartoes.show(corpo, CARTAO_LISTA);
            }
        } else if (ultimo instanceof ResultadoDoRegistro.SemPermissao p) {
            atualizarLista(List.of());
            // na caixa pequena do Resumo cabem poucas linhas: o comando e o botão de copiar têm de ficar À VISTA, então o texto é a frase curta e a explicação inteira vai na dica (e na janela Ver tudo)
            mensagem(p.frase(), compacto ? "Rode no servidor e conecte de novo (mais detalhes na dica e em Ver tudo):" : p.explicacao() + " O comando é este:", p.comandoParaCopiar());
            textoDaMensagem.setToolTipText(p.explicacao());
        } else if (ultimo instanceof ResultadoDoRegistro.Indisponivel i) {
            atualizarLista(List.of());
            mensagem("Não há registro para mostrar.", i.frase(), null);
        } else if (falhouDepois) {
            mensagem("Não consegui ler o registro agora.", "O app tenta de novo sozinho, mais devagar enquanto não der certo.", null);
        } else {
            cartoes.show(corpo, "esqueleto");
        }
        mostrarStatus();
    }

    private void mensagem(String titulo, String texto, String comandoParaCopiar) {
        tituloDaMensagem.setText(titulo);
        textoDaMensagem.setText(texto);
        textoDaMensagem.setToolTipText(null);
        blocoDoComando.setVisible(comandoParaCopiar != null);
        comando.setText(comandoParaCopiar == null ? "" : comandoParaCopiar);
        cartoes.show(corpo, CARTAO_MENSAGEM);
        corpo.revalidate();
    }

    /** Troca as linhas sem tirar o usuário de onde ele estava: a seleção continua e, se ele rolou para ver linhas antigas, a linha do topo da vista continua no topo. */
    private void atualizarLista(List<LinhaDeRegistro> novas) {
        if (novas.equals(visiveis)) {
            return;
        }
        int primeira = lista.getFirstVisibleIndex();
        LinhaDeRegistro doTopo = primeira > 0 && primeira < modelo.size() ? modelo.get(primeira) : null;
        // a seleção é guardada por (linha, ordem entre as IGUAIS): duas linhas idênticas no registro (mesma hora, origem e mensagem) são linhas diferentes, e selecionar uma não pode
        // selecionar a outra depois da atualização
        record Marca(LinhaDeRegistro linha, int ordem) { }
        List<Marca> selecionadas = new ArrayList<>();
        for (int i : lista.getSelectedIndices()) {
            if (i < modelo.size()) {
                LinhaDeRegistro l = modelo.get(i);
                int ordem = 0;
                for (int k = 0; k < i; k++) {
                    if (modelo.get(k).equals(l)) {
                        ordem++;
                    }
                }
                selecionadas.add(new Marca(l, ordem));
            }
        }
        modelo.clear();
        modelo.addAll(novas);
        visiveis = List.copyOf(novas);
        if (!selecionadas.isEmpty()) {
            List<Integer> indices = new ArrayList<>();
            for (Marca m : selecionadas) {
                int vistas = 0;
                for (int i = 0; i < modelo.size(); i++) {
                    if (modelo.get(i).equals(m.linha())) {
                        if (vistas == m.ordem()) {
                            indices.add(i);
                            break;
                        }
                        vistas++;
                    }
                }
            }
            lista.setSelectedIndices(indices.stream().mapToInt(Integer::intValue).toArray());
        }
        if (doTopo != null) {
            int onde = novas.indexOf(doTopo);
            if (onde >= 0) {
                Rectangle celula = lista.getCellBounds(onde, onde);
                if (celula != null) {
                    rolagem.getViewport().setViewPosition(new java.awt.Point(0, celula.y));
                }
            }
        }
    }

    private void mostrarStatus() {
        String t;
        if (falhouDepois && ultimo != null) {
            t = "Não consegui ler agora: mostrando a leitura das " + hora(lidoEm) + ".";
        } else if (ultimo instanceof ResultadoDoRegistro.Lido l) {
            t = "Registro " + l.fonte() + ". Lido às " + hora(lidoEm) + (l.cortada() ? "; há linhas mais antigas que o app não lê." : ".");
            int mostradas = visiveis.size();
            if (filtro.ativo()) {
                t += " " + mostradas + " de " + l.linhas().size() + " linhas.";
            }
        } else if (ultimo == null) {
            t = pausado ? "Em pausa." : "Lendo o registro…";
        } else {
            t = "";
        }
        if (pausado) {
            t = (t.isEmpty() ? "" : t + " ") + "Em pausa: não atualiza sozinho.";
        }
        status.setText(compacto ? resumirStatus(t) : t);
        status.setToolTipText(t.isEmpty() ? null : t);
        status.corDoToken(falhouDepois ? Tokens.ATENCAO : Tokens.TEXTO3);
    }

    /** Na caixa pequena o status é curto: "journalctl · 12:03:01" (a frase inteira fica na dica). */
    private String resumirStatus(String completo) {
        if (falhouDepois && ultimo != null) {
            return "sem leitura nova (das " + hora(lidoEm) + ")";
        }
        if (ultimo instanceof ResultadoDoRegistro.Lido l) {
            String origem = l.fonte().startsWith("do laboratório local") ? "laboratório local" : l.fonte().replaceFirst("^do (diário do sistema \\()?", "").replaceFirst("^arquivo ", "").replaceFirst("\\)$", "");
            return origem + " · " + hora(lidoEm) + (pausado ? " · em pausa" : "") + (filtro.ativo() ? " · " + visiveis.size() + "/" + l.linhas().size() : "");
        }
        return pausado ? "em pausa" : completo.replace("Lendo o registro…", "lendo…");
    }

    /** O rótulo do status (a caixa do Resumo o põe no título). */
    Rotulo rotuloDoStatus() {
        return status;
    }

    private String hora(Instant i) {
        return i == null ? "—" : br.com.labdesktop.util.Formato.fmtHora(i, fuso.get());
    }

    // ---------------------------------------------------------------------------------------------
    // Seleção e cópia
    // ---------------------------------------------------------------------------------------------

    /**
     * O menu de contexto da lista, montado NA HORA de abrir (nunca guardado): um menu criado uma vez e escondido não está em janela nenhuma, então a troca de tema
     * não o atualizava e ele voltava com as cores de antes.
     */
    public JPopupMenu menuDeContexto() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copiarSel = new JMenuItem("Copiar as linhas selecionadas");
        copiarSel.addActionListener(e -> copiarSelecionadas());
        copiarSel.setEnabled(!lista.isSelectionEmpty());
        JMenuItem copiarTudo = new JMenuItem("Copiar todas as linhas que aparecem");
        copiarTudo.addActionListener(e -> copiarTudo());
        menu.add(copiarSel);
        menu.add(copiarTudo);
        return menu;
    }

    private void aoClicar(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = lista.locationToIndex(e.getPoint());
        if (i >= 0 && !lista.isSelectedIndex(i)) {
            lista.setSelectedIndex(i);
        }
        menuDeContexto().show(lista, e.getX(), e.getY());
    }

    /** Copia as linhas selecionadas (ou, sem seleção, nenhuma), uma por linha: {@code HH:mm:ss  origem  mensagem}. */
    void copiarSelecionadas() {
        List<LinhaDeRegistro> s = lista.getSelectedValuesList();
        if (s.isEmpty()) {
            avisos.info("Selecione uma ou mais linhas para copiar (ou use o menu: Copiar todas).");
            return;
        }
        copiar(s);
    }

    void copiarTudo() {
        if (visiveis.isEmpty()) {
            avisos.info("Não há linhas para copiar.");
            return;
        }
        copiar(visiveis);
    }

    private void copiar(List<LinhaDeRegistro> linhas) {
        ZoneId z = fuso.get();
        String texto = String.join("\n", linhas.stream().map(l -> l.paraCopiar(z)).toList());
        if (copiador.copiar(texto)) {
            avisos.info(linhas.size() == 1 ? "Linha copiada." : linhas.size() + " linhas copiadas.");
        } else {
            avisos.atencao("Não consegui copiar. Selecione o texto e use " + br.com.labdesktop.ui.componentes.Teclas.nome() + "+C.");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Como cada linha é desenhada: ícone, palavra, hora, origem e mensagem (texto puro)
    // ---------------------------------------------------------------------------------------------

    /**
     * O desenho de uma linha, PINTADO à mão (ícone, palavra, hora, origem e mensagem): sem componentes filhos. Um renderizador feito de rótulos e layout depende de uma validação que o
     * Swing só faz quando a lista está numa janela de verdade; pintando direto, a linha sai igual em qualquer lugar (janela, teste sem janela, impressão) e é mais barato para 200 linhas.
     * Texto puro: nada é interpretado como HTML. O texto que não cabe termina em reticências.
     */
    private final class Desenho extends JPanel implements ListCellRenderer<LinhaDeRegistro> {
        private static final long serialVersionUID = 1L;
        private static final int MARGEM = 4;
        private static final int LARGURA_DA_SEVERIDADE = 66;
        private static final int LARGURA_DA_HORA = 74;
        // só para herdar fontes do tema (e a escala de texto do usuário): nunca entram na tela
        private final Rotulo molde = new Rotulo().mono(12f);
        private final Rotulo moldeNegrito = new Rotulo().negrito().tamanho(12f);
        private LinhaDeRegistro linha;
        private boolean selecionada;

        Desenho() {
            super(null); // sem filhos nem layout: tudo é pintado em paintComponent (e o JPanel dá o contexto de acessibilidade)
            setOpaque(false);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends LinhaDeRegistro> l, LinhaDeRegistro v, int indice, boolean selecionada, boolean foco) {
            this.linha = v;
            this.selecionada = selecionada;
            // o leitor de tela lê a linha inteira, com a gravidade em palavra
            getAccessibleContext().setAccessibleName(v.severidade().palavra() + ": " + v.paraCopiar(fuso.get()));
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(300, ALTURA_DA_LINHA);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (linha == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (selecionada) {
                    g2.setColor(Tokens.cor(Tokens.DESTAQUE_SUAVE));
                    g2.fillRect(0, 0, w, h);
                }
                String token = switch (linha.severidade()) {
                    case ERRO -> Tokens.PERIGO;
                    case AVISO -> Tokens.ATENCAO;
                    case INFO -> Tokens.TEXTO3;
                };
                String nomeDoIcone = switch (linha.severidade()) {
                    case ERRO -> "erro";
                    case AVISO -> "alerta";
                    case INFO -> "info";
                };
                int x = MARGEM;
                javax.swing.Icon icone = Icones.colorido(nomeDoIcone, 13, token);
                icone.paintIcon(this, g2, x, (h - icone.getIconHeight()) / 2);
                java.awt.Font negrito = moldeNegrito.getFont();
                g2.setFont(negrito);
                java.awt.FontMetrics fmN = g2.getFontMetrics();
                int base = (h - fmN.getHeight()) / 2 + fmN.getAscent();
                g2.setColor(Tokens.cor(linha.severidade() == Severidade.INFO ? Tokens.TEXTO2 : token));
                g2.drawString(linha.severidade().palavra(), x + 18, base);

                java.awt.Font mono = molde.getFont();
                g2.setFont(mono);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int baseMono = (h - fm.getHeight()) / 2 + fm.getAscent();
                x = MARGEM + LARGURA_DA_SEVERIDADE;
                g2.setColor(Tokens.cor(Tokens.TEXTO2));
                g2.drawString(linha.horaEmTexto(fuso.get()), x, baseMono);
                x += LARGURA_DA_HORA;
                int larguraDaOrigem = compacto ? 84 : 120;
                g2.setColor(Tokens.cor(Tokens.TEXTO2));
                g2.drawString(cortar(linha.origem(), fm, larguraDaOrigem - 8), x, baseMono);
                x += larguraDaOrigem;
                g2.setColor(Tokens.cor(Tokens.TEXTO));
                g2.drawString(cortar(linha.mensagem(), fm, w - x - MARGEM), x, baseMono);
            } finally {
                g2.dispose();
            }
        }

        /** O texto com reticências no fim se passar de {@code largura}. */
        private String cortar(String texto, java.awt.FontMetrics fm, int largura) {
            if (largura <= 0) {
                return "";
            }
            if (fm.stringWidth(texto) <= largura) {
                return texto;
            }
            String reticencias = "…";
            int limite = largura - fm.stringWidth(reticencias);
            int n = texto.length();
            int lo = 0;
            int hi = n;
            while (lo < hi) {
                int meio = (lo + hi + 1) >>> 1;
                if (fm.stringWidth(texto.substring(0, meio)) <= limite) {
                    lo = meio;
                } else {
                    hi = meio - 1;
                }
            }
            return texto.substring(0, lo) + reticencias;
        }
    }

    /** Barras cinzas, até a primeira leitura. */
    private static final class Esqueleto extends JPanel {
        private static final long serialVersionUID = 1L;

        Esqueleto() {
            setOpaque(false);
            getAccessibleContext().setAccessibleName("Lendo o registro do servidor");
            setName("esqueleto-do-registro");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(Tokens.cor(Tokens.ESQUELETO_A));
                for (int i = 0; i * ALTURA_DA_LINHA + 14 < getHeight() && i < 8; i++) {
                    int y = i * ALTURA_DA_LINHA + 6;
                    g2.fillRoundRect(4, y, 50, 9, 6, 6);
                    g2.fillRoundRect(70, y, 40, 9, 6, 6);
                    g2.fillRoundRect(124, y, Math.max(60, (int) ((getWidth() - 140) * (0.5 + 0.1 * ((i * 7) % 5)))), 9, 6, 6);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    void relogio(Supplier<Instant> novo) {
        this.relogio = novo;
    }

    void aoVerTudo(Runnable quem) {
        this.aoVerTudo = quem == null ? () -> { } : quem;
    }

    JList<LinhaDeRegistro> lista() {
        return lista;
    }

    JTextField campoDoFiltro() {
        return campoDoFiltro;
    }

    JToggleButton botaoDoNivel(FiltroDeRegistro.Nivel n) {
        return botoesDeNivel.get(n);
    }

    Botao botaoPausar() {
        return pausar;
    }

    Botao botaoAtualizar() {
        return atualizar;
    }

    Botao botaoVerTudo() {
        return verTudo;
    }

    Botao botaoCopiarComando() {
        return copiarComando;
    }

    boolean pausado() {
        return pausado;
    }

    List<LinhaDeRegistro> linhasQueAparecem() {
        return visiveis;
    }

    String textoDoStatus() {
        return status.getText();
    }

    /** O comando e o botão Copiar comando aparecem inteiros na vista do cartão da mensagem (sem precisar rolar)? */
    boolean comandoEstaNaVista() {
        if (!blocoDoComando.isVisible() || blocoDoComando.getHeight() == 0) {
            return false;
        }
        java.awt.Rectangle vista = rolagemDaMensagem.getViewport().getViewRect();
        java.awt.Rectangle bloco = javax.swing.SwingUtilities.convertRectangle(blocoDoComando.getParent(), blocoDoComando.getBounds(), rolagemDaMensagem.getViewport().getView());
        return vista.contains(bloco);
    }

    String textoDaMensagemDica() {
        return textoDaMensagem.getToolTipText();
    }

    String comandoParaCopiar() {
        return comando.getText();
    }

    String tituloDaMensagem() {
        return tituloDaMensagem.getText();
    }

    String textoDaMensagem() {
        return textoDaMensagem.getText();
    }

    String comandoMostrado() {
        return comando.getText();
    }

    /** Qual cartão está à frente: lista, mensagem ou esqueleto. */
    String cartaoAtual() {
        for (Component c : corpo.getComponents()) {
            if (c.isVisible()) {
                return c == rolagem ? CARTAO_LISTA : c == esqueleto ? "esqueleto" : CARTAO_MENSAGEM;
            }
        }
        return "";
    }
}
