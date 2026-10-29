package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.CatalogoDeInstalacao;
import br.com.labdesktop.instalador.EscolhaDeInstalacao;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.ItemDeInstalacao;
import br.com.labdesktop.instalador.ModoDaInstalacao;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.Passo;
import br.com.labdesktop.instalador.PassoDeInstalacao;
import br.com.labdesktop.instalador.PassoDeRemocao;
import br.com.labdesktop.instalador.RelatorioDeVerificacao;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaRemocao;
import br.com.labdesktop.instalador.ResultadoDaVerificacao;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.SecaoRecolhivel;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Fontes;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * O assistente do agente de UM servidor, em uma janela: <b>instalar</b>, <b>atualizar</b> ou <b>remover</b> (regra: NADA se
 * instala sozinho; este diálogo só abre pelo clique dele, e nada acontece no servidor antes do botão de confirmação, que leva o
 * nome do servidor: "Instalar em vps-1").
 * <ol>
 *   <li><b>Conferindo</b> (só leitura): as verificações prévias.</li>
 *   <li><b>O que instalar</b> (só ao INSTALAR num servidor sem agente): três cartões, <b>Completo</b> (marcado, "Recomendado"),
 *       <b>Escolher</b> (caixas só dos itens de {@link CatalogoDeInstalacao}, com a trava das dependências) e <b>Só SSH</b> (fecha, não
 *       instala nada). Atualizar, Remover e um servidor que já tem o agente pulam esta tela.</li>
 *   <li><b>Confirmar</b>: SÓ o que foi escolhido: o resultado das verificações como lista com marca (ok, aviso, problema) e frase simples, o comando EXATO
 *       que o usuário ou o administrador do servidor deve rodar quando falta permissão, o que vai acontecer passo a passo, e o que o
 *       agente é e o que ele alcança (acesso ao Docker, equivale a root; só loopback; token só como hash; nada no firewall).</li>
 *   <li><b>Andamento</b>: cada passo com estado ao vivo (ícone e palavra), o log completo recolhível, o botão Cancelar e, no fim, o
 *       sucesso ou o erro REAL do servidor (como texto) com o que foi desfeito e o botão "Tentar de novo".</li>
 * </ol>
 * Tudo o que fala com o servidor roda fora da EDT, numa thread que o Cancelar interrompe.
 */
public final class DialogoDoAgente extends Dialogo {

    /** O que o usuário pediu. */
    public enum Acao {
        INSTALAR("Instalar no servidor", ModoDaInstalacao.INSTALAR),
        ATUALIZAR("Atualizar o agente", ModoDaInstalacao.ATUALIZAR),
        REMOVER("Remover o agente", null);

        private final String titulo;
        private final ModoDaInstalacao modo;

        Acao(String titulo, ModoDaInstalacao modo) {
            this.titulo = titulo;
            this.modo = modo;
        }
    }

    /** O que o diálogo precisa saber do servidor, sem conhecer o {@code ServidorLab}. */
    public record Alvo(String nome, int portaDoAgente, String versaoInstalada) { }

    /** O texto de segurança que o usuário lê ANTES de confirmar. */
    public static final List<String> TEXTOS_DE_SEGURANCA = List.of(
        "O agente roda num container que tem acesso ao Docker do servidor. Isso equivale a ser administrador (root) nele: instale só em "
            + "servidor seu.",
        "Ele escuta SÓ em 127.0.0.1 do servidor (nunca em 0.0.0.0): nada entra pela rede, e só este app o alcança, pelo túnel SSH.",
        "O token deste computador fica no agente apenas como hash (SHA-256), nunca em claro.",
        "O app não abre porta no firewall e não mexe nele.");

    /** O que o usuário lê antes de confirmar quando SÓ o Docker vai ser instalado (o agente não entra). */
    public static final List<String> TEXTOS_DE_SEGURANCA_DO_DOCKER = List.of(
        "O Docker é instalado com permissão de administrador, pelo repositório oficial da Docker. Quem usa o Docker no servidor passa a ter "
            + "poder de administrador (root) sobre ele.",
        "O app não abre porta no firewall e não mexe nele.",
        "O agente de monitoramento NÃO é instalado agora: o app continua usando o servidor só por SSH até você pedir.");

    private static final String PAGINA_CONFERINDO = "conferindo";
    private static final String PAGINA_ESCOLHER = "escolher";
    private static final String PAGINA_CONFIRMAR = "confirmar";
    private static final String PAGINA_ANDAMENTO = "andamento";

    private final Acao acao;
    private final Alvo alvo;
    private final PacoteDoAgente pacote;
    private final Function<ParametrosDaInstalacao, InstaladorDeAgente> fabrica;
    private final Runnable aoMudarOServidor;
    private final Notificacoes avisos;
    private final CopiadorDeTexto copiador;
    /** O usuário escolheu "Só SSH": o servidor fica como está (o assistente de adicionar servidor conclui como "Agora não"). */
    private final Runnable aoEscolherSoSsh;
    /** Só ao INSTALAR num servidor que ainda não tem o agente há a tela "O que instalar". */
    private final boolean comEscolha;

    private final CardLayout paginas = new CartoesAjustaveis();
    private final JPanel pilha = new Painel(paginas);
    private final CardLayout cartoesDosRodapes = new CartoesAjustaveis();
    private final JPanel pilhaDeRodapes = new Painel(cartoesDosRodapes);
    private String paginaAtual = PAGINA_CONFERINDO;

    // ---- conferindo ----
    private final Rotulo textoDeConferindo = new Rotulo("Conferindo o servidor (só leitura)…").corDoToken(Tokens.TEXTO2);
    private final TextoCorrido erroDeConferir = erroCorrido();
    private final Botao tentarConferirDeNovo = Botoes.secundario("Tentar de novo", "atualizar");
    private final Botao cancelarNaConferencia = Botoes.secundario("Cancelar", null);

    // ---- o que instalar ----
    private final Map<EscolhaDeInstalacao.Tipo, JRadioButton> radios = new java.util.EnumMap<>(EscolhaDeInstalacao.Tipo.class);
    private final Map<EscolhaDeInstalacao.Tipo, TextoCorrido> frasesDosCartoes = new java.util.EnumMap<>(EscolhaDeInstalacao.Tipo.class);
    private final Map<EscolhaDeInstalacao.Tipo, Painel> cartoesDaEscolha = new java.util.EnumMap<>(EscolhaDeInstalacao.Tipo.class);
    private final Map<String, JCheckBox> caixasDosItens = new LinkedHashMap<>();
    private final JPanel listaDeItens = new Painel();
    private final JPanel detalhesDosItens = new Painel();
    private final TextoCorrido fraseDoCompleto = new TextoCorrido("", LARGURA_DO_TEXTO - 52).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido explicacaoDaEscolha = erroCorrido();
    private final Botao continuar = Botoes.primario("Continuar", "avancar");
    private final Botao cancelarNaEscolha = Botoes.secundario("Cancelar", null);
    private final Botao voltar = Botoes.fantasma("Mudar o que instalar", "voltar");
    private final SecaoRecolhivel oQueIssoFaz;
    private EscolhaDeInstalacao.Tipo tipoDaTela = EscolhaDeInstalacao.Tipo.COMPLETO;
    private final Set<String> marcadosNaTela = new LinkedHashSet<>();
    private RelatorioDeVerificacao relatorioCompleto;
    /** A escolha em vigor (sempre válida, nunca Só SSH): é ela que decide os passos, as verificações e o que a tela mostra. */
    private EscolhaDeInstalacao escolha;

