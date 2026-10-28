package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.instalador.Passo;
import br.com.labdesktop.instalador.ResultadoDaInstalacao;
import br.com.labdesktop.instalador.ResultadoDaRemocao;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Estado;
import br.com.labdesktop.local.InstaladorDeRequisitos;
import br.com.labdesktop.local.LaboratorioLocal;
import br.com.labdesktop.local.ParametrosDoLaboratorio;
import br.com.labdesktop.local.PassoDaRemocaoLocal;
import br.com.labdesktop.local.PassoDoLaboratorio;
import br.com.labdesktop.local.PlanoDeInstalacao;
import br.com.labdesktop.local.RelatorioDoComputador;
import br.com.labdesktop.local.ResultadoDoComputador;
import br.com.labdesktop.seguranca.TextoSeguro;
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
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * "Preparar este computador" (e "Remover laboratório local"): a tela do servidor "Este computador". <b>Regra: NADA SE
 * INSTALA SOZINHO.</b> A tela abre pelo clique dele e começa só LENDO; cada instalação (o Docker, o git) mostra o que será feito, com os
 * comandos exatos, e só roda no botão da confirmação.
 * <ol>
 *   <li><b>Conferindo</b> (só leitura): Docker, Compose, disco (5 GB), memória (4 GB), virtualização e WSL 2 (Windows), a porta do agente e o
 *       git, cada um com marca (ok, aviso, problema) e uma frase simples.</li>
 *   <li><b>Resultado</b>: o que falta, com o botão do que o app propõe (Instalar o Docker Desktop, Abrir o Docker, Instalar o git) ou o comando
 *       exato para o usuário rodar. Com tudo certo, "Criar meu laboratório local" e a lista do que vai acontecer.</li>
 *   <li><b>Confirmar a instalação</b>: o plano com os comandos exatos, o aviso de que o sistema pode pedir a senha (do sistema, nunca do app) e
 *       reiniciar, e o botão com o nome do que será instalado. Há sempre Voltar.</li>
 *   <li><b>Andamento</b>: passos com estado ao vivo, log recolhível, Cancelar, sucesso ou o erro REAL, o que foi desfeito e Tentar de novo.</li>
 * </ol>
 * Tudo o que fala com o computador roda fora da EDT, numa thread que o Cancelar interrompe (e o processo morre junto).
 */
public final class DialogoPrepararComputador extends Dialogo {

    /** O que o usuário pediu. */
    public enum Acao { PREPARAR, REMOVER }

    /** O que a tela precisa do domínio, sem conhecer o {@code ServidorLab}: em produção, {@code AcoesDaInterface} entrega o dele. */
    public interface Motor {
        /** Só leitura. */
        RelatorioDoComputador verificar(String pastaDosProjetos, int porta) throws Exception;

        /** Roda o plano (depois do clique). */
        InstaladorDeRequisitos.Resultado instalar(PlanoDeInstalacao plano, ObservadorDaInstalacao observador, Cancelamento cancelamento);

        /** Abre o Docker e espera o motor subir; {@code false} se passou do prazo. */
        boolean abrirODocker(ObservadorDaInstalacao observador, Cancelamento cancelamento) throws InterruptedException;

        /** Monta "criar" e "remover" (nada acontece até {@code criar} ou {@code remover} serem chamados). */
        LaboratorioLocal laboratorio(ParametrosDoLaboratorio parametros);

        /** Por que esta pasta não serve como pasta dos projetos (relativa, com {@code ..}, a raiz, a pasta pessoal, do sistema); {@code null} se serve. */
        default String problemaDaPasta(String pasta) {
            return null;
        }

        /** Abre uma das páginas oficiais de download no navegador. */
        void abrirPagina(String url);

        /** Guarda a porta e a pasta escolhidas (o servidor "Este computador" e a memória de pastas); o laboratório pede depois que o agente responde. */
        void guardarEscolhas(int porta, String pastaDosProjetos) throws Exception;
    }

    /** O que o diálogo sabe do servidor local. */
    public record Alvo(String nomeDoServidor, int porta, String pastaDosProjetos, String container, String versaoDoPacote, boolean laboratorioExiste) { }

    private static final String PAGINA_CONFERINDO = "conferindo";
    private static final String PAGINA_RESULTADO = "resultado";
    private static final String PAGINA_PLANO = "plano";
    private static final String PAGINA_ANDAMENTO = "andamento";
    private static final String PAGINA_REMOVER = "remover";

    private static volatile int prazoParaFecharMesmoAssimMs = 8000;

    private final Acao acao;
    private final Alvo alvo;
    private final Motor motor;
    private final Runnable aoMudarOLaboratorio;
    private final Notificacoes avisos;
    private final CopiadorDeTexto copiador;

    private final CardLayout paginas = new CartoesAjustaveis();
    private final JPanel pilha = new Painel(paginas);
    private final CardLayout cartoesDosRodapes = new CartoesAjustaveis();
    private final JPanel pilhaDeRodapes = new Painel(cartoesDosRodapes);
    private String paginaAtual = PAGINA_CONFERINDO;

    // ---- conferindo ----
    private final Rotulo textoDeConferindo = new Rotulo("Conferindo este computador (só leitura)…").corDoToken(Tokens.TEXTO2);
    private final TextoCorrido erroDeConferir = erroCorrido();
    private final Botao tentarConferirDeNovo = Botoes.secundario("Tentar de novo", "atualizar");
    private final Botao fecharNaConferencia = Botoes.secundario("Fechar", null);

