package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cloudflare.ComposeComEdge;
import br.com.labdesktop.cloudflare.NomeDoEndereco;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;

/**
 * "Adicionar domínio": o assistente em passos curtos para dar a um projeto um endereço novo na internet (um subdomínio do domínio que o usuário tem na Cloudflare): <b>1 Conta</b>,
 * <b>2 Domínio</b>, <b>3 Endereço</b>, <b>4 Serviço</b> (qual container e qual porta) e <b>5 Confirmar</b>. Os quatro primeiros só PERGUNTAM e leem (nada é escrito); o quinto é o
 * diálogo {@link DialogoPublicar}, que mostra a prévia de TUDO o que será criado e só executa depois do clique: é o mesmo motor de "Publicar na internet", sem repetir a lógica.
 * Sem Cloudflare conectada, o passo 1 leva ao "Conectar a Cloudflare" e volta.
 */
public final class DialogoAdicionarDominio extends Dialogo {

    /** O que o assistente sabe da conexão com a Cloudflare. */
    public record Conexao(String conta, String dominio, String tunel, String prefixo) { }

    /** O que o assistente precisa de fora. Tudo BLOQUEIA (menos {@code conexao} e {@code podeRecriar}): só roda fora da EDT. */
    public interface Motor {
        String projeto();

        /** A conta e o domínio conectados, ou vazio se não há Cloudflare conectada. Só lê o cofre local. */
        Optional<Conexao> conexao();

        /** Abre o "Conectar a Cloudflare" (ou "Trocar a conta"); {@code depois} roda (na EDT) quando ele fecha. */
        void conectar(Runnable depois);

        /** Os serviços do {@code docker-compose.yml} do projeto (só leitura). */
        List<ComposeComEdge.Servico> servicos() throws FalhaDoProjeto;

        /** O projeto pode ser recriado daqui (o botão Iniciar existe)? */
        boolean podeRecriar();
    }

    private enum Passo {
        CONTA("Conta"), DOMINIO("Domínio"), ENDERECO("Endereço"), SERVICO("Serviço"), CONFIRMAR("Confirmar");

        private final String rotulo;

        Passo(String rotulo) {
            this.rotulo = rotulo;
        }
    }

    private static final int LARGURA = 520;

    private final Motor motor;
    private final Consumer<DialogoPublicar.Predefinicao> aoConfirmar;
    private final Runnable aoCancelar;
    private final CardLayout cartoes = new CardLayout();
    private final javax.swing.JPanel pilha = new br.com.labdesktop.ui.componentes.Painel(cartoes);
    private final List<Rotulo> trilha = new ArrayList<>();
    private final Rotulo tituloDoPasso = new Rotulo("").negrito().tamanho(14f);
    private final Botao voltar = Botoes.secundario("Voltar", "voltar");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Botao continuar = Botoes.primario("Continuar", "avancar");

    // passo 1 e 2
    private final Selo seloDaConta = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido textoDaConta = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao conectar = Botoes.primario("Conectar a Cloudflare…", "link");
    private final Botao trocarConta = Botoes.secundario("Trocar a conta…", "link");
    private final Rotulo dominioEscolhido = new Rotulo("").mono(14f).negrito();
    private final TextoCorrido textoDoDominio = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao trocarDominio = Botoes.secundario("Trocar o domínio…", "link");

    // passo 3
    private final JTextField campoNome = Campos.mono(24, "lab-meu-projeto");
    private final Rotulo sufixoDoNome = new Rotulo("").mono(12.5f).corDoToken(Tokens.TEXTO2);
    private final TextoCorrido enderecoFinal = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido problemaDoNome = erroCorrido();

    // passo 4
    private final JComboBox<String> caixaDeServicos = new JComboBox<>();
    private final JTextField campoPorta = Campos.mono(7, "3000");
    private final JTextField campoContainer = Campos.mono(24, "meu-projeto-app");
    private final JCheckBox recriar = new JCheckBox("Recriar o projeto agora (docker compose up -d) para o container entrar na rede edge", true);
    private final TextoCorrido situacaoDoServico = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido problemaDoServico = erroCorrido();

    private final AtomicInteger geracao = new AtomicInteger();
    private Passo passo = Passo.CONTA;
    private Optional<Conexao> conexao = Optional.empty();
    private List<ComposeComEdge.Servico> servicos = List.of();
    private boolean servicosLidos;
    private boolean ajustando;
    private boolean entreguePara;
    private final DialogoPublicar.Predefinicao inicial;
    private String ultimoNomeSugerido = "";
    private String ultimoContainerSugerido = "";

