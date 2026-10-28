package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaEdicao;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.FichaRotulada;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JTextField;

/**
 * "Editar servidor": nome, endereço, porta do SSH, usuário e porta do painel de um servidor já cadastrado. Valida com as
 * MESMAS regras do assistente ({@link Dados#validar}). Mudar o endereço ou a porta do SSH apaga a impressão digital guardada, e o diálogo
 * avisa isso numa frase ENQUANTO o usuário digita, antes de salvar: a próxima conexão pede para confirmá-la de novo. Salvar
 * grava no cofre (fora da EDT) e o app reconecta com os dados novos. Mudar só a porta do painel (o erro de digitação mais
 * comum no passo 1 do assistente) NÃO apaga a impressão digital: a máquina é a mesma.
 *
 * <p>A chave SSH e o token não mudam aqui: continuam os mesmos. A seção "Acesso" mostra a impressão digital da chave que o app usa neste servidor, deixa copiar a chave
 * pública e leva ao "Instalar a chave com a senha…", o conserto de quando o servidor recusa a chave; ela só aparece quando quem abre o diálogo entrega o {@link Acesso}.
 */
public final class DialogoEditarServidor extends Dialogo {

    /** Quem grava a edição (fora da EDT): em produção, {@code GerenciadorServidores.atualizar}. */
    @FunctionalInterface
    public interface Gravacao {
        ResultadoDaEdicao gravar(Dados dados) throws Exception;
    }

    /**
     * A chave do app neste servidor, para a seção "Acesso" (sem segredo).
     *
     * @param aoInstalarChave abre "Instalar a chave com a senha…" (o diálogo de edição fecha antes)
     */
    public record Acesso(String nomeDaChave, String impressaoDaChave, String chavePublica, Runnable aoInstalarChave) { }

    /** A dica do botão Salvar, em texto simples: ele só liga depois de uma mudança, para ninguém salvar (e reconectar) sem querer. */
    public static final String DICA_DO_SALVAR = "O botão Salvar e reconectar só fica ativo depois que você muda algum campo.";

    /** O que a seção Acesso diz do botão de instalar a chave. */
    public static final String DICA_DO_ACESSO = "O app entra neste servidor com esta chave. Se o servidor a recusar (\"Chave recusada\"), instale-a com a senha do usuário: "
        + "o app usa a senha uma vez e confere que a chave entra.";

    /** Enquanto há mudança sem salvar, a chave não se instala: o conserto usa o servidor como está salvo. */
    public static final String DICA_DE_SALVAR_ANTES = "Salve as mudanças acima antes de instalar a chave: o app usa o endereço e o usuário já salvos.";

    /** O aviso de que a impressão digital será apagada. Uma frase, em palavras simples. */
    public static final String AVISO_DA_IMPRESSAO = "Como o endereço ou a porta mudou, a impressão digital guardada será "
        + "apagada e a próxima conexão pedirá para você confirmá-la de novo.";

    private final ConfigServidor original;
    private final Gravacao gravacao;
    private final Notificacoes avisos;
    private final JTextField nome = Campos.texto(14, "lab");
    private final JTextField host = Campos.mono(16, "192.0.2.5");
    private final JTextField porta = Campos.mono(5, "22");
    private final JTextField usuario = Campos.mono(14, "usuario");
    private final JTextField portaDoAgente = Campos.mono(5, "7000");
    private final TextoCorrido avisoDoEndereco = new TextoCorrido(AVISO_DA_IMPRESSAO, LARGURA_DO_TEXTO)
        .corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final TextoCorrido erro = erroCorrido();
    private final Botao salvar = Botoes.primario("Salvar e reconectar", "ok");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final TextoCorrido dicaDoSalvar = dicaCorrida(DICA_DO_SALVAR);
    private final Acesso acesso;
    private final FichaRotulada fichaDoAcesso = new FichaRotulada(1, 0);
    private final TextoCorrido dicaDoAcesso = dicaCorrida(DICA_DO_ACESSO);
    private final TextoCorrido dicaDeSalvarAntes = new TextoCorrido(DICA_DE_SALVAR_ANTES, LARGURA_DO_TEXTO).corDoToken(Tokens.ATENCAO).tamanho(12f);
    private final Botao instalarChave = Botoes.secundario("Instalar a chave com a senha…", "chave");
    private Botao copiarChave;
    private boolean ocupado;
    private Runnable desfazerOcupado = () -> { };

