package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.ImportacaoDeChaveException;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.servidor.AssistenteDeServidor;
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
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
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
 * "Adicionar servidor" (ARQUITETURA 8.3): o assistente de ponta a ponta, na ordem do documento.
 * <ol>
 *   <li><b>Dados e chave</b>: nome, endereço, porta, usuário (vazios na primeira vez, com a porta SSH 22);
 *       gerar uma chave Ed25519 nova (recomendado), usar uma que já está no cofre ou importar de um arquivo.
 *       Mostra a chave pública e o COMANDO pronto para colar no servidor, com botão Copiar.</li>
 *   <li><b>Conferir o servidor</b>: a sondagem captura a chave do host SEM autenticar; o usuário compara a impressão
 *       digital com o {@code ssh-keygen -lf} do servidor e confirma ({@link DialogoConfiarHost}); só então servidor
 *       e pin entram no cofre.</li>
 *   <li><b>Conectar e registrar o token</b>: autentica com a chave, registra o hash do token por SSH
 *       e testa {@code GET /api/versao} e lê {@code GET /api/limites}. O estado da conexão
 *       aparece o tempo todo com texto e ícone.</li>
 * </ol>
 * Cada passo de rede ou de disco roda fora da EDT ({@link Tarefas}). Fechar o diálogo depois do passo 2 NÃO desfaz
 * o cadastro: o servidor fica no cofre e o que faltou (token, por exemplo) se conclui pela faixa de aviso.
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

    /** De onde veio a chave escolhida; trocar de opção descarta uma chave que veio de outra. */
    private enum Modo { GERADA, EXISTENTE, IMPORTADA }

    private static final String PAGINA_DADOS = "dados";
    private static final String PAGINA_CONEXAO = "conexao";

    private final AssistenteDeServidor assistente;
    private final Notificacoes avisos;
    private final Consumer<ServidorLab> aoConcluir;
    private final ConfirmadorDeHost confirmadorDeHost;
    private final SeletorDeArquivo seletorDeArquivo;

    private final CardLayout paginas = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel pilha = new Painel(paginas);
    private String paginaAtual = PAGINA_DADOS;

    // ---- página 1: dados e chave ----
    private final JTextField nome = Campos.texto(14, "lab");
    private final JTextField host = Campos.mono(16, "192.0.2.5");
    private final JTextField porta = Campos.mono(5, "22");
    private final JTextField usuario = Campos.mono(14, "usuario");
    private final JTextField portaDoAgente = Campos.mono(5, "7000");
    private final JRadioButton gerarNova = new JRadioButton("Gerar uma chave nova (recomendado)", true);
    private final JRadioButton usarExistente = new JRadioButton("Usar uma chave que já está no cofre");
    private final JRadioButton importar = new JRadioButton("Importar de um arquivo");
    private final JComboBox<InfoChave> chavesDoCofre = new JComboBox<>();
    private final Botao botaoGerar = Botoes.secundario("Gerar chave", "chave");
    private final Botao botaoArquivo = Botoes.secundario("Escolher arquivo…", "pasta");
    private final JPasswordField passphrase = Campos.senha(16);
    private final Rotulo resumoDaChave = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido comandoDeInstalacao = new TextoCorrido("", LARGURA_DO_TEXTO - 24).mono(12f);
    private final JComponent caixaDaChave;
    private final TextoCorrido comandoNaConexao = new TextoCorrido("", LARGURA_DO_TEXTO - 24).mono(12f);
    private final JComponent caixaDeInstalacaoNaConexao;
    private final Rotulo estado = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Rotulo erro = erro();
    private final Botao testar = Botoes.primario("Testar conexão", "rede");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private InfoChave chave;
    private Modo modoDaChave;
    private Runnable desfazerOcupado = () -> { };
    private boolean ocupado;

    // ---- página 2: conexão ----
    private final Map<Etapa, Rotulo> linhasDasEtapas = new EnumMap<>(Etapa.class);
    private final Map<Etapa, Icon[]> iconesDasEtapas = new EnumMap<>(Etapa.class);
    private final Rotulo estadoDaConexao = new Rotulo().tamanho(13f);
    private final TextoCorrido mensagemDaConexao = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO);
    private final Botao tentarDeNovo = Botoes.secundario("Tentar de novo", "atualizar");
    private final Botao substituirToken = Botoes.perigo("Substituir o token do servidor", "chave");
    private final Botao concluir = Botoes.primario("Concluir", "ok");
    private final Botao fecharConexao = Botoes.fantasma("Fechar", null);
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
        super("Adicionar servidor");
        this.assistente = assistente;
        this.avisos = avisos;
        this.aoConcluir = aoConcluir;
        this.confirmadorDeHost = confirmadorDeHost != null ? confirmadorDeHost : this::confirmarComDialogo;
        this.seletorDeArquivo = seletorDeArquivo != null ? seletorDeArquivo : DialogoAdicionarServidor::escolherComFileDialog;

        Dados padrao = primeiro ? Dados.padraoDoPrimeiro() : new Dados("", "", 22, "", 7000);
        nome.setText(padrao.nome());
        host.setText(padrao.host());
        porta.setText(String.valueOf(padrao.porta()));
        usuario.setText(padrao.usuario());
        portaDoAgente.setText(String.valueOf(padrao.portaDoAgente()));

        caixaDaChave = montarCaixaDaChave();
        caixaDeInstalacaoNaConexao = montarCaixaDeComando(comandoNaConexao);
        caixaDeInstalacaoNaConexao.setVisible(false);
        pilha.add(montarPaginaDeDados(), PAGINA_DADOS);
        pilha.add(montarPaginaDeConexao(), PAGINA_CONEXAO);
        conteudo(pilha);
        botaoPadrao(testar);

        ligarEventos();
        atualizarModoDaChave();
        atualizarBotaoTestar(); // sem chave, não dá para testar
        mostrarPagina(PAGINA_DADOS);
        carregarEmFundo();
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private JComponent montarPaginaDeDados() {
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
        passphrase.putClientProperty("JTextField.placeholderText", "passphrase da chave (se tiver)");

        JComponent dados = Caixa.coluna(8,
            Rotulo.rotuloDeCampo("Servidor"),
            linhaDeCampos("Nome", nome, "Usuário", usuario),
            linhaDeCampos("Endereço", host, "Porta do SSH", porta),
            linhaDeCampos("Porta do agente", portaDoAgente, null, null),
            dicaCorrida("A porta do agente é a do painel no loopback DO SERVIDOR (7000), para onde o túnel aponta."));

        JComponent escolhaDaChave = Caixa.coluna(6,
            Rotulo.rotuloDeCampo("Chave SSH deste app"),
            gerarNova,
            Caixa.linha(8, usarExistente, chavesDoCofre),
            Caixa.linha(8, importar, botaoArquivo, passphrase),
            Caixa.linha(0, botaoGerar));

        return Caixa.coluna(12,
            cabecalho("servidor", "Adicionar servidor"),
            paragrafo("O app guarda a chave SSH no cofre e nunca a mostra. Você instala a chave PÚBLICA no servidor, "
                + "confere a impressão digital do host e o app registra o token deste Mac."),
            dados,
            escolhaDaChave,
            caixaDaChave,
            estado,
            erro,
            rodape(null, cancelar, testar));
    }

    private JComponent montarCaixaDaChave() {
        JPanel caixa = new Painel(new BorderLayout(0, 8)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        caixa.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        caixa.add(Caixa.coluna(8, resumoDaChave, montarCaixaDeComando(comandoDeInstalacao)), BorderLayout.CENTER);
        caixa.setVisible(false);
        return caixa;
    }

    /** O comando para colar no servidor, com os dois botões de copiar. Serve às duas páginas. */
    private JComponent montarCaixaDeComando(TextoCorrido destino) {
        Botao copiarComando = Botoes.mini("Copiar comando", "copiar");
        Botao copiarChave = Botoes.mini("Copiar chave pública", "copiar");
        copiarComando.addActionListener(e -> copiar(chave == null ? "" : chave.comandoDeInstalacao(portaDoAgenteAtual()),
            "Comando copiado. Cole no servidor."));
        copiarChave.addActionListener(e -> copiar(chave == null ? "" : chave.publica(), "Chave pública copiada."));
        return Caixa.coluna(8,
            new TextoCorrido("Cole este comando numa sessão ssh já aberta no servidor (com a chave antiga):", LARGURA_DO_TEXTO - 24)
                .corDoToken(Tokens.TEXTO2).tamanho(12.5f),
            destino,
            Caixa.linha(8, copiarComando, copiarChave),
            new TextoCorrido(InfoChave.RESTRICOES_DA_LINHA, LARGURA_DO_TEXTO - 24)
                .corDoToken(Tokens.TEXTO3).tamanho(12f));
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
                Icones.colorido("atualizar", 16, Tokens.ACENTO),
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
        concluir.setEnabled(false);
        return Caixa.coluna(14,
            cabecalho("rede", "Conectar e registrar o token"),
            Caixa.coluna(8, linhas.toArray(Component[]::new)),
            estadoDaConexao,
            mensagemDaConexao,
            caixaDeInstalacaoNaConexao,
            rodape(null, fecharConexao, substituirToken, tentarDeNovo, concluir));
    }

    private void ligarEventos() {
        gerarNova.addActionListener(e -> atualizarModoDaChave());
        usarExistente.addActionListener(e -> atualizarModoDaChave());
        importar.addActionListener(e -> atualizarModoDaChave());
        chavesDoCofre.addActionListener(e -> {
            if (usarExistente.isSelected() && chavesDoCofre.getSelectedItem() instanceof InfoChave k) {
                escolherChave(k, Modo.EXISTENTE);
            }
        });
        botaoGerar.addActionListener(e -> gerarChave());
        botaoArquivo.addActionListener(e -> importarChave());
        testar.addActionListener(e -> testar());
        cancelar.addActionListener(e -> aoCancelar());
        portaDoAgente.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(this::atualizarComando));
        tentarDeNovo.addActionListener(e -> executarConclusao());
        substituirToken.addActionListener(e -> substituirOToken());
        concluir.addActionListener(e -> concluirDeVez());
        fecharConexao.addActionListener(e -> aoCancelar());
    }

    private void mostrarPagina(String nomeDaPagina) {
        paginaAtual = nomeDaPagina;
        paginas.show(pilha, nomeDaPagina);
        botaoPadrao(PAGINA_DADOS.equals(nomeDaPagina) ? testar : concluir);
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

    public JPasswordField campoDaPassphrase() {
        return passphrase;
    }

    public Botao botaoGerar() {
        return botaoGerar;
    }

    public Botao botaoArquivo() {
        return botaoArquivo;
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

    public boolean mostraOComandoNaConexao() {
        return caixaDeInstalacaoNaConexao.isVisible();
    }

    public InfoChave chaveEscolhida() {
        return chave;
    }

    public boolean mostraACaixaDaChave() {
        return caixaDaChave.isVisible();
    }

    public String comandoMostrado() {
        return comandoDeInstalacao.getText();
    }

    public String textoDoErro() {
        return erro.getText();
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
    // Página 1: chave
    // ---------------------------------------------------------------------------------------------

    private void carregarEmFundo() {
        Tarefas.rodar(() -> assistente.chaves(), chaves -> {
            chavesDoCofre.setModel(new DefaultComboBoxModel<>(chaves.toArray(InfoChave[]::new)));
            usarExistente.setEnabled(!chaves.isEmpty());
            chavesDoCofre.setEnabled(!chaves.isEmpty() && usarExistente.isSelected());
        }, falha -> usarExistente.setEnabled(false));
        Tarefas.rodar(AssistenteDeServidor::sugerirNomeDoDispositivo, n -> nomeDoDispositivo = n, falha -> { });
    }

    private void atualizarModoDaChave() {
        boolean gerar = gerarNova.isSelected();
        boolean existente = usarExistente.isSelected();
        boolean importando = importar.isSelected();
        botaoGerar.setVisible(gerar && modoDaChave != Modo.GERADA);
        chavesDoCofre.setEnabled(existente && chavesDoCofre.getItemCount() > 0);
        botaoArquivo.setEnabled(importando);
        passphrase.setEnabled(importando);
        Modo escolhido = gerar ? Modo.GERADA : existente ? Modo.EXISTENTE : Modo.IMPORTADA;
        if (existente && chavesDoCofre.getSelectedItem() instanceof InfoChave k) {
            escolherChave(k, Modo.EXISTENTE);
        } else if (modoDaChave != null && modoDaChave != escolhido) {
            esquecerChave(); // a chave de outra opção não vale para esta
        }
        reajustar();
    }

    private void esquecerChave() {
        chave = null;
        modoDaChave = null;
        caixaDaChave.setVisible(false);
        botaoGerar.setVisible(gerarNova.isSelected());
        atualizarBotaoTestar();
    }

    private void escolherChave(InfoChave k, Modo modo) {
        this.chave = k;
        this.modoDaChave = modo;
        resumoDaChave.setText("Chave \"" + k.nome() + "\" · " + k.impressao());
        atualizarComando();
        caixaDaChave.setVisible(true);
        botaoGerar.setVisible(gerarNova.isSelected() && modo != Modo.GERADA);
        atualizarBotaoTestar();
        reajustar();
    }

    private void atualizarComando() {
        if (chave == null) {
            return;
        }
        String comando;
        try {
            comando = chave.comandoDeInstalacao(portaDoAgenteAtual());
        } catch (IllegalArgumentException e) {
            comando = "(informe uma porta do agente válida para ver o comando)";
        }
        comandoDeInstalacao.setText(comando);
        comandoNaConexao.setText(comando);
        reajustar();
    }

    private int portaDoAgenteAtual() {
        try {
            return Integer.parseInt(portaDoAgente.getText().strip());
        } catch (NumberFormatException e) {
            return 7000;
        }
    }

    private void gerarChave() {
        String nomeDaChave = nome.getText().isBlank() ? "lab" : nome.getText().strip();
        erro.setText("");
        desfazerOcupado = botaoGerar.ocupar();
        Tarefas.rodar(() -> assistente.gerarChave(nomeDaChave), k -> {
            desfazerOcupado.run();
            escolherChave(k, Modo.GERADA); // uma chave por servidor: o botão some, gerar de novo só criaria lixo no cofre
            avisos.ok("Chave gerada e guardada no cofre.");
        }, falha -> {
            desfazerOcupado.run();
            mostrarErro(MensagensDeErro.de(falha));
        });
    }

    private void importarChave() {
        Optional<Path> arquivo = seletorDeArquivo.escolher(janela());
        if (arquivo.isEmpty()) {
            return;
        }
        String nomeDaChave = nome.getText().isBlank() ? "lab" : nome.getText().strip();
        char[] frase = passphrase.getPassword();
        char[] copia = frase.length == 0 ? null : frase.clone();
        Zeragem.zerar(frase);
        erro.setText("");
        desfazerOcupado = botaoArquivo.ocupar();
        Tarefas.rodar(() -> {
            try {
                return assistente.importarChave(arquivo.get(), copia, nomeDaChave);
            } finally {
                if (copia != null) {
                    Zeragem.zerar(copia);
                }
            }
        }, k -> {
            desfazerOcupado.run();
            passphrase.setText("");
            escolherChave(k, Modo.IMPORTADA);
            avisos.ok("Chave importada para o cofre (o arquivo original não foi alterado).");
        }, falha -> {
            desfazerOcupado.run();
            if (falha instanceof ImportacaoDeChaveException e
                && (e.motivo() == ImportacaoDeChaveException.Motivo.PEDE_SENHA
                    || e.motivo() == ImportacaoDeChaveException.Motivo.SENHA_INCORRETA)) {
                mostrarErro(e.getMessage() + " Digite a passphrase no campo e escolha o arquivo de novo.");
            } else {
                mostrarErro(MensagensDeErro.de(falha));
            }
        });
    }

    private static Optional<Path> escolherComFileDialog(Window pai) {
        FileDialog dialogo = new FileDialog(pai instanceof Frame f ? f : null, "Escolher a chave privada (Ed25519)", FileDialog.LOAD);
        dialogo.setVisible(true);
        if (dialogo.getFile() == null) {
            return Optional.empty();
        }
        return Optional.of(Path.of(dialogo.getDirectory(), dialogo.getFile()));
    }

    private void copiar(String texto, String aviso) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(texto), null);
        avisos.info(aviso);
    }

    private void mostrarErro(String texto) {
        erro.setText(texto);
        reajustar();
    }

    private void atualizarBotaoTestar() {
        testar.setEnabled(!ocupado && chave != null);
    }

    // ---------------------------------------------------------------------------------------------
    // Página 1 → 2: sondar, confirmar o host, cadastrar
    // ---------------------------------------------------------------------------------------------

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
            throw new IllegalArgumentException("A porta do agente precisa ser um número.");
        }
        Dados d = new Dados(nome.getText().strip(), host.getText().strip(), p, usuario.getText().strip(), a);
        d.validar();
        return d;
    }

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
        if (chave == null) {
            mostrarErro("Gere, escolha ou importe a chave do servidor primeiro.");
            return;
        }
        erro.setText("");
        estado.setText("Consultando a chave do servidor em " + dados.host() + ":" + dados.porta() + "…");
        ocupar(true);
        InfoChave daVez = chave;
        Tarefas.rodar(() -> assistente.sondar(dados.host(), dados.porta()), resultado -> {
            ocupar(false);
            estado.setText("");
            confirmadorDeHost.confirmar(dados.nome(), dados.host() + ":" + dados.porta(), resultado, confiou -> {
                if (!confiou) {
                    mostrarErro("Servidor não confirmado. Nada foi gravado e nenhuma credencial foi enviada.");
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
        for (JComponent c : new JComponent[] {nome, host, porta, usuario, portaDoAgente, gerarNova, usarExistente,
            importar, botaoGerar, botaoArquivo, passphrase}) {
            c.setEnabled(!sim);
        }
        if (sim) {
            desfazerOcupado = testar.ocupar();
        } else {
            desfazerOcupado.run();
            desfazerOcupado = () -> { };
            atualizarModoDaChave();
        }
        atualizarBotaoTestar();
    }

    // ---------------------------------------------------------------------------------------------
    // Página 2: conectar, registrar o token, testar
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
        concluir.setEnabled(false);
        tentarDeNovo.setEnabled(false);
        caixaDeInstalacaoNaConexao.setVisible(false);
        marcarEtapa(Etapa.CONECTANDO);
        reajustar();
        Tarefas.rodar(() -> {
            assistente.concluir(lab, nomeDoDispositivo, etapa -> br.com.labdesktop.util.Fundo.naEdt(() -> marcarEtapa(etapa)));
            return Boolean.TRUE;
        }, ok -> {
            concluido = true;
            tentarDeNovo.setVisible(false);
            concluir.setEnabled(true);
            mensagemDaConexao.setText("Tudo certo: o servidor respondeu, o token deste Mac está registrado e os limites do "
                + "plano chegaram. Clique em Concluir para ver os projetos.");
            reajustar();
        }, this::falhouAConclusao);
    }

    private void falhouAConclusao(Throwable falha) {
        marcarFalhaNaEtapaEmAndamento();
        tentarDeNovo.setEnabled(true);
        String mensagem = MensagensDeErro.de(falha);
        if (falha instanceof ErroDoAssistente e) {
            mensagem = e.getMessage();
            if (e.motivo() == ErroDoAssistente.Motivo.CHAVE_RECUSADA) {
                caixaDeInstalacaoNaConexao.setVisible(true);
            }
        } else if (falha instanceof ErroProvisionamento e && ErroProvisionamento.TOKEN_CONFLITO.equals(e.codigo())) {
            mensagem = "Já existe um token chamado \"" + nomeDoDispositivo + "\" no agente (de outro Mac, ou de um cofre "
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
        DialogoConfiarHost confiar = new DialogoConfiarHost(servidor, endereco, resultado, avisos, aoDecidir);
        confiar.mostrar(janela());
    }

    @Override
    protected void aoFechar() {
        if (inscricaoDoEstado != null) {
            inscricaoDoEstado.close();
            inscricaoDoEstado = null;
        }
        passphrase.setText("");
    }

    @Override
    protected void antesDeMostrar() {
        nome.requestFocusInWindow();
    }
}