    /**
     * @param inicial     de onde vem o Voltar do último passo: tudo já preenchido e o assistente nasce no passo 4; {@code null} na primeira vez
     * @param aoConfirmar o usuário terminou os quatro passos: recebe o que ele escolheu e abre o último (a prévia e a confirmação)
     * @param aoCancelar  o usuário desistiu (Cancelar, Esc ou o X)
     */
    public DialogoAdicionarDominio(Motor motor, DialogoPublicar.Predefinicao inicial, Consumer<DialogoPublicar.Predefinicao> aoConfirmar, Runnable aoCancelar) {
        super("Adicionar domínio");
        this.motor = motor;
        this.inicial = inicial;
        this.aoConfirmar = aoConfirmar;
        this.aoCancelar = aoCancelar == null ? () -> { } : aoCancelar;
        for (Passo p : Passo.values()) {
            Rotulo r = new Rotulo((p.ordinal() + 1) + ". " + p.rotulo).tamanho(12f).corDoToken(Tokens.TEXTO3);
            trilha.add(r);
        }
        for (JTextField c : List.of(campoNome, campoPorta, campoContainer)) {
            c.putClientProperty("html.disable", Boolean.TRUE);
        }
        campoNome.getAccessibleContext().setAccessibleName("Nome do endereço");
        campoPorta.getAccessibleContext().setAccessibleName("Porta de dentro do container");
        campoContainer.getAccessibleContext().setAccessibleName("Nome do container");
        caixaDeServicos.getAccessibleContext().setAccessibleName("Serviço do projeto");
        caixaDeServicos.setName("servicos");
        recriar.putClientProperty("html.disable", Boolean.TRUE);
        recriar.setVisible(motor.podeRecriar());
        recriar.setSelected(motor.podeRecriar());
        for (JComponent c : List.of(problemaDoNome, problemaDoServico, situacaoDoServico, enderecoFinal, trocarConta, trocarDominio)) {
            c.setVisible(false);
        }

        pilha.add(cartaoDaConta(), Passo.CONTA.name());
        pilha.add(cartaoDoDominio(), Passo.DOMINIO.name());
        pilha.add(cartaoDoEndereco(), Passo.ENDERECO.name());
        pilha.add(cartaoDoServico(), Passo.SERVICO.name());
        JComponent topo = Caixa.coluna(10, cabecalho("externo", "Adicionar domínio"), Caixa.linha(12, trilha.toArray(JComponent[]::new)),
            new Rotulo("Projeto " + motor.projeto()).corDoToken(Tokens.TEXTO3).tamanho(12.5f));
        conteudoComRodape(Caixa.coluna(12, topo, tituloDoPasso, pilha), rodape(voltar, cancelar, continuar));
        botaoPadrao(continuar);
        voltar.addActionListener(e -> irPara(Passo.values()[passo.ordinal() - 1]));
        continuar.addActionListener(e -> avancar());
        cancelar.addActionListener(e -> aoCancelar());
        conectar.addActionListener(e -> motor.conectar(this::reler));
        trocarConta.addActionListener(e -> motor.conectar(this::reler));
        trocarDominio.addActionListener(e -> motor.conectar(this::reler));
        SimplesOuvinteDeDocumento nomeMudou = new SimplesOuvinteDeDocumento(this::aoMudarONome);
        campoNome.getDocument().addDocumentListener(nomeMudou);
        SimplesOuvinteDeDocumento servicoMudou = new SimplesOuvinteDeDocumento(this::aoMudarOServico);
        campoPorta.getDocument().addDocumentListener(servicoMudou);
        campoContainer.getDocument().addDocumentListener(servicoMudou);
        caixaDeServicos.addActionListener(e -> aoEscolherOServico());
        reler();
        irPara(inicial != null && conexao.isPresent() ? Passo.SERVICO : Passo.CONTA);
    }

    // =====================================================================================================
    // Os cartões
    // =====================================================================================================

    private JComponent cartaoDaConta() {
        return Caixa.coluna(10, Caixa.linha(8, seloDaConta, Caixa.mola()), textoDaConta, Caixa.linha(8, conectar, trocarConta, Caixa.mola()));
    }

    private JComponent cartaoDoDominio() {
        return Caixa.coluna(10, new Rotulo("Domínio").corDoToken(Tokens.TEXTO3).tamanho(12f), dominioEscolhido, textoDoDominio, Caixa.linha(8, trocarDominio, Caixa.mola()));
    }