    // ---- resultado ----
    private final JPanel listaDeVerificacoes = new Painel();
    private final JPanel listaDoQueVaiAcontecer = new Painel();
    private final TextoCorrido avisoDeBloqueio = erroCorrido();
    private final TextoCorrido avisoDoLaboratorioQueJaRoda = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.OK);
    private final JTextField campoDaPorta = Campos.mono(8, "7070");
    private final JTextField campoDaPasta = Campos.mono(32, "");
    private final TextoCorrido erroDasOpcoes = erroCorrido();
    private final SecaoRecolhivel opcoesAvancadas;
    private final Botao verificarDeNovo = Botoes.secundario("Verificar de novo", "atualizar");
    private final Botao fecharNoResultado = Botoes.secundario("Fechar", null);
    private final Botao criar = Botoes.primario("Criar meu laboratório local", "docker");

    // ---- plano ----
    private final Rotulo tituloDoPlano = new Rotulo().negrito();
    private final TextoCorrido resumoDoPlano = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2);
    private final JPanel detalhesDoPlano = new Painel(new java.awt.BorderLayout());
    private final Botao voltarDoPlano = Botoes.secundario("Voltar", null);
    private final Botao instalarPlano = Botoes.primario("Instalar", "baixar");
    private PlanoDeInstalacao planoAtual;

    // ---- remover ----
    private final JTextField campoDeConfirmacao = Campos.mono(18, "");
    private final Botao cancelarRemocao = Botoes.primario("Cancelar", null);
    private final Botao remover;

    // ---- andamento ----
    private final Map<Passo, Rotulo> linhasDosPassos = new LinkedHashMap<>();
    private final Map<Passo, TextoCorrido> detalhesDosPassos = new java.util.HashMap<>();
    private final Map<Passo, EstadoDoPasso> estadosDosPassos = new java.util.HashMap<>();
    private final JPanel listaDePassos = new Painel();
    private final AreaMonoespacada areaDoLog = new AreaMonoespacada("Log completo", true);
    private final SecaoRecolhivel secaoDoLog;
    private final Rotulo tituloDoAndamento = new Rotulo();
    private final Rotulo situacaoFinal = new Rotulo();
    private final TextoCorrido mensagemFinal = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO);
    private final JPanel listaDoDesfeito = new Painel();
    private final Botao cancelarExecucao = Botoes.secundario("Cancelar", "parar");
    private final Botao tentarDeNovo = Botoes.secundario("Tentar de novo", "atualizar");
    private final Botao voltarAoResultado = Botoes.secundario("Voltar", null);
    private final Botao fecharAoFim = Botoes.fantasma("Fechar", null);
    private final Botao concluir = Botoes.primario("Concluir", "ok");
    private Runnable repetir = () -> { };
    private Runnable depoisDeSucesso = () -> { };

    private RelatorioDoComputador relatorio;
    private boolean executando;
    private boolean terminouComSucesso;
    private final AtomicBoolean cancelado = new AtomicBoolean();
    private javax.swing.Timer prazoDoCancelamento;
    private boolean podeFecharMesmoAssim;
    private final AtomicReference<Thread> trabalhador = new AtomicReference<>();
    private int geracaoDaConferencia;

    public DialogoPrepararComputador(Acao acao, Alvo alvo, Motor motor, Runnable aoMudarOLaboratorio, Notificacoes avisos, CopiadorDeTexto copiador) {
        super(acao == Acao.PREPARAR ? "Preparar este computador" : "Remover laboratório local");
        this.acao = acao;
        this.alvo = alvo;
        this.motor = motor;
        this.aoMudarOLaboratorio = aoMudarOLaboratorio;
        this.avisos = avisos;
        this.copiador = copiador;
        this.remover = Botoes.perigo("Remover " + alvo.container(), "lixeira");
        remover.setEnabled(false);
        campoDaPorta.setText(String.valueOf(alvo.porta()));
        campoDaPasta.setText(alvo.pastaDosProjetos());

        opcoesAvancadas = new SecaoRecolhivel("Opções avançadas", "Esconder as opções avançadas", montarOpcoesAvancadas(), this::reajustar);
        secaoDoLog = new SecaoRecolhivel("Ver o log completo", "Esconder o log", montarLog(), this::reajustar);

        pilha.add(montarPaginaConferindo(), PAGINA_CONFERINDO);
        pilha.add(montarPaginaResultado(), PAGINA_RESULTADO);
        pilha.add(montarPaginaDoPlano(), PAGINA_PLANO);
        pilha.add(montarPaginaDeRemocao(), PAGINA_REMOVER);
        pilha.add(montarPaginaDeAndamento(), PAGINA_ANDAMENTO);
        pilhaDeRodapes.add(rodape(null, fecharNaConferencia, tentarConferirDeNovo), PAGINA_CONFERINDO);
        pilhaDeRodapes.add(rodape(verificarDeNovo, fecharNoResultado, criar), PAGINA_RESULTADO);
        pilhaDeRodapes.add(rodape(null, voltarDoPlano, instalarPlano), PAGINA_PLANO);
        pilhaDeRodapes.add(rodape(null, cancelarRemocao, remover), PAGINA_REMOVER);
        pilhaDeRodapes.add(rodape(null, cancelarExecucao, voltarAoResultado, fecharAoFim, tentarDeNovo, concluir), PAGINA_ANDAMENTO);
        conteudoComRodape(pilha, pilhaDeRodapes);

        ligarEventos();
        if (acao == Acao.REMOVER) {
            mostrarPagina(PAGINA_REMOVER);
        } else {
            mostrarPagina(PAGINA_CONFERINDO);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private JComponent montarOpcoesAvancadas() {
        return Caixa.coluna(6,
            Rotulo.rotuloDeCampo("Porta do agente (em 127.0.0.1)"), campoDaPorta,
            dicaCorrida("A porta que o Docker publica SÓ neste computador. O padrão é 7070: no macOS a 7000 é do AirPlay."),
            Rotulo.rotuloDeCampo("Pasta dos projetos"), campoDaPasta,
            dicaCorrida("Onde os projetos locais ficam. O app a cria e a mostra ao container do agente no mesmo caminho."), erroDasOpcoes);
    }

    private JComponent montarLog() {
        JScrollPane rolagem = AreaMonoespacada.emRolagem(areaDoLog);
        rolagem.setPreferredSize(new Dimension(LARGURA_DO_TEXTO - 20, 170));
        return rolagem;
    }

    private JComponent montarPaginaConferindo() {
        return Caixa.coluna(14,
            cabecalho("sistema", "Preparar este computador"),
            paragrafo("O app confere o que este computador precisa para rodar o laboratório. É só leitura: nada é instalado nem alterado agora."),
            textoDeConferindo, erroDeConferir);
    }

    private JComponent montarPaginaResultado() {
        listaDeVerificacoes.setLayout(new javax.swing.BoxLayout(listaDeVerificacoes, javax.swing.BoxLayout.Y_AXIS));
        listaDoQueVaiAcontecer.setLayout(new javax.swing.BoxLayout(listaDoQueVaiAcontecer, javax.swing.BoxLayout.Y_AXIS));
        return Caixa.coluna(10,
            cabecalho("sistema", "Preparar este computador"),
            paragrafo("O laboratório roda aqui, num container do Docker. NADA foi instalado nem criado agora: confira abaixo. Cada instalação mostra "
                + "o que vai fazer e só roda depois do seu clique."),
            avisoDoLaboratorioQueJaRoda,
            Rotulo.rotuloDeCampo("O que o app conferiu"), listaDeVerificacoes, avisoDeBloqueio,
            Rotulo.rotuloDeCampo("O que vai acontecer quando você criar o laboratório"), listaDoQueVaiAcontecer, opcoesAvancadas);
    }

    private JComponent montarPaginaDoPlano() {
        return Caixa.coluna(12, cabecalho("baixar", "Confirmar a instalação"), tituloDoPlano, resumoDoPlano, detalhesDoPlano);
    }

    private JComponent montarPaginaDeRemocao() {
        campoDeConfirmacao.setToolTipText("Digite o nome do agente para liberar o botão");
        return Caixa.coluna(12,
            cabecalho("lixeira", "Remover laboratório local"),
            paragrafo("O app vai remover o agente (container " + alvo.container() + "), os dados dele (o token deste app no agente e o 2FA do agente), "
                + "a imagem e a rede " + ParametrosDoLaboratorio.REDE_PADRAO + " (só se for do app e estiver vazia)."),
            dicaCorrida("Os seus projetos em " + alvo.pastaDosProjetos() + " NÃO são apagados, e o Docker continua instalado. Este computador continua na "
                + "lista, e você pode criar o laboratório de novo quando quiser."),
            Rotulo.rotuloDeCampo("Para confirmar, digite o nome do agente: " + alvo.container()), campoDeConfirmacao);
    }

    private JComponent montarPaginaDeAndamento() {
        listaDePassos.setLayout(new javax.swing.BoxLayout(listaDePassos, javax.swing.BoxLayout.Y_AXIS));
        listaDoDesfeito.setLayout(new javax.swing.BoxLayout(listaDoDesfeito, javax.swing.BoxLayout.Y_AXIS));
        mensagemFinal.setVisible(false);
        situacaoFinal.setVisible(false);
        return Caixa.coluna(14, cabecalho("sistema", "Preparar este computador"), tituloDoAndamento, listaDePassos, situacaoFinal, mensagemFinal,
            listaDoDesfeito, secaoDoLog);
    }

    private void ligarEventos() {
        fecharNaConferencia.addActionListener(e -> aoCancelar());
        tentarConferirDeNovo.addActionListener(e -> conferir());
        fecharNoResultado.addActionListener(e -> aoCancelar());
        verificarDeNovo.addActionListener(e -> conferir());
        criar.addActionListener(e -> criarOLaboratorio());
        voltarDoPlano.addActionListener(e -> mostrarPagina(PAGINA_RESULTADO));
        instalarPlano.addActionListener(e -> aoClicarEmInstalarOPlano());
        cancelarRemocao.addActionListener(e -> aoCancelar());
        remover.addActionListener(e -> removerOLaboratorio());
        campoDeConfirmacao.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(
            () -> remover.setEnabled(!executando && campoDeConfirmacao.getText().strip().equals(alvo.container()))));
        cancelarExecucao.addActionListener(e -> aoCancelar());
        voltarAoResultado.addActionListener(e -> conferir());
        fecharAoFim.addActionListener(e -> fechar());
        concluir.addActionListener(e -> fechar());
        tentarDeNovo.addActionListener(e -> repetir.run());
    }

    private void mostrarPagina(String nome) {
        paginaAtual = nome;
        paginas.show(pilha, nome);
        cartoesDosRodapes.show(pilhaDeRodapes, nome);
        rolarParaOTopo();
        botaoPadrao(switch (nome) {
            case PAGINA_RESULTADO -> criar.isEnabled() ? criar : fecharNoResultado;
            case PAGINA_PLANO -> instalarPlano;
            case PAGINA_REMOVER -> cancelarRemocao;
            case PAGINA_ANDAMENTO -> concluir.isVisible() ? concluir : cancelarExecucao;
            default -> fecharNaConferencia;
        });
        reajustar();
    }

    /** Começa a conferir (só leitura). Chamado por quem abre o diálogo. */
    public void iniciarConferencia() {
        if (acao == Acao.PREPARAR) {
            conferir();
        }
    }

    @Override
    protected void antesDeMostrar() {
        iniciarConferencia();
    }

    // ---------------------------------------------------------------------------------------------
    // Conferir (só leitura)
    // ---------------------------------------------------------------------------------------------

    private void conferir() {
        if (executando) {
            return;
        }
        int porta;
        String pasta = campoDaPasta.getText().strip();
        try {
            porta = Integer.parseInt(campoDaPorta.getText().strip());
            new ParametrosDoLaboratorio(alvo.container(), porta, ParametrosDoLaboratorio.REDE_PADRAO, ParametrosDoLaboratorio.IMAGEM_PADRAO, pasta);
        } catch (RuntimeException e) {
            opcoesAvancadas.definirAberta(true);
            dizerErro(erroDasOpcoes, e instanceof NumberFormatException ? "A porta deve ser um número." : e.getMessage());
            mostrarPagina(PAGINA_RESULTADO);
            return;
        }
        String problemaDaPasta = motor.problemaDaPasta(pasta);
        if (problemaDaPasta != null) {
            opcoesAvancadas.definirAberta(true);
            dizerErro(erroDasOpcoes, problemaDaPasta);
            criar.setEnabled(false);
            mostrarPagina(PAGINA_RESULTADO);
            return;
        }
        dizerErro(erroDasOpcoes, "");
        dizerErro(erroDeConferir, "");
        tentarConferirDeNovo.setVisible(false);
        textoDeConferindo.setVisible(true);
        mostrarPagina(PAGINA_CONFERINDO);
        int minha = ++geracaoDaConferencia;
        int portaFinal = porta;
        Fundo.emFundoComResultado(() -> motor.verificar(pasta, portaFinal)).whenComplete((r, falha) -> Fundo.naEdt(() -> {
            if (minha != geracaoDaConferencia || jaFechou()) {
                return;
            }
            if (falha != null) {
                Throwable causa = falha instanceof java.util.concurrent.CompletionException && falha.getCause() != null ? falha.getCause() : falha;
                textoDeConferindo.setVisible(false);
                dizerErro(erroDeConferir, "Não consegui conferir este computador: " + MensagensDeErro.de(causa));
                tentarConferirDeNovo.setVisible(true);
                reajustar();
                return;
            }
            this.relatorio = r;
            montarResultado(r);
            mostrarPagina(PAGINA_RESULTADO);
        }));
    }

    private void montarResultado(RelatorioDoComputador r) {
        listaDeVerificacoes.removeAll();
        for (ResultadoDoComputador v : r.resultados()) {
            listaDeVerificacoes.add(linhaDaVerificacao(v, r));
            listaDeVerificacoes.add(Caixa.espacoVertical(6));
        }
        boolean bloqueado = r.bloqueado();
        criar.setEnabled(r.pronto());
        String aviso = "";
        if (bloqueado) {
            aviso = "Há " + (r.problemas().size() == 1 ? "um problema" : r.problemas().size() + " problemas") + " que impede"
                + (r.problemas().size() == 1 ? "" : "m") + " de criar o laboratório. Resolva e clique em Verificar de novo.";
        } else if (!r.dockerPronto()) {
            aviso = "O Docker ainda não responde.";
        }
        dizerErro(avisoDeBloqueio, aviso);
        boolean jaRoda = r.laboratorio().rodando() || alvo.laboratorioExiste();
        avisoDoLaboratorioQueJaRoda.setName("laboratorio-ja-roda");
        avisoDoLaboratorioQueJaRoda.setText(jaRoda
            ? "Seu laboratório local já está rodando" + (r.laboratorio().rodando() && r.laboratorio().portaPublicada() > 0
                ? " (agente " + r.laboratorio().versao() + ", porta " + r.laboratorio().portaPublicada() + ")" : "") + ". Se clicar em Criar de novo, o app só confere "
            + "tudo: não estraga nada, e só recria o agente se a versão do app, a porta ou a pasta dos projetos mudou (os dados e o token ficam)."
            : "");
        avisoDoLaboratorioQueJaRoda.setVisible(jaRoda);
        montarOQueVaiAcontecer();
        reajustar();
    }

    private Component linhaDaVerificacao(ResultadoDoComputador v, RelatorioDoComputador r) {
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
            .corDoToken(v.estado() == Estado.PROBLEMA ? Tokens.PERIGO : Tokens.TEXTO).tamanho(12.5f);
        List<Component> coluna = new ArrayList<>();
        coluna.add(frase);
        if (v.comandoSugerido() != null) {
            TextoCorrido cmd = new TextoCorrido(v.comandoSugerido(), LARGURA_DO_TEXTO - 160).mono(12f);
            cmd.setName("comando-sugerido");
            Botao copiar = BotaoCopiar.criar("Copiar comando", v::comandoSugerido, copiador, avisos);
            coluna.add(Caixa.linha(8, cmd, copiar));
        }
        List<Component> botoes = new ArrayList<>();
        if (v.oferecerAbrirDocker()) {
            Botao abrir = Botoes.miniPrimario("Abrir o Docker", "docker");
            abrir.setName("abrir-o-docker");
            abrir.addActionListener(e -> abrirODocker());
            botoes.add(abrir);
        }
        for (PlanoDeInstalacao plano : v.planos()) {
            Botao b = plano.recomendado() ? Botoes.miniPrimario(plano.titulo(), "baixar") : Botoes.mini(plano.titulo(), "baixar");
            b.setName("plano-" + plano.id());
            b.addActionListener(e -> mostrarOPlano(plano));
            botoes.add(b);
        }
        if (v.item() == br.com.labdesktop.local.ItemDoComputador.PORTA && v.estado() == Estado.PROBLEMA && r.portaLivreSugerida() > 0) {
            Botao usar = Botoes.miniPrimario("Usar a porta " + r.portaLivreSugerida(), "ok");
            usar.setName("usar-porta-sugerida");
            usar.addActionListener(e -> {
                campoDaPorta.setText(String.valueOf(r.portaLivreSugerida()));
                conferir();
            });
            botoes.add(usar);
        }
        for (Component b : botoes) {
            coluna.add(Caixa.linha(0, b, Caixa.mola())); // um por linha: os rótulos dos planos são compridos e não cabem lado a lado
        }
        return Caixa.linha(8, marca, Caixa.coluna(4, coluna.toArray(Component[]::new)));
    }

    private br.com.labdesktop.local.SistemaOperacional sistemaDoComputador() {
        return relatorio != null ? relatorio.sistema() : br.com.labdesktop.local.SistemaOperacional.detectar();
    }

    private void montarOQueVaiAcontecer() {
        listaDoQueVaiAcontecer.removeAll();
        String pasta = campoDaPasta.getText().strip();
        String porta = campoDaPorta.getText().strip();
        List<String> itens = List.of(
            "1. Criar a pasta dos projetos (" + pasta + "), se ela não existir.",
            "2. Criar a rede " + ParametrosDoLaboratorio.REDE_PADRAO + " do Docker (vazia: os projetos entram nela depois), se ela não existir.",
            "3. Construir a imagem do agente (versão " + alvo.versaoDoPacote() + ", leva alguns minutos; o pacote vai direto ao Docker).",
            "4. Subir o agente (container " + alvo.container() + ") escutando SÓ em 127.0.0.1:" + porta + ", com reinício automático e os dados num "
                + "volume do Docker.",
            "5. Conferir que o agente responde e registrar o token deste app (o agente guarda só o hash).");
        for (String t : itens) {
            listaDoQueVaiAcontecer.add(dicaCorrida(t));
            listaDoQueVaiAcontecer.add(Caixa.espacoVertical(3));
        }
        for (String p : br.com.labdesktop.local.AvisoDeSeguranca.paragrafos(sistemaDoComputador(), pasta)) {
            TextoCorrido t = dicaCorrida(p);
            t.setName("aviso-de-seguranca");
            listaDoQueVaiAcontecer.add(t);
            listaDoQueVaiAcontecer.add(Caixa.espacoVertical(3));
        }
        listaDoQueVaiAcontecer.add(dicaCorrida("Se algum passo falhar, o app mostra o erro e desfaz só o que ele criou. Rodar de novo não estraga nada."));
    }

    // ---------------------------------------------------------------------------------------------
    // Planos de instalação (só o clique roda)
    // ---------------------------------------------------------------------------------------------

    private void mostrarOPlano(PlanoDeInstalacao plano) {
        planoAtual = plano;
        tituloDoPlano.setText(plano.titulo());
        resumoDoPlano.setText(plano.resumo());
        detalhesDoPlano.removeAll();
        List<Component> d = new ArrayList<>();
        if (plano.executavelPeloApp()) {
            d.add(Rotulo.rotuloDeCampo("O app vai rodar exatamente isto"));
            for (String c : plano.comandosParaTela()) {
                TextoCorrido cmd = new TextoCorrido(c, LARGURA_DO_TEXTO - 160).mono(12f);
                cmd.setName("comando-do-plano");
                d.add(Caixa.linha(8, cmd, BotaoCopiar.criar("Copiar comando", () -> c, copiador, avisos)));
            }
        }
        if (plano.paginaParaAbrir() != null) {
            d.add(Rotulo.rotuloDeCampo("Página oficial"));
            TextoCorrido url = new TextoCorrido(plano.paginaParaAbrir(), LARGURA_DO_TEXTO).mono(12f);
            url.setName("pagina-do-plano");
            d.add(url);
        }
        if (plano.comandoParaCopiar() != null) {
            d.add(Rotulo.rotuloDeCampo("Comando para você rodar"));
            TextoCorrido cmd = new TextoCorrido(plano.comandoParaCopiar(), LARGURA_DO_TEXTO - 160).mono(12f);
            d.add(Caixa.linha(8, cmd, BotaoCopiar.criar("Copiar comando", plano::comandoParaCopiar, copiador, avisos)));
        }
        if (!plano.passosManuais().isEmpty()) {
            d.add(Rotulo.rotuloDeCampo(plano.executavelPeloApp() ? "Depois" : "Passos"));
            for (String p : plano.passosManuais()) {
                d.add(dicaCorrida("• " + p));
            }
        }
        if (plano.pedeSenhaDoSistema()) {
            d.add(dicaCorrida("O sistema pode pedir a sua senha de administrador. Isso é do sistema, não do app: o app nunca vê, digita "
                + "nem guarda essa senha."));
        }
        if (plano.precisaReiniciar()) {
            d.add(dicaCorrida("Pode ser preciso reiniciar o computador. O app não reinicia nada sozinho."));
        }
        detalhesDoPlano.add(Caixa.coluna(8, d.toArray(Component[]::new)), java.awt.BorderLayout.NORTH);
        instalarPlano.setText(plano.executavelPeloApp() ? plano.titulo() : "Abrir a página oficial");
        instalarPlano.setVisible(plano.executavelPeloApp() || plano.paginaParaAbrir() != null);
        mostrarPagina(PAGINA_PLANO);
    }

    private void aoClicarEmInstalarOPlano() {
        PlanoDeInstalacao plano = planoAtual;
        if (plano == null || executando) {
            return;
        }
        if (!plano.executavelPeloApp()) {
            if (plano.paginaParaAbrir() != null) {
                motor.abrirPagina(plano.paginaParaAbrir());
            }
            return;
        }
        prepararAndamento("Instalando: " + plano.titulo(), List.of(), () -> aoClicarEmInstalarOPlano());
        executarEmFundo(obs -> {
            InstaladorDeRequisitos.Resultado r = motor.instalar(plano, obs, cancelado::get);
            Fundo.naEdt(() -> aoTerminarDeInstalarRequisito(plano, r));
        });
    }

    private void aoTerminarDeInstalarRequisito(PlanoDeInstalacao plano, InstaladorDeRequisitos.Resultado r) {
        terminar();
        if (r.concluido()) {
            terminouComSucesso = true;
            mostrarFim(true, plano.titulo() + ": pronto", r.mensagem() + " Clique em Voltar para o app conferir de novo.", true);
        } else {
            mostrarFalha(r.cancelado() ? "Cancelado" : "Não deu certo", r.mensagem(), List.of(), true);
        }
    }

    private void abrirODocker() {
        if (executando) {
            return;
        }
        prepararAndamento("Abrindo o Docker e esperando ele ligar", List.of(), this::abrirODocker);
        executarEmFundo(obs -> {
            boolean subiu = false;
            Throwable falha = null;
            try {
                subiu = motor.abrirODocker(obs, cancelado::get);
            } catch (InterruptedException e) {
                Thread.interrupted();
                falha = e;
            } catch (RuntimeException e) {
                falha = e;
            }
            boolean ok = subiu;
            Throwable f = falha;
            Fundo.naEdt(() -> {
                terminar();
                if (ok) {
                    terminouComSucesso = true;
                    mostrarFim(true, "O Docker está rodando", "O motor respondeu. Clique em Voltar para o app conferir de novo.", true);
                } else if (f != null && (f instanceof InterruptedException || cancelado.get())) {
                    mostrarFalha("Cancelado", "Você cancelou a espera. Se o Docker abriu, ele continua ligando por conta própria.", List.of(), true);
                } else {
                    mostrarFalha("O Docker não respondeu a tempo", f == null
                        ? "Passou do prazo e o Docker ainda não respondeu. A primeira vez demora, e o Docker Desktop pode estar esperando você aceitar "
                            + "os termos de uso na janela dele. Quando ele estiver rodando, clique em Voltar."
                        : MensagensDeErro.de(f), List.of(), true);
                }
            });
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Criar e remover
    // ---------------------------------------------------------------------------------------------

    private ParametrosDoLaboratorio parametros() {
        return new ParametrosDoLaboratorio(alvo.container(), Integer.parseInt(campoDaPorta.getText().strip()), ParametrosDoLaboratorio.REDE_PADRAO,
            ParametrosDoLaboratorio.IMAGEM_PADRAO, campoDaPasta.getText().strip());
    }

    private void criarOLaboratorio() {
        if (executando || !criar.isEnabled()) {
            return;
        }
        ParametrosDoLaboratorio p;
        try {
            p = parametros();
        } catch (RuntimeException e) {
            opcoesAvancadas.definirAberta(true);
            dizerErro(erroDasOpcoes, e instanceof NumberFormatException ? "A porta deve ser um número." : e.getMessage());
            reajustar();
            return;
        }
        String problemaDaPasta = motor.problemaDaPasta(p.pastaDosProjetos());
        if (problemaDaPasta != null) { // a pasta pode ter mudado depois de conferir
            opcoesAvancadas.definirAberta(true);
            dizerErro(erroDasOpcoes, problemaDaPasta);
            reajustar();
            return;
        }
        List<Passo> passos = List.of(PassoDoLaboratorio.values());
        prepararAndamento("Criando o laboratório local", passos, this::criarOLaboratorio);
        executarEmFundo(obs -> {
            ResultadoDaInstalacao r;
            try {
                // A porta e a pasta só são guardadas depois que o agente responde na porta pedida, e voltam atrás se o resto falhar ou for cancelado
                r = motor.laboratorio(p).comEscolhas(new br.com.labdesktop.local.EscolhasDoLaboratorio() {
                    @Override
                    public void gravar() throws Exception {
                        motor.guardarEscolhas(p.porta(), p.pastaDosProjetos());
                    }

                    @Override
                    public void restaurar() throws Exception {
                        motor.guardarEscolhas(alvo.porta(), alvo.pastaDosProjetos());
                    }
                }).criar(obs, cancelado::get);
            } catch (Exception e) {
                r = new ResultadoDaInstalacao(ResultadoDaInstalacao.Desfecho.FALHOU, null, MensagensDeErro.de(e), List.of());
            }
            ResultadoDaInstalacao resultado = r;
            Fundo.naEdt(() -> aoTerminarDeCriar(resultado));
        });
    }

    private void aoTerminarDeCriar(ResultadoDaInstalacao r) {
        terminar();
        switch (r.desfecho()) {
            case CONCLUIDA -> {
                terminouComSucesso = true;
                mostrarFim(true, "Laboratório local criado",
                    "Pronto: o agente está funcionando em 127.0.0.1:" + campoDaPorta.getText().strip() + ". Este computador já aparece como servidor, "
                        + "com terminal, arquivos e métricas.", false);
                avisos.ok("Laboratório local criado.");
                aoMudarOLaboratorio.run();
            }
            case CANCELADA -> mostrarFalha("Cancelado", "Você cancelou. O que o app já tinha criado foi desfeito.", r.desfeito(), true);
            case RECUSADA -> mostrarFalha("Não foi possível começar", r.erro(), r.desfeito(), true);
            case FALHOU -> mostrarFalha("Parou em um passo",
                (r.passo() == null ? "" : "Passo: " + r.passo().rotulo() + ".\n") + r.erro(), r.desfeito(), true);
        }
    }

    private void removerOLaboratorio() {
        if (executando || !remover.isEnabled()) {
            return;
        }
        List<Passo> passos = List.of(PassoDaRemocaoLocal.values());
        ParametrosDoLaboratorio p = new ParametrosDoLaboratorio(alvo.container(), alvo.porta(), ParametrosDoLaboratorio.REDE_PADRAO,
            ParametrosDoLaboratorio.IMAGEM_PADRAO, alvo.pastaDosProjetos());
        prepararAndamento("Removendo o laboratório local", passos, this::removerOLaboratorio);
        executarEmFundo(obs -> {
            try {
                ResultadoDaRemocao r = motor.laboratorio(p).remover(obs, cancelado::get);
                Fundo.naEdt(() -> aoTerminarDeRemover(r, null));
            } catch (java.io.IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.interrupted();
                }
                Fundo.naEdt(() -> aoTerminarDeRemover(null, e));
            }
        });
    }

    private void aoTerminarDeRemover(ResultadoDaRemocao r, Throwable falha) {
        terminar();
        if (falha != null) {
            mostrarFalha(falha instanceof InterruptedException || cancelado.get() ? "Cancelado" : "Parou em um passo", MensagensDeErro.de(falha), List.of(),
                false);
            return;
        }
        if (r.recusada()) {
            mostrarFalha("O app não removeu nada", String.join(" ", r.avisos()), List.of(), false);
            return;
        }
        if (r.removido()) {
            terminouComSucesso = true;
            mostrarFim(true, "Laboratório local removido", "O agente, os dados dele e a imagem saíram. Seus projetos continuam em "
                + alvo.pastaDosProjetos() + ".", false);
            for (String f : r.feito()) {
                listaDoDesfeito.add(dicaCorrida("Feito: " + f));
            }
            for (String a : r.avisos()) {
                listaDoDesfeito.add(dicaCorrida("Aviso: " + a));
            }
            avisos.ok("Laboratório local removido.");
            aoMudarOLaboratorio.run();
            reajustar();
        } else {
            mostrarFalha("Não consegui remover o agente", String.join(" ", r.avisos()), r.feito(), false);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Andamento
    // ---------------------------------------------------------------------------------------------

    private void prepararAndamento(String titulo, List<Passo> passos, Runnable aoTentarDeNovo) {
        executando = true;
        cancelado.set(false);
        pararOPrazoDoCancelamento();
        terminouComSucesso = false;
        repetir = aoTentarDeNovo;
        tituloDoAndamento.setText(titulo);
        tituloDoAndamento.negrito();
        linhasDosPassos.clear();
        detalhesDosPassos.clear();
        estadosDosPassos.clear();
        listaDePassos.removeAll();
        for (Passo p : passos) {
            Rotulo r = new Rotulo(p.rotulo()).corDoToken(Tokens.TEXTO3);
            r.setIcon(iconeDo(EstadoDoPasso.PENDENTE));
            r.setIconTextGap(8);
            TextoCorrido detalhe = new TextoCorrido("", LARGURA_DO_TEXTO - 26).corDoToken(Tokens.PERIGO).tamanho(12.5f);
            detalhe.setVisible(false);
            linhasDosPassos.put(p, r);
            detalhesDosPassos.put(p, detalhe);
            estadosDosPassos.put(p, EstadoDoPasso.PENDENTE);
            listaDePassos.add(Caixa.coluna(2, r, Caixa.linha(0, Caixa.espaco(26), detalhe)));
            listaDePassos.add(Caixa.espacoVertical(6));
        }
        listaDePassos.setVisible(!passos.isEmpty());
        areaDoLog.setText("");
        concluir.setVisible(false);
        fecharAoFim.setVisible(false);
        tentarDeNovo.setVisible(false);
        voltarAoResultado.setVisible(false);
        cancelarExecucao.setVisible(true);
        cancelarExecucao.setEnabled(true);
        cancelarExecucao.setText("Cancelar");
        situacaoFinal.setVisible(false);
        mensagemFinal.setVisible(false);
        listaDoDesfeito.removeAll();
        mostrarPagina(PAGINA_ANDAMENTO);
    }

    /** Roda {@code trabalho} numa thread virtual que o Cancelar interrompe; erro inesperado nunca deixa a tela presa em "executando". */
    private void executarEmFundo(java.util.function.Consumer<ObservadorDaInstalacao> trabalho) {
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
        Thread t = Thread.ofVirtual().name("preparar-computador").unstarted(() -> {
            try {
                trabalho.accept(obs);
            } catch (RuntimeException e) {
                Fundo.naEdt(() -> {
                    terminar();
                    mostrarFalha("Erro inesperado", MensagensDeErro.de(e), List.of(), true);
                });
            }
        });
        trabalhador.set(t);
        t.start();
    }

    private void terminar() {
        executando = false;
        trabalhador.set(null);
        pararOPrazoDoCancelamento();
        cancelarExecucao.setVisible(false);
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

    private void mostrarFim(boolean sucesso, String titulo, String mensagem, boolean voltarParaConferir) {
        situacaoFinal.setText(titulo);
        situacaoFinal.setIcon(Icones.colorido(sucesso ? "ok-circulo" : "erro", 18, sucesso ? Tokens.OK : Tokens.PERIGO));
        situacaoFinal.setIconTextGap(8);
        situacaoFinal.corDoToken(sucesso ? Tokens.OK : Tokens.PERIGO).negrito();
        situacaoFinal.setVisible(true);
        mensagemFinal.setText(mensagem);
        mensagemFinal.setVisible(true);
        tentarDeNovo.setVisible(false);
        fecharAoFim.setVisible(false);
        voltarAoResultado.setVisible(voltarParaConferir);
        concluir.setVisible(!voltarParaConferir);
        botaoPadrao(voltarParaConferir ? voltarAoResultado : concluir);
        reajustar();
    }

    private void mostrarFalha(String titulo, String mensagem, List<String> desfeito, boolean podeVoltar) {
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
        voltarAoResultado.setVisible(podeVoltar);
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
                t.interrupt();
            }
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

    private void pararOPrazoDoCancelamento() {
        podeFecharMesmoAssim = false;
        if (prazoDoCancelamento != null) {
            prazoDoCancelamento.stop();
            prazoDoCancelamento = null;
        }
    }

    /** Só os testes: encurta o tempo até o botão virar "Fechar mesmo assim". */
    public static void definirPrazoParaFecharMesmoAssimParaTeste(int milissegundos) {
        prazoParaFecharMesmoAssimMs = milissegundos;
    }

    @Override
    protected void aoFechar() {
        geracaoDaConferencia++;
        pararOPrazoDoCancelamento();
        Thread t = trabalhador.get();
        if (t != null) {
            t.interrupt();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public String paginaAtual() {
        return paginaAtual;
    }

    public Botao botaoCriar() {
        return criar;
    }

    public Botao botaoVerificarDeNovo() {
        return verificarDeNovo;
    }

    public Botao botaoInstalarPlano() {
        return instalarPlano;
    }

    public Botao botaoVoltarDoPlano() {
        return voltarDoPlano;
    }

    public Botao botaoRemover() {
        return remover;
    }

    public Botao botaoCancelarRemocao() {
        return cancelarRemocao;
    }

    public Botao botaoCancelarExecucao() {
        return cancelarExecucao;
    }

    public Botao botaoTentarDeNovo() {
        return tentarDeNovo;
    }

    public Botao botaoVoltarAoResultado() {
        return voltarAoResultado;
    }

    public Botao botaoConcluir() {
        return concluir;
    }

    public JTextField campoDeConfirmacao() {
        return campoDeConfirmacao;
    }

    public JTextField campoDaPorta() {
        return campoDaPorta;
    }

    public String textoDoErroDasOpcoes() {
        return erroDasOpcoes.isVisible() ? erroDasOpcoes.getText() : "";
    }

    public JTextField campoDaPasta() {
        return campoDaPasta;
    }

    public SecaoRecolhivel opcoesAvancadas() {
        return opcoesAvancadas;
    }

    public boolean executando() {
        return executando;
    }

    public boolean terminouComSucesso() {
        return terminouComSucesso;
    }

    public boolean fechada() {
        return jaFechou();
    }

    public RelatorioDoComputador relatorio() {
        return relatorio;
    }

    public String situacaoFinal() {
        return situacaoFinal.getText();
    }

    public String textoFinal() {
        return mensagemFinal.getText();
    }

    public String textoDoLog() {
        return areaDoLog.getText();
    }

    public String textoDaConferencia() {
        return erroDeConferir.getText();
    }

    public String textoDoLaboratorioQueJaRoda() {
        return avisoDoLaboratorioQueJaRoda.isVisible() ? avisoDoLaboratorioQueJaRoda.getText() : "";
    }

    public String textoDoBloqueio() {
        return avisoDeBloqueio.getText();
    }

    public EstadoDoPasso estadoDoPasso(Passo p) {
        return estadosDosPassos.get(p);
    }

    public List<String> linhasDoDesfeito() {
        return textosDe(listaDoDesfeito);
    }

    public List<String> frasesDasVerificacoes() {
        return textosDe(listaDeVerificacoes);
    }

    public List<String> frasesDoQueVaiAcontecer() {
        return textosDe(listaDoQueVaiAcontecer);
    }

    public List<String> textosDoPlano() {
        List<String> l = new ArrayList<>(List.of(tituloDoPlano.getText(), resumoDoPlano.getText()));
        l.addAll(textosDe(detalhesDoPlano));
        return l;
    }

    /** Os botões que a tela de resultado mostra (planos, abrir o Docker, usar a porta), por nome. */
    public Botao botaoNomeado(String nome) {
        Botao[] achado = new Botao[1];
        coletar(listaDeVerificacoes, c -> {
            if (c instanceof Botao b && nome.equals(b.getName())) {
                achado[0] = b;
            }
        });
        return achado[0];
    }

    public List<String> comandosSugeridos() {
        List<String> l = new ArrayList<>();
        coletar(listaDeVerificacoes, c -> {
            if (c instanceof TextoCorrido t && "comando-sugerido".equals(t.getName())) {
                l.add(t.getText());
            }
        });
        return l;
    }

    public List<String> comandosDoPlano() {
        List<String> l = new ArrayList<>();
        coletar(detalhesDoPlano, c -> {
            if (c instanceof TextoCorrido t && "comando-do-plano".equals(t.getName())) {
                l.add(t.getText());
            }
        });
        return l;
    }

    private static List<String> textosDe(JComponent raiz) {
        List<String> l = new ArrayList<>();
        coletar(raiz, c -> {
            if (c instanceof TextoCorrido t && t.isVisible() && !"comando-sugerido".equals(t.getName()) && !"comando-do-plano".equals(t.getName())) {
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
}
