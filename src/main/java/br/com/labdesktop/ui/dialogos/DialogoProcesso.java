package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.FalhaDeProcesso;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.processos.Via;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.FichaRotulada;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * "Encerrar" e "Reiniciar" um processo: a confirmação que mostra o NOME, o NÚMERO, o DONO, o projeto ou container, a hora em que o programa começou
 * e O QUE VAI ACONTECER, antes de qualquer envio. Ao abrir só LÊ (a identidade real do processo). Encerrar pede primeiro ao programa para fechar
 * (SIGTERM), espera mostrando o progresso e, se ele continuar rodando, só oferece "Forçar" (SIGKILL) por OUTRO clique. Processo de outro usuário:
 * "sem permissão" com o comando exato para o usuário rodar (o app nunca usa sudo nem digita senha). Resultado sempre claro: encerrou, ainda rodando,
 * sem permissão, já tinha saído, não é mais o mesmo processo.
 *
 * <p>Nunca fica preso: Esc, o X e o botão, com a ação em andamento, primeiro PEDEM para parar de esperar; o segundo clique fecha e a ação termina
 * em segundo plano, com o resultado nos avisos. Clique duplo não age duas vezes. Nada de rede na EDT.
 */
public final class DialogoProcesso extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        Previa preparar() throws FalhaDeProcesso, InterruptedException;

        Resultado encerrar(Previa previa, ObservadorDeProcesso observador, Cancelamento cancelamento) throws InterruptedException;

        Resultado forcar(Previa previa, ObservadorDeProcesso observador, Cancelamento cancelamento) throws InterruptedException;

        Resultado reiniciar(Previa previa, ObservadorDeProcesso observador, Cancelamento cancelamento) throws InterruptedException;
    }

    private static final int LARGURA = 500;

    private final ProcessoAlvo alvo;
    private final Oferta oferta;
    private final String origem;
    private final String rotuloDoForcar;
    private final Motor motor;
    private final Notificacoes avisos;
    private final Runnable aoTerminar;
    private final Rotulo situacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final FichaRotulada ficha = new FichaRotulada(2, 20);
    private final TextoCorrido explicacao = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido servico = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido bloqueio = erroCorrido();
    private final TextoCorrido avisoDeAdministrador = erroCorrido();
    private final Rotulo pedidoDeConfirmacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JTextField campoDaConfirmacao = Campos.mono(30, "digite o nome");
    private final boolean administrador;
    private final String nomeParaDigitar;
    private final Rotulo tituloDoComando = new Rotulo("Rode no Terminal, com a sua permissão").negrito();
    private final AreaMonoespacada areaDoComando = new AreaMonoespacada("Comando para você rodar", true);
    private final JScrollPane rolagemDoComando = AreaMonoespacada.emRolagem(areaDoComando);
    private final Botao copiarComando;
    private final JProgressBar progresso = new JProgressBar();
    private final Rotulo textoDoProgresso = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Selo selo = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido textoDoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JComponent blocoDoResultado;
    private final Botao encerrar = Botoes.perigo("Encerrar", "parar");
    private final Botao reiniciar = Botoes.secundario("Reiniciar", "atualizar");
    private final Botao forcar;
    private final Botao proteger = Botoes.secundario("Proteger este container", "chave");
    private final Botao fechar = Botoes.primario("Cancelar", null);
    private volatile Cancelamento cancelamento = new Cancelamento();
    private Previa previa;
    private Resultado ultimo;
    private boolean ocupado;
    private boolean pediuParar;
    private boolean terminouAlgo;
    private Runnable aoProteger;

    /**
     * @param origem         o texto de "Projeto / container" como a lista o mostra
     * @param acaoInicial    qual botão nasce em destaque ({@link Acao#ENCERRAR} ou {@link Acao#REINICIAR}); os dois existem quando valem
     * @param rotuloDoForcar o texto do botão de forçar ({@code Forçar (SIGKILL)}, ou o do taskkill no Windows)
     * @param aoTerminar     roda (na EDT) quando a janela fecha depois de alguma ação ter acontecido: a lista se atualiza
     */
    public DialogoProcesso(ProcessoAlvo alvo, Oferta oferta, Acao acaoInicial, String origem, String rotuloDoForcar, Motor motor, CopiadorDeTexto copiador,
                           Notificacoes avisos, Runnable aoTerminar) {
        this(alvo, oferta, acaoInicial, origem, rotuloDoForcar, false, motor, copiador, avisos, aoTerminar);
    }

    /**
     * @param administrador o usuário da conexão é o administrador (root): o app avisa, em destaque, e só liga os botões depois de o usuário DIGITAR o nome do programa
     *                      (ou do container), como no "Remover endereço": como root, tudo é alcançável, e o clique rápido custa caro
     */
    public DialogoProcesso(ProcessoAlvo alvo, Oferta oferta, Acao acaoInicial, String origem, String rotuloDoForcar, boolean administrador, Motor motor, CopiadorDeTexto copiador,
                           Notificacoes avisos, Runnable aoTerminar) {
        super(tituloDe(oferta, acaoInicial));
        this.administrador = administrador && oferta.situacao() == Oferta.Situacao.LIBERADO;
        this.nomeParaDigitar = oferta.via() == Via.CONTAINER ? String.valueOf(alvo.container()) : alvo.nomeCurto();
        this.alvo = alvo;
        this.oferta = oferta;
        this.origem = origem;
        this.rotuloDoForcar = rotuloDoForcar;
        this.motor = motor;
        this.avisos = avisos;
        this.aoTerminar = aoTerminar == null ? () -> { } : aoTerminar;
        this.forcar = Botoes.perigo(rotuloDoForcar, "parar");
        this.copiarComando = BotaoCopiar.criar("Copiar o comando", areaDoComando::getText, copiador, avisos);
        encerrar.setName("encerrar");
        reiniciar.setName("reiniciar");
        forcar.setName("forcar");
        fechar.setName("fechar");
        proteger.setName("proteger");
        proteger.setVisible(false);
        proteger.setToolTipText("O app deixa de oferecer parar e reiniciar este container neste servidor");
        encerrar.setText(oferta.via() == Via.CONTAINER ? "Parar o container" : "Encerrar");
        reiniciar.setText(oferta.via() == Via.CONTAINER ? "Reiniciar o container" : "Reiniciar");
        for (JComponent c : List.of(encerrar, reiniciar, forcar, ficha, explicacao, servico, bloqueio, avisoDeAdministrador, pedidoDeConfirmacao, campoDaConfirmacao, tituloDoComando,
            rolagemDoComando, copiarComando, progresso, textoDoProgresso)) {
            c.setVisible(false);
        }
        campoDaConfirmacao.putClientProperty("html.disable", Boolean.TRUE);
        campoDaConfirmacao.getAccessibleContext().setAccessibleName("Digite o nome para confirmar");
        campoDaConfirmacao.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(this::conferirAConfirmacao));
        rolagemDoComando.setPreferredSize(new java.awt.Dimension(LARGURA, 92));
        rolagemDoComando.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); // o comando comprido quebra dentro da caixa, nunca é cortado
        progresso.setIndeterminate(true);
        blocoDoResultado = Caixa.coluna(6, Caixa.linha(8, selo, Caixa.mola()), textoDoResultado);
        blocoDoResultado.setVisible(false);
        String titulo = tituloDe(oferta, acaoInicial);
        JComponent corpo = Caixa.coluna(10, cabecalho(acaoInicial == Acao.REINICIAR ? "atualizar" : "parar", titulo), situacao, ficha, explicacao, servico, avisoDeAdministrador,
            pedidoDeConfirmacao, campoDaConfirmacao, bloqueio, tituloDoComando, rolagemDoComando, Caixa.linha(8, copiarComando, Caixa.mola()), progresso, textoDoProgresso, blocoDoResultado);
        conteudoComRodape(corpo, rodape(proteger, fechar, forcar, reiniciar, encerrar));
        botaoPadrao(fechar); // o Enter aciona o seguro, nunca o que mata
        fechar.addActionListener(e -> aoCancelar());
        proteger.addActionListener(e -> {
            if (!ocupado && aoProteger != null) {
                aoProteger.run();
                fechar();
            }
        });
        encerrar.addActionListener(e -> agir(Acao.ENCERRAR));
        reiniciar.addActionListener(e -> agir(Acao.REINICIAR));
        forcar.addActionListener(e -> forcar());
        carregar();
    }

    /** Liga o botão "Proteger este container" (só para container que o app deixa agir): o clique marca o container como protegido neste servidor e fecha o diálogo. */
    public void permitirProteger(Runnable aoProteger) {
        this.aoProteger = aoProteger;
        proteger.setVisible(aoProteger != null && oferta.via() == Via.CONTAINER && oferta.situacao() == Oferta.Situacao.LIBERADO);
    }

    /** "Encerrar processo" para um programa; "Parar o container" e "Reiniciar o container" quando a ação é no container (que não tem número de processo). */
    private static String tituloDe(Oferta oferta, Acao acao) {
        if (oferta.via() == Via.CONTAINER) {
            return acao == Acao.REINICIAR ? "Reiniciar o container" : "Parar o container";
        }
        return acao == Acao.REINICIAR ? "Reiniciar" : "Encerrar processo";
    }

    // =====================================================================================================
    // Ler (só leitura)
    // =====================================================================================================

    private void carregar() {
        situacao.setText("Lendo o processo (só leitura)…");
        Tarefas.rodar(motor::preparar, p -> {
            if (jaFechou()) {
                return;
            }
            previa = p;
            situacao.setVisible(false);
            mostrarOsFatos(p);
            explicacao.setText(oferta.explicacao());
            explicacao.setVisible(true);
            if (p.temServico() && oferta.situacao() == Oferta.Situacao.LIBERADO) {
                servico.setText("Este programa pertence ao serviço " + p.unidade() + " do seu usuário no systemd. Reiniciar o serviço o fecha e o religa pelo systemd.");
                servico.setVisible(true);
                reiniciar.setText("Reiniciar o serviço");
            }
            if (oferta.situacao() == Oferta.Situacao.SEM_PERMISSAO) {
                mostrarComando(oferta.comandoParaODono());
                fechar.setText("Fechar");
            } else {
                encerrar.setVisible(true);
                reiniciar.setVisible(p.temServico() || oferta.reiniciarLigado());
                if (administrador) {
                    avisoDeAdministrador.setText("Você está conectado como administrador (root). Como administrador o app alcança qualquer programa, e encerrar o errado "
                        + "pode derrubar o servidor. Confira o nome acima e digite-o para liberar os botões.");
                    avisoDeAdministrador.setVisible(true);
                    pedidoDeConfirmacao.setText("Para confirmar, digite: " + nomeParaDigitar);
                    pedidoDeConfirmacao.setVisible(true);
                    campoDaConfirmacao.setVisible(true);
                    conferirAConfirmacao();
                }
            }
            reajustar();
        }, falha -> {
            if (jaFechou()) {
                return;
            }
            situacao.setVisible(false);
            if (falha instanceof FalhaDeProcesso f) {
                mostrarFalhaDeLeitura(f);
            } else {
                dizerErro(bloqueio, MensagensDeErro.de(falha));
            }
            fechar.setText("Fechar");
            reajustar();
        });
    }

    private void mostrarFalhaDeLeitura(FalhaDeProcesso f) {
        dizerErro(bloqueio, f.getMessage());
        if (!f.comandoParaODono().isBlank()) {
            mostrarComando(f.comandoParaODono());
        }
        terminouAlgo = terminouAlgo || f.desfecho() == Desfecho.JA_SAIU || f.desfecho() == Desfecho.MUDOU; // a lista está desatualizada: relê ao fechar
    }

    private void mostrarOsFatos(Previa p) {
        List<FichaRotulada.Item> itens = new ArrayList<>();
        if (oferta.via() == Via.CONTAINER) {
            // a ação é no container: não há número de processo nem dono para conferir, e a tela não finge que há
            itens.add(FichaRotulada.Item.de("Container", String.valueOf(alvo.container())));
            itens.add(FichaRotulada.Item.de("Projeto", alvo.projeto() == null ? "(nenhum)" : alvo.projeto()));
            itens.add(FichaRotulada.Item.de("Programa escolhido", alvo.nome().isBlank() ? "(sem nome)" : alvo.nomeCurto()));
            ficha.definir(itens);
            return;
        }
        itens.add(FichaRotulada.Item.de("Programa", alvo.nome().isBlank() ? "(sem nome)" : alvo.nomeCurto()));
        itens.add(FichaRotulada.Item.de("Número do processo (PID)", String.valueOf(alvo.pid())));
        itens.add(FichaRotulada.Item.de("Dono (conferido agora)", donoConferido(p)));
        itens.add(FichaRotulada.Item.de("Projeto / container", origem));
        if (!p.iniciouEm().isBlank()) {
            itens.add(FichaRotulada.Item.de("Começou em", p.iniciouEmParaATela()));
        }
        ficha.definir(itens);
    }

    /** O usuário que o SISTEMA disse ao abrir (o número do usuário no servidor; o nome em Este computador), junto do nome que a lista mostrou. */
    private String donoConferido(Previa p) {
        String lido = p.identidade() == null ? "" : p.identidade().uid();
        String lista = alvo.usuario();
        if (lido.isBlank()) {
            return lista.isBlank() ? "(a lista não disse)" : lista;
        }
        if (lido.matches("[0-9]+")) {
            return (lista.isBlank() ? "" : lista + " ") + "(usuário nº " + lido + ")";
        }
        return lido;
    }

    private void conferirAConfirmacao() {
        boolean liberado = !administrador || campoDaConfirmacao.getText().strip().equals(nomeParaDigitar);
        if (ocupado || previa == null) {
            return;
        }
        encerrar.setEnabled(liberado);
        reiniciar.setEnabled(liberado);
    }

    private void mostrarComando(String comando) {
        areaDoComando.setText(comando);
        areaDoComando.setCaretPosition(0);
        tituloDoComando.setVisible(true);
        rolagemDoComando.setVisible(true);
        copiarComando.setVisible(true);
    }

    // =====================================================================================================
    // Agir
    // =====================================================================================================

    private void agir(Acao acao) {
        if (ocupado || previa == null || jaFechou() || !confirmado()) {
            return;
        }
        Previa p = previa;
        comecar(acao == Acao.REINICIAR ? "Reiniciando…" : "Enviando o pedido…");
        ObservadorDeProcesso obs = observador();
        Cancelamento meu = cancelamento;
        Tarefas.rodar(() -> acao == Acao.REINICIAR ? motor.reiniciar(p, obs, meu) : motor.encerrar(p, obs, meu), this::aoTerminarAcao, falha -> aoTerminarAcao(Resultado.de(Desfecho.FALHOU, MensagensDeErro.de(falha))));
    }

    private void forcar() {
        if (ocupado || previa == null || jaFechou() || !confirmado()) {
            return;
        }
        Previa p = previa;
        comecar("Forçando…");
        ObservadorDeProcesso obs = observador();
        Cancelamento meu = cancelamento;
        Tarefas.rodar(() -> motor.forcar(p, obs, meu), this::aoTerminarAcao, falha -> aoTerminarAcao(Resultado.de(Desfecho.FALHOU, MensagensDeErro.de(falha))));
    }

    /** Como administrador, só depois de digitar o nome certo (o botão já está desligado; isto garante também pelo teclado e pelo teste). */
    private boolean confirmado() {
        return !administrador || campoDaConfirmacao.getText().strip().equals(nomeParaDigitar);
    }

    private void comecar(String frase) {
        ocupado = true;
        pediuParar = false;
        cancelamento = new Cancelamento();
        for (JComponent c : List.of(encerrar, reiniciar, forcar)) {
            c.setEnabled(false);
        }
        blocoDoResultado.setVisible(false);
        bloqueio.setVisible(false);
        progresso.setIndeterminate(true);
        progresso.setVisible(true);
        textoDoProgresso.setText(frase);
        textoDoProgresso.setVisible(true);
        fechar.setText("Parar de esperar");
        reajustar();
    }

    private ObservadorDeProcesso observador() {
        return new ObservadorDeProcesso() {
            @Override
            public void etapa(String texto) {
                Fundo.naEdt(() -> {
                    textoDoProgresso.setText(texto);
                    reajustar();
                });
            }

            @Override
            public void aguardando(int passados, int total) {
                Fundo.naEdt(() -> {
                    progresso.setIndeterminate(false);
                    progresso.setMaximum(total);
                    progresso.setValue(passados);
                    textoDoProgresso.setText("Esperando o programa fechar: " + passados + " de " + total + " s…");
                });
            }
        };
    }

    private void aoTerminarAcao(Resultado r) {
        ocupado = false;
        ultimo = r;
        terminouAlgo = terminouAlgo || r.desfecho() != Desfecho.CANCELADO;
        if (jaFechou()) {
            if (r.desfecho().deuCerto()) {
                avisos.ok(r.mensagem());
            } else {
                avisos.atencao(r.desfecho().rotulo() + ": " + r.mensagem());
            }
            aoTerminar.run();
            return;
        }
        progresso.setVisible(false);
        textoDoProgresso.setVisible(false);
        pedidoDeConfirmacao.setVisible(false);
        campoDaConfirmacao.setVisible(false);
        blocoDoResultado.setVisible(true);
        selo.atualizar(r.desfecho().rotulo().toUpperCase(java.util.Locale.ROOT), gravidadeDe(r.desfecho()), null);
        textoDoResultado.setText(r.mensagem());
        if (!r.comandoParaODono().isBlank()) {
            mostrarComando(r.comandoParaODono());
        }
        fechar.setText("Fechar");
        encerrar.setVisible(false);
        reiniciar.setVisible(false);
        if (r.desfecho() == Desfecho.AINDA_RODANDO && oferta.via() == Via.PROCESSO) {
            forcar.setVisible(true);
            forcar.setEnabled(true);
        } else {
            forcar.setVisible(false);
        }
        reajustar();
    }

    private static Gravidade gravidadeDe(Desfecho d) {
        return switch (d) {
            case ENCERRADO, REINICIADO, JA_SAIU -> Gravidade.OK;
            case AINDA_RODANDO, MUDOU, SEM_CONFERENCIA, CANCELADO, SEM_PERMISSAO -> Gravidade.ATENCAO;
            case FALHOU, PROTEGIDO -> Gravidade.PERIGO;
        };
    }

    // =====================================================================================================
    // Fechar
    // =====================================================================================================

    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
            return;
        }
        if (!pediuParar) {
            pediuParar = true;
            cancelamento.cancelar();
            fechar.setText("Fechar mesmo assim");
            textoDoProgresso.setText("Parando de esperar… (o pedido que já saiu não volta)");
            reajustar();
            return;
        }
        avisos.info("A espera foi parada. O resultado aparece nos avisos quando terminar.");
        fechar();
    }

    @Override
    protected void aoFechar() {
        if (terminouAlgo) {
            aoTerminar.run();
        }
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public Botao botaoEncerrar() {
        return encerrar;
    }

    public Botao botaoReiniciar() {
        return reiniciar;
    }

    public Botao botaoForcar() {
        return forcar;
    }

    public Botao botaoFechar() {
        return fechar;
    }

    public Botao botaoProteger() {
        return proteger;
    }

    /** A caixa do comando quebra a linha comprida dentro dela (nada cortado, sem barra horizontal)? */
    public boolean comandoQuebraLinha() {
        return areaDoComando.getLineWrap() && rolagemDoComando.getHorizontalScrollBarPolicy() == javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER;
    }

    public JTextField campoDaConfirmacao() {
        return campoDaConfirmacao;
    }

    public String textoDoAvisoDeAdministrador() {
        return avisoDeAdministrador.isVisible() ? avisoDeAdministrador.getText() : "";
    }

    public boolean ocupado() {
        return ocupado;
    }

    public boolean fechou() {
        return jaFechou();
    }

    public List<String> fatos() {
        List<String> l = new ArrayList<>();
        for (List<String> par : ficha.pares()) {
            l.add(par.get(0) + ": " + par.get(1));
        }
        return l;
    }

    public String textoDaExplicacao() {
        return explicacao.isVisible() ? explicacao.getText() : "";
    }

    public String textoDoBloqueio() {
        return bloqueio.isVisible() ? bloqueio.getText() : "";
    }

    public String textoDoComando() {
        return rolagemDoComando.isVisible() ? areaDoComando.getText() : "";
    }

    public String textoDoServico() {
        return servico.isVisible() ? servico.getText() : "";
    }

    public String seloDoResultado() {
        return blocoDoResultado.isVisible() ? selo.texto() : "";
    }

    public String textoDoResultado() {
        return blocoDoResultado.isVisible() ? textoDoResultado.getText() : "";
    }

    public String textoDoProgresso() {
        return textoDoProgresso.isVisible() ? textoDoProgresso.getText() : "";
    }

    public Resultado ultimoResultado() {
        return ultimo;
    }

    public String rotuloDoForcar() {
        return rotuloDoForcar;
    }
}
