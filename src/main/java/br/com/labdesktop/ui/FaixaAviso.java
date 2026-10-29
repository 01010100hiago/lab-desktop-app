package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.ConteudoDosAvisos.Aviso;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeConexao;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeCredito;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDePausas;
import br.com.labdesktop.ui.ConteudoDosAvisos.Pausada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * A faixa de aviso do servidor aberto (ARQUITETURA 6.3, 8.2 e 9): até três faixas empilhadas no topo da tela, na
 * ordem em que o {@link ConteudoDosAvisos} as manda:
 * <ol>
 *   <li><b>conexão</b>: "Sem conexão com lab desde 14:05. Reconectando (tentativa 3, próxima em 8 s)...", chave
 *       recusada, host a confirmar, token recusado, código do autenticador pendente (botão "Digitar código"). A tela
 *       mantém a última leitura: nunca fica vazia;</li>
 *   <li><b>crédito</b> do plano a partir de 75 % (âmbar), e de 90 % (terracota);</li>
 *   <li><b>sessões pausadas</b> pelo teto do projeto ou pelo freio global, cada uma com o botão Retomar.</li>
 * </ol>
 * Cada faixa tem ícone e texto: nada só pela cor. Quando a contagem de "próxima em N s" corre, um relógio de 1 s
 * refaz só o texto, sem recriar o que não mudou.
 */
public final class FaixaAviso extends Painel {

    private static final long serialVersionUID = 1L;

    /** O que a faixa pede a quem a usa. */
    public interface Ouvinte {
        /** Um botão da faixa de conexão. */
        void aoAcionar(Acao acao);

        /** O botão Retomar de uma sessão pausada. */
        void aoRetomar(String projeto);
    }

    private final Ouvinte ouvinte;
    private br.com.labdesktop.ui.componentes.CopiadorDeTexto copiador = br.com.labdesktop.ui.componentes.CopiadorDeTexto.doSistema();
    private final java.util.function.Supplier<Instant> relogio;
    private final Timer contagem;
    private final boolean comRetomar;
    private final Map<String, Botao> botoesDeRetomar = new HashMap<>();
    private final List<JComponent> faixas = new ArrayList<>();
    private EstadoServidor estado;
    private List<Aviso> avisos = List.of();
    private Set<String> projetosOcupados = Set.of();

    public FaixaAviso(Ouvinte ouvinte) {
        this(ouvinte, Instant::now);
    }

    /** Com o relógio trocável (testes). */
    public FaixaAviso(Ouvinte ouvinte, java.util.function.Supplier<Instant> relogio) {
        this(ouvinte, relogio, true);
    }

    /**
     * @param comRetomar {@code false} na tela somente leitura: as sessões pausadas aparecem, sem o botão
     *                   Retomar (que é ação de escrita)
     */
    public FaixaAviso(Ouvinte ouvinte, java.util.function.Supplier<Instant> relogio, boolean comRetomar) {
        super();
        this.ouvinte = ouvinte;
        this.relogio = relogio;
        this.comRetomar = comRetomar;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        this.contagem = new Timer(1000, e -> reavaliar());
        contagem.setRepeats(true);
        getAccessibleContext().setAccessibleName("Avisos do servidor");
        setVisible(false);
    }

    /** Troca a área de transferência do botão Copiar (os testes não têm a do sistema). */
    public void usarCopiador(br.com.labdesktop.ui.componentes.CopiadorDeTexto novo) {
        this.copiador = novo;
    }

    /** Mostra os avisos de um servidor (ou nenhum, com {@code null}). Na EDT. */
    public void atualizar(EstadoServidor novo) {
        this.estado = novo;
        reavaliar();
    }

    /** Os projetos cujo Retomar está em andamento (o botão fica ocupado). */
    public void definirOcupados(Set<String> projetos) {
        this.projetosOcupados = Set.copyOf(projetos);
        botoesDeRetomar.forEach((p, b) -> b.definirOcupado(projetosOcupados.contains(p)));
    }

    /** Para o relógio (a tela saiu da vista). */
    public void parar() {
        contagem.stop();
    }

    private void reavaliar() {
        List<Aviso> novos = estado == null ? List.of() : ConteudoDosAvisos.de(estado, relogio.get(), ZoneId.systemDefault());
        boolean contando = estado != null && estado.conexao() == br.com.labdesktop.ssh.EstadoConexao.RECONECTANDO;
        if (contando && !contagem.isRunning()) {
            contagem.start();
        } else if (!contando) {
            contagem.stop();
        }
        if (novos.equals(avisos)) {
            return;
        }
        avisos = novos;
        reconstruir();
    }