    private JComponent cartaoDoEndereco() {
        return Caixa.coluna(10,
            paragrafo("Escolha o nome do endereço. Ele fica à frente do domínio, num nível só (sem ponto): por exemplo lab-meu-projeto.example.com."),
            Caixa.linha(4, new Rotulo("Endereço").corDoToken(Tokens.TEXTO2).tamanho(12.5f), Caixa.espaco(4), campoNome, sufixoDoNome, Caixa.mola()),
            enderecoFinal, problemaDoNome);
    }

    private JComponent cartaoDoServico() {
        return Caixa.coluna(10,
            paragrafo("Qual parte do projeto vai para a internet? Escolha o serviço do docker-compose.yml e a porta que ele usa DENTRO do container."),
            Caixa.linha(8, new Rotulo("Serviço").corDoToken(Tokens.TEXTO2).tamanho(12.5f), caixaDeServicos,
                new Rotulo("porta do container").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoPorta, Caixa.mola()),
            Caixa.linha(8, new Rotulo("Nome do container").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoContainer, Caixa.mola()),
            recriar, situacaoDoServico, problemaDoServico);
    }

    // =====================================================================================================
    // Passos
    // =====================================================================================================

    /** Lê de novo a conexão (o cofre local) e atualiza o que depende dela. Na EDT. */
    private void reler() {
        if (jaFechou()) {
            return;
        }
        conexao = motor.conexao();
        if (conexao.isPresent()) {
            Conexao c = conexao.get();
            seloDaConta.atualizar("CONECTADA", Gravidade.OK, null);
            textoDaConta.setText("Conta da Cloudflare: " + c.conta() + (c.tunel().isEmpty() ? "." : ". Túnel: " + c.tunel() + "."));
            conectar.setVisible(false);
            trocarConta.setVisible(true);
            dominioEscolhido.setText(c.dominio());
            textoDoDominio.setText("Os endereços ficam sob este domínio, num nível só (nome." + c.dominio() + "). Para usar outro domínio, troque a conta ou a escolha de domínio.");
            trocarDominio.setVisible(true);
            sufixoDoNome.setText("." + c.dominio());
            if (campoNome.getText().isEmpty() || campoNome.getText().equals(ultimoNomeSugerido)) {
                ajustando = true;
                try {
                    String sugestao = inicial != null ? inicial.nome() : NomeDoEndereco.sugerir(c.prefixo(), motor.projeto());
                    campoNome.setText(sugestao);
                    ultimoNomeSugerido = NomeDoEndereco.sugerir(c.prefixo(), motor.projeto());
                } finally {
                    ajustando = false;
                }
            }
            mostrarOEnderecoFinal();
        } else {
            seloDaConta.atualizar("NÃO CONECTADA", Gravidade.NEUTRO, null);
            textoDaConta.setText("Para dar ao projeto um endereço novo, o app precisa da sua conta da Cloudflare. Ele usa um token de API que você mesmo cria, com as permissões mínimas, "
                + "e o guarda só no cofre cifrado. Nada é escrito na Cloudflare nesta etapa.");
            conectar.setVisible(true);
            trocarConta.setVisible(false);
            dominioEscolhido.setText("(nenhum ainda)");
            textoDoDominio.setText("Conecte a conta no passo 1 para escolher o domínio.");
            trocarDominio.setVisible(false);
        }
        atualizarBotoes();
        reajustar();
    }

    private void irPara(Passo novo) {
        passo = novo;
        cartoes.show(pilha, novo.name());
        for (int i = 0; i < trilha.size(); i++) {
            Rotulo r = trilha.get(i);
            r.corDoToken(i == novo.ordinal() ? Tokens.TEXTO : Tokens.TEXTO3);
            r.negrito(i == novo.ordinal());
        }
        tituloDoPasso.setText("Passo " + (novo.ordinal() + 1) + " de 5: " + switch (novo) {
            case CONTA -> "a conta da Cloudflare";
            case DOMINIO -> "o domínio";
            case ENDERECO -> "o nome do endereço";
            case SERVICO -> "o que vai para a internet";
            case CONFIRMAR -> "confira e confirme";
        });
        if (novo == Passo.SERVICO && !servicosLidos) {
            lerOsServicos();
        }
        voltar.setVisible(novo != Passo.CONTA);
        atualizarBotoes();
        reajustar();
    }

