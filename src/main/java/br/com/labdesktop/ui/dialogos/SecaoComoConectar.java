package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.seguranca.ValidadorDeTransporte;
import br.com.labdesktop.transporte.RelatorioDeFerramentas;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.CardLayout;
import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A seção "Como conectar", a mesma em "Adicionar servidor" e em "Editar servidor": três opções (rádio) e, de cada uma, só os campos dela.
 * <ul>
 *   <li><b>Direto (SSH)</b>, o padrão: o endereço e a porta do SSH do servidor (como sempre foi);</li>
 *   <li><b>AWS Session Manager (SSM)</b>: ID da instância, região, perfil da AWS (opcional) e porta SSH da instância;</li>
 *   <li><b>Cloudflare</b>: o endereço (hostname) do túnel.</li>
 * </ul>
 * Os dois modos sem porta aberta são validados ENQUANTO se digita (mensagem simples, só do campo preenchido e errado); o que falta preencher é cobrado ao continuar
 * ({@link #erroParaContinuar()}). O botão "Conferir as ferramentas neste computador" roda a conferência numa thread de fundo e abre o {@link DialogoDasFerramentas}: só
 * procura os programas e roda {@code --version}; nunca instala nada.
 */
public final class SecaoComoConectar {

    /** Os três modos da tela. */
    public enum Modo { DIRETO, SSM, CLOUDFLARE }

    /** O que roda a conferência das ferramentas (bloqueia: sempre numa thread de fundo). */
    @FunctionalInterface
    public interface Conferencia {
        RelatorioDeFerramentas conferir() throws Exception;
    }

    public static final String ROTULO_DA_SECAO = "Como conectar";
    public static final String ROTULO_DIRETO = "Direto (SSH)";
    public static final String ROTULO_SSM = "AWS Session Manager (SSM)";
    public static final String ROTULO_CLOUDFLARE = "Cloudflare";
    public static final String ROTULO_DO_BOTAO = "Conferir as ferramentas neste computador";
    public static final String DICA_DO_SSM = "Sem porta aberta no servidor: o app entra por um túnel do AWS Session Manager. Este computador precisa do aws e do plugin do Session Manager.";
    public static final String DICA_DA_CLOUDFLARE = "Sem porta aberta no servidor: o app entra pelo túnel da Cloudflare. Este computador precisa do cloudflared.";

    private static final int LARGURA = 500;

    private final JRadioButton direto = new JRadioButton(ROTULO_DIRETO, true);
    private final JRadioButton ssm = new JRadioButton(ROTULO_SSM);
    private final JRadioButton cloudflare = new JRadioButton(ROTULO_CLOUDFLARE);
    // direto
    private final JTextField host = Campos.mono(16, "192.0.2.5");
    private final JTextField porta = Campos.mono(5, "22");
    // SSM
    private final JTextField instancia = Campos.mono(19, "i-0123456789abcdef0");
    private final JTextField regiao = Campos.mono(11, "us-east-1");
    private final JTextField perfil = Campos.mono(13, "opcional");
    private final JTextField portaSsh = Campos.mono(5, "22");
    // Cloudflare
    private final JTextField hostname = Campos.mono(34, "ssh.example.com");

    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel pilha = new Painel(cartoes);
    private final TextoCorrido explicacao = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final TextoCorrido mensagem = new TextoCorrido("", LARGURA).corDoToken(Tokens.PERIGO).tamanho(12.5f);
    private final Botao conferir = Botoes.mini(ROTULO_DO_BOTAO, "sistema");
    private final JComponent raiz;
    private final boolean comExplicacao;
    private final Conferencia conferencia;
    private final Consumer<RelatorioDeFerramentas> mostrarRelatorio;
    private final Notificacoes avisos;
    private final Runnable aoReajustar;
    private Runnable aoMudar = () -> { };
    private Runnable desfazerOcupado = () -> { };
    private boolean ocupado;

    /**
     * @param conferencia      quem confere as ferramentas (fora da EDT)
     * @param mostrarRelatorio o que fazer com o relatório (na EDT): abrir o {@link DialogoDasFerramentas}
     * @param aoReajustar      o diálogo refaz o tamanho quando a seção troca de modo
     */
    public SecaoComoConectar(Conferencia conferencia, Consumer<RelatorioDeFerramentas> mostrarRelatorio, Notificacoes avisos, Runnable aoReajustar) {
        this(conferencia, mostrarRelatorio, avisos, aoReajustar, true);
    }

    /**
     * @param comExplicacao {@code true} mostra, embaixo dos campos, a frase de cada modo sem porta aberta (quem chega agora lê); {@code false} deixa a frase só na dica de cada opção
     *                      (o Editar servidor, que já é alto, e cujo usuário já escolheu o modo)
     */
    public SecaoComoConectar(Conferencia conferencia, Consumer<RelatorioDeFerramentas> mostrarRelatorio, Notificacoes avisos, Runnable aoReajustar, boolean comExplicacao) {
        this.comExplicacao = comExplicacao;
        this.conferencia = conferencia;
        this.mostrarRelatorio = mostrarRelatorio;
        this.avisos = avisos;
        this.aoReajustar = aoReajustar;
        ButtonGroup grupo = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[] {direto, ssm, cloudflare}) {
            grupo.add(r);
            r.setOpaque(false);
        }
        ssm.setToolTipText(DICA_DO_SSM);
        cloudflare.setToolTipText(DICA_DA_CLOUDFLARE);
        direto.setName("modo-direto");
        ssm.setName("modo-ssm");
        cloudflare.setName("modo-cloudflare");
        host.setName("campo-host");
        porta.setName("campo-porta");
        instancia.setName("campo-instancia");
        regiao.setName("campo-regiao");
        perfil.setName("campo-perfil");
        portaSsh.setName("campo-porta-ssh-da-instancia");
        hostname.setName("campo-hostname-cloudflare");
        host.getAccessibleContext().setAccessibleName("Endereço do servidor");
        porta.getAccessibleContext().setAccessibleName("Porta do SSH");
        instancia.getAccessibleContext().setAccessibleName("ID da instância");
        regiao.getAccessibleContext().setAccessibleName("Região da AWS");
        perfil.getAccessibleContext().setAccessibleName("Perfil da AWS (opcional)");
        portaSsh.getAccessibleContext().setAccessibleName("Porta SSH da instância");
        hostname.getAccessibleContext().setAccessibleName("Endereço (hostname) do túnel da Cloudflare");
        conferir.setName("botao-conferir-ferramentas");
        mensagem.setName("mensagem-do-como-conectar");
        explicacao.setName("explicacao-do-como-conectar");

        pilha.add(linhaDoDireto(), Modo.DIRETO.name());
        pilha.add(linhaDoSsm(), Modo.SSM.name());
        pilha.add(linhaDaCloudflare(), Modo.CLOUDFLARE.name());
        mensagem.setVisible(false);
        explicacao.setVisible(false);

        // o título e o botão de conferir dividem a linha de cima: um diálogo a menos de altura (o Editar não passa dos 608 px de altura máxima)
        raiz = Caixa.coluna(2,
            Caixa.linha(10, new Rotulo(ROTULO_DA_SECAO).negrito(), Caixa.mola(), conferir),
            Caixa.linha(14, direto, ssm, cloudflare, Caixa.mola()),
            pilha,
            explicacao,
            mensagem);
        raiz.setName("secao-como-conectar");
        raiz.getAccessibleContext().setAccessibleName(ROTULO_DA_SECAO);

        for (JRadioButton r : new JRadioButton[] {direto, ssm, cloudflare}) {
            r.addActionListener(e -> trocouOModo());
        }
        DocumentListener aoDigitar = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                digitou();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                digitou();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                digitou();
            }
        };
        for (JTextField c : todosOsCampos()) {
            c.getDocument().addDocumentListener(aoDigitar);
        }
        portaSsh.setText(String.valueOf(ValidadorDeTransporte.PORTA_SSH_PADRAO));
        conferir.addActionListener(e -> conferirAsFerramentas());
        cartoes.show(pilha, Modo.DIRETO.name());
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private static JComponent campo(String rotulo, JComponent c) {
        return Caixa.coluna(3, Rotulo.rotuloDeCampo(rotulo), c);
    }

    private JComponent linhaDoDireto() {
        return Caixa.linha(12, campo("Endereço", host), campo("Porta do SSH", porta), Caixa.mola());
    }

    private JComponent linhaDoSsm() {
        return Caixa.linha(12, campo("ID da instância", instancia), campo("Região", regiao), campo("Perfil (opcional)", perfil), campo("Porta SSH", portaSsh),
            Caixa.mola());
    }

    private JComponent linhaDaCloudflare() {
        return Caixa.linha(12, campo("Endereço (hostname) do túnel", hostname), Caixa.mola());
    }

    private List<JTextField> todosOsCampos() {
        return List.of(host, porta, instancia, regiao, perfil, portaSsh, hostname);
    }

    /** A seção inteira, para pôr no diálogo. */
    public JComponent componente() {
        return raiz;
    }

    /** Quem quer saber quando algo mudou (o modo ou um campo): o diálogo liga o botão Salvar e refaz o aviso da impressão. */
    public void aoMudar(Runnable ouvinte) {
        this.aoMudar = ouvinte == null ? () -> { } : ouvinte;
    }

    // ---------------------------------------------------------------------------------------------
    // Estado
    // ---------------------------------------------------------------------------------------------

    public Modo modo() {
        return ssm.isSelected() ? Modo.SSM : cloudflare.isSelected() ? Modo.CLOUDFLARE : Modo.DIRETO;
    }

    /** Escolhe o modo (sem avisar quem ouve: é o diálogo que está se montando). */
    public void definirModo(Modo modo) {
        direto.setSelected(modo == Modo.DIRETO);
        ssm.setSelected(modo == Modo.SSM);
        cloudflare.setSelected(modo == Modo.CLOUDFLARE);
        mostrarOModo();
    }

    /** Preenche a seção com o que o servidor tem hoje (o Editar servidor). */
    public void carregar(ConfigServidor c) {
        Transporte t = c.transporte();
        switch (t.tipo()) {
            case SSM -> {
                instancia.setText(t.instanciaId());
                regiao.setText(t.regiao() == null ? "" : t.regiao());
                perfil.setText(t.perfilAws() == null ? "" : t.perfilAws());
                portaSsh.setText(String.valueOf(t.portaSshNaInstancia()));
                definirModo(Modo.SSM);
            }
            case CLOUDFLARED -> {
                hostname.setText(t.hostname());
                definirModo(Modo.CLOUDFLARE);
            }
            case DIRETO, LOCAL -> definirModo(Modo.DIRETO);
        }
        if (!c.transporte().ehTunel()) {
            host.setText(c.host());
            porta.setText(String.valueOf(c.porta()));
        } else {
            portaSsh.setText(String.valueOf(t.tipo() == Transporte.Tipo.SSM ? t.portaSshNaInstancia() : ValidadorDeTransporte.PORTA_SSH_PADRAO));
        }
    }

    /** O endereço do modo direto (como digitado, sem espaços nas pontas). */
    public String hostDireto() {
        return host.getText().strip();
    }

    /** A porta do SSH do modo direto, como texto. */
    public String portaDiretaEmTexto() {
        return porta.getText().strip();
    }

    /**
     * O modo escolhido, já validado: direto devolve {@link Transporte#DIRETO}; SSM e Cloudflare só devolvem se todos os campos valem.
     *
     * @throws IllegalArgumentException com a frase simples do que está errado ou falta
     */
    public Transporte transporte() {
        return switch (modo()) {
            case DIRETO -> Transporte.DIRETO;
            case SSM -> {
                String erro = erroDoSsm(true);
                if (!erro.isEmpty()) {
                    throw new IllegalArgumentException(erro);
                }
                yield Transporte.ssm(instancia.getText().strip(), regiao.getText().strip(), perfil.getText().strip(), ValidadorDeTransporte.portaDe(portaSsh.getText()).getAsInt());
            }
            case CLOUDFLARE -> {
                String erro = ValidadorDeTransporte.erroDoHostname(hostname.getText().strip());
                if (!erro.isEmpty()) {
                    throw new IllegalArgumentException(erro);
                }
                yield Transporte.cloudflared(hostname.getText().strip());
            }
        };
    }

    /** O que falta ou está errado nos campos do modo sem porta aberta ({@code ""} se vale, ou se o modo é direto: quem valida o endereço e a porta é o assistente). */
    public String erroParaContinuar() {
        return switch (modo()) {
            case DIRETO -> "";
            case SSM -> erroDoSsm(true);
            case CLOUDFLARE -> ValidadorDeTransporte.erroDoHostname(hostname.getText().strip());
        };
    }

    /** Mudou alguma coisa em relação ao que o servidor tem? (o Salvar só liga com mudança) */
    public boolean mudouEmRelacaoA(ConfigServidor original) {
        Modo atual = modo();
        Modo antes = original.transporte().tipo() == Transporte.Tipo.SSM ? Modo.SSM : original.transporte().tipo() == Transporte.Tipo.CLOUDFLARED ? Modo.CLOUDFLARE : Modo.DIRETO;
        if (atual != antes) {
            return true;
        }
        return switch (atual) {
            case DIRETO -> !host.getText().strip().equalsIgnoreCase(original.host()) || !porta.getText().strip().equals(String.valueOf(original.porta()));
            case SSM -> {
                Transporte t = original.transporte();
                yield !instancia.getText().strip().equals(t.instanciaId()) || !regiao.getText().strip().equals(t.regiao() == null ? "" : t.regiao())
                    || !perfil.getText().strip().equals(t.perfilAws() == null ? "" : t.perfilAws())
                    || !portaSsh.getText().strip().equals(String.valueOf(t.portaSshNaInstancia()));
            }
            case CLOUDFLARE -> !hostname.getText().strip().equalsIgnoreCase(original.transporte().hostname());
        };
    }

    /**
     * Esta edição aponta para OUTRA máquina dentro do mesmo modo (outro endereço ou porta, outra instância, região ou porta SSH, outro hostname)? Então a impressão digital
     * guardada será apagada (a regra de {@link ConfigServidor#editar}); trocar de modo NÃO apaga, e mudar só o perfil da AWS também não.
     */
    public boolean apagaAImpressaoDe(ConfigServidor original) {
        Modo atual = modo();
        Modo antes = original.transporte().tipo() == Transporte.Tipo.SSM ? Modo.SSM : original.transporte().tipo() == Transporte.Tipo.CLOUDFLARED ? Modo.CLOUDFLARE : Modo.DIRETO;
        if (atual != antes) {
            return false;
        }
        return switch (atual) {
            case DIRETO -> !host.getText().strip().equalsIgnoreCase(original.host()) || !porta.getText().strip().equals(String.valueOf(original.porta()));
            case SSM -> {
                Transporte t = original.transporte();
                yield !instancia.getText().strip().equals(t.instanciaId()) || !regiao.getText().strip().equals(t.regiao() == null ? "" : t.regiao())
                    || !portaSsh.getText().strip().equals(String.valueOf(t.portaSshNaInstancia()));
            }
            case CLOUDFLARE -> !hostname.getText().strip().equalsIgnoreCase(original.transporte().hostname());
        };
    }

    /** A explicação curta do modo escolhido (vazia no direto). */
    public String textoDaExplicacao() {
        return explicacao.isVisible() ? explicacao.getText() : "";
    }

    /** A mensagem de erro que aparece ao digitar (vazia se não há). */
    public String textoDaMensagem() {
        return mensagem.isVisible() ? mensagem.getText() : "";
    }

    // ---------------------------------------------------------------------------------------------
    // Comportamento
    // ---------------------------------------------------------------------------------------------

    private void trocouOModo() {
        mostrarOModo();
        aoMudar.run();
        aoReajustar.run();
    }

    private void mostrarOModo() {
        Modo m = modo();
        cartoes.show(pilha, m.name());
        explicacao.setText(switch (m) {
            case DIRETO -> "";
            case SSM -> DICA_DO_SSM;
            case CLOUDFLARE -> DICA_DA_CLOUDFLARE;
        });
        explicacao.setVisible(comExplicacao && m != Modo.DIRETO);
        atualizarAMensagem();
    }

    private void digitou() {
        atualizarAMensagem();
        aoMudar.run();
    }

    /** A mensagem de AO DIGITAR: só do campo que tem texto e está errado (o que ainda falta preencher só é cobrado ao continuar). */
    private void atualizarAMensagem() {
        String texto = switch (modo()) {
            case DIRETO -> "";
            case SSM -> erroDoSsm(false);
            case CLOUDFLARE -> hostname.getText().isBlank() ? "" : ValidadorDeTransporte.erroDoHostname(hostname.getText().strip());
        };
        boolean mudou = !texto.equals(mensagem.isVisible() ? mensagem.getText() : "");
        mensagem.setText(texto);
        mensagem.setVisible(!texto.isEmpty());
        if (mudou) {
            aoReajustar.run();
        }
    }

    /** O primeiro erro dos campos do SSM. Com {@code cobrarOQueFalta}, campo obrigatório vazio também é erro; sem ele, só texto digitado e errado. */
    private String erroDoSsm(boolean cobrarOQueFalta) {
        String id = instancia.getText().strip();
        if (cobrarOQueFalta || !id.isEmpty()) {
            String e = ValidadorDeTransporte.erroDaInstancia(id);
            if (!e.isEmpty()) {
                return e;
            }
        }
        String r = ValidadorDeTransporte.erroDaRegiao(regiao.getText().strip());
        if (!r.isEmpty()) {
            return r;
        }
        String p = ValidadorDeTransporte.erroDoPerfil(perfil.getText().strip());
        if (!p.isEmpty()) {
            return p;
        }
        String ps = portaSsh.getText().strip();
        if (cobrarOQueFalta || !ps.isEmpty()) {
            return ValidadorDeTransporte.erroDaPorta(ps);
        }
        return "";
    }

    private void conferirAsFerramentas() {
        if (ocupado || conferencia == null) {
            return;
        }
        desfazerOcupado = conferir.ocupar();
        ocupado = true;
        Tarefas.rodar(conferencia::conferir, relatorio -> {
            terminouAConferencia();
            mostrarRelatorio.accept(relatorio);
        }, falha -> {
            terminouAConferencia();
            avisos.erro(MensagensDeErro.de(falha));
        });
    }

    private void terminouAConferencia() {
        ocupado = false;
        desfazerOcupado.run();
        desfazerOcupado = () -> { };
    }

    /** Liga ou desliga os campos (enquanto o diálogo salva ou fala com o servidor). */
    public void ocupar(boolean sim) {
        for (Component c : new Component[] {direto, ssm, cloudflare, host, porta, instancia, regiao, perfil, portaSsh, hostname}) {
            c.setEnabled(!sim);
        }
        if (!ocupado) {
            conferir.setEnabled(!sim);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public JRadioButton radioDireto() {
        return direto;
    }

    public JRadioButton radioSsm() {
        return ssm;
    }

    public JRadioButton radioCloudflare() {
        return cloudflare;
    }

    public JTextField campoDoHost() {
        return host;
    }

    public JTextField campoDaPorta() {
        return porta;
    }

    public JTextField campoDaInstancia() {
        return instancia;
    }

    public JTextField campoDaRegiao() {
        return regiao;
    }

    public JTextField campoDoPerfil() {
        return perfil;
    }

    public JTextField campoDaPortaSsh() {
        return portaSsh;
    }

    public JTextField campoDoHostname() {
        return hostname;
    }

    public Botao botaoConferir() {
        return conferir;
    }

    /** Quais campos estão à vista agora (por nome), para provar que cada modo mostra só os dele. */
    public List<String> camposVisiveis() {
        return todosOsCampos().stream().filter(c -> c.isShowing() || visivelNaPilha(c)).map(JTextField::getName).toList();
    }

    private boolean visivelNaPilha(JComponent c) {
        for (Component card : pilha.getComponents()) {
            if (card.isVisible() && javax.swing.SwingUtilities.isDescendingFrom(c, card)) {
                return true;
            }
        }
        return false;
    }
}