    private void reconstruir() {
        removeAll();
        faixas.clear();
        botoesDeRetomar.clear();
        for (Aviso a : avisos) {
            JComponent faixa = switch (a) {
                case AvisoDeConexao c -> faixaDeConexao(c);
                case AvisoDeCredito c -> faixaDeCredito(c);
                case AvisoDePausas p -> faixaDePausas(p);
            };
            faixa.setAlignmentX(LEFT_ALIGNMENT);
            if (!faixas.isEmpty()) {
                add(Caixa.espacoVertical(8));
            }
            add(faixa);
            faixas.add(faixa);
        }
        definirOcupados(projetosOcupados);
        setVisible(!avisos.isEmpty());
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------------------------------------
    // Faixas
    // ---------------------------------------------------------------------------------------------

    private static Painel moldura(Gravidade g) {
        Painel p = new Painel(new BorderLayout(12, 0));
        p.fundo(g.tokenDoFundo()).borda(g == Gravidade.NEUTRO ? Tokens.BORDA : g.tokenDaBorda()).raio(10);
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 12));
        return p;
    }

    private static Rotulo marca(String icone, Gravidade g) {
        Rotulo m = new Rotulo().icone(Icones.colorido(icone, 16, g.tokenDoTexto()));
        m.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        return m;
    }

    private JComponent faixaDeConexao(AvisoDeConexao a) {
        Painel p = moldura(a.gravidade());
        p.add(marca(a.icone(), a.gravidade()), BorderLayout.WEST);
        Rotulo titulo = new Rotulo(a.titulo()).negrito();
        TextoCorrido texto = new TextoCorrido(a.texto(), 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
        p.add(Caixa.coluna(2, titulo, texto), BorderLayout.CENTER);
        if (!a.acoes().isEmpty() || a.textoCopiavel() != null) {
            List<java.awt.Component> botoes = new ArrayList<>();
            if (a.textoCopiavel() != null) {
                // o passo de instalar, o comando de login ou o endereço de login: TEXTO que o usuário copia (o app nunca o executa nem o abre)
                Botao copiar = br.com.labdesktop.ui.componentes.BotaoCopiar.criarSemToast("Copiar", a::textoCopiavel, copiador);
                copiar.setName("copiar-do-aviso");
                botoes.add(copiar);
            }
            for (Acao acao : a.acoes()) {
                Botao b = acao == a.acoes().get(0) ? Botoes.miniPrimario(acao.rotulo(), null) : Botoes.mini(acao.rotulo(), null);
                b.addActionListener(e -> ouvinte.aoAcionar(acao));
                b.setName("acao-" + acao.name());
                botoes.add(b);
            }
            JPanel lado = new Painel(new BorderLayout());
            lado.add(Caixa.linha(8, botoes.toArray(java.awt.Component[]::new)), BorderLayout.NORTH);
            p.add(lado, BorderLayout.EAST);
        }
        p.getAccessibleContext().setAccessibleName(a.titulo());
        return p;
    }

    private JComponent faixaDeCredito(AvisoDeCredito a) {
        Painel p = moldura(a.gravidade());
        p.add(marca(a.icone(), a.gravidade()), BorderLayout.WEST);
        Rotulo titulo = new Rotulo(a.titulo()).negrito();
        // cada dado na sua linha (antes eram juntados com " · " num parágrafo que quebrava no meio)
        List<java.awt.Component> linhas = new ArrayList<>();
        linhas.add(titulo);
        for (String parte : a.partes()) {
            linhas.add(new Rotulo(parte).corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel());
        }
        p.add(Caixa.coluna(2, linhas.toArray(java.awt.Component[]::new)), BorderLayout.CENTER);
        p.getAccessibleContext().setAccessibleName(a.titulo());
        return p;
    }

    private JComponent faixaDePausas(AvisoDePausas a) {
        Painel p = moldura(a.gravidade());
        p.add(marca(a.icone(), a.gravidade()), BorderLayout.WEST);
        List<java.awt.Component> linhas = new ArrayList<>();
        linhas.add(new Rotulo(a.titulo()).negrito());
        for (Pausada x : a.itens()) {
            Rotulo nome = new Rotulo(x.nome()).negrito().tamanho(12.5f);
            Rotulo motivo = new Rotulo(x.motivo()).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
            if (!comRetomar) {
                linhas.add(Caixa.linha(8, nome, motivo));
                continue;
            }
            Botao retomar = Botoes.mini("Retomar", "play");
            retomar.setToolTipText("Tira a pausa e reabre o Claude continuando a conversa");
            retomar.addActionListener(e -> ouvinte.aoRetomar(x.projeto()));
            retomar.setName("retomar-" + x.projeto());
            botoesDeRetomar.put(x.projeto(), retomar);
            linhas.add(Caixa.linha(8, nome, motivo, retomar));
        }
        if (a.maisN() > 0) {
            linhas.add(Rotulo.secundario("e mais " + a.maisN()).tamanho(12.5f));
        }
        p.add(Caixa.coluna(4, linhas.toArray(java.awt.Component[]::new)), BorderLayout.CENTER);
        p.getAccessibleContext().setAccessibleName(a.titulo());
        return p;
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    /** Os avisos que a faixa mostra agora. */
    public List<Aviso> avisosMostrados() {
        return avisos;
    }

    /** O botão de uma ação da faixa de conexão, ou {@code null}. */
    public Botao botaoDaAcao(Acao acao) {
        return (Botao) achar(this, "acao-" + acao.name());
    }

    /** O botão Retomar de um projeto pausado, ou {@code null}. */
    public Botao botaoDeRetomar(String projeto) {
        return botoesDeRetomar.get(projeto);
    }

    public boolean relogioCorrendo() {
        return contagem.isRunning();
    }

    private static java.awt.Component achar(java.awt.Container raiz, String nome) {
        for (java.awt.Component c : raiz.getComponents()) {
            if (nome.equals(c.getName())) {
                return c;
            }
            if (c instanceof java.awt.Container k) {
                java.awt.Component r = achar(k, nome);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