    private void avancar() {
        if (!continuar.isEnabled() || jaFechou()) {
            return;
        }
        if (passo == Passo.SERVICO) {
            DialogoPublicar.Predefinicao p = predefinicao();
            if (p == null) {
                return;
            }
            entreguePara = true;
            fechar();
            aoConfirmar.accept(p);
            return;
        }
        irPara(Passo.values()[passo.ordinal() + 1]);
    }

    private void atualizarBotoes() {
        boolean conectada = conexao.isPresent();
        boolean pode = switch (passo) {
            case CONTA, DOMINIO -> conectada;
            case ENDERECO -> NomeDoEndereco.valido(campoNome.getText());
            case SERVICO -> servicoEscolhido() != null && numero(campoPorta.getText()) != null && containerValido() && problemaDoServico.getText().isEmpty();
            case CONFIRMAR -> false;
        };
        continuar.setEnabled(pode);
        continuar.setText(passo == Passo.SERVICO ? "Ver o que será criado" : "Continuar");
        continuar.setToolTipText(pode ? null : switch (passo) {
            case CONTA, DOMINIO -> "Conecte a Cloudflare para continuar.";
            case ENDERECO -> "Escreva um nome válido para o endereço.";
            case SERVICO -> "Escolha o serviço e confira a porta e o nome do container.";
            case CONFIRMAR -> null;
        });
    }

    // ---- passo 3: o nome ----

    private void aoMudarONome() {
        if (ajustando) {
            return;
        }
        mostrarOEnderecoFinal();
        atualizarBotoes();
        reajustar();
    }

    /** Confere o nome e mostra o endereço que ele vira (ou a frase do que está errado). */
    private void mostrarOEnderecoFinal() {
        String nome = campoNome.getText();
        NomeDoEndereco.Resultado r = NomeDoEndereco.conferir(nome);
        String dominio = conexao.map(Conexao::dominio).orElse("");
        enderecoFinal.setText(r.valido() && !dominio.isEmpty() ? "Endereço público: " + NomeDoEndereco.urlPublica(nome + "." + dominio) : "");
        enderecoFinal.setVisible(r.valido() && !dominio.isEmpty());
        dizerErro(problemaDoNome, r.valido() || r.mensagem().isEmpty() ? "" : r.mensagem());
    }

    // ---- passo 4: o serviço ----

