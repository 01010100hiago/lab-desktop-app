package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.ImportacaoDeChaveException;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Conclusao;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.AssistenteDeServidor.ErroDoAssistente;
import br.com.labdesktop.servidor.AssistenteDeServidor.Etapa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ui.ApresentacaoDoEstado;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.SecaoRecolhivel;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * "Adicionar servidor" (ARQUITETURA 8.3): o assistente de ponta a ponta, em três passos e SEM decisões
 * sobre chave no caminho normal.
 * <ol>
 *   <li><b>Dados do servidor</b>: nome, endereço, porta, usuário (vazios na primeira vez, com a porta SSH 22).</li>
 *   <li><b>Chave de acesso</b>: o app cria sozinho (fora da EDT) uma chave só para este servidor, guarda-a cifrada no cofre
 *       e mostra a chave PÚBLICA com o botão Copiar e uma frase dizendo o que fazer com ela. Quem já tem uma chave abre as
 *       "Opções avançadas": usar uma que já está no cofre ou importar de um arquivo. O botão "Gerar chave" que o usuário
 *       precisava lembrar de clicar (e sem o qual o assistente recusava seguir) não existe mais.</li>
 *   <li><b>Conferir o servidor e conectar</b>: a sondagem captura a chave do host SEM autenticar; o usuário compara a
 *       impressão digital com o comando do servidor e confirma ({@link DialogoConfiarHost}); só então servidor e pin entram
 *       no cofre; depois autentica, registra o token por SSH, testa {@code GET /api/versao} e
 *       lê {@code GET /api/limites}. O estado da conexão aparece o tempo todo com texto e ícone.</li>
 * </ol>
 * Cada passo de rede ou de disco roda fora da EDT ({@link Tarefas}). Fechar o diálogo depois do passo 2 NÃO desfaz o
 * cadastro: o servidor fica no cofre e o que faltou (token, por exemplo) se conclui pela faixa de aviso. A chave criada
 * pelo assistente também fica no cofre se o usuário desistir (ele pode ter instalado a pública no servidor); só é apagada
 * quando o usuário escolhe outra no lugar e ninguém a usa.
 */
public final class DialogoAdicionarServidor extends Dialogo {

    /** Quem mostra o {@link DialogoConfiarHost}. Em produção abre o diálogo modal; os testes confirmam direto. */
    @FunctionalInterface
    public interface ConfirmadorDeHost {
        void confirmar(String servidor, String endereco, SondagemDeHost.Resultado resultado, Consumer<Boolean> aoDecidir);
    }

    /** Quem deixa o usuário escolher o arquivo da chave a importar. Em produção é o seletor nativo do macOS. */
    @FunctionalInterface
    public interface SeletorDeArquivo {
        Optional<Path> escolher(Window pai);
    }

    /** De onde vem a chave usada; trocar de opção troca a chave mostrada (a que o app criou só some se o usuário pedir outra). */
    private enum Modo { GERADA, EXISTENTE, IMPORTADA }

    /** Primeira frase do passo da chave: onde a chave mora, sem arquivo e sem senha extra. */
    public static final String TEXTO_DA_CHAVE = "O app cria uma chave só para este servidor e a guarda cifrada dentro do "
        + "cofre. Não existe arquivo para guardar nem senha extra para lembrar além da senha-mestra.";

    /** A pergunta do último passo quando o SSH conectou e o servidor não tem o agente (sem a palavra "erro"). */
    public static final String TEXTO_DA_PERGUNTA_DO_AGENTE = "O servidor está conectado por SSH, mas o agente ainda não está instalado "
        + "nele. Quer instalar agora? Sem o agente você usa o servidor só por SSH (terminal, Segurança e Arquivos funcionam), mas não vê "
        + "CPU, memória, containers nem projetos.";

    /** O último passo quando o SSH conectou, o servidor não tem o agente e ESTE app não leva o pacote do agente (nada a perguntar). */
    public static final String TEXTO_SEM_O_AGENTE_NO_APP = "O servidor está conectado por SSH. "
        + br.com.labdesktop.instalador.PacoteDoAgenteAusenteException.TEXTO;

    /** A dica do campo "Porta do painel no servidor", a mesma no assistente e em "Editar servidor". */
    public static final String DICA_DA_PORTA_DO_PAINEL = "Deixe 7000, a não ser que o painel do servidor use outra porta.";

    /**
     * O que o usuário lê quando não confirma a impressão digital. Uma impressão diferente da esperada quase sempre quer dizer
     * endereço ou porta errados (outro aparelho na rede), e o caminho de volta é o botão Voltar.
     */
    public static final String TEXTO_DE_SERVIDOR_NAO_CONFIRMADO = "Servidor não confirmado. Nada foi gravado e nenhuma "
        + "credencial foi enviada. Confira o endereço e a porta (botão Voltar): uma impressão digital diferente pode ser "
        + "outro aparelho na rede, e não o seu servidor.";

    private static final String PAGINA_DADOS = "dados";
    private static final String PAGINA_CHAVE = "chave";
    private static final String PAGINA_CONEXAO = "conexao";

    private final AssistenteDeServidor assistente;
    private final Notificacoes avisos;
    private final Consumer<ServidorLab> aoConcluir;
    private final ConfirmadorDeHost confirmadorDeHost;
    private final SeletorDeArquivo seletorDeArquivo;
    private final CopiadorDeTexto copiador;
    /** Abre a confirmação "Instalar agente" para o servidor; o {@code Runnable} roda (na EDT) se o agente ficou instalado. */
    private final BiConsumer<ServidorLab, Runnable> instalarAgente;

