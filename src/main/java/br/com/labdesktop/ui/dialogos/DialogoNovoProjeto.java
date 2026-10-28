package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.FiltroDeEnvio;
import br.com.labdesktop.projetos.FluxoDeCriacao;
import br.com.labdesktop.projetos.ImportadorDeProjeto;
import br.com.labdesktop.projetos.NomeDoProjeto;
import br.com.labdesktop.projetos.UrlDeGit;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Abas;
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
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/**
 * "Novo projeto": a porta de entrada para ter um projeto no servidor (ou no computador local), em três caminhos simples:
 * <ol>
 *   <li><b>Criar do zero</b>: a pasta nova com {@code docker-compose.yml} vazio, rede própria, README e STATUS; a caixa "Incluir guia para o Claude"
 *       só aparece quando o servidor tem Claude e o agente entende a opção;</li>
 *   <li><b>Importar um projeto que já existe</b>: do Git (clone no servidor), de uma pasta do computador do usuário (copiada, sem {@code node_modules}, sem
 *       {@code .git} e sem {@code .env} a menos que marcados) ou de uma pasta que já está no servidor (mover ou copiar). Cada origem diz, antes do
 *       clique, o que vai acontecer;</li>
 *   <li><b>Adicionar um container</b> fica na página de cada projeto (aqui só o aviso).</li>
 * </ol>
 * O nome é conferido a cada letra pela mesma regra do agente; o botão fica desabilitado enquanto envia (clique duplo não cria duas vezes) e, se o
 * resultado de uma criação é incerto (prazo), a tela manda conferir a lista em vez de repetir. Nada de rede, disco ou processo na EDT.
 */
public final class DialogoNovoProjeto extends Dialogo {

    /** As páginas do diálogo. */
    public enum Pagina { ESCOLHER, CRIAR, IMPORTAR }

    /** De onde vem o projeto importado. */
    public enum Origem { GIT, PASTA_DO_COMPUTADOR, PASTA_DO_SERVIDOR }

    /** O que o diálogo precisa do domínio (a tela não sabe se o servidor é o computador local ou um Debian). Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {

        /** O servidor é "Este computador"? (some a origem "pasta do servidor"; os textos falam do computador). */
        boolean local();

        /** Mostrar a caixa "Incluir guia para o Claude"? (só com Claude no servidor e um agente que entende a opção). */
        boolean guiaIaDisponivel();

        /** A pasta dos projetos, como o usuário a vê. */
        String pastaDosProjetos();

        /** O caminho completo do projeto {@code nome} (ou do modelo "&lt;nome&gt;"), como o usuário o vê. */
        String caminhoDoProjeto(String nome);

        FluxoDeCriacao.Desfecho criar(String nome, Boolean guiaIa);

        ImportadorDeProjeto.Resultado clonar(UrlDeGit url, String nome, ImportadorDeProjeto.Observador obs, Cancelamento cancelamento)
            throws FalhaDoProjeto, InterruptedException;

        ImportadorDeProjeto.Resultado importarPasta(Path pasta, String nome, FiltroDeEnvio filtro, ImportadorDeProjeto.Observador obs,
                                                    Cancelamento cancelamento) throws FalhaDoProjeto;

        List<ImportadorDeProjeto.Candidata> candidatas() throws FalhaDoProjeto;

        ImportadorDeProjeto.Resultado adotar(String origem, String nome, ImportadorDeProjeto.Modo modo, ImportadorDeProjeto.Observador obs,
                                             Cancelamento cancelamento) throws FalhaDoProjeto, InterruptedException;

