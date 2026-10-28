package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.projetos.BlocoDeServico;
import br.com.labdesktop.projetos.ComposeDoProjeto;
import br.com.labdesktop.projetos.EscritorDeCompose;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.ModelosDeContainer;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.projetos.ModelosDeContainer.Parametros;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * "Adicionar container": o usuário escolhe um modelo (Aplicação Node, Banco Postgres, Redis ou uma imagem pelo nome), o app MOSTRA a diferença do
 * {@code docker-compose.yml} (as linhas que entram, com "+", e as que saem, com "-") e só GRAVA depois do clique em "Gravar no docker-compose.yml". Antes de gravar,
 * por baixo, o app guarda uma cópia datada, confere o resultado com {@code docker compose config} (se o Docker o recusa, nada é gravado) e põe as
 * senhas no {@code .env}, nunca no compose. Compose que o usuário já mexeu (serviços, redes, volumes) NUNCA é reescrito: aí a tela só mostra o bloco
 * para copiar. "Iniciar" é um clique separado, depois de gravar. Nada de rede, disco ou processo na EDT.
 */
public final class DialogoAdicionarContainer extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        EscritorDeCompose.Previa preparar(BlocoDeServico bloco) throws FalhaDoProjeto;

        EscritorDeCompose.Gravado gravar(EscritorDeCompose.Previa previa) throws FalhaDoProjeto;
    }

    private static final int LARGURA = 480;

    private final String projeto;
    private final Motor motor;
    private final Runnable aoGravar;
    private final Runnable aoIniciar;

    private final Map<Modelo, JRadioButton> opcoes = new EnumMap<>(Modelo.class);
    private final TextoCorrido descricaoDoModelo = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JTextField campoServico = Campos.texto(20, "nome-do-servico");
    private final JTextField campoImagem = Campos.texto(30, "nginx:alpine");
    private final Rotulo rotuloImagem = new Rotulo("Imagem").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JCheckBox abrirPorta = new JCheckBox("Abrir uma porta neste computador (só em 127.0.0.1)");
    private final JTextField campoPortaNoComputador = Campos.texto(6, "3000");
    private final JTextField campoPortaNoContainer = Campos.texto(6, "3000");
    private final Rotulo rotuloPortas = new Rotulo("porta de fora   →   porta de dentro do container").corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final TextoCorrido problemas = erroCorrido();
    private final Rotulo titulo = new Rotulo("").negrito();
    private final AreaMonoespacada previa = new AreaMonoespacada("O que será acrescentado ao docker-compose.yml", false);
    private final JScrollPane previaRolavel = AreaMonoespacada.emRolagem(previa);
    private final TextoCorrido nota = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido falha = erroCorrido();
    private final TextoCorrido sucesso = new TextoCorrido("", LARGURA).corDoToken(Tokens.OK).tamanho(12.5f);
    private final Botao gravar = Botoes.primario("Gravar no docker-compose.yml", "salvar");
    private final Botao iniciar = Botoes.secundario("Iniciar o projeto", "play");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Botao copiar;
    private final AtomicInteger geracao = new AtomicInteger();
    private Modelo modelo = Modelo.NODE;
    private BlocoDeServico blocoAtual;
    private EscritorDeCompose.Previa previaAtual;
    private boolean ocupado;
    private boolean gravado;
    private boolean ajustando;

    public DialogoAdicionarContainer(String projeto, Motor motor, Runnable aoGravar, Runnable aoIniciar, Notificacoes avisos, CopiadorDeTexto copiador) {
        super("Adicionar container");
        this.projeto = projeto;
        this.motor = motor;
        this.aoGravar = aoGravar;
        this.aoIniciar = aoIniciar;
        this.copiar = BotaoCopiar.criar("Copiar o bloco", () -> blocoAtual == null ? "" : blocoAtual.textoParaCopiar(), copiador, avisos);

        titulo.setText("Projeto " + projeto);
        ButtonGroup grupo = new ButtonGroup();
        List<JComponent> radios = new ArrayList<>();
        for (Modelo m : Modelo.values()) {
            JRadioButton r = new JRadioButton(m.rotulo(), m == modelo);
            r.putClientProperty("html.disable", Boolean.TRUE);
            r.setName("modelo-" + m.name());
            grupo.add(r);
            opcoes.put(m, r);
            radios.add(r);
            r.addActionListener(e -> escolher(m));
        }
        sucesso.setVisible(false);
        nota.setVisible(false);
        previaRolavel.setPreferredSize(new java.awt.Dimension(LARGURA, 170));
        iniciar.setVisible(aoIniciar != null);
        iniciar.setEnabled(false);
        abrirPorta.putClientProperty("html.disable", Boolean.TRUE);
        campoServico.putClientProperty("html.disable", Boolean.TRUE);
        campoImagem.putClientProperty("html.disable", Boolean.TRUE);
        campoServico.getAccessibleContext().setAccessibleName("Nome do serviço");
        campoImagem.getAccessibleContext().setAccessibleName("Nome da imagem do Docker");

        JComponent corpo = Caixa.coluna(8,
            titulo,
            new Rotulo("Modelo").negrito(),
            Caixa.coluna(2, radios.toArray(JComponent[]::new)),
            descricaoDoModelo,
            Caixa.linha(8, new Rotulo("Nome do serviço").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoServico, Caixa.mola()),
            Caixa.linha(8, rotuloImagem, campoImagem, Caixa.mola()),
            abrirPorta,
            Caixa.linha(8, campoPortaNoComputador, new Rotulo("→").corDoToken(Tokens.TEXTO3), campoPortaNoContainer, rotuloPortas, Caixa.mola()),
            problemas,
            Caixa.linha(8, new Rotulo("O que muda no docker-compose.yml").negrito(), Caixa.mola(), copiar),
            previaRolavel,
            nota,
            falha,
            sucesso);
        conteudoComRodape(Caixa.coluna(10, cabecalho("docker", "Adicionar container"), corpo), rodape(null, cancelar, iniciar, gravar));
        botaoPadrao(gravar);

        cancelar.addActionListener(e -> aoCancelar());
        gravar.addActionListener(e -> gravar());
        iniciar.addActionListener(e -> {
            fechar();
            if (aoIniciar != null) {
                aoIniciar.run();
            }
        });
        SimplesOuvinteDeDocumento mudou = new SimplesOuvinteDeDocumento(this::aoMudarOsCampos);
        campoServico.getDocument().addDocumentListener(mudou);
        campoImagem.getDocument().addDocumentListener(mudou);
        campoPortaNoComputador.getDocument().addDocumentListener(mudou);
        campoPortaNoContainer.getDocument().addDocumentListener(mudou);
        abrirPorta.addActionListener(e -> aoMudarOsCampos());
        escolher(Modelo.NODE);
    }

    private void escolher(Modelo m) {
        modelo = m;
        ajustando = true;
        try {
            opcoes.get(m).setSelected(true);
            descricaoDoModelo.setText(m.descricao());
            campoServico.setText(m.servicoPadrao());
            campoImagem.setText("");
            Integer fora = m.portaPadraoNoComputador();
            Integer dentro = m.portaPadraoNoContainer();
            abrirPorta.setSelected(fora != null);
            campoPortaNoComputador.setText(fora == null ? (dentro == null ? "" : String.valueOf(dentro)) : String.valueOf(fora));
            campoPortaNoContainer.setText(dentro == null ? "" : String.valueOf(dentro));
            boolean imagem = m == Modelo.IMAGEM;
            rotuloImagem.setVisible(imagem);
            campoImagem.setVisible(imagem);
        } finally {
            ajustando = false;
        }
        aoMudarOsCampos();
    }

    private static Integer numero(String t) {
        try {
            int n = Integer.parseInt(t.strip());
            return n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Parametros parametros(List<String> avisos) {
        Integer fora = null;
        Integer dentro = null;
        boolean porta = abrirPorta.isSelected();
        campoPortaNoComputador.setEnabled(porta && !gravado);
        campoPortaNoContainer.setEnabled(porta && !gravado);
        if (porta) {
            fora = numero(campoPortaNoComputador.getText());
            dentro = numero(campoPortaNoContainer.getText());
            if (fora == null || dentro == null) {
                avisos.add("As portas são números de 1 a 65535.");
                fora = fora == null ? 0 : fora;
                dentro = dentro == null ? 0 : dentro;
            }
        }
        return new Parametros(campoServico.getText().strip(), campoImagem.getText().strip(), fora, dentro);
    }

    private void aoMudarOsCampos() {
        if (ajustando || gravado) {
            return;
        }
        List<String> avisos = new ArrayList<>();
        Parametros p = parametros(avisos);
        avisos.addAll(ModelosDeContainer.conferir(modelo, p));
        dizerErro(problemas, String.join("\n", avisos.stream().distinct().toList()));
        dizerErro(falha, "");
        previaAtual = null;
        blocoAtual = null;
        if (!avisos.isEmpty()) {
            previa.setText("");
            nota.setVisible(false);
            gravar.setEnabled(false);
            copiar.setEnabled(false);
            reajustar();
            return;
        }
        BlocoDeServico bloco = ModelosDeContainer.gerar(modelo, p);
        blocoAtual = bloco;
        copiar.setEnabled(true);
        gravar.setEnabled(false);
        previa.setText("Lendo o docker-compose.yml…");
        int minha = geracao.incrementAndGet();
        Tarefas.rodar(() -> motor.preparar(bloco), r -> {
            if (minha != geracao.get() || jaFechou()) {
                return; // o usuário já mudou o que pediu: este resultado é de antes
            }
            mostrarPrevia(r);
        }, f -> {
            if (minha != geracao.get() || jaFechou()) {
                return;
            }
            previa.setText("");
            nota.setVisible(false);
            dizerErro(falha, f instanceof FalhaDoProjeto fp ? fp.mensagem() : MensagensDeErro.de(f));
            reajustar();
        });
    }

    private void mostrarPrevia(EscritorDeCompose.Previa r) {
        previaAtual = r;
        StringBuilder n = new StringBuilder();
        if (r.gravavel()) {
            previa.setText(r.diferenca().isEmpty() ? "(nada muda)" : r.diferenca());
            n.append(r.situacao() == ComposeDoProjeto.Situacao.AUSENTE
                ? "O projeto ainda não tem docker-compose.yml: ele será criado com o esqueleto e este container.\n"
                : "");
            n.append("Antes de gravar, o app guarda uma cópia datada do compose e confere o resultado com docker compose config; se o Docker o recusar, nada é gravado.");
            if (!r.bloco().variaveis().isEmpty()) {
                n.append("\nA senha (")
                    .append(String.join(", ", r.bloco().variaveis().stream().map(BlocoDeServico.Variavel::nome).toList()))
                    .append(") vai para o arquivo .env, escolhida ao acaso e sem aparecer aqui; o compose só a referencia.");
            }
            nota.setText(n.toString());
            gravar.setEnabled(!ocupado);
        } else {
            previa.setText(r.textoParaCopiar());
            nota.setText("Este docker-compose.yml já tem serviços, redes ou volumes seus, e o app não reescreve o que é do usuário. Copie o bloco acima e cole no arquivo "
                + "(use o gerenciador de arquivos do projeto ou o terminal). Depois use Iniciar projeto.");
            gravar.setEnabled(false);
        }
        nota.setVisible(true);
        previa.setCaretPosition(0);
        reajustar();
    }

    private void gravar() {
        if (ocupado || previaAtual == null || !previaAtual.gravavel()) {
            return;
        }
        EscritorDeCompose.Previa p = previaAtual;
        ocupado = true;
        gravar.definirOcupado(true);
        cancelar.setEnabled(false);
        dizerErro(falha, "");
        Tarefas.rodar(() -> motor.gravar(p), g -> {
            ocupado = false;
            gravar.definirOcupado(false);
            gravado = true;
            gravar.setVisible(false);
            cancelar.setText("Fechar");
            cancelar.setEnabled(true);
            iniciar.setEnabled(true);
            for (JRadioButton r : opcoes.values()) {
                r.setEnabled(false);
            }
            campoServico.setEnabled(false);
            campoImagem.setEnabled(false);
            abrirPorta.setEnabled(false);
            campoPortaNoComputador.setEnabled(false);
            campoPortaNoContainer.setEnabled(false);
            StringBuilder s = new StringBuilder("Gravado em docker-compose.yml.");
            if (g.copia() != null) {
                s.append(" A cópia de antes ficou em ").append(g.copia()).append('.');
            }
            if (!g.variaveisNovas().isEmpty()) {
                s.append(" Variáveis novas no .env: ").append(String.join(", ", g.variaveisNovas())).append(" (a senha foi gerada e não aparece aqui).");
            }
            for (String a : g.avisos()) {
                s.append('\n').append(a);
            }
            s.append("\nO container ainda não subiu: use \"Iniciar o projeto\" quando quiser.");
            sucesso.setText(s.toString());
            sucesso.setVisible(true);
            aoGravar.run();
            reajustar();
        }, f -> {
            ocupado = false;
            gravar.definirOcupado(false);
            cancelar.setEnabled(true);
            dizerErro(falha, f instanceof FalhaDoProjeto fp ? fp.mensagem() : MensagensDeErro.de(f));
            gravar.setEnabled(previaAtual != null && previaAtual.gravavel());
            reajustar();
        });
    }

    @Override
    protected void aoCancelar() {
        if (ocupado) {
            return;
        }
        fechar();
    }

    @Override
    protected void aoFechar() {
        geracao.incrementAndGet();
    }

    // ---- para os testes ----

    public Botao botaoGravar() {
        return gravar;
    }

    public Botao botaoIniciar() {
        return iniciar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoCopiar() {
        return copiar;
    }

    public JRadioButton opcao(Modelo m) {
        return opcoes.get(m);
    }

    public JTextField campoServico() {
        return campoServico;
    }

    public JTextField campoImagem() {
        return campoImagem;
    }

    public JCheckBox caixaDaPorta() {
        return abrirPorta;
    }

    public JTextField campoPortaNoComputador() {
        return campoPortaNoComputador;
    }

    public JTextField campoPortaNoContainer() {
        return campoPortaNoContainer;
    }

    public String textoDaPrevia() {
        return previa.getText();
    }

    public String textoDaNota() {
        return nota.isVisible() ? nota.getText() : "";
    }

    public String textoDosProblemas() {
        return problemas.getText();
    }

    public String textoDaFalha() {
        return falha.getText();
    }

    public String textoDoSucesso() {
        return sucesso.isVisible() ? sucesso.getText() : "";
    }

    public Modelo modeloAtual() {
        return modelo;
    }

    public boolean gravado() {
        return gravado;
    }

    public String projeto() {
        return projeto;
    }

    public void escolherModelo(Modelo m) {
        escolher(m);
    }
}
