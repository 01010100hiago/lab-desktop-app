package br.com.labdesktop.ui.telas;

import br.com.labdesktop.cloudflare.EnderecoDoApp;
import br.com.labdesktop.cloudflare.ErroCloudflare;
import br.com.labdesktop.cloudflare.ServicoCloudflare;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.AcoesDaInterface;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaDaPagina;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.FichaRotulada;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Cloudflare" do servidor: conectar a conta (token de API no cofre), ver o que está escolhido (domínio, túnel, onde estão as regras), conferir o token, trocar ou
 * desconectar, e a lista dos endereços públicos que o APP criou, com Abrir e Remover. Ao ficar visível, lê da Cloudflare (só leitura: os registros de DNS com a marca do app e
 * as regras do túnel); escrever é sempre de um diálogo, depois do clique de confirmação. Em "Este computador" mostra, em português, por que não há túnel.
 *
 * <p>Conectar e Desconectar não dependem da trava {@code labdesktop.acoes}: só mexem no cofre local (e leem da Cloudflare); Remover, que escreve na Cloudflare e no servidor, só aparece
 * com as ações liberadas. Nada de rede na EDT.
 */
public final class PaginaCloudflare extends Painel {

    private static final long serialVersionUID = 1L;

    /** O que a aba mostra. */
    public enum Modo { CARREGANDO, LOCAL, NAO_CONECTADO, CONECTADO }