        /** O seletor de pastas do sistema; vazio se o usuário cancelou. */
        Optional<Path> escolherPasta();
    }

    /**
     * O que o diálogo entrega ao fechar com o projeto pronto (ou que talvez esteja pronto): a janela atualiza a lista, seleciona o projeto, dá o aviso e
     * leva à página dele.
     *
     * @param talvez o resultado era incerto (prazo): a janela só atualiza a lista e manda conferir, sem abrir a página
     */
    public record Concluido(String nome, String mensagem, List<String> avisos, boolean talvez) {
        public Concluido {
            avisos = List.copyOf(avisos);
        }
    }

    private static final int LARGURA = 470;

    private final Motor motor;
    private final Notificacoes avisos;
    private final CopiadorDeTexto copiador;
    private final Consumer<Concluido> aoConcluir;

    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private final Botao voltar = Botoes.fantasma("Voltar", "voltar");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Botao principal = Botoes.primario("Criar projeto", null);
    private Pagina pagina = Pagina.ESCOLHER;
    private boolean ocupado;
    private boolean incerto;
    private Concluido talvezConcluido;
    /** O pedido de parar da importação em curso: vale desde o primeiro instante, para todas as origens (a thread pode nem ter começado). */
    private volatile Cancelamento cancelamentoDoEnvio = new Cancelamento();
    private volatile Thread threadDoTrabalho;
    private boolean pediuParar;

    // ---- criar do zero ----
    private final JTextField campoNome = Campos.texto(30, "meu-projeto");
    private final TextoCorrido erroDoNome = erroCorrido();
    private final Rotulo caminhoCriar = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final JCheckBox guiaIa = new JCheckBox("Incluir guia para o Claude (CLAUDE.md)", true);
    private final TextoCorrido listaDoQueVemPronto;
    private final TextoCorrido falhaDeCriar = erroCorrido();
    private final TextoCorrido avisoIncerto = new TextoCorrido("", LARGURA).corDoToken(Tokens.ATENCAO).tamanho(12.5f);

    // ---- importar ----
    private final Abas abasDeOrigem = new Abas(true);
    private final CardLayout cartoesDeOrigem = new CartoesAjustaveis();
    private final JPanel painelDeOrigem = new Painel(cartoesDeOrigem);
    private Origem origem = Origem.GIT;
    private final JTextField campoUrl = Campos.texto(34, "https://github.com/usuario/repositorio.git");
    private final TextoCorrido erroDaUrl = erroCorrido();
    private final Rotulo pastaEscolhida = new Rotulo("Nenhuma pasta escolhida").corDoToken(Tokens.TEXTO2).truncavel();
    private final Botao escolherPasta = Botoes.secundario("Escolher pasta…", "pasta");
    private Path pastaDoComputador;
    private final JCheckBox levarGit = new JCheckBox("Levar a pasta .git (o histórico do Git)", false);
    private final JCheckBox levarEnv = new JCheckBox("Levar os arquivos de segredos (.env, .npmrc, chaves)", false);
    private final JComboBox<String> candidatas = new JComboBox<>();
    private final DefaultComboBoxModel<String> modeloDeCandidatas = new DefaultComboBoxModel<>();
    private List<ImportadorDeProjeto.Candidata> listaDeCandidatas = List.of();
    private final JTextField campoOutraPasta = Campos.texto(34, "/home/voce/minha-pasta");
    private final JRadioButton copiar = new JRadioButton("Copiar (a pasta de lá continua onde está)", true);
    private final JRadioButton mover = new JRadioButton("Mover (a pasta sai do lugar de antes)", false);
    private final Rotulo situacaoDasCandidatas = new Rotulo("").corDoToken(Tokens.TEXTO3).tamanho(12f);
    private boolean candidatasCarregadas;
    private final JTextField campoNomeImportar = Campos.texto(30, "meu-projeto");
    private final TextoCorrido erroDoNomeImportar = erroCorrido();
    private final Rotulo caminhoImportar = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final TextoCorrido oQueVaiAcontecer = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido falhaDeImportar = erroCorrido();
    private final AreaMonoespacada comandoDaFalha = new AreaMonoespacada("Comando para rodar no servidor", true);
    private final JScrollPane comandoRolavel = AreaMonoespacada.emRolagem(comandoDaFalha);
    private final JPanel blocoDoComando;
    private final JProgressBar progresso = new JProgressBar();
    private final Rotulo progressoTexto = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private String nomeSugeridoAntes = "";

    public DialogoNovoProjeto(Motor motor, Notificacoes avisos, CopiadorDeTexto copiador, Consumer<Concluido> aoConcluir) {
        super("Novo projeto");
        this.motor = motor;
        this.avisos = avisos;
        this.copiador = copiador;
        this.aoConcluir = aoConcluir;
        this.listaDoQueVemPronto = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);

        guiaIa.setVisible(motor.guiaIaDisponivel());
        guiaIa.putClientProperty("html.disable", Boolean.TRUE);
        campoNome.putClientProperty("html.disable", Boolean.TRUE);
        campoUrl.putClientProperty("html.disable", Boolean.TRUE);
        campoNomeImportar.putClientProperty("html.disable", Boolean.TRUE);
        campoOutraPasta.putClientProperty("html.disable", Boolean.TRUE);
        campoNome.getAccessibleContext().setAccessibleName("Nome do projeto");
        campoNomeImportar.getAccessibleContext().setAccessibleName("Nome do projeto importado");
        campoUrl.getAccessibleContext().setAccessibleName("Endereço do repositório Git");
        avisoIncerto.setVisible(false);
        candidatas.setModel(modeloDeCandidatas);
        ButtonGroup modo = new ButtonGroup();
        modo.add(copiar);
        modo.add(mover);
        progresso.setIndeterminate(true);
        progresso.setVisible(false);
        progressoTexto.setVisible(false);
        blocoDoComando = Caixa.coluna(4, comandoRolavel, Caixa.linha(0, BotaoCopiar.criar("Copiar o comando", comandoDaFalha::getText, copiador, avisos), Caixa.mola()));
        blocoDoComando.setVisible(false);
        comandoRolavel.setPreferredSize(new java.awt.Dimension(LARGURA, 64));

        corpo.add(montarPaginaDeEscolha(), Pagina.ESCOLHER.name());
        corpo.add(montarPaginaDeCriar(), Pagina.CRIAR.name());
        corpo.add(montarPaginaDeImportar(), Pagina.IMPORTAR.name());
        JComponent cabecalho = cabecalho("pasta-mais", "Novo projeto");
        JComponent topo = Caixa.coluna(10, cabecalho, corpo);
        conteudoComRodape(topo, rodape(voltar, cancelar, principal));
        botaoPadrao(principal);

        voltar.addActionListener(e -> aoVoltar());
        cancelar.addActionListener(e -> aoCancelar());
        principal.addActionListener(e -> aoClicarNoPrincipal());
        ligarCampos();
        irPara(Pagina.ESCOLHER);
    }

    // =====================================================================================================
    // Montagem
    // =====================================================================================================

    private JComponent montarPaginaDeEscolha() {
        Botao criar = Botoes.primario("Criar do zero", "pasta-mais");
        criar.addActionListener(e -> irPara(Pagina.CRIAR));
        Botao importar = Botoes.secundario("Importar", "baixar");
        importar.addActionListener(e -> irPara(Pagina.IMPORTAR));
        criar.setName("escolher-criar");
        importar.setName("escolher-importar");
        JComponent adicionar = cartao("docker", "Adicionar um container a um projeto",
            "Na página de cada projeto há o botão \"Adicionar container\": Node, Postgres, Redis ou uma imagem do Docker pelo nome. Primeiro crie ou importe o projeto.", null);
        return Caixa.coluna(10,
            paragrafo("Como você quer começar?"),
            cartao("pasta-mais", "Criar do zero",
                "Uma pasta nova com um docker-compose.yml vazio, a rede do projeto, README e STATUS. Os containers você adiciona depois.", criar),
            cartao("baixar", "Importar um projeto que já existe",
                motor.local() ? "Do GitHub (git clone) ou de uma pasta do seu computador. O que já está no projeto nunca é sobrescrito."
                    : "Do GitHub (git clone no servidor), de uma pasta do seu computador ou de uma pasta que já está no servidor. O que já está no projeto nunca é sobrescrito.",
                importar),
            adicionar);
    }

    private static JComponent cartao(String icone, String titulo, String texto, Botao botao) {
        Painel c = new Painel(new BorderLayout(0, 8)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
        c.setBorder(new EmptyBorder(12, 14, 12, 14));
        Rotulo t = new Rotulo(titulo).icone(Icones.colorido(icone, 18, botao == null ? Tokens.TEXTO3 : Tokens.DESTAQUE)).negrito();
        t.setIconTextGap(8);
        c.add(t, BorderLayout.NORTH);
        c.add(new TextoCorrido(texto, LARGURA - 32).corDoToken(Tokens.TEXTO2).tamanho(12.5f), BorderLayout.CENTER);
        if (botao != null) {
            c.add(Caixa.linha(0, botao, Caixa.mola()), BorderLayout.SOUTH);
        }
        return c;
    }

    private JComponent montarPaginaDeCriar() {
        Rotulo pergunta = new Rotulo("Nome do projeto").negrito();
        TextoCorrido dica = dicaCorrida("Letras minúsculas, números e hífen (até " + NomeDoProjeto.MAXIMO + " caracteres). É também o nome da pasta.");
        return Caixa.coluna(8, pergunta, campoNome, erroDoNome, caminhoCriar, dica, Caixa.espacoVertical(2),
            new Rotulo("O que vem pronto").negrito(), listaDoQueVemPronto, guiaIa, avisoIncerto, falhaDeCriar);
    }

    private JComponent montarPaginaDeImportar() {
        abasDeOrigem.adicionar(Origem.GIT.name(), "Repositório Git");
        abasDeOrigem.adicionar(Origem.PASTA_DO_COMPUTADOR.name(), "Pasta do meu computador");
        if (!motor.local()) {
            abasDeOrigem.adicionar(Origem.PASTA_DO_SERVIDOR.name(), "Pasta do servidor");
        }
        abasDeOrigem.selecionar(Origem.GIT.name());
        abasDeOrigem.aoSelecionar(id -> escolherOrigem(Origem.valueOf(id)));

        painelDeOrigem.add(Caixa.coluna(6, new Rotulo("Endereço do repositório").negrito(), campoUrl, erroDaUrl,
            dicaCorrida("Cole o endereço do GitHub ou do GitLab: https://… ou git@github.com:usuario/repositorio.git. Se o repositório é privado, "
                + (motor.local() ? "este computador precisa estar logado nele (gh auth login ou uma chave SSH)." : "o servidor precisa estar logado nele (gh auth login ou uma chave de deploy).")
                + " O app nunca pede nem guarda senha.")), Origem.GIT.name());

        escolherPasta.addActionListener(e -> escolherAPastaDoComputador());
        painelDeOrigem.add(Caixa.coluna(6, new Rotulo("Pasta do seu computador").negrito(), Caixa.linha(8, escolherPasta, pastaEscolhida), levarGit, levarEnv,
            dicaCorrida("Ficam de fora: node_modules (volta com o npm install), .git e os arquivos de segredos (.env, .envrc, .npmrc, *.pem, id_rsa*), "
                + "a menos que você marque. Eles guardam senhas e chaves: só leve se tiver certeza.")), Origem.PASTA_DO_COMPUTADOR.name());

        painelDeOrigem.add(Caixa.coluna(6, new Rotulo("Pasta que já está no servidor").negrito(), candidatas, situacaoDasCandidatas,
            new Rotulo("Ou escreva o caminho completo").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoOutraPasta, copiar, mover,
            dicaCorrida("Se o seu usuário não tiver permissão, o app mostra o comando exato para quem tem rodar no servidor.")), Origem.PASTA_DO_SERVIDOR.name());

        Rotulo nome = new Rotulo("Nome do projeto").negrito();
        return Caixa.coluna(8, abasDeOrigem, painelDeOrigem, Caixa.espacoVertical(2), nome, campoNomeImportar, erroDoNomeImportar, caminhoImportar,
            new Rotulo("O que vai acontecer").negrito(), oQueVaiAcontecer, progresso, progressoTexto, falhaDeImportar, blocoDoComando);
    }

    private void ligarCampos() {
        campoNome.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            dizerErro(falhaDeCriar, ""); // o usuário mexeu no que pediu: a frase do erro de antes não vale mais
            revalidarCriar();
        }));
        campoNomeImportar.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            limparAFalhaDeImportar();
            revalidarImportar();
        }));
        campoUrl.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            limparAFalhaDeImportar();
            sugerirNomeDaUrl();
            revalidarImportar();
        }));
        campoOutraPasta.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            limparAFalhaDeImportar();
            sugerirNomeDaPastaDoServidor();
            revalidarImportar();
        }));
        candidatas.addActionListener(e -> {
            sugerirNomeDaPastaDoServidor();
            revalidarImportar();
        });
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[] {levarGit, levarEnv, copiar, mover}) {
            b.addActionListener(e -> revalidarImportar());
        }
        guiaIa.addActionListener(e -> revalidarCriar());
    }

    // =====================================================================================================
    // Navegação
    // =====================================================================================================

    private void irPara(Pagina nova) {
        pagina = nova;
        cartoes.show(corpo, nova.name());
        voltar.setVisible(nova != Pagina.ESCOLHER);
        principal.setVisible(nova != Pagina.ESCOLHER);
        principal.setText(nova == Pagina.CRIAR ? "Criar projeto" : "Importar projeto");
        switch (nova) {
            case CRIAR -> revalidarCriar();
            case IMPORTAR -> {
                escolherOrigem(origem);
                revalidarImportar();
            }
            case ESCOLHER -> { }
        }
        rolarParaOTopo();
        reajustar();
        Fundo.naEdt(() -> {
            if (!jaFechou()) {
                if (nova == Pagina.CRIAR) {
                    campoNome.requestFocusInWindow();
                } else if (nova == Pagina.IMPORTAR) {
                    campoUrl.requestFocusInWindow();
                }
            }
        });
    }

    private void aoVoltar() {
        if (ocupado) {
            return;
        }
        incerto = false;
        talvezConcluido = null;
        avisoIncerto.setVisible(false);
        irPara(Pagina.ESCOLHER);
    }

    /**
     * Esc, o X e o botão. Parado: fecha. Importando: o primeiro pedido PARA a importação; o segundo (o botão já mudou para "Fechar mesmo assim") fecha o
     * diálogo e deixa a tarefa terminar em segundo plano. Criando: o pedido já saiu e não se desfaz, então o botão é "Fechar mesmo assim" desde o
     * começo. O diálogo nunca fica preso.
     */
    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
            return;
        }
        if (pagina == Pagina.IMPORTAR && !pediuParar) {
            pedirParaParar();
            return;
        }
        fecharEmSegundoPlano();
    }

    private void fecharEmSegundoPlano() {
        if (pagina == Pagina.IMPORTAR) {
            avisos.info("A importação foi mandada parar e termina em segundo plano. Confira a lista de projetos daqui a pouco.");
        } else {
            avisos.info("O pedido já saiu: o projeto pode ser criado mesmo com esta janela fechada. Confira a lista de projetos.");
        }
        fechar();
    }

    private void aoClicarNoPrincipal() {
        if (ocupado || !principal.isEnabled()) {
            return;
        }
        if (incerto && talvezConcluido != null) {
            Concluido c = talvezConcluido;
            fechar();
            aoConcluir.accept(c);
            return;
        }
        if (pagina == Pagina.CRIAR) {
            criar();
        } else if (pagina == Pagina.IMPORTAR) {
            importar();
        }
    }

    private void definirOcupado(boolean sim, String texto) {
        ocupado = sim;
        principal.definirOcupado(sim);
        voltar.setEnabled(!sim);
        campoNome.setEnabled(!sim);
        campoNomeImportar.setEnabled(!sim);
        campoUrl.setEnabled(!sim);
        guiaIa.setEnabled(!sim);
        escolherPasta.setEnabled(!sim);
        abasDeOrigem.setEnabled(!sim);
        // na importação o botão PARA; na criação o pedido já saiu e não se desfaz (só se fecha a janela); nos dois casos ele nunca fica preso
        cancelar.setEnabled(true);
        cancelar.setText(!sim ? "Cancelar" : pagina == Pagina.IMPORTAR && !pediuParar ? "Parar" : "Fechar mesmo assim");
        progresso.setVisible(sim && pagina == Pagina.IMPORTAR);
        progressoTexto.setVisible(sim && pagina == Pagina.IMPORTAR);
        progressoTexto.setText(texto == null ? "" : texto);
        reajustar();
    }

    // =====================================================================================================
    // Criar do zero
    // =====================================================================================================

    private void revalidarCriar() {
        String texto = campoNome.getText();
        NomeDoProjeto.Resultado r = NomeDoProjeto.conferir(texto);
        String problema = r.mensagem();
        if (r.valido() && motor.local() && NomeDoProjeto.reservado(texto)) {
            problema = "Esse nome é reservado pela infraestrutura do lab.";
        }
        boolean ok = r.valido() && problema.isEmpty();
        dizerErro(erroDoNome, problema);
        caminhoCriar.setText("Será criado em: " + motor.caminhoDoProjeto(ok ? texto : "<nome>"));
        listaDoQueVemPronto.setText(oQueVemPronto(ok ? texto : "<nome>"));
        if (!incerto) {
            principal.setEnabled(ok && !ocupado);
        }
        reajustar();
    }

    private String oQueVemPronto(String nome) {
        StringBuilder sb = new StringBuilder();
        sb.append("• docker-compose.yml vazio (os containers entram depois)\n");
        sb.append("• a rede do projeto (").append(nome).append("_default)\n");
        sb.append("• README.md e STATUS.md\n• .gitignore e .env.example");
        if (motor.guiaIaDisponivel() && guiaIa.isSelected()) {
            sb.append("\n• CLAUDE.md com o guia para o Claude");
        }
        return sb.toString();
    }

    private void criar() {
        String nome = campoNome.getText();
        if (!NomeDoProjeto.valido(nome)) {
            return;
        }
        Boolean guia = motor.guiaIaDisponivel() ? Boolean.valueOf(guiaIa.isSelected()) : null;
        dizerErro(falhaDeCriar, "");
        avisoIncerto.setVisible(false);
        definirOcupado(true, "Criando " + nome + "…");
        Tarefas.rodar(() -> motor.criar(nome, guia), desfecho -> aoTerminarDeCriar(nome, desfecho), falha -> {
            definirOcupado(false, "");
            if (jaFechou()) {
                avisos.erro("Criar " + nome + ": " + MensagensDeErro.de(falha));
                return;
            }
            dizerErro(falhaDeCriar, MensagensDeErro.de(falha));
            revalidarCriar();
        });
    }

    private void aoTerminarDeCriar(String nome, FluxoDeCriacao.Desfecho d) {
        definirOcupado(false, "");
        if (jaFechou()) {
            // o usuário fechou a janela enquanto o pedido estava em voo: o resultado chega do mesmo jeito, pela janela principal e pelos avisos
            switch (d.tipo()) {
                case CRIADO -> aoConcluir.accept(new Concluido(nome, d.mensagem(), d.avisos(), false));
                case TALVEZ -> aoConcluir.accept(new Concluido(nome, d.mensagem(), d.avisos(), true));
                case CANCELADO -> { }
                case FALHOU, NAO_AUTORIZADO -> avisos.erro("Criar " + nome + ": " + d.mensagem());
            }
            return;
        }
        switch (d.tipo()) {
            case CRIADO -> {
                fechar();
                aoConcluir.accept(new Concluido(nome, d.mensagem(), d.avisos(), false));
            }
            case TALVEZ -> {
                incerto = true;
                talvezConcluido = new Concluido(nome, d.mensagem(), d.avisos(), true);
                avisoIncerto.setText(d.mensagem());
                avisoIncerto.setVisible(true);
                principal.setText("Ver a lista");
                principal.setEnabled(true);
                campoNome.setEnabled(false);
                guiaIa.setEnabled(false);
                reajustar();
            }
            case CANCELADO -> revalidarCriar();
            case FALHOU, NAO_AUTORIZADO -> {
                dizerErro(falhaDeCriar, d.mensagem());
                revalidarCriar();
            }
        }
    }

    // =====================================================================================================
    // Importar
    // =====================================================================================================

    private void escolherOrigem(Origem nova) {
        origem = nova;
        cartoesDeOrigem.show(painelDeOrigem, nova.name());
        if (nova == Origem.PASTA_DO_SERVIDOR) {
            carregarCandidatas();
        }
        revalidarImportar();
        reajustar();
    }

    private void escolherAPastaDoComputador() {
        // o seletor do sistema abre uma janela própria: vale na EDT (é o clique do usuário), e só devolve o caminho
        Optional<Path> p = motor.escolherPasta();
        if (p.isEmpty()) {
            return;
        }
        pastaDoComputador = p.get();
        pastaEscolhida.setText(pastaDoComputador.toString());
        pastaEscolhida.setToolTipText(pastaDoComputador.toString());
        String sugestao = NomeDoProjeto.sugerirDe(pastaDoComputador.getFileName() == null ? "" : pastaDoComputador.getFileName().toString());
        usarNomeSugerido(sugestao);
        revalidarImportar();
    }

    private void carregarCandidatas() {
        if (candidatasCarregadas) {
            return;
        }
        candidatasCarregadas = true;
        situacaoDasCandidatas.setText("Procurando pastas no servidor…");
        Tarefas.rodar(motor::candidatas, lista -> {
            listaDeCandidatas = lista;
            modeloDeCandidatas.removeAllElements();
            for (ImportadorDeProjeto.Candidata c : lista) {
                modeloDeCandidatas.addElement(TextoSeguro.limparLinha(c.nome()) + "   (" + TextoSeguro.limparLinha(c.caminho()) + ")");
            }
            situacaoDasCandidatas.setText(lista.isEmpty() ? "Não achei pastas na sua pasta pessoal do servidor. Escreva o caminho completo abaixo."
                : lista.size() + " pasta(s) na sua pasta pessoal do servidor.");
            if (!lista.isEmpty()) {
                candidatas.setSelectedIndex(0);
            }
            sugerirNomeDaPastaDoServidor();
            revalidarImportar();
        }, falha -> {
            candidatasCarregadas = false;
            situacaoDasCandidatas.setText(falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha) + " Escreva o caminho completo abaixo.");
            revalidarImportar();
        });
    }

    private String origemDoServidor() {
        String livre = campoOutraPasta.getText().strip();
        if (!livre.isEmpty()) {
            return livre;
        }
        int i = candidatas.getSelectedIndex();
        return i >= 0 && i < listaDeCandidatas.size() ? listaDeCandidatas.get(i).caminho() : "";
    }

    private void limparAFalhaDeImportar() {
        if (!ocupado) {
            dizerErro(falhaDeImportar, "");
            blocoDoComando.setVisible(false);
        }
    }

    private void sugerirNomeDaUrl() {
        UrlDeGit.Resultado r = UrlDeGit.conferir(campoUrl.getText());
        dizerErro(erroDaUrl, campoUrl.getText().isEmpty() ? "" : r.mensagem());
        if (r.valida()) {
            usarNomeSugerido(r.url().nomeSugerido());
        }
    }

    private void sugerirNomeDaPastaDoServidor() {
        String o = origemDoServidor();
        if (!o.isEmpty()) {
            usarNomeSugerido(NomeDoProjeto.sugerirDe(o.substring(o.lastIndexOf('/') + 1)));
        }
    }

    /** Põe a sugestão no campo do nome, a menos que o usuário já tenha escrito o dele. */
    private void usarNomeSugerido(String sugestao) {
        String atual = campoNomeImportar.getText();
        if (sugestao.isEmpty() || !(atual.isEmpty() || atual.equals(nomeSugeridoAntes))) {
            return;
        }
        nomeSugeridoAntes = sugestao;
        campoNomeImportar.setText(sugestao);
    }

    private boolean entradaDaOrigemVale() {
        return switch (origem) {
            case GIT -> UrlDeGit.conferir(campoUrl.getText()).valida();
            case PASTA_DO_COMPUTADOR -> pastaDoComputador != null;
            case PASTA_DO_SERVIDOR -> !origemDoServidor().isEmpty();
        };
    }

    private void revalidarImportar() {
        String texto = campoNomeImportar.getText();
        NomeDoProjeto.Resultado r = NomeDoProjeto.conferir(texto);
        String problema = r.mensagem();
        if (r.valido() && motor.local() && NomeDoProjeto.reservado(texto)) {
            problema = "Esse nome é reservado pela infraestrutura do lab.";
        }
        boolean nomeOk = r.valido() && problema.isEmpty();
        dizerErro(erroDoNomeImportar, problema);
        caminhoImportar.setText("Será criado em: " + motor.caminhoDoProjeto(nomeOk ? texto : "<nome>"));
        oQueVaiAcontecer.setText(resumoDoQueVaiAcontecer(nomeOk ? texto : "<nome>"));
        if (pagina == Pagina.IMPORTAR) {
            principal.setEnabled(nomeOk && entradaDaOrigemVale() && !ocupado);
        }
        reajustar();
    }

    private String resumoDoQueVaiAcontecer(String nome) {
        String destino = motor.caminhoDoProjeto(nome);
        String fim = "• Cria um docker-compose.yml vazio SÓ se o projeto não tiver nenhum, e a rede " + nome + "_default. Nada do que já está no projeto é sobrescrito.";
        return switch (origem) {
            case GIT -> "• Roda git clone " + (UrlDeGit.conferir(campoUrl.getText()).valida() ? campoUrl.getText() : "<endereço>") + " em " + destino
                + (motor.local() ? ", neste computador" : ", no servidor") + ", sem pedir senha.\n" + fim;
            case PASTA_DO_COMPUTADOR -> "• Copia " + (pastaDoComputador == null ? "<a pasta>" : pastaDoComputador.toString()) + " para " + destino + ", sem node_modules"
                + (levarGit.isSelected() ? "" : ", sem .git") + (levarEnv.isSelected() ? ", COM os arquivos de segredos" : ", sem os arquivos de segredos (.env, chaves .pem e id_rsa, .npmrc)")
                + ". A cópia vai primeiro para uma pasta provisória e só vira o projeto no fim.\n" + fim;
            case PASTA_DO_SERVIDOR -> {
                String o = origemDoServidor().isEmpty() ? "<a pasta>" : origemDoServidor();
                yield "• " + (mover.isSelected() ? "Move " : "Copia ") + o + " para " + destino + ".\n  Comando: "
                    + ImportadorDeProjeto.comandoParaCopiar(mover.isSelected() ? br.com.labdesktop.projetos.ComandosDeProjeto.mover(o, destino)
                        : br.com.labdesktop.projetos.ComandosDeProjeto.copiar(o, destino)) + "\n" + fim;
            }
        };
    }

    private void importar() {
        String nome = campoNomeImportar.getText();
        if (!NomeDoProjeto.valido(nome) || !entradaDaOrigemVale()) {
            return;
        }
        dizerErro(falhaDeImportar, "");
        blocoDoComando.setVisible(false);
        pediuParar = false;
        cancelamentoDoEnvio = new Cancelamento();
        Cancelamento cancelamento = cancelamentoDoEnvio;
        ImportadorDeProjeto.Observador obs = (frase, fracao) -> Fundo.naEdt(() -> {
            if (ocupado && pagina == Pagina.IMPORTAR) {
                progressoTexto.setText(frase);
                if (fracao >= 0 && fracao <= 1) {
                    progresso.setIndeterminate(false);
                    progresso.setMaximum(1000);
                    progresso.setValue((int) Math.round(fracao * 1000));
                } else {
                    progresso.setIndeterminate(true);
                }
            }
        });
        Origem quem = origem;
        UrlDeGit url = quem == Origem.GIT ? UrlDeGit.conferir(campoUrl.getText()).url() : null;
        Path pasta = pastaDoComputador;
        FiltroDeEnvio filtro = new FiltroDeEnvio(levarGit.isSelected(), levarEnv.isSelected());
        String doServidor = origemDoServidor();
        ImportadorDeProjeto.Modo modo = mover.isSelected() ? ImportadorDeProjeto.Modo.MOVER : ImportadorDeProjeto.Modo.COPIAR;
        definirOcupado(true, "Começando…");
        progresso.setIndeterminate(true);
        Tarefas.rodar(() -> {
            threadDoTrabalho = Thread.currentThread();
            try {
                if (cancelamento.cancelado()) { // o usuário clicou em Parar antes de a tarefa começar
                    throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado. Nada foi criado.");
                }
                return switch (quem) {
                    case GIT -> motor.clonar(url, nome, obs, cancelamento);
                    case PASTA_DO_COMPUTADOR -> motor.importarPasta(pasta, nome, filtro, obs, cancelamento);
                    case PASTA_DO_SERVIDOR -> motor.adotar(doServidor, nome, modo, obs, cancelamento);
                };
            } finally {
                threadDoTrabalho = null;
            }
        }, r -> {
            definirOcupado(false, "");
            fechar();
            aoConcluir.accept(new Concluido(r.nome(), "Projeto " + r.nome() + " importado.", r.avisos(), false));
        }, falha -> {
            definirOcupado(false, "");
            if (jaFechou()) {
                avisos.atencao("Importar " + nome + ": " + (falha instanceof FalhaDoProjeto f ? f.mensagem()
                    : falha instanceof InterruptedException ? "cancelado." : MensagensDeErro.de(falha)));
                return;
            }
            if (falha instanceof FalhaDoProjeto f) {
                dizerErro(falhaDeImportar, f.mensagem());
                if (f.comandoParaCopiar() != null) {
                    comandoDaFalha.setText(f.comandoParaCopiar());
                    blocoDoComando.setVisible(true);
                }
            } else if (falha instanceof InterruptedException) {
                dizerErro(falhaDeImportar, "Cancelado.");
            } else {
                dizerErro(falhaDeImportar, MensagensDeErro.de(falha));
            }
            revalidarImportar();
        });
    }

    /**
     * "Parar": o envio para no próximo bloco, o clone é interrompido, e o que ficou pela metade é apagado. O botão passa a "Fechar mesmo assim" (um
     * segundo clique, ou Esc, fecha a janela e deixa a limpeza terminar em segundo plano).
     */
    private void pedirParaParar() {
        pediuParar = true;
        progressoTexto.setText("Parando e apagando o que ficou pela metade… (se demorar, use \"Fechar mesmo assim\")");
        cancelar.setText("Fechar mesmo assim");
        cancelar.setEnabled(true);
        cancelamentoDoEnvio.cancelar();
        Thread t = threadDoTrabalho;
        if (t != null) {
            t.interrupt();
        }
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public Pagina paginaAtual() {
        return pagina;
    }

    public Origem origemAtual() {
        return origem;
    }

    public boolean ocupado() {
        return ocupado;
    }

    /** O diálogo já fechou (pelo botão, por Esc, pelo X ou ao concluir). */
    public boolean fechou() {
        return jaFechou();
    }

    public JTextField campoNome() {
        return campoNome;
    }

    public JTextField campoNomeImportar() {
        return campoNomeImportar;
    }

    public JTextField campoUrl() {
        return campoUrl;
    }

    public JCheckBox caixaGuiaIa() {
        return guiaIa;
    }

    public JCheckBox caixaLevarGit() {
        return levarGit;
    }

    public JCheckBox caixaLevarEnv() {
        return levarEnv;
    }

    public JRadioButton opcaoMover() {
        return mover;
    }

    public JRadioButton opcaoCopiar() {
        return copiar;
    }

    public JTextField campoOutraPasta() {
        return campoOutraPasta;
    }

    public JComboBox<String> listaDeCandidatas() {
        return candidatas;
    }

    public Botao botaoPrincipal() {
        return principal;
    }

    public Botao botaoVoltar() {
        return voltar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoEscolherPasta() {
        return escolherPasta;
    }

    public Abas abasDeOrigem() {
        return abasDeOrigem;
    }

    public String textoDoErroDoNome() {
        return erroDoNome.getText();
    }

    public String textoDoErroDoNomeImportar() {
        return erroDoNomeImportar.getText();
    }

    public String textoDoErroDaUrl() {
        return erroDaUrl.getText();
    }

    public String textoDaFalhaDeCriar() {
        return falhaDeCriar.getText();
    }

    public String textoDaFalhaDeImportar() {
        return falhaDeImportar.getText();
    }

    public String textoDoAvisoIncerto() {
        return avisoIncerto.isVisible() ? avisoIncerto.getText() : "";
    }

    public String textoDoCaminho() {
        return caminhoCriar.getText();
    }

    public String textoDoCaminhoDaImportacao() {
        return caminhoImportar.getText();
    }

    public String textoDoQueVemPronto() {
        return listaDoQueVemPronto.getText();
    }

    public String textoDoQueVaiAcontecer() {
        return oQueVaiAcontecer.getText();
    }

    public String textoDoProgresso() {
        return progressoTexto.isVisible() ? progressoTexto.getText() : "";
    }

    public String comandoMostrado() {
        return blocoDoComando.isVisible() ? comandoDaFalha.getText() : "";
    }

    public List<String> botoesVisiveis() {
        List<String> l = new ArrayList<>();
        for (Botao b : new Botao[] {voltar, cancelar, principal}) {
            if (b.isVisible()) {
                l.add(b.getText());
            }
        }
        return l;
    }

    /** Escolhe uma pasta como se o usuário tivesse usado o seletor (os testes e a captura de tela). */
    public void usarPastaDoComputador(Path p) {
        pastaDoComputador = p;
        pastaEscolhida.setText(p.toString());
        usarNomeSugerido(NomeDoProjeto.sugerirDe(p.getFileName() == null ? "" : p.getFileName().toString()));
        revalidarImportar();
    }

    public void escolherAPagina(Pagina p) {
        irPara(p);
    }

    public void escolherAOrigem(Origem o) {
        abasDeOrigem.selecionar(o.name());
        escolherOrigem(o);
    }

    @Override
    protected void antesDeMostrar() {
        // o foco inicial vai para o campo da página aberta (a de escolha não tem campo)
    }

    @Override
    protected void aoFechar() {
        Thread t = threadDoTrabalho;
        if (t != null && ocupado) {
            t.interrupt();
        }
    }
}