    public DialogoEditarServidor(ConfigServidor atual, Gravacao gravacao, Notificacoes avisos) {
        this(atual, gravacao, avisos, null, null);
    }

    /** Com a seção "Acesso" (a chave do app, o botão de copiar e o de instalar a chave com a senha); {@code acesso} nulo a esconde. */
    public DialogoEditarServidor(ConfigServidor atual, Gravacao gravacao, Notificacoes avisos, Acesso acesso, CopiadorDeTexto copiador) {
        super("Editar servidor");
        this.original = atual;
        this.gravacao = gravacao;
        this.avisos = avisos;
        this.acesso = acesso;
        nome.setText(atual.nome());
        host.setText(atual.host());
        porta.setText(String.valueOf(atual.porta()));
        usuario.setText(atual.usuario());
        portaDoAgente.setText(String.valueOf(atual.portaAgente()));
        avisoDoEndereco.setVisible(false);
        salvar.setEnabled(false);
        salvar.setToolTipText(DICA_DO_SALVAR);
        JComponent secaoDoAcesso = montarAcesso(copiador);

        java.util.List<java.awt.Component> corpo = new java.util.ArrayList<>(List.of(
            cabecalho("config", "Editar servidor"),
            paragrafo("Mude os dados de " + atual.nome() + ". A chave e o token continuam os mesmos; ao salvar, o app "
                + "reconecta com os dados novos."),
            Caixa.coluna(8,
                linhaDeCampos("Nome", nome, "Usuário", usuario),
                linhaDeCampos("Endereço", host, "Porta do SSH", porta),
                linhaDeCampos("Porta do painel no servidor", portaDoAgente, null, null),
                dicaCorrida(DialogoAdicionarServidor.DICA_DA_PORTA_DO_PAINEL)),
            avisoDoEndereco));
        if (secaoDoAcesso != null) {
            corpo.add(secaoDoAcesso);
        }
        corpo.addAll(List.of(dicaDoSalvar, erro, rodape(null, cancelar, salvar)));
        conteudo(Caixa.coluna(12, corpo.toArray(new java.awt.Component[0])));
        botaoPadrao(salvar);

        SimplesOuvinteDeDocumento aoDigitar = new SimplesOuvinteDeDocumento(this::atualizarTela);
        for (JTextField c : new JTextField[] {nome, host, porta, usuario, portaDoAgente}) {
            c.getDocument().addDocumentListener(aoDigitar);
        }
        salvar.addActionListener(e -> salvar());
        cancelar.addActionListener(e -> aoCancelar());
        instalarChave.addActionListener(e -> instalarAChave());
    }

    /** A seção "Acesso": impressão digital da chave do app, copiar a chave pública e instalar a chave com a senha. Vazia (e invisível) sem {@link Acesso}. */
    private JComponent montarAcesso(CopiadorDeTexto copiador) {
        dicaDeSalvarAntes.setVisible(false);
        if (acesso == null) {
            instalarChave.setVisible(false);
            dicaDoAcesso.setVisible(false);
            return null;
        }
        fichaDoAcesso.definir(List.of(FichaRotulada.Item.de("Impressão digital da chave deste app", acesso.nomeDaChave() + "  " + acesso.impressaoDaChave())));
        copiarChave = BotaoCopiar.criar("Copiar a chave pública", acesso::chavePublica, copiador, avisos);
        return Caixa.coluna(8,
            new Rotulo("Acesso").negrito(),
            fichaDoAcesso,
            Caixa.linha(8, copiarChave, instalarChave, Caixa.mola()),
            dicaDoAcesso,
            dicaDeSalvarAntes);
    }

    /** Fecha a edição e abre o conserto: ele usa o servidor como está salvo, então nada de mudança pendente. */
    private void instalarAChave() {
        if (ocupado || acesso == null || algoMudou()) {
            return;
        }
        fechar();
        acesso.aoInstalarChave().run();
    }