    private void lerOsServicos() {
        servicosLidos = true;
        situacaoDoServico.setText("Lendo o docker-compose.yml do projeto…");
        situacaoDoServico.setVisible(true);
        int minha = geracao.incrementAndGet();
        Tarefas.rodar(motor::servicos, lista -> {
            if (jaFechou() || minha != geracao.get()) {
                return;
            }
            situacaoDoServico.setVisible(false);
            servicos = lista;
            ajustando = true;
            try {
                caixaDeServicos.removeAllItems();
                for (ComposeComEdge.Servico s : lista) {
                    caixaDeServicos.addItem(s.nome());
                }
            } finally {
                ajustando = false;
            }
            if (lista.isEmpty()) {
                dizerErro(problemaDoServico, "Não achei serviços no docker-compose.yml do projeto (ele não existe, não está em UTF-8 ou está num formato que o app não lê). Adicione um container ao projeto antes.");
                atualizarBotoes();
                reajustar();
                return;
            }
            if (inicial != null) {
                aplicarOInicial();
            } else {
                aoEscolherOServico();
            }
        }, falha -> {
            if (jaFechou() || minha != geracao.get()) {
                return;
            }
            situacaoDoServico.setVisible(false);
            servicosLidos = false;
            dizerErro(problemaDoServico, falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            atualizarBotoes();
            reajustar();
        });
    }

    private ComposeComEdge.Servico servicoEscolhido() {
        int i = caixaDeServicos.getSelectedIndex();
        return i >= 0 && i < servicos.size() ? servicos.get(i) : null;
    }

    private void aplicarOInicial() {
        ajustando = true;
        try {
            int indice = 0;
            for (int i = 0; i < servicos.size(); i++) {
                if (servicos.get(i).nome().equals(inicial.servico())) {
                    indice = i;
                }
            }
            caixaDeServicos.setSelectedIndex(indice);
            ComposeComEdge.Servico s = servicos.get(indice);
            campoContainer.setText(inicial.container());
            campoContainer.setEnabled(s.containerName() == null);
            campoPorta.setText(String.valueOf(inicial.porta()));
            recriar.setSelected(inicial.recriar() && motor.podeRecriar());
            ultimoContainerSugerido = s.containerName() != null ? s.containerName() : PublicadorNaInternet.sugerirContainer(motor.projeto(), s.nome());
        } finally {
            ajustando = false;
        }
        aoMudarOServico();
    }

    private void aoEscolherOServico() {
        if (ajustando) {
            return;
        }
        ComposeComEdge.Servico s = servicoEscolhido();
        if (s == null) {
            return;
        }
        ajustando = true;
        try {
            String sugerido = s.containerName() != null ? s.containerName() : PublicadorNaInternet.sugerirContainer(motor.projeto(), s.nome());
            if (campoContainer.getText().isEmpty() || campoContainer.getText().equals(ultimoContainerSugerido)) {
                campoContainer.setText(sugerido);
            }
            ultimoContainerSugerido = sugerido;
            campoContainer.setEnabled(s.containerName() == null);
            campoContainer.setToolTipText(s.containerName() == null ? null : "O serviço já tem este container_name no compose: o app usa o que o arquivo diz.");
            if (s.portaSugerida() != null) {
                campoPorta.setText(String.valueOf(s.portaSugerida()));
            }
        } finally {
            ajustando = false;
        }
        aoMudarOServico();
    }

    private void aoMudarOServico() {
        if (ajustando) {
            return;
        }
        List<String> l = new ArrayList<>();
        String p = campoPorta.getText().strip();
        if (!p.isEmpty() && numero(p) == null) {
            l.add("A porta é um número de 1 a 65535.");
        }
        if (!campoContainer.getText().isEmpty() && !containerValido()) {
            l.add("Nome do container: letras minúsculas, números, ponto, hífen ou sublinhado, até 41 caracteres.");
        }
        dizerErro(problemaDoServico, String.join("\n", l));
        atualizarBotoes();
        reajustar();
    }

    private boolean containerValido() {
        return campoContainer.getText().matches("^[a-z0-9][a-z0-9_.-]{0,40}$");
    }

    private static Integer numero(String t) {
        try {
            int n = Integer.parseInt(t.strip());
            return n >= 1 && n <= 65535 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DialogoPublicar.Predefinicao predefinicao() {
        ComposeComEdge.Servico s = servicoEscolhido();
        Integer porta = numero(campoPorta.getText());
        if (s == null || porta == null || !containerValido() || !NomeDoEndereco.valido(campoNome.getText())) {
            return null;
        }
        return new DialogoPublicar.Predefinicao(s.nome(), porta, campoContainer.getText(), campoNome.getText(), recriar.isSelected() && recriar.isVisible());
    }

    // =====================================================================================================
    // Fechar
    // =====================================================================================================

    @Override
    protected void aoCancelar() {
        if (jaFechou()) {
            return;
        }
        fechar();
        if (!entreguePara) {
            aoCancelar.run();
        }
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public String passoAtual() {
        return passo.name();
    }

    public Botao botaoContinuar() {
        return continuar;
    }

    public Botao botaoVoltar() {
        return voltar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoConectar() {
        return conectar;
    }

    public Botao botaoTrocarConta() {
        return trocarConta;
    }

    public Botao botaoTrocarDominio() {
        return trocarDominio;
    }

    public JTextField campoNome() {
        return campoNome;
    }

    public JTextField campoPorta() {
        return campoPorta;
    }

    public JTextField campoContainer() {
        return campoContainer;
    }

    public JComboBox<String> caixaDeServicos() {
        return caixaDeServicos;
    }

    public JCheckBox caixaDeRecriar() {
        return recriar;
    }

    public String textoDoTituloDoPasso() {
        return tituloDoPasso.getText();
    }

    public String textoDaConta() {
        return textoDaConta.getText();
    }

    public String seloDaConta() {
        return seloDaConta.texto();
    }

    public String textoDoDominio() {
        return dominioEscolhido.getText();
    }

    public String textoDoEnderecoFinal() {
        return enderecoFinal.isVisible() ? enderecoFinal.getText() : "";
    }

    public String textoDoProblemaDoNome() {
        return problemaDoNome.isVisible() ? problemaDoNome.getText() : "";
    }

    public String textoDoProblemaDoServico() {
        return problemaDoServico.isVisible() ? problemaDoServico.getText() : "";
    }

    public boolean fechou() {
        return jaFechou();
    }

    public List<String> trilha() {
        return trilha.stream().map(Rotulo::getText).toList();
    }
}