    private final transient ServidorLab servidor;
    private final transient AcoesDaInterface acoes;
    private final boolean acoesLiberadas;
    private final transient Notificacoes avisos;
    private final CaixaDoServidor caixaDaConta = new CaixaDoServidor("Cloudflare", "link");
    private final CaixaDoServidor caixaDosEnderecos = new CaixaDoServidor("Endereços criados pelo app", "rede");
    private final Selo seloDaConta = new Selo("", Gravidade.NEUTRO);
    private final FichaRotulada ficha = new FichaRotulada(2, 16);
    private final TextoCorrido explicacao = new TextoCorrido("", 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido resultadoDoToken = new TextoCorrido("", 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao conectar = Botoes.miniPrimario("Conectar a Cloudflare…", "link");
    private final Botao adicionarDominio = Botoes.miniPrimario("Adicionar domínio…", "externo");
    private final Botao trocar = Botoes.mini("Trocar a conta…", "editar");
    private final Botao conferirToken = Botoes.mini("Conferir o token", "ok");
    private final Botao desconectar = Botoes.miniPerigo("Desconectar", "fechar");
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    private final TextoCorrido situacaoDosEnderecos = new TextoCorrido("", 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido erroDosEnderecos = new TextoCorrido("", 640).corDoToken(Tokens.PERIGO).tamanho(12.5f);
    private final ListaDeEnderecos lista;
    private final AtomicInteger geracao = new AtomicInteger();
    private Modo modo = Modo.CARREGANDO;
    private List<EnderecoDoApp> enderecos = List.of();
    private boolean carregou;

    private final transient java.util.function.Consumer<Runnable> aoAdicionarDominio;

    PaginaCloudflare(ServidorLab servidor, AcoesDaInterface acoes, boolean acoesLiberadas, Notificacoes avisos) {
        this(servidor, acoes, acoesLiberadas, avisos, depois -> { });
    }

    /**
     * @param aoAdicionarDominio o que "Adicionar domínio…" faz (pergunta para qual projeto e abre o assistente); o argumento roda quando termina
     */
    PaginaCloudflare(ServidorLab servidor, AcoesDaInterface acoes, boolean acoesLiberadas, Notificacoes avisos, java.util.function.Consumer<Runnable> aoAdicionarDominio) {
        super(new BorderLayout());
        this.aoAdicionarDominio = aoAdicionarDominio == null ? depois -> { } : aoAdicionarDominio;
        fundo(Tokens.BG);
        this.servidor = servidor;
        this.acoes = acoes;
        this.acoesLiberadas = acoesLiberadas;
        this.avisos = avisos;
        this.lista = new ListaDeEnderecos(true, acoesLiberadas, host -> {
            if (acoes != null) {
                acoes.abrirEnderecoPublico(servidor, host);
            }
        }, host -> {
            if (acoes != null) {
                acoes.removerEnderecoDaInternet(servidor, host, this::recarregar);
            }
        });
        explicacao.setVisible(false);
        resultadoDoToken.setVisible(false);
        situacaoDosEnderecos.setVisible(false);
        erroDosEnderecos.setVisible(false);

        adicionarDominio.setName("adicionar-dominio");
        adicionarDominio.addActionListener(e -> this.aoAdicionarDominio.accept(this::recarregar));
        conectar.addActionListener(e -> conectarOuTrocar());
        trocar.addActionListener(e -> conectarOuTrocar());
        desconectar.addActionListener(e -> {
            if (acoes != null && servidor != null) {
                acoes.desconectarCloudflare(servidor, this::recarregar);
            }
        });
        conferirToken.addActionListener(e -> conferirOToken());
        atualizar.addActionListener(e -> recarregar());
        atualizar.setToolTipText("Lê de novo os endereços na Cloudflare");

        caixaDaConta.apoio(new AEsquerda(seloDaConta));
        caixaDosEnderecos.apoio(new AEsquerda(atualizar));

        JPanel coluna = new Painel();
        coluna.setLayout(new BoxLayout(coluna, BoxLayout.Y_AXIS));
        coluna.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 20, 16, 20));
        empilhar(coluna, caixaDaConta, 0);
        empilhar(coluna, caixaDosEnderecos, 12);
        add(new AreaDaPagina(coluna, false), BorderLayout.CENTER);
        setName("pagina-cloudflare");
        getAccessibleContext().setAccessibleName("Cloudflare");
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                recarregar();
            }
        });
        redesenhar();
    }

    private static void empilhar(JPanel destino, JComponent c, int antes) {
        if (antes > 0) {
            destino.add(Caixa.espacoVertical(antes));
        }
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        destino.add(c);
    }

    // =====================================================================================================
    // Ler
    // =====================================================================================================

    /** Lê o que está escolhido (o cofre) e, conectado, os endereços na Cloudflare (só leitura). Chamado ao ficar visível, ao atualizar e depois de cada diálogo. */
    public void recarregar() {
        if (servidor == null) {
            modo = Modo.NAO_CONECTADO; // testes de tela sem servidor vivo
            carregou = true;
            enderecos = List.of();
            redesenhar();
            return;
        }
        ServicoCloudflare s = servidor.cloudflare();
        if (s.ehLocal()) {
            modo = Modo.LOCAL;
            carregou = true;
            enderecos = List.of();
            redesenhar();
            return;
        }
        boolean conectado;
        try {
            conectado = s.conectado();
        } catch (RuntimeException e) {
            conectado = false; // o cofre foi bloqueado no meio: a tela é descartada em seguida
        }
        if (!conectado) {
            modo = Modo.NAO_CONECTADO;
            carregou = true;
            enderecos = List.of();
            redesenhar();
            return;
        }
        modo = Modo.CONECTADO;
        int minha = geracao.incrementAndGet();
        situacaoDosEnderecos.setText(carregou ? "Atualizando…" : "Lendo da Cloudflare…");
        redesenhar();
        Tarefas.rodar(() -> s.enderecos(null), lista -> {
            if (minha != geracao.get()) {
                return;
            }
            carregou = true;
            enderecos = lista;
            erroDosEnderecos.setVisible(false);
            situacaoDosEnderecos.setText("");
            redesenhar();
        }, falha -> {
            if (minha != geracao.get()) {
                return;
            }
            carregou = true;
            erroDosEnderecos.setText(falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            erroDosEnderecos.setVisible(true);
            situacaoDosEnderecos.setText("");
            redesenhar();
        });
    }

    private void conectarOuTrocar() {
        if (acoes != null && servidor != null) {
            acoes.conectarCloudflare(servidor, this::recarregar);
        }
    }

    private void conferirOToken() {
        conferirToken.definirOcupado(true);
        resultadoDoToken.setText("Conferindo com a Cloudflare…");
        resultadoDoToken.setVisible(true);
        Tarefas.rodar(() -> servidor.cloudflare().conferirToken(), v -> {
            conferirToken.definirOcupado(false);
            resultadoDoToken.corDoToken(Tokens.OK);
            resultadoDoToken.setText("O token está ativo na Cloudflare" + (v.expiraEm().isEmpty() ? "." : " (vale até " + v.expiraEm() + ")."));
            revalidate();
        }, falha -> {
            conferirToken.definirOcupado(false);
            resultadoDoToken.corDoToken(Tokens.PERIGO);
            resultadoDoToken.setText(falha instanceof ErroCloudflare e ? e.mensagem() : falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            revalidate();
        });
    }

    // =====================================================================================================
    // Desenhar
    // =====================================================================================================

    private void redesenhar() {
        caixaDaConta.limpar();
        caixaDosEnderecos.limpar();
        switch (modo) {
            case CARREGANDO -> {
                seloDaConta.atualizar("LENDO", Gravidade.NEUTRO, null);
                caixaDaConta.linha(texto("Lendo o que está guardado…"), 0);
                caixaDosEnderecos.linha(texto("Os endereços aparecem quando a conta estiver conectada."), 0);
            }
            case LOCAL -> {
                seloDaConta.atualizar("SEM TÚNEL", Gravidade.NEUTRO, null);
                caixaDaConta.linha(texto("Publicar na internet pede um servidor com o cloudflared (o túnel da Cloudflare). \"Este computador\" não tem um túnel configurado pelo app: "
                    + "o app só monta endereços públicos em um servidor por SSH que já roda o cloudflared. Para publicar um projeto, conecte um servidor assim."), 0);
                caixaDosEnderecos.linha(texto("Nenhum endereço aqui."), 0);
            }
            case NAO_CONECTADO -> {
                seloDaConta.atualizar("NÃO CONECTADA", Gravidade.NEUTRO, null);
                explicacao.setText("Conecte a sua conta da Cloudflare para dar a um projeto um endereço público (como lab-meu-projeto.example.com). O app usa um token de API que você mesmo "
                    + "cria, com as permissões mínimas (DNS só do seu domínio e, se o túnel é gerido pela Cloudflare, o túnel). O token fica só no cofre cifrado deste app.");
                explicacao.setVisible(true);
                caixaDaConta.linha(explicacao, 0);
                caixaDaConta.linha(Caixa.linha(8, adicionarDominio, conectar, Caixa.mola()), 10);
                desligarSemAsAcoes();
                caixaDosEnderecos.linha(texto("Depois de conectar, os endereços criados pelo app aparecem aqui."), 0);
            }
            case CONECTADO -> desenharConectado();
        }
        caixaDaConta.terminar();
        caixaDosEnderecos.terminar();
        revalidate();
        repaint();
    }

    /** Sem as ações de escrita ligadas o botão aparece desligado, com a explicação, e não some. */
    private void desligarSemAsAcoes() {
        adicionarDominio.setEnabled(acoesLiberadas);
        adicionarDominio.setToolTipText(acoesLiberadas ? "Escolhe um projeto e dá a ele um endereço novo na internet, em passos curtos" : TextosDeAcao.TRAVA);
    }

    private static TextoCorrido texto(String t) {
        return new TextoCorrido(t, 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    }

    private void desenharConectado() {
        Optional<ConfiguracaoCloudflare> o = servidor.cloudflare().escolha();
        if (o.isEmpty()) {
            seloDaConta.atualizar("NÃO CONECTADA", Gravidade.NEUTRO, null);
            return;
        }
        ConfiguracaoCloudflare c = o.get();
        seloDaConta.atualizar("CONECTADA", Gravidade.OK, null);
        ficha.definir(List.of(
            FichaRotulada.Item.de("Domínio", c.zonaNome()),
            FichaRotulada.Item.de("Conta", c.contaNome().isEmpty() ? c.contaId() : c.contaNome()),
            FichaRotulada.Item.de("Túnel", c.tunelNome().isEmpty() ? c.tunelId() : c.tunelNome()),
            FichaRotulada.Item.de("As regras do túnel ficam", c.porArquivo() ? "no config.yml do servidor (" + c.caminhoDoConfig() + "), container " + c.containerDoCloudflared()
                : "na configuração do túnel, na Cloudflare"),
            FichaRotulada.Item.de("Começo do nome dos endereços", c.prefixo()),
            FichaRotulada.Item.de("Token", "guardado no cofre, cifrado")));
        caixaDaConta.linha(ficha, 0);
        caixaDaConta.linha(Caixa.fluxo(java.awt.FlowLayout.LEFT, 8, adicionarDominio, trocar, conferirToken, desconectar), 10);
        desligarSemAsAcoes();
        caixaDaConta.linha(resultadoDoToken, 6);
        if (!erroDosEnderecos.isVisible() && situacaoDosEnderecos.getText().isEmpty() && enderecos.isEmpty()) {
            caixaDosEnderecos.linha(texto("Nenhum endereço criado pelo app ainda. Abra um projeto e use a aba Internet para publicá-lo."), 0);
        }
        if (!situacaoDosEnderecos.getText().isEmpty()) {
            situacaoDosEnderecos.setVisible(true);
            caixaDosEnderecos.linha(situacaoDosEnderecos, 0);
        }
        if (erroDosEnderecos.isVisible()) {
            caixaDosEnderecos.linha(erroDosEnderecos, 0);
        }
        if (!enderecos.isEmpty()) {
            lista.definir(enderecos);
            caixaDosEnderecos.linha(lista, 0);
            if (!acoesLiberadas) {
                caixaDosEnderecos.linha(new Rotulo("Remover endereços fica desligado nesta versão (as ações de escrita estão desligadas).").corDoToken(Tokens.TEXTO3).tamanho(12f), 8);
            }
        }
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public Modo modo() {
        return modo;
    }

    public Botao botaoAdicionarDominio() {
        return adicionarDominio;
    }

    public Botao botaoConectar() {
        return conectar;
    }

    public Botao botaoTrocar() {
        return trocar;
    }

    public Botao botaoConferirToken() {
        return conferirToken;
    }

    public Botao botaoDesconectar() {
        return desconectar;
    }

    public Botao botaoAtualizar() {
        return atualizar;
    }

    public List<String> enderecosMostrados() {
        return lista.hostnames();
    }

    public List<Botao> botoesDeRemover() {
        return lista.botoesDeRemover();
    }

    public List<Botao> botoesDeAbrir() {
        return lista.botoesDeAbrir();
    }

    public String textoDoSelo() {
        return seloDaConta.texto();
    }

    public String textoDoResultadoDoToken() {
        return resultadoDoToken.isVisible() ? resultadoDoToken.getText() : "";
    }

    public String textoDoErroDosEnderecos() {
        return erroDosEnderecos.isVisible() ? erroDosEnderecos.getText() : "";
    }

    public boolean carregou() {
        return carregou;
    }
}