    private static JComponent linhaDeCampos(String r1, JComponent c1, String r2, JComponent c2) {
        JComponent a = Caixa.coluna(3, Rotulo.rotuloDeCampo(r1), c1);
        if (r2 == null) {
            return Caixa.linha(12, a, Caixa.mola());
        }
        return Caixa.linha(12, a, Caixa.coluna(3, Rotulo.rotuloDeCampo(r2), c2));
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks para os testes
    // ---------------------------------------------------------------------------------------------

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

    public Botao botaoSalvar() {
        return salvar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoInstalarChave() {
        return instalarChave;
    }

    public Botao botaoCopiarChave() {
        return copiarChave;
    }

    public boolean mostraAAcesso() {
        return acesso != null;
    }

    public String textoDaDicaDoSalvar() {
        return dicaDoSalvar.isVisible() ? dicaDoSalvar.getText() : "";
    }

    public boolean mostraADicaDeSalvarAntes() {
        return dicaDeSalvarAntes.isVisible();
    }

    public boolean mostraOAvisoDaImpressao() {
        return avisoDoEndereco.isVisible();
    }

    public String textoDoAviso() {
        return avisoDoEndereco.getText();
    }

    public String textoDoErro() {
        return erro.getText();
    }

    public boolean ocupado() {
        return ocupado;
    }

    // ---------------------------------------------------------------------------------------------
    // Comportamento
    // ---------------------------------------------------------------------------------------------

    /** Salvar só vale se algo mudou; o aviso da impressão aparece assim que o endereço ou a porta mudam. */
    private void atualizarTela() {
        dizerErro(erro, "");
        avisoDoEndereco.setVisible(enderecoMudou());
        salvar.setEnabled(!ocupado && algoMudou());
        atualizarDicas();
        reajustar();
    }

    /** A dica do Salvar vale enquanto nada mudou; a chave só se instala sem mudança pendente. */
    private void atualizarDicas() {
        boolean mudou = algoMudou();
        dicaDoSalvar.setVisible(!mudou);
        instalarChave.setEnabled(!ocupado && !mudou);
        dicaDeSalvarAntes.setVisible(acesso != null && mudou);
    }

    private boolean algoMudou() {
        return !nome.getText().strip().equals(original.nome()) || !usuario.getText().strip().equals(original.usuario())
            || portaDoPainelMudou() || enderecoMudou();
    }

    private boolean portaDoPainelMudou() {
        try {
            return Integer.parseInt(portaDoAgente.getText().strip()) != original.portaAgente();
        } catch (NumberFormatException e) {
            return !portaDoAgente.getText().strip().equals(String.valueOf(original.portaAgente()));
        }
    }

    /** Mesma regra do cofre ({@link ConfigServidor#mesmoEnderecoQue}): endereço sem diferença de maiúsculas, e a porta. */
    private boolean enderecoMudou() {
        if (!host.getText().strip().equalsIgnoreCase(original.host())) {
            return true;
        }
        try {
            return Integer.parseInt(porta.getText().strip()) != original.porta();
        } catch (NumberFormatException e) {
            return !porta.getText().strip().equals(String.valueOf(original.porta()));
        }
    }

    private void salvar() {
        if (!salvar.isEnabled()) {
            return;
        }
        Dados dados;
        try {
            dados = lerDados();
        } catch (IllegalArgumentException e) {
            dizerErro(erro, e.getMessage());
            reajustar();
            return;
        }
        dizerErro(erro, "");
        ocupar(true);
        Tarefas.rodar(() -> gravacao.gravar(dados), resultado -> {
            ocupar(false);
            fechar();
            if (!resultado.mudouAlgo()) {
                avisos.info("Nada mudou.");
                return;
            }
            avisos.ok("Servidor " + resultado.config().nome() + " salvo. Reconectando…");
            if (resultado.impressaoApagada()) {
                avisos.atencao("O endereço mudou: confirme a impressão digital do servidor quando o app pedir.");
            }
        }, falha -> {
            ocupar(false);
            dizerErro(erro, MensagensDeErro.de(falha));
            reajustar();
        });
    }

    /** As mesmas validações do assistente: {@link Dados#validar}. */
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

    private void ocupar(boolean sim) {
        ocupado = sim;
        for (JComponent c : new JComponent[] {nome, host, porta, usuario, portaDoAgente, cancelar}) {
            c.setEnabled(!sim);
        }
        instalarChave.setEnabled(!sim && !algoMudou());
        if (sim) {
            desfazerOcupado = salvar.ocupar();
        } else {
            desfazerOcupado.run();
            desfazerOcupado = () -> { };
            salvar.setEnabled(algoMudou());
        }
    }

    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
        }
    }

    @Override
    protected void antesDeMostrar() {
        nome.requestFocusInWindow();
    }
}