    // ---- confirmar ----
    private final TextoCorrido introDaConfirmacao = paragrafo("");
    private final JPanel listaDeSeguranca = new Painel();
    private final Rotulo tituloDaSeguranca = secao("Segurança");
    private final JPanel listaDeVerificacoes = new Painel();
    private final JPanel listaDoQueVaiAcontecer = new Painel();
    private final TextoCorrido avisoDeBloqueio = erroCorrido();
    private final TextoCorrido versaoAtual = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2);
    private final JTextField campoDoContainer = Campos.mono(18, ParametrosDaInstalacao.CONTAINER_PADRAO);
    private final TextoCorrido erroDoContainer = erroCorrido();
    private final SecaoRecolhivel opcoesAvancadas;
    private final Botao cancelar;
    private final Botao conferirDeNovo = Botoes.secundario("Conferir de novo", "atualizar");
    private final Botao confirmar;

    // ---- andamento ----
    private final Map<Passo, Rotulo> linhasDosPassos = new java.util.LinkedHashMap<>();
    private final Map<Passo, Component> blocosDosPassos = new java.util.HashMap<>();
    private final Map<Passo, TextoCorrido> detalhesDosPassos = new java.util.HashMap<>();
    private final Map<Passo, EstadoDoPasso> estadosDosPassos = new java.util.HashMap<>();
    private final AreaMonoespacada areaDoLog = new AreaMonoespacada("Log completo do instalador", true);
    private final SecaoRecolhivel secaoDoLog;
    private final Rotulo situacaoFinal = new Rotulo();
    private final TextoCorrido mensagemFinal = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO);
    private final JPanel listaDoDesfeito = new Painel();
    private final Botao cancelarExecucao = Botoes.secundario("Cancelar", "parar");
    private final Botao tentarDeNovo = Botoes.secundario("Tentar de novo", "atualizar");
    private final Botao fecharAoFim = Botoes.fantasma("Fechar", null);
    private final Botao concluir = Botoes.primario("Concluir", "ok");

    private ParametrosDaInstalacao parametros;
    private InstaladorDeAgente instalador;
    private RelatorioDeVerificacao relatorio;
    private boolean executando;
    private boolean terminouComSucesso;
    private final AtomicBoolean cancelado = new AtomicBoolean();
    /** Quanto o botão fica em "Cancelando…" antes de oferecer "Fechar mesmo assim" (o desfazer continua em segundo plano). */
    private static volatile int prazoParaFecharMesmoAssimMs = 8000;
    /** Quanto o "Instalar em ..." fica desligado depois do "Continuar" (acima). */
    private static volatile int prazoDaTravaDaConfirmacaoMs = 600;
    private javax.swing.Timer travaDaConfirmacao;
    private javax.swing.Timer prazoDoCancelamento;
    private boolean podeFecharMesmoAssim;
    private final AtomicReference<Thread> trabalhador = new AtomicReference<>();

    /**
     * @param fabrica          monta o instalador do servidor com os parâmetros (em produção, {@code ServidorLab::instaladorDoAgente})
     * @param aoMudarOServidor chamado quando o servidor mudou de verdade (instalou, atualizou ou removeu): o app repete o teste do agente
     */
    public DialogoDoAgente(Acao acao, Alvo alvo, PacoteDoAgente pacote, Function<ParametrosDaInstalacao, InstaladorDeAgente> fabrica,
                           Runnable aoMudarOServidor, Notificacoes avisos, CopiadorDeTexto copiador) {
        this(acao, alvo, pacote, fabrica, aoMudarOServidor, () -> { }, avisos, copiador);
    }

    /**
     * @param aoEscolherSoSsh chamado (na EDT) quando o usuário escolhe "Só SSH, não instalar nada": quem abriu o diálogo decide o que
     *                        isso significa (o assistente de adicionar servidor conclui como "Agora não"; a página do servidor só avisa)
     */
    public DialogoDoAgente(Acao acao, Alvo alvo, PacoteDoAgente pacote, Function<ParametrosDaInstalacao, InstaladorDeAgente> fabrica,
                           Runnable aoMudarOServidor, Runnable aoEscolherSoSsh, Notificacoes avisos, CopiadorDeTexto copiador) {
        super(acao.titulo);
        this.aoEscolherSoSsh = aoEscolherSoSsh == null ? () -> { } : aoEscolherSoSsh;
        this.comEscolha = acao == Acao.INSTALAR && alvo.versaoInstalada() == null;
        this.acao = acao;
        this.alvo = alvo;
        this.pacote = pacote;
        this.fabrica = fabrica;
        this.aoMudarOServidor = aoMudarOServidor;
        this.avisos = avisos;
        this.copiador = copiador;
        this.parametros = ParametrosDaInstalacao.padrao(alvo.portaDoAgente());
        campoDoContainer.setText(parametros.container());
        this.instalador = fabrica.apply(parametros);

        boolean remover = acao == Acao.REMOVER;
        this.cancelar = remover ? Botoes.primario("Cancelar", null) : Botoes.secundario("Cancelar", null);
        this.confirmar = remover ? Botoes.perigo("Remover agente de " + alvo.nome(), "lixeira")
            : Botoes.primario((acao == Acao.ATUALIZAR ? "Atualizar em " : "Instalar em ") + alvo.nome(), "servidor");
        confirmar.setEnabled(remover);

        oQueIssoFaz = new SecaoRecolhivel("O que isso faz?", "Esconder", detalhesDosItens, this::reajustar);
        opcoesAvancadas = new SecaoRecolhivel("Opções avançadas (para testar)", "Esconder as opções avançadas", montarOpcoesAvancadas(),
            this::reajustar);
        secaoDoLog = new SecaoRecolhivel("Ver o log completo", "Esconder o log", montarLog(), this::reajustar);

        pilha.add(montarPaginaConferindo(), PAGINA_CONFERINDO);
        pilha.add(montarPaginaEscolher(), PAGINA_ESCOLHER);
        pilha.add(montarPaginaConfirmar(), PAGINA_CONFIRMAR);
        pilha.add(montarPaginaAndamento(), PAGINA_ANDAMENTO);
        pilhaDeRodapes.add(rodape(null, cancelarNaConferencia, tentarConferirDeNovo), PAGINA_CONFERINDO);
        pilhaDeRodapes.add(rodape(conferirDeNovo, cancelar, confirmar), PAGINA_CONFIRMAR);
        pilhaDeRodapes.add(rodape(null, cancelarNaEscolha, continuar), PAGINA_ESCOLHER);
        pilhaDeRodapes.add(rodape(null, cancelarExecucao, fecharAoFim, tentarDeNovo, concluir), PAGINA_ANDAMENTO);
        conteudoComRodape(pilha, pilhaDeRodapes);

        ligarEventos();
        mostrarPagina(remover ? PAGINA_CONFIRMAR : PAGINA_CONFERINDO);
        if (remover) {
            montarConfirmacaoDeRemocao();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private JComponent montarOpcoesAvancadas() {
        return Caixa.coluna(6,
            Rotulo.rotuloDeCampo("Nome do container do agente"),
            campoDoContainer,
            dicaCorrida("Deixe " + ParametrosDaInstalacao.CONTAINER_PADRAO + ". Só mude para testar ao lado de outro agente (por exemplo "
                + "painel-teste) sem tocar nele; o app nunca mexe num container que não criou. A porta é a do painel deste servidor ("
                + alvo.portaDoAgente() + "): para testar em outra, edite o servidor."),
            erroDoContainer);
    }

    private JComponent montarLog() {
        JScrollPane rolagem = AreaMonoespacada.emRolagem(areaDoLog);
        rolagem.setPreferredSize(new Dimension(LARGURA_DO_TEXTO - 20, 170));
        return rolagem;
    }

    private JComponent montarPaginaConferindo() {
        listaDeVerificacoes.setLayout(new javax.swing.BoxLayout(listaDeVerificacoes, javax.swing.BoxLayout.Y_AXIS));
        return Caixa.coluna(14,
            cabecalho("servidor", tituloDaTela()),
            paragrafo("Antes de qualquer coisa, o app só LÊ o servidor para conferir se dá certo. Nada é instalado nem alterado agora."),
            textoDeConferindo,
            erroDeConferir);
    }

    /** O título das telas: "Instalar em vps-1", "Atualizar o agente em vps-1", "Remover o agente em vps-1". */
    private String tituloDaTela() {
        return acao == Acao.INSTALAR ? "Instalar em " + alvo.nome() : acao.titulo + " em " + alvo.nome();
    }

    private JComponent montarPaginaConfirmar() {
        listaDeVerificacoes.setLayout(new javax.swing.BoxLayout(listaDeVerificacoes, javax.swing.BoxLayout.Y_AXIS));
        listaDoQueVaiAcontecer.setLayout(new javax.swing.BoxLayout(listaDoQueVaiAcontecer, javax.swing.BoxLayout.Y_AXIS));
        listaDeSeguranca.setLayout(new javax.swing.BoxLayout(listaDeSeguranca, javax.swing.BoxLayout.Y_AXIS));
        List<Component> corpo = new ArrayList<>();
        corpo.add(cabecalho(acao == Acao.REMOVER ? "lixeira" : "servidor", tituloDaTela()));
        if (acao == Acao.REMOVER) {
            corpo.add(paragrafo("O app vai remover do servidor o que ele mesmo instalou. O Docker continua instalado, e os seus projetos "
                + "e arquivos não são tocados."));
        } else {
            corpo.add(introDaConfirmacao);
            if (comEscolha) {
                corpo.add(Caixa.linha(0, voltar, Caixa.mola()));
            }
            corpo.add(versaoAtual);
            corpo.add(secao("O que o app conferiu no servidor"));
            corpo.add(listaDeVerificacoes);
            corpo.add(avisoDeBloqueio);
        }
        corpo.add(secao(acao == Acao.REMOVER ? "O que vai ser removido" : "O que vai acontecer"));
        corpo.add(listaDoQueVaiAcontecer);
        if (acao != Acao.REMOVER) {
            corpo.add(tituloDaSeguranca);
            corpo.add(listaDeSeguranca);
            corpo.add(opcoesAvancadas);
        }
        return Caixa.coluna(10, corpo.toArray(Component[]::new));
    }

    private static Rotulo secao(String texto) {
        return Rotulo.rotuloDeCampo(texto);
    }

    private JComponent montarPaginaAndamento() {
        List<? extends Passo> passos = acao == Acao.REMOVER ? List.of(PassoDeRemocao.values()) : List.of(PassoDeInstalacao.values());
        List<Component> linhas = new ArrayList<>();
        for (Passo p : passos) {
            Rotulo r = new Rotulo(p.rotulo()).corDoToken(Tokens.TEXTO3);
            r.setIcon(iconeDo(EstadoDoPasso.PENDENTE));
            r.setIconTextGap(8);
            TextoCorrido detalhe = new TextoCorrido("", LARGURA_DO_TEXTO - 26).corDoToken(Tokens.PERIGO).tamanho(12.5f);
            detalhe.setVisible(false);
            linhasDosPassos.put(p, r);
            detalhesDosPassos.put(p, detalhe);
            estadosDosPassos.put(p, EstadoDoPasso.PENDENTE);
            JPanel bloco = Caixa.coluna(2, r, Caixa.linha(0, Caixa.espaco(26), detalhe));
            blocosDosPassos.put(p, bloco);
            linhas.add(bloco);
        }
        listaDoDesfeito.setLayout(new javax.swing.BoxLayout(listaDoDesfeito, javax.swing.BoxLayout.Y_AXIS));
        mensagemFinal.setVisible(false);
        situacaoFinal.setVisible(false);
        return Caixa.coluna(14,
            cabecalho(acao == Acao.REMOVER ? "lixeira" : "servidor", tituloDaTela()),
            Caixa.coluna(8, linhas.toArray(Component[]::new)),
            situacaoFinal,
            mensagemFinal,
            listaDoDesfeito,
            secaoDoLog);
    }

    private void ligarEventos() {
        cancelarNaConferencia.addActionListener(e -> aoCancelar());
        tentarConferirDeNovo.addActionListener(e -> conferir());
        cancelarNaEscolha.addActionListener(e -> aoCancelar());
        continuar.addActionListener(e -> aoContinuarDaEscolha());
        voltar.addActionListener(e -> voltarParaAEscolha());
        cancelar.addActionListener(e -> aoCancelar());
        conferirDeNovo.addActionListener(e -> conferirDepoisDeEditarOContainer());
        confirmar.addActionListener(e -> confirmar());
        cancelarExecucao.addActionListener(e -> aoCancelar());
        fecharAoFim.addActionListener(e -> fechar());
        concluir.addActionListener(e -> fechar());
        tentarDeNovo.addActionListener(e -> executar());
    }

    private void mostrarPagina(String nome) {
        paginaAtual = nome;
        paginas.show(pilha, nome);
        cartoesDosRodapes.show(pilhaDeRodapes, nome);
        rolarParaOTopo();
        botaoPadrao(switch (nome) {
            case PAGINA_ESCOLHER -> continuar.isEnabled() ? continuar : cancelarNaEscolha;
            case PAGINA_CONFIRMAR -> acao == Acao.REMOVER ? cancelar : (confirmar.isEnabled() ? confirmar : cancelar);
            case PAGINA_ANDAMENTO -> concluir.isVisible() ? concluir : cancelarExecucao;
            default -> cancelarNaConferencia;
        });
        reajustar();
    }

    @Override
    protected void antesDeMostrar() {
        iniciarConferencia();
    }

    /**
     * Começa a conferência (só leitura) quando a janela aparece. Remover não confere nada: vai direto à confirmação. Público para os testes
     * (sem janela de verdade, {@code antesDeMostrar} não roda).
     */
    public void iniciarConferencia() {
        if (acao != Acao.REMOVER && relatorioCompleto == null && !executando) {
            conferir();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public String paginaAtual() {
        return paginaAtual;
    }

    public Botao botaoConfirmar() {
        return confirmar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoConferirDeNovo() {
        return conferirDeNovo;
    }

    public Botao botaoCancelarExecucao() {
        return cancelarExecucao;
    }

    public Botao botaoTentarDeNovo() {
        return tentarDeNovo;
    }

    public Botao botaoConcluir() {
        return concluir;
    }

    public Botao botaoFechar() {
        return fecharAoFim;
    }

    public Botao botaoTentarConferirDeNovo() {
        return tentarConferirDeNovo;
    }

    /** "Continuar" na tela "O que instalar" (vira "Usar só por SSH" quando esse cartão está marcado). */
    public Botao botaoContinuar() {
        return continuar;
    }

    public Botao botaoVoltar() {
        return voltar;
    }

    public Botao botaoCancelarNaEscolha() {
        return cancelarNaEscolha;
    }

    /** A bolinha de um dos três cartões. */
    public JRadioButton cartao(EscolhaDeInstalacao.Tipo tipo) {
        return radios.get(tipo);
    }

    /** A caixa de um item na lista do Escolher ({@code null} se o item não é oferecido: o Docker só aparece se faltar). */
    public JCheckBox caixaDoItem(String id) {
        return caixasDosItens.get(id);
    }

    /** Os ids dos itens que a lista do Escolher mostra, na ordem. */
    public List<String> itensListados() {
        return List.copyOf(caixasDosItens.keySet());
    }

    /** A explicação da combinação travada (ou do "marque pelo menos um"); vazia quando a escolha vale. */
    public String explicacaoDaEscolha() {
        return explicacaoDaEscolha.getText();
    }

    /** Só o texto da frase do cartão Completo. */
    public String fraseDoCompleto() {
        return fraseDoCompleto.getText();
    }

    public boolean listaDeItensVisivel() {
        return listaDeItens.isVisible();
    }

    public SecaoRecolhivel oQueIssoFaz() {
        return oQueIssoFaz;
    }

    /** A escolha em vigor (a que a confirmação mostra e o instalador executa); {@code null} antes de escolher. */
    public EscolhaDeInstalacao escolhaEmVigor() {
        return escolha;
    }

    /** Os passos que a tela de andamento mostra (os que não foram escolhidos ficam escondidos). */
    public List<Passo> passosMostrados() {
        List<Passo> l = new ArrayList<>();
        for (Passo p : linhasDosPassos.keySet()) {
            if (blocosDosPassos.get(p).isVisible()) {
                l.add(p);
            }
        }
        return l;
    }

    public JTextField campoDoContainer() {
        return campoDoContainer;
    }

    public SecaoRecolhivel opcoesAvancadas() {
        return opcoesAvancadas;
    }

    public SecaoRecolhivel secaoDoLog() {
        return secaoDoLog;
    }

    public String textoDoLog() {
        return areaDoLog.getText();
    }

    public String textoDoBloqueio() {
        return avisoDeBloqueio.getText();
    }

    public String textoDaConferencia() {
        return erroDeConferir.getText();
    }

    public String textoFinal() {
        return mensagemFinal.getText();
    }

    public String situacaoFinal() {
        return situacaoFinal.getText();
    }

    public boolean executando() {
        return executando;
    }

    public boolean terminouComSucesso() {
        return terminouComSucesso;
    }

    public RelatorioDeVerificacao relatorio() {
        return relatorio;
    }

    /** O estado de um passo na tela de andamento. */
    public EstadoDoPasso estadoDoPasso(Passo passo) {
        return estadosDosPassos.get(passo);
    }

    /** O erro mostrado embaixo de um passo. */
    public String detalheDoPasso(Passo passo) {
        TextoCorrido t = detalhesDosPassos.get(passo);
        return t == null ? "" : t.getText();
    }

    /** As frases das verificações mostradas, na ordem. */
    public List<String> frasesDasVerificacoes() {
        return textosDe(listaDeVerificacoes);
    }

    /** O que a tela diz que vai acontecer, um item por linha. */
    public List<String> frasesDoQueVaiAcontecer() {
        return textosDe(listaDoQueVaiAcontecer);
    }

    public List<String> linhasDoDesfeito() {
        return textosDe(listaDoDesfeito);
    }

    /** Os comandos sugeridos mostrados (os que o usuário pode copiar). */
    public List<String> comandosSugeridos() {
        List<String> l = new ArrayList<>();
        coletar(listaDeVerificacoes, c -> {
            if (c instanceof TextoCorrido t && "comando-sugerido".equals(t.getName())) {
                l.add(t.getText());
            }
        });
        return l;
    }

    private static List<String> textosDe(JComponent raiz) {
        List<String> l = new ArrayList<>();
        coletar(raiz, c -> {
            if (c instanceof TextoCorrido t && t.isVisible() && !"comando-sugerido".equals(t.getName())) {
                l.add(t.getText());
            } else if (c instanceof Rotulo r && r.isVisible() && !r.getText().isEmpty() && c.getName() == null) {
                l.add(r.getText());
            }
        });
        return l;
    }

    private static void coletar(Component c, java.util.function.Consumer<Component> f) {
        f.accept(c);
        if (c instanceof java.awt.Container k) {
            for (Component filho : k.getComponents()) {
                coletar(filho, f);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // O que instalar
    // ---------------------------------------------------------------------------------------------

    private JComponent montarPaginaEscolher() {
        listaDeItens.setLayout(new javax.swing.BoxLayout(listaDeItens, javax.swing.BoxLayout.Y_AXIS));
        detalhesDosItens.setLayout(new javax.swing.BoxLayout(detalhesDosItens, javax.swing.BoxLayout.Y_AXIS));
        ButtonGroup grupo = new ButtonGroup();
        for (EscolhaDeInstalacao.Tipo t : EscolhaDeInstalacao.Tipo.values()) {
            JRadioButton r = new JRadioButton(switch (t) {
                case COMPLETO -> "Completo";
                case ESCOLHER -> "Escolher";
                case SO_SSH -> "Só SSH, não instalar nada";
            });
            r.setFont(Fontes.fonte(Fontes.TEXTO, java.awt.Font.BOLD, 13.5f));
            r.setOpaque(false);
            r.setFocusPainted(false);
            r.putClientProperty("html.disable", Boolean.TRUE);
            r.getAccessibleContext().setAccessibleName(r.getText());
            r.setSelected(t == tipoDaTela);
            r.addActionListener(e -> escolherOTipo(t));
            radios.put(t, r);
            grupo.add(r);
        }
        listaDeItens.setVisible(false);
        JComponent completo = cartaoDaEscolha(EscolhaDeInstalacao.Tipo.COMPLETO, new Selo("Recomendado", Gravidade.OK), fraseDoCompleto, null);
        JComponent escolher = cartaoDaEscolha(EscolhaDeInstalacao.Tipo.ESCOLHER, null,
            dicaDoCartao("Você marca só o que quer. O app nunca instala metade."), listaDeItens);
        JComponent soSsh = cartaoDaEscolha(EscolhaDeInstalacao.Tipo.SO_SSH, null,
            dicaDoCartao("Não instala nada. Terminal, Segurança e Arquivos seguem funcionando por SSH; dá para instalar depois."), null);
        return Caixa.coluna(8,
            cabecalho("servidor", "O que instalar em " + alvo.nome()),
            paragrafo("Nada é instalado agora. Você escolhe e confirma antes."),
            completo, escolher, soSsh, explicacaoDaEscolha, oQueIssoFaz);
    }

    private static TextoCorrido dicaDoCartao(String texto) {
        return new TextoCorrido(texto, LARGURA_DO_TEXTO - 52).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    }

    /** Um cartão com a bolinha de escolha, o título, uma frase e (no Escolher) a lista de caixas; o clique no cartão marca a bolinha. */
    private JComponent cartaoDaEscolha(EscolhaDeInstalacao.Tipo tipo, Selo selo, TextoCorrido frase, JPanel extra) {
        Painel c = new Painel(new java.awt.BorderLayout(0, 4)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        c.setBorder(new javax.swing.border.EmptyBorder(10, 14, 10, 14));
        List<Component> topo = new ArrayList<>();
        topo.add(radios.get(tipo));
        if (selo != null) {
            topo.add(Caixa.fluxo(java.awt.FlowLayout.LEFT, 0, selo));
        }
        topo.add(Caixa.mola());
        c.add(Caixa.linha(8, topo.toArray(Component[]::new)), java.awt.BorderLayout.NORTH);
        JPanel meio = Caixa.coluna(6, extra == null ? new Component[] {frase} : new Component[] {frase, extra});
        c.add(Caixa.linha(0, Caixa.espaco(24), meio), java.awt.BorderLayout.CENTER);
        java.awt.event.MouseAdapter clicar = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                radios.get(tipo).doClick();
            }
        };
        c.addMouseListener(clicar);
        frase.addMouseListener(clicar);
        cartoesDaEscolha.put(tipo, c);
        frasesDosCartoes.put(tipo, frase);
        return c;
    }

    /** (Re)monta a lista de caixas e a frase do Completo a partir da conferência: o Docker só aparece se faltar. */
    private void montarAEscolha() {
        List<ItemDeInstalacao> oferecidos = completoAgora().oferecidos();
        Set<String> antes = new LinkedHashSet<>(marcadosNaTela);
        boolean primeira = caixasDosItens.isEmpty();
        marcadosNaTela.clear();
        caixasDosItens.clear();
        listaDeItens.removeAll();
        detalhesDosItens.removeAll();
        List<String> titulos = new ArrayList<>();
        for (ItemDeInstalacao item : oferecidos) {
            titulos.add(item.titulo());
            JCheckBox caixa = new JCheckBox(item.titulo());
            caixa.setFont(Fontes.fonte(Fontes.TEXTO, java.awt.Font.BOLD, 13f));
            caixa.setOpaque(false);
            caixa.setFocusPainted(false);
            caixa.putClientProperty("html.disable", Boolean.TRUE);
            caixa.getAccessibleContext().setAccessibleName(item.titulo());
            caixa.setName("item-" + item.id());
            boolean marcado = primeira || antes.contains(item.id());
            caixa.setSelected(marcado);
            if (marcado) {
                marcadosNaTela.add(item.id());
            }
            caixa.addActionListener(e -> {
                if (caixa.isSelected()) {
                    marcadosNaTela.add(item.id());
                } else {
                    marcadosNaTela.remove(item.id());
                }
                atualizarAEscolha();
            });
            caixasDosItens.put(item.id(), caixa);
            listaDeItens.add(Caixa.linha(0, caixa, Caixa.mola()));
            listaDeItens.add(Caixa.linha(0, Caixa.espaco(24), Caixa.coluna(2,
                dicaDoItem(item.frase(), Tokens.TEXTO2),
                dicaDoItem("Alcança: " + item.alcance(), Tokens.TEXTO3),
                dicaDoItem("Exige: " + exigenciasDe(item), Tokens.TEXTO3))));
            listaDeItens.add(Caixa.espacoVertical(8));

            detalhesDosItens.add(new Rotulo(item.titulo()).negrito());
            detalhesDosItens.add(dicaDoItem(item.frase(), Tokens.TEXTO2));
            detalhesDosItens.add(dicaDoItem("Alcança: " + item.alcance(), Tokens.TEXTO3));
            detalhesDosItens.add(dicaDoItem("Exige: " + exigenciasDe(item), Tokens.TEXTO3));
            List<String> rotulos = new ArrayList<>();
            item.passos().forEach(p -> rotulos.add(p.rotulo()));
            detalhesDosItens.add(dicaDoItem("O que o app faz: " + String.join("; ", rotulos) + ".", Tokens.TEXTO3));
            detalhesDosItens.add(Caixa.espacoVertical(8));
        }
        detalhesDosItens.add(dicaDoItem("Nenhum item instala o Claude nem outra IA: isso fica por sua conta.", Tokens.TEXTO3));
        fraseDoCompleto.setText("Instala tudo o que falta neste servidor: " + String.join(" e ", titulos) + ".");
        radios.get(tipoDaTela).setSelected(true);
        atualizarAEscolha();
    }

    private static TextoCorrido dicaDoItem(String texto, String token) {
        return new TextoCorrido(texto, LARGURA_DO_TEXTO - 76).corDoToken(token).tamanho(12f);
    }

    /** O que o item exige, em uma frase: o dele mais, para cada dependência, se ela já está no servidor ou se falta marcá-la. */
    private String exigenciasDe(ItemDeInstalacao item) {
        StringBuilder sb = new StringBuilder(item.exige());
        for (String id : item.dependeDe()) {
            for (ItemDeInstalacao dep : CatalogoDeInstalacao.itens()) {
                if (dep.id().equals(id)) {
                    sb.append(' ').append(dep.resolvidoNoServidor().test(relatorioCompleto)
                        ? "Precisa do " + dep.titulo() + ": já está neste servidor."
                        : "Precisa do " + dep.titulo() + ": falta neste servidor, marque-o também.");
                }
            }
        }
        return sb.toString();
    }

    private void escolherOTipo(EscolhaDeInstalacao.Tipo t) {
        tipoDaTela = t;
        atualizarAEscolha();
    }

    /** A escolha que a tela mostra agora (ainda não aplicada). */
    private EscolhaDeInstalacao escolhaDaTela() {
        return switch (tipoDaTela) {
            case COMPLETO -> completoAgora();
            case ESCOLHER -> EscolhaDeInstalacao.escolher(CatalogoDeInstalacao.itens(), relatorioCompleto, marcadosNaTela);
            case SO_SSH -> EscolhaDeInstalacao.soSsh();
        };
    }

    /** Liga e desliga o Continuar pelas dependências, explica a combinação travada e mostra a lista só no Escolher. */
    private void atualizarAEscolha() {
        if (relatorioCompleto == null) {
            return;
        }
        EscolhaDeInstalacao e = escolhaDaTela();
        List<String> problemas = e.problemas();
        listaDeItens.setVisible(tipoDaTela == EscolhaDeInstalacao.Tipo.ESCOLHER);
        dizerErro(explicacaoDaEscolha, String.join(" ", problemas));
        continuar.setEnabled(problemas.isEmpty());
        continuar.setText(tipoDaTela == EscolhaDeInstalacao.Tipo.SO_SSH ? "Usar só por SSH" : "Continuar");
        for (Map.Entry<EscolhaDeInstalacao.Tipo, Painel> c : cartoesDaEscolha.entrySet()) {
            c.getValue().borda(c.getKey() == tipoDaTela ? Tokens.DESTAQUE : Tokens.BORDA);
            // com a lista de caixas aberta os outros dois cartões encolhem para o título (a tela cabe sem rolagem)
            EscolhaDeInstalacao.Tipo t = c.getKey();
            frasesDosCartoes.get(t).setVisible(tipoDaTela != EscolhaDeInstalacao.Tipo.ESCOLHER || t == EscolhaDeInstalacao.Tipo.ESCOLHER);
        }
        if (PAGINA_ESCOLHER.equals(paginaAtual)) {
            botaoPadrao(continuar.isEnabled() ? continuar : cancelarNaEscolha);
        }
        reajustar();
    }

    /** "Continuar" (ou "Usar só por SSH"): Só SSH fecha sem tocar em nada; as outras duas seguem para a confirmação. */
    private void aoContinuarDaEscolha() {
        if (executando || !continuar.isEnabled()) {
            return;
        }
        EscolhaDeInstalacao e = escolhaDaTela();
        if (e.tipo() == EscolhaDeInstalacao.Tipo.SO_SSH) {
            // fecha ANTES de avisar: o assistente de adicionar servidor conclui (e fecha a si mesmo) e não pode haver este diálogo filho aberto
            fechar();
            aoEscolherSoSsh.run();
            return;
        }
        if (!e.valida()) {
            return;
        }
        aplicarAEscolha(e);
        mostrarPagina(PAGINA_CONFIRMAR);
        travarAConfirmacaoPorUmInstante();
    }

    /**
     * O botão "Instalar em vps-1" nasce no mesmo lugar do "Continuar" que acabou de ser clicado: um clique duplo (ou dois Enter) cairia nele e
     * instalaria SEM o clique de confirmação. Por um instante depois de chegar aqui vindo do "Continuar" o botão fica desligado e nenhum botão
     * responde ao Enter; passado o prazo o usuário confirma com um clique deliberado.
     */
    private void travarAConfirmacaoPorUmInstante() {
        pararATravaDaConfirmacao();
        int ms = prazoDaTravaDaConfirmacaoMs;
        if (ms <= 0 || !confirmar.isEnabled()) {
            return;
        }
        confirmar.setEnabled(false);
        botaoPadrao(null);
        travaDaConfirmacao = new javax.swing.Timer(ms, e -> {
            travaDaConfirmacao = null;
            if (PAGINA_CONFIRMAR.equals(paginaAtual) && !executando && relatorio != null && !relatorio.bloqueado()) {
                confirmar.setEnabled(true);
                botaoPadrao(confirmar);
            }
        });
        travaDaConfirmacao.setRepeats(false);
        travaDaConfirmacao.start();
    }

    private void pararATravaDaConfirmacao() {
        if (travaDaConfirmacao != null) {
            travaDaConfirmacao.stop();
            travaDaConfirmacao = null;
        }
    }

    /** Só os testes: quanto o botão de confirmar fica desligado depois do "Continuar" (0 desliga a trava). */
    public static void definirPrazoDaTravaDaConfirmacaoParaTeste(int milissegundos) {
        prazoDaTravaDaConfirmacaoMs = milissegundos;
    }

    private void voltarParaAEscolha() {
        if (executando) {
            return;
        }
        pararATravaDaConfirmacao();
        montarAEscolha();
        mostrarPagina(PAGINA_ESCOLHER);
    }

    // ---------------------------------------------------------------------------------------------
    // Conferir (só leitura)
    // ---------------------------------------------------------------------------------------------

    private void conferir() {
        if (executando) {
            return;
        }
        dizerErro(erroDeConferir, "");
        tentarConferirDeNovo.setVisible(false);
        textoDeConferindo.setVisible(true);
        mostrarPagina(PAGINA_CONFERINDO);
        pararATravaDaConfirmacao();
        InstaladorDeAgente alvoDaConferencia = instalador;
        Fundo.emFundoComResultado(() -> alvoDaConferencia.verificar(acao.modo)).whenComplete((r, falha) -> Fundo.naEdt(() -> {
            if (alvoDaConferencia != instalador || jaFechou()) {
                return; // o usuário trocou o nome do container no meio: vale a conferência nova
            }
            if (falha != null) {
                Throwable causa = falha instanceof java.util.concurrent.CompletionException && falha.getCause() != null ? falha.getCause() : falha;
                textoDeConferindo.setVisible(false);
                dizerErro(erroDeConferir, "Não consegui conferir o servidor: " + MensagensDeErro.de(causa));
                tentarConferirDeNovo.setVisible(true);
                reajustar();
                return;
            }
            aoTerminarAConferencia(r);
        }));
    }

    /**
     * A conferência (só leitura) terminou. Ao INSTALAR num servidor sem agente a próxima tela é "O que instalar" (a primeira vez); depois
     * de "Conferir de novo" (a escolha já feita) vale a mesma escolha, reavaliada com o servidor como ele está agora, e a confirmação segue.
     * Atualizar e um servidor que já tem o agente vão direto à confirmação, com tudo o que o app instalaria ali (como sempre foi).
     */
    private void aoTerminarAConferencia(RelatorioDeVerificacao r) {
        this.relatorioCompleto = r;
        EscolhaDeInstalacao e = escolha == null ? (comEscolha ? null : completoAgora()) : reavaliarAEscolha();
        if (e == null) {
            montarAEscolha();
            mostrarPagina(PAGINA_ESCOLHER);
            return;
        }
        aplicarAEscolha(e);
        mostrarPagina(PAGINA_CONFIRMAR);
    }

    private EscolhaDeInstalacao completoAgora() {
        return EscolhaDeInstalacao.completo(CatalogoDeInstalacao.itens(), relatorioCompleto);
    }

    /** A escolha que já estava em vigor, refeita contra o relatório novo; {@code null} se não vale mais (a tela de escolha volta). */
    private EscolhaDeInstalacao reavaliarAEscolha() {
        EscolhaDeInstalacao nova;
        if (escolha.tipo() == EscolhaDeInstalacao.Tipo.COMPLETO) {
            nova = completoAgora();
        } else {
            Set<String> oferecidos = new LinkedHashSet<>();
            completoAgora().itens().forEach(i -> oferecidos.add(i.id()));
            Set<String> ainda = new LinkedHashSet<>(marcadosNaTela);
            ainda.retainAll(oferecidos);
            nova = EscolhaDeInstalacao.escolher(CatalogoDeInstalacao.itens(), relatorioCompleto, ainda);
        }
        return nova.instalaAlgo() && nova.valida() ? nova : null;
    }

    /** Fixa a escolha no instalador (só os passos dela rodam, só as verificações dela valem) e monta a confirmação e o andamento. */
    private void aplicarAEscolha(EscolhaDeInstalacao e) {
        this.escolha = e;
        this.relatorio = instalador.escolher(e);
        for (Map.Entry<Passo, Component> b : blocosDosPassos.entrySet()) {
            b.getValue().setVisible(!(b.getKey() instanceof PassoDeInstalacao pi) || e.passos().contains(pi));
        }
        montarConfirmacao(relatorio);
    }

    private boolean escolheuOAgente() {
        return escolha == null || escolha.passos().contains(PassoDeInstalacao.SUBIR_CONTAINER);
    }

    private void montarConfirmacao(RelatorioDeVerificacao r) {
        boolean comAgente = escolheuOAgente();
        introDaConfirmacao.setText(switch (acao) {
            case ATUALIZAR -> "O app vai instalar a versão nova do agente por cima da atual, mantendo os dados e o token deste computador.";
            default -> comAgente
                ? "O agente é um programa pequeno que roda num container do Docker do servidor. Com ele o app mostra CPU, memória, disco, "
                    + "processos e containers. NADA foi instalado ainda: confira abaixo e clique no botão para começar."
                : "O app vai instalar só o Docker neste servidor; o agente de monitoramento NÃO será instalado agora. NADA foi instalado "
                    + "ainda: confira abaixo e clique no botão para começar.";
        });
        listaDeSeguranca.removeAll();
        for (String t : comAgente ? TEXTOS_DE_SEGURANCA : TEXTOS_DE_SEGURANCA_DO_DOCKER) {
            listaDeSeguranca.add(dicaCorrida("• " + t));
            listaDeSeguranca.add(Caixa.espacoVertical(4));
        }
        opcoesAvancadas.setVisible(comAgente);
        listaDeVerificacoes.removeAll();
        for (ResultadoDaVerificacao v : r.resultados()) {
            listaDeVerificacoes.add(linhaDaVerificacao(v));
            listaDeVerificacoes.add(Caixa.espacoVertical(6));
        }
        boolean bloqueado = r.bloqueado();
        confirmar.setEnabled(!bloqueado);
        String aviso = "";
        if (bloqueado) {
            aviso = "Há " + (r.problemas().size() == 1 ? "um problema" : r.problemas().size() + " problemas") + " que impede"
                + (r.problemas().size() == 1 ? "" : "m") + " de continuar. O app não tenta resolver isto sozinho: resolva no servidor e "
                + (r.dockerAusente() && !r.vaiInstalarODocker() ? "clique em Já instalei, continuar." : "clique em Conferir de novo.");
        }
        dizerErro(avisoDeBloqueio, aviso);
        boolean pedeJaInstalei = bloqueado && r.dockerAusente() && !r.vaiInstalarODocker();
        conferirDeNovo.setText(pedeJaInstalei ? "Já instalei, continuar" : "Conferir de novo");
        String atual = alvo.versaoInstalada();
        versaoAtual.setVisible(acao == Acao.ATUALIZAR);
        if (acao == Acao.ATUALIZAR) {
            versaoAtual.setText(atual == null ? "A versão do agente instalado não foi informada; o pacote do app é " + pacote.versao() + "."
                : atual.equals(pacote.versao())
                    ? "O agente já está na versão mais recente (" + atual + "). Reinstalar não muda nada."
                    : "Versão instalada: " + atual + ". Versão do pacote deste app: " + pacote.versao() + ".");
        }
        montarOQueVaiAcontecer(r);
        reajustar();
    }

    private Component linhaDaVerificacao(ResultadoDaVerificacao v) {
        Rotulo marca = new Rotulo().icone(Icones.colorido(switch (v.estado()) {
            case OK -> "ok-circulo";
            case AVISO -> "alerta";
            case PROBLEMA -> "erro";
        }, 16, switch (v.estado()) {
            case OK -> Tokens.OK;
            case AVISO -> Tokens.ATENCAO;
            case PROBLEMA -> Tokens.PERIGO;
        }));
        marca.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        String palavra = switch (v.estado()) {
            case OK -> "ok";
            case AVISO -> "aviso";
            case PROBLEMA -> "problema";
        };
        TextoCorrido frase = new TextoCorrido(v.item().rotulo() + " (" + palavra + "): " + v.frase(), LARGURA_DO_TEXTO - 28)
            .corDoToken(v.estado() == ResultadoDaVerificacao.Estado.PROBLEMA ? Tokens.PERIGO : Tokens.TEXTO).tamanho(12.5f);
        List<Component> coluna = new ArrayList<>();
        coluna.add(frase);
        if (v.comandoSugerido() != null) {
            TextoCorrido cmd = new TextoCorrido(v.comandoSugerido(), LARGURA_DO_TEXTO - 160).mono(12f);
            cmd.setName("comando-sugerido");
            Botao copiar = BotaoCopiar.criar("Copiar comando", v::comandoSugerido, copiador, avisos);
            coluna.add(Caixa.linha(8, cmd, copiar));
        }
        return Caixa.linha(8, marca, Caixa.coluna(4, coluna.toArray(Component[]::new)));
    }

    private void montarOQueVaiAcontecer(RelatorioDeVerificacao r) {
        listaDoQueVaiAcontecer.removeAll();
        String pasta = parametros.pasta();
        Set<PassoDeInstalacao> passos = EnumSet.noneOf(PassoDeInstalacao.class);
        passos.addAll(escolha == null ? List.of(PassoDeInstalacao.values()) : escolha.passos());
        boolean atualizar = acao == Acao.ATUALIZAR;
        // SÓ o que foi escolhido: um item que não foi escolhido não vira linha (nem "já está instalado, nada a fazer")
        List<String> itens = new ArrayList<>();
        if (passos.contains(PassoDeInstalacao.DOCKER)) {
            itens.add(r.dockerPresente() ? "Docker: já está instalado, nada a fazer."
                : r.vaiInstalarODocker() ? "Instalar o Docker pelo repositório oficial da Docker (precisa de alguns minutos)."
                    : "Docker: você precisa instalar antes (veja acima).");
        }
        if (passos.contains(PassoDeInstalacao.ENVIAR_PACOTE)) {
            itens.add("Enviar o pacote do agente (versão " + pacote.versao() + ", " + (pacote.tamanho() / 1024 + 1) + " KB) para a pasta ~/" + pasta
                + " do servidor e conferir o SHA-256.");
        }
        if (passos.contains(PassoDeInstalacao.CONSTRUIR_IMAGEM)) {
            itens.add("Construir a imagem do agente no próprio servidor (leva alguns minutos; evita problema de arquitetura).");
        }
        if (passos.contains(PassoDeInstalacao.SUBIR_CONTAINER)) {
            itens.add((atualizar ? "Trocar o container " : "Subir o container ") + parametros.container()
                + ": reinício automático, escutando só em 127.0.0.1:" + parametros.porta() + ", dados num volume do Docker ("
                + parametros.volume() + ")."
                + (atualizar ? " O agente atual é guardado (parado) como " + parametros.containerAnterior()
                    + " enquanto o novo sobe: se o novo não responder, o app o devolve ao ar; só no fim, com o novo funcionando, o antigo é removido." : ""));
        }
        if (passos.contains(PassoDeInstalacao.SONDAR)) {
            itens.add(atualizar
                ? "Conferir que o agente responde. Os dados e o token deste computador ficam como estão."
                : "Conferir que o agente responde e registrar o token deste computador (o agente guarda só o hash).");
        }
        for (int i = 0; i < itens.size(); i++) {
            listaDoQueVaiAcontecer.add(dicaCorrida((i + 1) + ". " + itens.get(i)));
            listaDoQueVaiAcontecer.add(Caixa.espacoVertical(3));
        }
        listaDoQueVaiAcontecer.add(dicaCorrida(escolheuOAgente()
            ? "Se algum passo falhar, o app mostra o erro e desfaz só o que ele criou (o container, os dados novos, a imagem e a pasta)"
                + (atualizar ? ", e devolve o agente que estava funcionando" : "") + ". O Docker, se foi instalado, não é desinstalado."
            : "Se o passo falhar, o app mostra o erro e não muda mais nada. O Docker, depois de instalado, não é desinstalado."));
    }

    private void montarConfirmacaoDeRemocao() {
        listaDoQueVaiAcontecer.removeAll();
        String c = parametros.container();
        for (String t : List.of(
            "• O container " + c + " é parado e removido (só se foi o app que o criou: o app não mexe no que não instalou).",
            "• Os dados do agente (o token deste computador no agente e o 2FA do agente) são apagados, junto com o volume " + parametros.volume() + ".",
            "• A imagem do agente e a pasta ~/" + parametros.pasta() + " do servidor são apagadas.",
            "• O Docker continua instalado.",
            "• Depois disso o app volta a usar " + alvo.nome() + " só por SSH: terminal, Segurança e Arquivos seguem funcionando.")) {
            listaDoQueVaiAcontecer.add(dicaCorrida(t));
            listaDoQueVaiAcontecer.add(Caixa.espacoVertical(3));
        }
    }

    /** Muda o nome do container (opções avançadas) e confere de novo; "Já instalei, continuar" também passa por aqui. */
    private void conferirDepoisDeEditarOContainer() {
        if (executando) {
            return;
        }
        String nome = campoDoContainer.getText().strip();
        // o nome do container é do agente: se só o Docker foi escolhido o campo (escondido) não conta, e não pode deixar o botão mudo
        if (escolheuOAgente() && !nome.equals(parametros.container())) {
            try {
                parametros = new ParametrosDaInstalacao(nome, parametros.porta(), parametros.pasta());
            } catch (IllegalArgumentException e) {
                opcoesAvancadas.definirAberta(true); // o erro mora lá dentro: abre a seção para o usuário vê-lo
                dizerErro(erroDoContainer, e.getMessage());
                reajustar();
                return;
            }
            try {
                instalador = fabrica.apply(parametros);
            } catch (br.com.labdesktop.servidor.ProtecaoDoServidor.ServidorProtegidoException protegido) {
                // o usuário protegeu o servidor com esta tela aberta: o app não instala; mostra a frase onde o usuário está olhando (a seção avançada abre) e desliga o botão
                opcoesAvancadas.definirAberta(true);
                dizerErro(erroDoContainer, protegido.getMessage());
                confirmar.setEnabled(false);
                reajustar();
                return;
            }
        }
        dizerErro(erroDoContainer, "");
        conferir();
    }

    // ---------------------------------------------------------------------------------------------
    // Confirmar e executar
    // ---------------------------------------------------------------------------------------------

    private void confirmar() {
        if (executando || !confirmar.isEnabled()) {
            return;
        }
        if (escolheuOAgente() && !campoDoContainer.getText().strip().equals(parametros.container())) {
            // O nome do container mudou nas opções avançadas e ainda não foi conferido: as verificações (porta, container existente) eram de
            // outro nome. Confere de novo antes de instalar, em vez de instalar com o nome velho ou sem conferir o novo.
            conferirDepoisDeEditarOContainer();
            return;
        }
        executar();
    }

    private void executar() {
        if (executando) {
            return;
        }
        executando = true;
        cancelado.set(false);
        pararOPrazoDoCancelamento();
        terminouComSucesso = false;
        concluir.setVisible(false);
        fecharAoFim.setVisible(false);
        tentarDeNovo.setVisible(false);
        cancelarExecucao.setVisible(true);
        cancelarExecucao.setEnabled(true);
        cancelarExecucao.setText("Cancelar");
        situacaoFinal.setVisible(false);
        mensagemFinal.setVisible(false);
        listaDoDesfeito.removeAll();
        for (Passo p : linhasDosPassos.keySet()) {
            marcar(p, EstadoDoPasso.PENDENTE, "");
        }
        mostrarPagina(PAGINA_ANDAMENTO);

        ObservadorDaInstalacao obs = new ObservadorDaInstalacao() {
            @Override
            public void aoMudarPasso(Passo passo, EstadoDoPasso estado, String detalhe) {
                Fundo.naEdt(() -> marcar(passo, estado, detalhe));
            }

            @Override
            public void aoRegistrar(String linha) {
                Fundo.naEdt(() -> areaDoLog.append(TextoSeguro.limpar(linha) + "\n"));
            }
        };
        Cancelamento cancelamento = cancelado::get;
        InstaladorDeAgente deste = instalador;
        Thread t = Thread.ofVirtual().name("instalador-do-agente").unstarted(() -> {
            if (acao == Acao.REMOVER) {
                try {
                    ResultadoDaRemocao r = deste.remover(obs, cancelamento);
                    Fundo.naEdt(() -> aoTerminarRemocao(r, null));
                } catch (java.io.IOException | InterruptedException | RuntimeException e) {
                    if (e instanceof InterruptedException) {
                        Thread.interrupted();
                    }
                    Fundo.naEdt(() -> aoTerminarRemocao(null, e));
                }
            } else {
                ResultadoDaInstalacao r;
                try {
                    r = deste.instalar(obs, cancelamento);
                } catch (RuntimeException e) {
                    // Um erro inesperado (nome inválido que escapou, falha de programa) não pode deixar a janela presa em "executando".
                    r = new ResultadoDaInstalacao(ResultadoDaInstalacao.Desfecho.FALHOU, null, MensagensDeErro.de(e), List.of());
                }
                ResultadoDaInstalacao resultado = r;
                Fundo.naEdt(() -> aoTerminarInstalacao(resultado));
            }
        });
        trabalhador.set(t);
        t.start();
    }

    private void marcar(Passo passo, EstadoDoPasso estado, String detalhe) {
        Rotulo r = linhasDosPassos.get(passo);
        if (r == null) {
            return;
        }
        estadosDosPassos.put(passo, estado);
        r.setIcon(iconeDo(estado));
        r.setText(passo.rotulo() + (estado == EstadoDoPasso.PENDENTE ? "" : " — " + estado.palavra()));
        r.corDoToken(switch (estado) {
            case PENDENTE -> Tokens.TEXTO3;
            case EM_ANDAMENTO, FEITO, JA_ESTAVA_FEITO -> Tokens.TEXTO;
            case FALHOU -> Tokens.PERIGO;
            case DESFEITO -> Tokens.TEXTO2;
        });
        TextoCorrido d = detalhesDosPassos.get(passo);
        d.setText(estado == EstadoDoPasso.FALHOU ? detalhe : "");
        d.setVisible(estado == EstadoDoPasso.FALHOU && !detalhe.isEmpty());
        reajustar();
    }

    private static javax.swing.Icon iconeDo(EstadoDoPasso e) {
        return switch (e) {
            case PENDENTE -> Icones.colorido("relogio", 16, Tokens.TEXTO3);
            case EM_ANDAMENTO -> Icones.colorido("atualizar", 16, Tokens.DESTAQUE);
            case FEITO, JA_ESTAVA_FEITO -> Icones.colorido("ok-circulo", 16, Tokens.OK);
            case FALHOU -> Icones.colorido("erro", 16, Tokens.PERIGO);
            case DESFEITO -> Icones.colorido("desfazer", 16, Tokens.TEXTO2);
        };
    }

    private void aoTerminarInstalacao(ResultadoDaInstalacao r) {
        executando = false;
        pararOPrazoDoCancelamento();
        trabalhador.set(null);
        cancelarExecucao.setVisible(false);
        switch (r.desfecho()) {
            case CONCLUIDA -> {
                terminouComSucesso = true;
                if (escolheuOAgente()) {
                    mostrarFim(true, acao == Acao.ATUALIZAR ? "Agente atualizado" : "Agente instalado",
                        "Pronto: o agente (versão " + pacote.versao() + ") está funcionando em " + alvo.nome() + ". O app já pode mostrar CPU, "
                            + "memória, disco, processos e containers do servidor.");
                    avisos.ok((acao == Acao.ATUALIZAR ? "Agente atualizado em " : "Agente instalado em ") + alvo.nome() + ".");
                } else {
                    mostrarFim(true, "Docker instalado", "Pronto: o Docker foi instalado em " + alvo.nome() + ". O agente de monitoramento ainda não foi "
                        + "instalado: dá para instalá-lo quando quiser, pelo botão Instalar agente.");
                    avisos.ok("Docker instalado em " + alvo.nome() + ".");
                }
                aoMudarOServidor.run();
            }
            case CANCELADA -> mostrarFalha("Cancelado", "Você cancelou. O que o app já tinha criado foi desfeito.", r.desfeito());
            case RECUSADA -> mostrarFalha("Não foi possível começar", r.erro(), r.desfeito());
            case FALHOU -> mostrarFalha("Parou em um passo",
                (r.passo() == null ? "" : "Passo: " + r.passo().rotulo() + ".\n") + r.erro(), r.desfeito());
        }
    }

    private void aoTerminarRemocao(ResultadoDaRemocao r, Throwable falha) {
        executando = false;
        pararOPrazoDoCancelamento();
        trabalhador.set(null);
        cancelarExecucao.setVisible(false);
        if (falha != null) {
            mostrarFalha(falha instanceof InterruptedException || cancelado.get() ? "Cancelado" : "Parou em um passo",
                MensagensDeErro.de(falha), List.of());
            return;
        }
        if (r.recusada()) {
            mostrarFalha("O app não removeu nada", String.join(" ", r.avisos()), List.of());
            return;
        }
        if (r.removido()) {
            terminouComSucesso = true;
            mostrarFim(true, "Agente removido",
                "O agente foi removido de " + alvo.nome() + ". O Docker continua instalado, e o app volta a usar o servidor só por SSH.");
            avisos.ok("Agente removido de " + alvo.nome() + ".");
            aoMudarOServidor.run();
        } else {
            mostrarFalha("Não consegui remover o container", String.join(" ", r.avisos()), r.feito());
            return;
        }
        for (String a : r.avisos()) {
            listaDoDesfeito.add(dicaCorrida("Aviso: " + a));
        }
        reajustar();
    }

    private void mostrarFim(boolean sucesso, String titulo, String mensagem) {
        situacaoFinal.setText(titulo);
        situacaoFinal.setIcon(Icones.colorido(sucesso ? "ok-circulo" : "erro", 18, sucesso ? Tokens.OK : Tokens.PERIGO));
        situacaoFinal.setIconTextGap(8);
        situacaoFinal.corDoToken(sucesso ? Tokens.OK : Tokens.PERIGO).negrito();
        situacaoFinal.setVisible(true);
        mensagemFinal.setText(mensagem);
        mensagemFinal.setVisible(true);
        concluir.setVisible(true);
        fecharAoFim.setVisible(false);
        tentarDeNovo.setVisible(false);
        botaoPadrao(concluir);
        reajustar();
    }

    private void mostrarFalha(String titulo, String mensagem, List<String> desfeito) {
        situacaoFinal.setText(titulo);
        situacaoFinal.setIcon(Icones.colorido("erro", 18, Tokens.PERIGO));
        situacaoFinal.setIconTextGap(8);
        situacaoFinal.corDoToken(Tokens.PERIGO).negrito();
        situacaoFinal.setVisible(true);
        mensagemFinal.setText(mensagem);
        mensagemFinal.setVisible(true);
        listaDoDesfeito.removeAll();
        for (String d : desfeito) {
            listaDoDesfeito.add(dicaCorrida("Desfeito: " + d));
        }
        concluir.setVisible(false);
        fecharAoFim.setVisible(true);
        tentarDeNovo.setVisible(true);
        tentarDeNovo.setEnabled(true);
        secaoDoLog.definirAberta(false);
        botaoPadrao(tentarDeNovo);
        reajustar();
    }

    // ---------------------------------------------------------------------------------------------
    // Cancelar
    // ---------------------------------------------------------------------------------------------

    /** Esc, a janela e o botão Cancelar: se está executando, cancela o trabalho (e espera ele desfazer); senão, fecha. */
    @Override
    protected void aoCancelar() {
        if (executando) {
            if (cancelado.get()) {
                // Segundo pedido (Esc, X ou o botão): se o trabalho já passou do prazo de desfazer, fecha a janela mesmo assim.
                if (podeFecharMesmoAssim) {
                    fechar();
                }
                return;
            }
            cancelado.set(true);
            cancelarExecucao.setEnabled(false);
            cancelarExecucao.setText("Cancelando…");
            Thread t = trabalhador.get();
            if (t != null) {
                t.interrupt(); // solta o comando em andamento (o canal exec fecha); o instalador desfaz e termina
            }
            // O botão nunca fica para sempre em "Cancelando…": passado o prazo, oferece fechar a janela (o desfazer segue sozinho).
            prazoDoCancelamento = new javax.swing.Timer(prazoParaFecharMesmoAssimMs, e -> {
                if (executando) {
                    podeFecharMesmoAssim = true;
                    cancelarExecucao.setText("Fechar mesmo assim");
                    cancelarExecucao.setEnabled(true);
                }
            });
            prazoDoCancelamento.setRepeats(false);
            prazoDoCancelamento.start();
            return;
        }
        fechar();
    }

    /** Só os testes: encurta o tempo até o botão virar "Fechar mesmo assim". */
    public static void definirPrazoParaFecharMesmoAssimParaTeste(int milissegundos) {
        prazoParaFecharMesmoAssimMs = milissegundos;
    }

    /** A janela já foi fechada? (testes) */
    public boolean fechada() {
        return jaFechou();
    }

    private void pararOPrazoDoCancelamento() {
        podeFecharMesmoAssim = false;
        if (prazoDoCancelamento != null) {
            prazoDoCancelamento.stop();
            prazoDoCancelamento = null;
        }
    }

    @Override
    protected void aoFechar() {
        pararATravaDaConfirmacao();
        pararOPrazoDoCancelamento();
        Thread t = trabalhador.get();
        if (t != null) {
            t.interrupt();
        }
    }
}