    private final CardLayout paginas = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel pilha = new Painel(paginas);
    /** Os botões de cada passo, FORA da área que rola: o passo da chave, aberto por inteiro, passa da altura da tela. */
    private final CardLayout cartoesDosRodapes = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel pilhaDeRodapes = new Painel(cartoesDosRodapes);
    private String paginaAtual = PAGINA_DADOS;

    // ---- passo 1: dados ----
    private final JTextField nome = Campos.texto(14, "lab");
    private final JTextField host = Campos.mono(16, "192.0.2.5");
    private final JTextField porta = Campos.mono(5, "22");
    private final JTextField usuario = Campos.mono(14, "usuario");
    private final JTextField portaDoAgente = Campos.mono(5, "7000");
    private final TextoCorrido erroDosDados = erroCorrido();
    private final Botao continuar = Botoes.primario("Continuar", "avancar");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);

    // ---- passo 2: chave ----
    private final Rotulo estado = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido erro = erroCorrido();
    private final Botao repetirGeracao = Botoes.secundario("Tentar de novo", "atualizar");
    private final InstrucaoDaChave instrucaoDaChave;
    private final JRadioButton gerarNova = new JRadioButton("A chave criada pelo app (recomendado)", true);
    private final JRadioButton usarExistente = new JRadioButton("Usar uma chave que já está no cofre");
    private final JRadioButton importar = new JRadioButton("Importar de um arquivo");
    private final JComboBox<InfoChave> chavesDoCofre = new JComboBox<>();
    private final Botao botaoArquivo = Botoes.secundario("Escolher arquivo…", "pasta");
    private final JPasswordField senhaDoArquivo = Campos.senha(16);
    private final Botao botaoImportarComSenha = Botoes.secundario("Importar com esta senha", "chave");
    private final JComponent linhaDaSenhaDoArquivo;
    private final Botao botaoGerarOutra = Botoes.secundario("Gerar outra chave", "atualizar");
    private final SecaoRecolhivel opcoesAvancadas;
    private final Botao voltar = Botoes.fantasma("Voltar", "voltar");
    private final Botao cancelarNoPassoDaChave = Botoes.secundario("Cancelar", null);
    private final Botao testar = Botoes.primario("Testar conexão", "rede");
    private InfoChave chaveGerada;
    private InfoChave chaveImportada;
    private Path arquivoParaImportar;
    private boolean gerando;
    private Runnable desfazerOcupado = () -> { };
    private boolean ocupado;

    // ---- passo 3: conexão ----
    private final Map<Etapa, Rotulo> linhasDasEtapas = new EnumMap<>(Etapa.class);
    private final Map<Etapa, Icon[]> iconesDasEtapas = new EnumMap<>(Etapa.class);
    private final Rotulo estadoDaConexao = new Rotulo().tamanho(13f);
    private final TextoCorrido mensagemDaConexao = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO);
    private final InstrucaoDaChave instrucaoNaConexao;
    private final Botao tentarDeNovo = Botoes.secundario("Tentar de novo", "atualizar");
    private final Botao substituirToken = Botoes.perigo("Substituir o token do servidor", "chave");
    private final Botao concluir = Botoes.primario("Concluir", "ok");
    private final Botao fecharConexao = Botoes.fantasma("Fechar", null);
    /** O fim do passo 3 quando o SSH conectou e o agente não existe: uma pergunta, não um erro. */
    private final Botao instalarOAgente = Botoes.primario("Instalar agente", "servidor");
    private final Botao agoraNao = Botoes.secundario("Agora não, usar só por SSH", null);
    private boolean perguntandoDoAgente;
    private ServidorLab lab;
    private Inscricao inscricaoDoEstado;
    private String nomeDoDispositivo = "mac";
    private boolean concluido;

    /**
     * @param primeiro  {@code true}: ainda não há servidor, então os campos começam vazios (porta 22)
     * @param aoConcluir recebe o servidor pronto (na EDT), para a janela selecioná-lo
     */
    public DialogoAdicionarServidor(AssistenteDeServidor assistente, boolean primeiro, Notificacoes avisos,
                                    Consumer<ServidorLab> aoConcluir) {
        this(assistente, primeiro, avisos, aoConcluir, null, null);
    }

    /** Com o confirmador do host e o seletor de arquivo trocáveis (testes). */
    public DialogoAdicionarServidor(AssistenteDeServidor assistente, boolean primeiro, Notificacoes avisos,
                                    Consumer<ServidorLab> aoConcluir, ConfirmadorDeHost confirmadorDeHost,
                                    SeletorDeArquivo seletorDeArquivo) {
        this(assistente, primeiro, avisos, aoConcluir, confirmadorDeHost, seletorDeArquivo, CopiadorDeTexto.doSistema());
    }

    /** Também com a área de transferência trocável (testes: a do sistema não existe sem janela). */
    public DialogoAdicionarServidor(AssistenteDeServidor assistente, boolean primeiro, Notificacoes avisos,
                                    Consumer<ServidorLab> aoConcluir, ConfirmadorDeHost confirmadorDeHost,
                                    SeletorDeArquivo seletorDeArquivo, CopiadorDeTexto copiador) {
        this(assistente, primeiro, avisos, aoConcluir, confirmadorDeHost, seletorDeArquivo, copiador, null);
    }

    /** Com o que abre a confirmação "Instalar agente" (a pergunta do último passo, quando o servidor não tem o agente). */
    public DialogoAdicionarServidor(AssistenteDeServidor assistente, boolean primeiro, Notificacoes avisos,
                                    Consumer<ServidorLab> aoConcluir, ConfirmadorDeHost confirmadorDeHost,
                                    SeletorDeArquivo seletorDeArquivo, CopiadorDeTexto copiador,
                                    BiConsumer<ServidorLab, Runnable> instalarAgente) {
        super("Adicionar servidor");
        this.instalarAgente = instalarAgente != null ? instalarAgente : (lab, depois) -> { };
        this.assistente = assistente;
        this.avisos = avisos;
        this.aoConcluir = aoConcluir;
        this.confirmadorDeHost = confirmadorDeHost != null ? confirmadorDeHost : this::confirmarComDialogo;
        this.seletorDeArquivo = seletorDeArquivo != null ? seletorDeArquivo : DialogoAdicionarServidor::escolherComFileDialog;
        this.copiador = copiador;

        Dados padrao = primeiro ? Dados.padraoDoPrimeiro() : new Dados("", "", 22, "", 7000);
        nome.setText(padrao.nome());
        host.setText(padrao.host());
        porta.setText(String.valueOf(padrao.porta()));
        usuario.setText(padrao.usuario());
        portaDoAgente.setText(String.valueOf(padrao.portaDoAgente()));

        instrucaoDaChave = new InstrucaoDaChave(copiador, avisos, this::reajustar);
        instrucaoNaConexao = new InstrucaoDaChave(copiador, avisos, this::reajustar);
        repetirGeracao.setVisible(false);
        linhaDaSenhaDoArquivo = montarLinhaDaSenhaDoArquivo();
        opcoesAvancadas = new SecaoRecolhivel("Opções avançadas (já tenho uma chave)",
            "Esconder as opções avançadas", montarOpcoesAvancadas(), this::reajustar);
        pilha.add(montarPaginaDeDados(), PAGINA_DADOS);
        pilha.add(montarPaginaDaChave(), PAGINA_CHAVE);
        pilha.add(montarPaginaDeConexao(), PAGINA_CONEXAO);
        pilhaDeRodapes.add(rodape(null, cancelar, continuar), PAGINA_DADOS);
        pilhaDeRodapes.add(rodape(voltar, cancelarNoPassoDaChave, testar), PAGINA_CHAVE);
        pilhaDeRodapes.add(rodape(null, fecharConexao, agoraNao, instalarOAgente, substituirToken, tentarDeNovo, concluir), PAGINA_CONEXAO);
        conteudoComRodape(pilha, pilhaDeRodapes);

        ligarEventos();
        atualizarControlesDaChave(); // sem chave, não dá para testar
        mostrarPagina(PAGINA_DADOS);
        carregarEmFundo();
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private static Rotulo passo(String texto) {
        return Rotulo.dica(texto);
    }

    private JComponent montarPaginaDeDados() {
        JComponent dados = Caixa.coluna(8,
            linhaDeCampos("Nome", nome, "Usuário", usuario),
            linhaDeCampos("Endereço", host, "Porta do SSH", porta),
            linhaDeCampos("Porta do painel no servidor", portaDoAgente, null, null),
            dicaCorrida(DICA_DA_PORTA_DO_PAINEL));
        return Caixa.coluna(12,
            cabecalho("servidor", "Adicionar servidor"),
            passo("Passo 1 de 3 · Dados do servidor"),
            paragrafo("Diga onde o servidor está. No próximo passo o app cria sozinho a chave de acesso e, no último, você "
                + "confere a impressão digital do servidor e o app conecta."),
            dados,
            erroDosDados);
    }

    private JComponent montarPaginaDaChave() {
        return Caixa.coluna(12,
            cabecalho("chave", "Chave deste servidor"),
            passo("Passo 2 de 3 · Chave de acesso"),
            paragrafo(TEXTO_DA_CHAVE),
            estado,
            instrucaoDaChave.componente(),
            Caixa.linha(0, repetirGeracao, Caixa.mola()),
            opcoesAvancadas,
            erro);
    }

    /** O que só quem já tem uma chave usa. Fechado por padrão ({@link #opcoesAvancadas}). */
    private JComponent montarOpcoesAvancadas() {
        ButtonGroup grupo = new ButtonGroup();
        grupo.add(gerarNova);
        grupo.add(usarExistente);
        grupo.add(importar);
        for (JRadioButton r : new JRadioButton[] {gerarNova, usarExistente, importar}) {
            r.setOpaque(false);
        }
        chavesDoCofre.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> lista, Object valor, int i, boolean sel, boolean foco) {
                String texto = valor instanceof InfoChave k ? k.nome() + " · " + k.impressao() : "";
                return super.getListCellRendererComponent(lista, texto, i, sel, foco);
            }
        });
        // Cada opção ocupa suas linhas, com o controle dela recuado embaixo: lado a lado a lista de chaves estourava a largura.
        JComponent opcoes = Caixa.coluna(6,
            gerarNova,
            recuado(botaoGerarOutra),
            usarExistente,
            recuado(chavesDoCofre),
            importar,
            recuado(botaoArquivo),
            linhaDaSenhaDoArquivo);
        opcoes.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 0));
        return opcoes;
    }

    private static JComponent recuado(JComponent controle) {
        return Caixa.linha(0, Caixa.espaco(26), controle, Caixa.mola());
    }

    private JComponent montarLinhaDaSenhaDoArquivo() {
        JComponent linha = Caixa.coluna(4,
            Rotulo.rotuloDeCampo("Senha deste arquivo de chave (não é a senha-mestra do app)"),
            Caixa.linha(8, senhaDoArquivo, botaoImportarComSenha));
        linha.setBorder(BorderFactory.createEmptyBorder(0, 26, 0, 0));
        linha.setVisible(false);
        return linha;
    }

    private static JComponent linhaDeCampos(String r1, JComponent c1, String r2, JComponent c2) {
        JComponent a = Caixa.coluna(3, Rotulo.rotuloDeCampo(r1), c1);
        if (r2 == null) {
            return Caixa.linha(12, a, Caixa.mola());
        }
        return Caixa.linha(12, a, Caixa.coluna(3, Rotulo.rotuloDeCampo(r2), c2));
    }

    private JComponent montarPaginaDeConexao() {
        List<Component> linhas = new ArrayList<>();
        for (Etapa e : new Etapa[] {Etapa.CONECTANDO, Etapa.REGISTRANDO_TOKEN, Etapa.TESTANDO_AGENTE, Etapa.LENDO_LIMITES}) {
            Rotulo r = new Rotulo(e.rotulo()).corDoToken(Tokens.TEXTO3);
            Icon[] icones = {
                Icones.colorido("relogio", 16, Tokens.TEXTO3),
                Icones.colorido("atualizar", 16, Tokens.DESTAQUE),
                Icones.colorido("ok-circulo", 16, Tokens.OK),
                Icones.colorido("erro", 16, Tokens.PERIGO)
            };
            r.setIcon(icones[0]);
            r.setIconTextGap(8);
            linhasDasEtapas.put(e, r);
            iconesDasEtapas.put(e, icones);
            linhas.add(r);
        }
        substituirToken.setVisible(false);
        instalarOAgente.setVisible(false);
        agoraNao.setVisible(false);
        concluir.setEnabled(false);
        return Caixa.coluna(14,
            cabecalho("rede", "Conectar e registrar o token"),
            passo("Passo 3 de 3 · Conectar"),
            Caixa.coluna(8, linhas.toArray(Component[]::new)),
            estadoDaConexao,
            mensagemDaConexao,
            instrucaoNaConexao.componente());
    }

    private void ligarEventos() {
        continuar.addActionListener(e -> continuar());
        voltar.addActionListener(e -> {
            if (!ocupado) {
                mostrarPagina(PAGINA_DADOS);
            }
        });
        gerarNova.addActionListener(e -> aoTrocarDeOpcao());
        usarExistente.addActionListener(e -> aoTrocarDeOpcao());
        importar.addActionListener(e -> aoTrocarDeOpcao());
        chavesDoCofre.addActionListener(e -> {
            if (usarExistente.isSelected()) {
                aoMudarAChaveEscolhida();
            }
        });
        botaoGerarOutra.addActionListener(e -> gerarOutraChave());
        repetirGeracao.addActionListener(e -> garantirChaveGerada());
        botaoArquivo.addActionListener(e -> escolherArquivoDaChave());
        botaoImportarComSenha.addActionListener(e -> importarDoArquivoEscolhido());
        senhaDoArquivo.addActionListener(e -> importarDoArquivoEscolhido());
        testar.addActionListener(e -> testar());
        cancelar.addActionListener(e -> aoCancelar());
        cancelarNoPassoDaChave.addActionListener(e -> aoCancelar());
        tentarDeNovo.addActionListener(e -> executarConclusao());
        substituirToken.addActionListener(e -> substituirOToken());
        concluir.addActionListener(e -> concluirDeVez());
        fecharConexao.addActionListener(e -> aoCancelar());
        instalarOAgente.addActionListener(e -> instalarAgente.accept(lab, this::executarConclusao));
        agoraNao.addActionListener(e -> concluirSoPorSsh());
    }

    private void mostrarPagina(String nomeDaPagina) {
        paginaAtual = nomeDaPagina;
        paginas.show(pilha, nomeDaPagina);
        cartoesDosRodapes.show(pilhaDeRodapes, nomeDaPagina);
        rolarParaOTopo();
        botaoPadrao(switch (nomeDaPagina) {
            case PAGINA_DADOS -> continuar;
            case PAGINA_CHAVE -> testar;
            default -> concluir;
        });
        reajustar();
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks para os testes
    // ---------------------------------------------------------------------------------------------

    public String paginaAtual() {
        return paginaAtual;
    }

    public JTextField campoDoNome() {
        return nome;
    }

    public JTextField campoDoHost() {
        return host;
    }

    public JTextField campoDaPorta() {
        return porta;
    }

    public JTextField campoDoUsuario() {
        return usuario;
    }

    public JTextField campoDaPortaDoAgente() {
        return portaDoAgente;
    }

    public JRadioButton opcaoGerar() {
        return gerarNova;
    }

    public JRadioButton opcaoUsarExistente() {
        return usarExistente;
    }

    public JRadioButton opcaoImportar() {
        return importar;
    }

    public JComboBox<InfoChave> comboDeChaves() {
        return chavesDoCofre;
    }

    public JPasswordField campoDaSenhaDoArquivo() {
        return senhaDoArquivo;
    }

    public Botao botaoContinuar() {
        return continuar;
    }

    public Botao botaoVoltar() {
        return voltar;
    }

    public Botao botaoGerarOutra() {
        return botaoGerarOutra;
    }

    public Botao botaoRepetirGeracao() {
        return repetirGeracao;
    }

    public Botao botaoArquivo() {
        return botaoArquivo;
    }

    public Botao botaoImportarComSenha() {
        return botaoImportarComSenha;
    }

    public Botao botaoTestar() {
        return testar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoConcluir() {
        return concluir;
    }

    public Botao botaoTentarDeNovo() {
        return tentarDeNovo;
    }

    public Botao botaoSubstituirToken() {
        return substituirToken;
    }

    /** "Instalar agente", do fim do passo 3 quando o servidor não tem o agente. */
    public Botao botaoInstalarAgente() {
        return instalarOAgente;
    }

    /** "Agora não, usar só por SSH". */
    public Botao botaoAgoraNao() {
        return agoraNao;
    }

    /** O passo 3 está mostrando a pergunta "quer instalar o agente?" (e não um erro). */
    public boolean perguntandoDoAgente() {
        return perguntandoDoAgente;
    }

    public Botao botaoCopiarChave() {
        return instrucaoDaChave.botaoCopiarChave();
    }

    public SecaoRecolhivel opcoesAvancadas() {
        return opcoesAvancadas;
    }

    public SecaoRecolhivel secaoDoComando() {
        return instrucaoDaChave.secaoDoComando();
    }

    public boolean mostraOComandoNaConexao() {
        return instrucaoNaConexao.visivel();
    }

    public boolean mostraASenhaDoArquivo() {
        return linhaDaSenhaDoArquivo.isVisible();
    }

    /** A chave que seria usada agora (a criada pelo app, a do cofre ou a importada), ou {@code null} se ainda não há. */
    public InfoChave chaveEscolhida() {
        return chaveEscolhidaAgora();
    }

    public boolean mostraACaixaDaChave() {
        return instrucaoDaChave.visivel();
    }

    /** O comando técnico do "Ver comando". */
    public String comandoMostrado() {
        return instrucaoDaChave.comandoMostrado();
    }

    public String chavePublicaMostrada() {
        return instrucaoDaChave.chavePublicaMostrada();
    }

    public String proximoPassoMostrado() {
        return instrucaoDaChave.proximoPassoMostrado();
    }

    /** O erro da página em que o usuário está. */
    public String textoDoErro() {
        return PAGINA_DADOS.equals(paginaAtual) ? erroDosDados.getText() : erro.getText();
    }

    public String textoDoEstado() {
        return estado.getText();
    }

    public String mensagemDaConexao() {
        return mensagemDaConexao.getText();
    }

    public String estadoDaConexao() {
        return estadoDaConexao.getText();
    }

    public ServidorLab servidorCadastrado() {
        return lab;
    }

    public boolean ocupado() {
        return ocupado;
    }

    /** O estado de uma etapa na página de conexão: 0 pendente, 1 em andamento, 2 feita, 3 falhou. */
    public int estadoDaEtapa(Etapa e) {
        Rotulo r = linhasDasEtapas.get(e);
        Icon[] icones = iconesDasEtapas.get(e);
        for (int i = 0; i < icones.length; i++) {
            if (r.getIcon() == icones[i]) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Passo 1 → 2: validar e deixar a chave pronta
    // ---------------------------------------------------------------------------------------------

    private void carregarEmFundo() {
        Tarefas.rodar(() -> assistente.chaves(), chaves -> {
            chavesDoCofre.setModel(new DefaultComboBoxModel<>(chaves.toArray(InfoChave[]::new)));
            atualizarControlesDaChave();
        }, falha -> atualizarControlesDaChave());
        Tarefas.rodar(AssistenteDeServidor::sugerirNomeDoDispositivo, n -> nomeDoDispositivo = n, falha -> { });
    }

    private Dados lerDados() {
        int p;
        try {
            p = Integer.parseInt(porta.getText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("A porta do SSH precisa ser um número.");
        }
        int a;
        try {
            a = Integer.parseInt(portaDoAgente.getText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("A porta do painel precisa ser um número.");
        }
        Dados d = new Dados(nome.getText().strip(), host.getText().strip(), p, usuario.getText().strip(), a);
        d.validar();
        return d;
    }

    /**
     * "Continuar": confere os dados e vai para o passo da chave, onde a chave nova nasce SOZINHA. O usuário não decide nada
     * sobre chave para chegar ao fim.
     */
    private void continuar() {
        if (ocupado) {
            return;
        }
        try {
            lerDados();
        } catch (IllegalArgumentException e) {
            dizerErro(erroDosDados, e.getMessage());
            reajustar();
            return;
        }
        dizerErro(erroDosDados, "");
        mostrarPagina(PAGINA_CHAVE);
        garantirChaveGerada();
        atualizarCaixaDaChave();
    }

    // ---------------------------------------------------------------------------------------------
    // Passo 2: chave
    // ---------------------------------------------------------------------------------------------

    private Modo modoSelecionado() {
        return usarExistente.isSelected() ? Modo.EXISTENTE : importar.isSelected() ? Modo.IMPORTADA : Modo.GERADA;
    }

    private InfoChave chaveEscolhidaAgora() {
        return switch (modoSelecionado()) {
            case GERADA -> chaveGerada;
            case EXISTENTE -> chavesDoCofre.getSelectedItem() instanceof InfoChave k ? k : null;
            case IMPORTADA -> chaveImportada;
        };
    }

    private String nomeAtual() {
        String texto = nome.getText().strip();
        return texto.isEmpty() ? "lab" : texto;
    }

    private String usuarioAtual() {
        String texto = usuario.getText().strip();
        return texto.isEmpty() ? "do servidor" : texto;
    }

    /**
     * Cria a chave nova se ainda não há uma e o usuário está na opção padrão: roda fora da EDT, mostra "Criando…" e, quando
     * termina, desenha a chave pública. Chamada ao entrar no passo da chave e ao voltar para a opção "criada pelo app".
     */
    private void garantirChaveGerada() {
        if (modoSelecionado() != Modo.GERADA || chaveGerada != null || gerando) {
            atualizarCaixaDaChave();
            return;
        }
        String nomeDaChave = nomeAtual();
        gerando = true;
        repetirGeracao.setVisible(false);
        dizerErro(erro, "");
        estado.setText("Criando a chave deste servidor…");
        atualizarControlesDaChave();
        Tarefas.rodar(() -> assistente.gerarChave(nomeDaChave), k -> {
            gerando = false;
            chaveGerada = k;
            estado.setText("");
            atualizarControlesDaChave();
            atualizarCaixaDaChave();
        }, falha -> {
            gerando = false;
            estado.setText("");
            repetirGeracao.setVisible(true);
            mostrarErro("Não consegui criar a chave: " + MensagensDeErro.de(falha));
            atualizarControlesDaChave();
        });
    }

    /** "Gerar outra chave" (nas opções avançadas): troca a criada pelo app por uma nova e descarta a antiga se ninguém a usa. */
    private void gerarOutraChave() {
        InfoChave antiga = chaveGerada;
        String nomeDaChave = nomeAtual();
        gerando = true;
        dizerErro(erro, "");
        estado.setText("Criando a chave deste servidor…");
        atualizarControlesDaChave();
        Tarefas.rodar(() -> assistente.gerarChave(nomeDaChave), k -> {
            gerando = false;
            chaveGerada = k;
            estado.setText("");
            atualizarControlesDaChave();
            atualizarCaixaDaChave();
            avisos.ok("Chave nova criada e guardada no cofre.");
            descartarEmFundo(antiga);
        }, falha -> {
            gerando = false;
            estado.setText("");
            mostrarErro(MensagensDeErro.de(falha));
            atualizarControlesDaChave();
        });
    }

    private void descartarEmFundo(InfoChave chave) {
        if (chave != null) {
            Tarefas.rodar(() -> assistente.descartarChaveNaoUsada(chave.id()), apagou -> { }, falha -> { });
        }
    }

    private void aoTrocarDeOpcao() {
        dizerErro(erro, "");
        if (modoSelecionado() == Modo.GERADA) {
            garantirChaveGerada();
        }
        atualizarControlesDaChave();
        atualizarCaixaDaChave();
    }

    private void aoMudarAChaveEscolhida() {
        atualizarControlesDaChave();
        atualizarCaixaDaChave();
    }

    /** Liga e desliga o que cada momento permite; é a única que decide se "Testar conexão" vale. */
    private void atualizarControlesDaChave() {
        Modo modo = modoSelecionado();
        boolean temChavesNoCofre = chavesDoCofre.getItemCount() > 0;
        gerarNova.setEnabled(!ocupado);
        usarExistente.setEnabled(!ocupado && temChavesNoCofre);
        importar.setEnabled(!ocupado);
        chavesDoCofre.setEnabled(!ocupado && modo == Modo.EXISTENTE && temChavesNoCofre);
        botaoArquivo.setEnabled(!ocupado && modo == Modo.IMPORTADA);
        botaoGerarOutra.setVisible(modo == Modo.GERADA && chaveGerada != null);
        botaoGerarOutra.setEnabled(!ocupado && !gerando);
        senhaDoArquivo.setEnabled(!ocupado);
        botaoImportarComSenha.setEnabled(!ocupado);
        voltar.setEnabled(!ocupado);
        testar.setEnabled(!ocupado && !gerando && chaveEscolhidaAgora() != null);
        reajustar();
    }

    /** Mostra a chave pública da chave usada agora, com o próximo passo, ou esconde o quadro se ainda não há chave. */
    private void atualizarCaixaDaChave() {
        InfoChave k = chaveEscolhidaAgora();
        if (k == null) {
            instrucaoDaChave.esconder();
        } else {
            instrucaoDaChave.atualizar(k, usuarioAtual(), portaDoAgenteAtual());
        }
        reajustar();
    }

    private int portaDoAgenteAtual() {
        try {
            return Integer.parseInt(portaDoAgente.getText().strip());
        } catch (NumberFormatException e) {
            return 7000;
        }
    }

    // ---- importar de um arquivo (opções avançadas) ----

    private void escolherArquivoDaChave() {
        Optional<Path> arquivo = seletorDeArquivo.escolher(janela());
        if (arquivo.isEmpty()) {
            return;
        }
        arquivoParaImportar = arquivo.get();
        senhaDoArquivo.setText("");
        linhaDaSenhaDoArquivo.setVisible(false);
        importarDoArquivoEscolhido();
    }

    /** Importa o arquivo já escolhido, com a senha que o usuário digitou (se digitou). Primeiro sem senha: o normal é não ter. */
    private void importarDoArquivoEscolhido() {
        Path arquivo = arquivoParaImportar;
        if (arquivo == null || ocupado) {
            return;
        }
        String nomeDaChave = nomeAtual();
        char[] frase = senhaDoArquivo.getPassword();
        char[] copia = frase.length == 0 ? null : frase.clone();
        Zeragem.zerar(frase);
        dizerErro(erro, "");
        Botao quemPediu = linhaDaSenhaDoArquivo.isVisible() ? botaoImportarComSenha : botaoArquivo;
        Runnable soltar = quemPediu.ocupar();
        Tarefas.rodar(() -> {
            try {
                return assistente.importarChave(arquivo, copia, nomeDaChave);
            } finally {
                if (copia != null) {
                    Zeragem.zerar(copia);
                }
            }
        }, k -> {
            soltar.run();
            senhaDoArquivo.setText("");
            linhaDaSenhaDoArquivo.setVisible(false);
            arquivoParaImportar = null;
            chaveImportada = k;
            importar.setSelected(true);
            atualizarControlesDaChave();
            atualizarCaixaDaChave();
            avisos.ok("Chave importada para o cofre (o arquivo original não foi alterado).");
        }, falha -> {
            soltar.run();
            if (falha instanceof ImportacaoDeChaveException e
                && (e.motivo() == ImportacaoDeChaveException.Motivo.PEDE_SENHA
                    || e.motivo() == ImportacaoDeChaveException.Motivo.SENHA_INCORRETA)) {
                // O arquivo é uma chave de verdade, só protegida por uma senha própria: pede a senha, sem refazer a escolha.
                senhaDoArquivo.setText("");
                linhaDaSenhaDoArquivo.setVisible(true);
                senhaDoArquivo.requestFocusInWindow();
                mostrarErro(e.getMessage());
            } else {
                arquivoParaImportar = null;
                linhaDaSenhaDoArquivo.setVisible(false);
                mostrarErro(MensagensDeErro.de(falha));
            }
            atualizarControlesDaChave();
        });
    }

    private static Optional<Path> escolherComFileDialog(Window pai) {
        FileDialog dialogo = new FileDialog(pai instanceof Frame f ? f : null, "Escolher o arquivo da chave (o que não termina em .pub)", FileDialog.LOAD);
        dialogo.setVisible(true);
        if (dialogo.getFile() == null) {
            return Optional.empty();
        }
        return Optional.of(Path.of(dialogo.getDirectory(), dialogo.getFile()));
    }

    private void mostrarErro(String texto) {
        dizerErro(PAGINA_DADOS.equals(paginaAtual) ? erroDosDados : erro, texto);
        reajustar();
    }

    // ---------------------------------------------------------------------------------------------
    // Passo 2 → 3: sondar, confirmar o host, cadastrar
    // ---------------------------------------------------------------------------------------------

    private void testar() {
        if (!testar.isEnabled()) {
            return;
        }
        Dados dados;
        try {
            dados = lerDados();
        } catch (IllegalArgumentException e) {
            mostrarErro(e.getMessage());
            return;
        }
        InfoChave daVez = chaveEscolhidaAgora();
        if (daVez == null) {
            // Só se chega aqui por um clique atrasado: o botão fica desligado enquanto a chave não existe.
            mostrarErro("A chave ainda não está pronta. Espere um instante, ou escolha uma nas opções avançadas.");
            return;
        }
        dizerErro(erro, "");
        estado.setText("Consultando a chave do servidor em " + dados.host() + ":" + dados.porta() + "…");
        ocupar(true);
        Tarefas.rodar(() -> assistente.sondar(dados.host(), dados.porta()), resultado -> {
            ocupar(false);
            estado.setText("");
            confirmadorDeHost.confirmar(dados.nome(), dados.host() + ":" + dados.porta(), resultado, confiou -> {
                if (!confiou) {
                    mostrarErro(TEXTO_DE_SERVIDOR_NAO_CONFIRMADO);
                    return;
                }
                cadastrar(dados, daVez, resultado);
            });
        }, falha -> {
            ocupar(false);
            estado.setText("");
            mostrarErro(MensagensDeErro.de(falha));
        });
    }

    private void cadastrar(Dados dados, InfoChave daVez, SondagemDeHost.Resultado resultado) {
        estado.setText("Gravando o servidor e a impressão digital no cofre…");
        ocupar(true);
        Tarefas.rodar(() -> assistente.cadastrar(dados, daVez.id(), resultado.pin()), novo -> {
            ocupar(false);
            estado.setText("");
            this.lab = novo;
            if (chaveGerada != null && !chaveGerada.id().equals(daVez.id())) {
                descartarEmFundo(chaveGerada); // o usuário usou outra chave: a que o app criou ficou órfã
            }
            inscreverNoEstado();
            mostrarPagina(PAGINA_CONEXAO);
            executarConclusao();
        }, falha -> {
            ocupar(false);
            estado.setText("");
            mostrarErro(MensagensDeErro.de(falha));
        });
    }

    private void ocupar(boolean sim) {
        ocupado = sim;
        for (JComponent c : new JComponent[] {nome, host, porta, usuario, portaDoAgente, continuar}) {
            c.setEnabled(!sim);
        }
        if (sim) {
            desfazerOcupado = testar.ocupar();
        } else {
            desfazerOcupado.run();
            desfazerOcupado = () -> { };
        }
        atualizarControlesDaChave();
    }

    // ---------------------------------------------------------------------------------------------
    // Passo 3: conectar, registrar o token, testar
    // ---------------------------------------------------------------------------------------------

    private void inscreverNoEstado() {
        if (inscricaoDoEstado != null) {
            inscricaoDoEstado.close();
        }
        inscricaoDoEstado = lab.observarEstado(this::mostrarEstadoDaConexao);
    }

    private void mostrarEstadoDaConexao(EstadoServidor e) {
        ApresentacaoDoEstado.Apresentacao a = ApresentacaoDoEstado.de(e);
        estadoDaConexao.setIcon(Icones.colorido(a.icone(), 16, a.gravidade().tokenDoTexto()));
        estadoDaConexao.setIconTextGap(8);
        estadoDaConexao.setText("Conexão: " + a.texto());
        estadoDaConexao.corDoToken(a.gravidade().tokenDoTexto());
    }

    private void marcarEtapa(Etapa atual) {
        for (Map.Entry<Etapa, Rotulo> par : linhasDasEtapas.entrySet()) {
            Etapa e = par.getKey();
            Rotulo r = par.getValue();
            Icon[] icones = iconesDasEtapas.get(e);
            if (atual == Etapa.PRONTO || e.ordinal() < atual.ordinal()) {
                r.setIcon(icones[2]);
                r.corDoToken(Tokens.TEXTO);
            } else if (e == atual) {
                r.setIcon(icones[1]);
                r.corDoToken(Tokens.TEXTO);
            } else {
                r.setIcon(icones[0]);
                r.corDoToken(Tokens.TEXTO3);
            }
        }
        // O agente do perfil monitor não tem limites do plano: a linha "Lendo os limites" não se aplica a este servidor.
        Rotulo limites = linhasDasEtapas.get(Etapa.LENDO_LIMITES);
        if (limites != null && atual == Etapa.PRONTO && lab != null && Boolean.FALSE.equals(lab.estado().temClaude())) {
            limites.setVisible(false);
        }
    }

    private void marcarFalhaNaEtapaEmAndamento() {
        for (Map.Entry<Etapa, Rotulo> par : linhasDasEtapas.entrySet()) {
            Icon[] icones = iconesDasEtapas.get(par.getKey());
            if (par.getValue().getIcon() == icones[1]) {
                par.getValue().setIcon(icones[3]);
            }
        }
    }

    private void executarConclusao() {
        mensagemDaConexao.setText("");
        substituirToken.setVisible(false);
        instalarOAgente.setVisible(false);
        agoraNao.setVisible(false);
        perguntandoDoAgente = false;
        concluir.setVisible(true);
        concluir.setEnabled(false);
        tentarDeNovo.setEnabled(false);
        instrucaoNaConexao.esconder();
        marcarEtapa(Etapa.CONECTANDO);
        reajustar();
        Tarefas.rodar(() -> assistente.concluir(lab, nomeDoDispositivo,
            etapa -> br.com.labdesktop.util.Fundo.naEdt(() -> marcarEtapa(etapa))), conclusao -> {
            if (conclusao == Conclusao.AGENTE_NAO_INSTALADO) {
                perguntarSobreOAgente();
                return;
            }
            concluido = true;
            tentarDeNovo.setVisible(false);
            concluir.setEnabled(true);
            boolean semClaude = Boolean.FALSE.equals(lab.estado().temClaude());
            mensagemDaConexao.setText(semClaude
                ? "Tudo certo: o servidor respondeu e o token deste computador está registrado. Clique em Concluir para ver o servidor."
                : "Tudo certo: o servidor respondeu, o token deste computador está registrado e os limites do "
                    + "plano chegaram. Clique em Concluir para ver os projetos.");
            reajustar();
        }, this::falhouAConclusao);
    }

    /**
     * O SSH conectou e o servidor ainda não tem o agente. NÃO é erro: a tela pergunta, sem vermelho e sem a palavra "erro", e o usuário
     * escolhe instalar (abre a confirmação) ou usar só por SSH (terminal, Segurança e Arquivos funcionam). O registro do token só roda
     * depois que o agente existir.
     */
    private void perguntarSobreOAgente() {
        perguntandoDoAgente = true;
        marcarEtapa(Etapa.CONECTANDO);
        for (Map.Entry<Etapa, Rotulo> par : linhasDasEtapas.entrySet()) {
            if (par.getKey() == Etapa.CONECTANDO) {
                par.getValue().setIcon(iconesDasEtapas.get(Etapa.CONECTANDO)[2]); // conectar deu certo
                par.getValue().corDoToken(Tokens.TEXTO);
            }
        }
        tentarDeNovo.setVisible(false);
        concluir.setEnabled(false);
        concluir.setVisible(false); // o rodapé não tem largura para tantos botões; Concluir volta quando o assistente terminar
        boolean comAgente = br.com.labdesktop.instalador.PacoteDoAgente.disponivel();
        instalarOAgente.setVisible(comAgente);
        agoraNao.setVisible(true);
        mensagemDaConexao.setText(comAgente ? TEXTO_DA_PERGUNTA_DO_AGENTE : TEXTO_SEM_O_AGENTE_NO_APP);
        botaoPadrao(comAgente ? instalarOAgente : agoraNao);
        reajustar();
    }

    /** "Agora não, usar só por SSH": o servidor já está no cofre; fica o aviso fixo no cartão dele, com o botão Instalar agente. */
    private void concluirSoPorSsh() {
        if (lab == null) {
            return;
        }
        concluido = true;
        avisos.ok("Servidor " + lab.estado().nome() + " adicionado, só por SSH. Instale o agente quando quiser: o botão está no cartão do servidor.");
        ServidorLab pronto = lab;
        fechar();
        aoConcluir.accept(pronto);
    }

    private void falhouAConclusao(Throwable falha) {
        marcarFalhaNaEtapaEmAndamento();
        tentarDeNovo.setEnabled(true);
        String mensagem = MensagensDeErro.de(falha);
        if (falha instanceof ErroDoAssistente e) {
            mensagem = e.getMessage();
            if (e.motivo() == ErroDoAssistente.Motivo.CHAVE_RECUSADA) {
                instrucaoNaConexao.atualizar(chaveEscolhidaAgora() != null ? chaveEscolhidaAgora() : chaveGerada,
                    usuarioAtual(), portaDoAgenteAtual());
            }
        } else if (falha instanceof ErroProvisionamento e && ErroProvisionamento.TOKEN_CONFLITO.equals(e.codigo())) {
            mensagem = "Já existe um token chamado \"" + nomeDoDispositivo + "\" no agente (de outro computador, ou de um cofre "
                + "antigo). Posso revogar o antigo e registrar um novo: a entrada antiga deixa de valer agora.";
            substituirToken.setVisible(true);
        }
        mensagemDaConexao.setText(mensagem);
        reajustar();
    }

    private void substituirOToken() {
        substituirToken.setVisible(false);
        tentarDeNovo.setEnabled(false);
        mensagemDaConexao.setText("Substituindo o token…");
        Tarefas.rodar(() -> {
            lab.reprovisionarToken(nomeDoDispositivo);
            return Boolean.TRUE;
        }, ok -> executarConclusao(), this::falhouAConclusao);
    }

    private void concluirDeVez() {
        if (!concluido || lab == null) {
            return;
        }
        avisos.ok("Servidor " + lab.estado().nome() + " adicionado.");
        ServidorLab pronto = lab;
        fechar();
        aoConcluir.accept(pronto);
    }

    // ---------------------------------------------------------------------------------------------
    // Host
    // ---------------------------------------------------------------------------------------------

    private void confirmarComDialogo(String servidor, String endereco, SondagemDeHost.Resultado resultado,
                                     Consumer<Boolean> aoDecidir) {
        DialogoConfiarHost confiar = new DialogoConfiarHost(servidor, endereco, resultado, avisos, aoDecidir, copiador);
        confiar.mostrar(janela());
    }

    @Override
    protected void aoFechar() {
        if (inscricaoDoEstado != null) {
            inscricaoDoEstado.close();
            inscricaoDoEstado = null;
        }
        senhaDoArquivo.setText("");
    }

    @Override
    protected void antesDeMostrar() {
        nome.requestFocusInWindow();
    }
}
