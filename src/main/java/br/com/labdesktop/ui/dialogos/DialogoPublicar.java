package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.ComposeComEdge;
import br.com.labdesktop.cloudflare.Etapa;
import br.com.labdesktop.cloudflare.NomeDoEndereco;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cloudflare.TestadorDeUrl;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;

/**
 * "Publicar na internet": o usuário escolhe o serviço, a porta e o nome do endereço; o app MOSTRA tudo o que será feito (o {@code docker-compose.yml} com a rede {@code edge} e o
 * {@code container_name}, a regra no túnel, o registro de DNS), com a diferença legível, e só EXECUTA depois do clique em "Publicar". A prévia só lê (o compose, as regras, o
 * DNS) e se refaz sozinha a cada mudança nos campos. Ao executar, cada passo aparece com o estado; se algo falha, o que a execução fez é desfeito (e o diálogo diz o que foi
 * desfeito e o que o usuário precisa desfazer à mão); no fim o endereço é testado e o diálogo mostra "no ar" ou o motivo.
 *
 * <p>Nunca fica preso: Esc, o X e o botão, com a execução em andamento, primeiro PEDEM para parar (o app desfaz o que já fez) e o botão vira "Fechar mesmo assim"; o segundo clique
 * fecha a janela e a execução termina em segundo plano, com o resultado nos avisos. Clique duplo em "Publicar" não publica duas vezes. Nada de rede, disco ou SSH na EDT.
 */
public final class DialogoPublicar extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        String projeto();

        /** O domínio da zona escolhida ({@code example.com}). */
        String zona();

        /** O começo sugerido do nome do endereço ({@code lab}). */
        String prefixo();

        /** O projeto pode ser recriado daqui (o botão "Iniciar" existe nesta tela)? */
        boolean podeRecriar();

        List<ComposeComEdge.Servico> servicos() throws FalhaDoProjeto;

        PublicadorNaInternet.Previa preparar(PublicadorNaInternet.Pedido pedido) throws FalhaDoProjeto;

        PublicadorNaInternet.Resultado executar(PublicadorNaInternet.Previa previa, ObservadorDaEtapa observador, Cancelamento cancelamento);

        TestadorDeUrl.Resultado testarDeNovo(String hostname, Cancelamento cancelamento);

        void abrirEndereco(String hostname);

        /** O diálogo fechou: solta o que o motor guardava (o token do cliente da Cloudflare, que é zerado). */
        void encerrar();
    }

    private static final int LARGURA = 560;

    private final Motor motor;
    private final Notificacoes avisos;
    private final Runnable aoPublicar;
    private final Rotulo titulo = new Rotulo("").negrito();
    private final JComboBox<String> caixaDeServicos = new JComboBox<>();
    private final JTextField campoPorta = Campos.mono(7, "3000");
    private final JTextField campoContainer = Campos.mono(24, "meu-projeto-app");
    private final JTextField campoNome = Campos.mono(24, "lab-meu-projeto");
    private final Rotulo sufixoDoNome = new Rotulo("").mono(12.5f).corDoToken(Tokens.TEXTO2);
    private final TextoCorrido enderecoFinal = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final JCheckBox recriar = new JCheckBox("Recriar o projeto agora (docker compose up -d) para o container entrar na rede edge", true);
    private final TextoCorrido problemasDosCampos = erroCorrido();
    private final TextoCorrido situacao = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Rotulo tituloDoPasso = new Rotulo("O que será feito").negrito();
    private final TextoCorrido passos = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Rotulo tituloDoCompose = new Rotulo("docker-compose.yml: o que muda").negrito();
    private final AreaMonoespacada areaDoCompose = new AreaMonoespacada("O que muda no docker-compose.yml", false);
    private final JScrollPane rolagemDoCompose = AreaMonoespacada.emRolagem(areaDoCompose);
    private final Rotulo tituloDoTunel = new Rotulo("Túnel: o que muda").negrito();
    private final AreaMonoespacada areaDoTunel = new AreaMonoespacada("O que muda no túnel", false);
    private final JScrollPane rolagemDoTunel = AreaMonoespacada.emRolagem(areaDoTunel);
    private final TextoCorrido avisosDaPrevia = new TextoCorrido("", LARGURA).corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final TextoCorrido bloqueioDaPrevia = erroCorrido();
    private final Rotulo tituloDoBloco = new Rotulo("Bloco para copiar (o compose é seu, o app não o reescreve)").negrito();
    private final AreaMonoespacada areaDoBloco = new AreaMonoespacada("Bloco para copiar", false);
    private final JScrollPane rolagemDoBloco = AreaMonoespacada.emRolagem(areaDoBloco);
    private final Botao copiarBloco;
    private final Botao publicar = Botoes.primario("Publicar na internet", "externo");
    private final Botao conferir = Botoes.secundario("Conferir de novo", "atualizar");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Botao abrirEndereco = Botoes.secundario("Abrir o endereço", "externo");
    private final Botao testarDeNovo = Botoes.secundario("Testar de novo", "atualizar");
    private final Map<Etapa, Rotulo> linhasDeEtapa = new EnumMap<>(Etapa.class);
    private final JComponent blocoDasEtapas;
    private final Selo seloDoResultado = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido textoDoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido desfeitoNoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido pendenciasNoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final JComponent blocoDoResultado;
    private final Timer atrasoDaPrevia;
    private final AtomicInteger geracao = new AtomicInteger();
    private volatile Cancelamento cancelamento = new Cancelamento();
    private PublicadorNaInternet.Previa previaAtual;
    private PublicadorNaInternet.Resultado resultado;
    private List<ComposeComEdge.Servico> servicos = List.of();
    private boolean ocupado;
    private boolean pediuParar;
    private boolean ajustando;
    private boolean motorSolto;
    private String ultimoNomeSugerido = "";
    private String ultimoContainerSugerido = "";

    public DialogoPublicar(Motor motor, Notificacoes avisos, CopiadorDeTexto copiador, Runnable aoPublicar) {
        super("Publicar na internet");
        this.motor = motor;
        this.avisos = avisos;
        this.aoPublicar = aoPublicar;
        this.copiarBloco = BotaoCopiar.criar("Copiar o bloco", areaDoBloco::getText, copiador, avisos);
        titulo.setText("Projeto " + motor.projeto());
        sufixoDoNome.setText("." + motor.zona());
        for (JTextField c : List.of(campoPorta, campoContainer, campoNome)) {
            c.putClientProperty("html.disable", Boolean.TRUE);
        }
        campoPorta.getAccessibleContext().setAccessibleName("Porta de dentro do container");
        campoContainer.getAccessibleContext().setAccessibleName("Nome do container");
        campoNome.getAccessibleContext().setAccessibleName("Nome do endereço");
        caixaDeServicos.getAccessibleContext().setAccessibleName("Serviço do projeto");
        caixaDeServicos.setName("servicos");
        recriar.putClientProperty("html.disable", Boolean.TRUE);
        recriar.setVisible(motor.podeRecriar());
        recriar.setEnabled(motor.podeRecriar());
        recriar.setSelected(motor.podeRecriar());
        rolagemDoCompose.setPreferredSize(new java.awt.Dimension(LARGURA, 150));
        rolagemDoTunel.setPreferredSize(new java.awt.Dimension(LARGURA, 130));
        rolagemDoBloco.setPreferredSize(new java.awt.Dimension(LARGURA, 150));
        for (JComponent c : List.of(tituloDoPasso, passos, tituloDoCompose, rolagemDoCompose, tituloDoTunel, rolagemDoTunel, avisosDaPrevia, bloqueioDaPrevia, tituloDoBloco, rolagemDoBloco, copiarBloco,
            situacao, enderecoFinal)) {
            c.setVisible(false);
        }
        blocoDasEtapas = Caixa.coluna(4);
        blocoDasEtapas.setVisible(false);
        for (Etapa e : List.of(Etapa.COMPOSE, Etapa.REGRA, Etapa.CONFERIR_E_REINICIAR, Etapa.DNS, Etapa.PROJETO, Etapa.TESTE)) {
            Rotulo r = new Rotulo(e.rotulo()).corDoToken(Tokens.TEXTO3).tamanho(12.5f);
            r.setIcon(Icones.colorido("relogio", 14, Tokens.TEXTO3));
            r.setIconTextGap(8);
            linhasDeEtapa.put(e, r);
            blocoDasEtapas.add(r);
        }
        blocoDoResultado = Caixa.coluna(6, Caixa.linha(8, seloDoResultado, Caixa.mola()), textoDoResultado, desfeitoNoResultado, pendenciasNoResultado,
            Caixa.linha(8, abrirEndereco, testarDeNovo, Caixa.mola()));
        blocoDoResultado.setVisible(false);
        desfeitoNoResultado.setVisible(false);
        pendenciasNoResultado.setVisible(false);
        abrirEndereco.setVisible(false);
        testarDeNovo.setVisible(false);

        JComponent corpo = Caixa.coluna(8,
            titulo,
            new Rotulo("O que vai para a internet").negrito(),
            Caixa.linha(8, new Rotulo("Serviço").corDoToken(Tokens.TEXTO2).tamanho(12.5f), caixaDeServicos,
                new Rotulo("porta do container").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoPorta, Caixa.mola()),
            Caixa.linha(8, new Rotulo("Nome do container").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoContainer, Caixa.mola()),
            Caixa.linha(4, new Rotulo("Endereço").corDoToken(Tokens.TEXTO2).tamanho(12.5f), Caixa.espaco(4), campoNome, sufixoDoNome, Caixa.mola()),
            enderecoFinal,
            recriar,
            problemasDosCampos,
            situacao,
            tituloDoPasso, passos,
            tituloDoCompose, rolagemDoCompose,
            tituloDoTunel, rolagemDoTunel,
            avisosDaPrevia, bloqueioDaPrevia,
            tituloDoBloco, rolagemDoBloco, Caixa.linha(8, copiarBloco, Caixa.mola()),
            blocoDasEtapas, blocoDoResultado);
        conteudoComRodape(Caixa.coluna(10, cabecalho("externo", "Publicar na internet"), corpo), rodape(null, cancelar, conferir, publicar));
        botaoPadrao(publicar);
        publicar.setEnabled(false);

        atrasoDaPrevia = new Timer(350, e -> calcularAPrevia());
        atrasoDaPrevia.setRepeats(false);
        cancelar.addActionListener(e -> aoCancelar());
        conferir.addActionListener(e -> calcularAPrevia());
        publicar.addActionListener(e -> publicar());
        abrirEndereco.addActionListener(e -> {
            if (resultado != null) {
                motor.abrirEndereco(resultado.hostname());
            }
        });
        testarDeNovo.addActionListener(e -> testarOutraVez());
        caixaDeServicos.addActionListener(e -> aoEscolherOServico());
        SimplesOuvinteDeDocumento mudou = new SimplesOuvinteDeDocumento(this::aoMudarOsCampos);
        campoPorta.getDocument().addDocumentListener(mudou);
        campoContainer.getDocument().addDocumentListener(mudou);
        campoNome.getDocument().addDocumentListener(mudou);
        recriar.addActionListener(e -> aoMudarOsCampos());
        carregarOsServicos();
    }

    // =====================================================================================================
    // Os campos e a prévia
    // =====================================================================================================

    private void carregarOsServicos() {
        mostrarSituacao("Lendo o docker-compose.yml do projeto…");
        Tarefas.rodar(motor::servicos, lista -> {
            if (jaFechou()) {
                return;
            }
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
                mostrarSituacao(null);
                dizerErro(problemasDosCampos, "Não achei serviços no docker-compose.yml do projeto (ele não existe, não está em UTF-8 ou está num formato que o app não lê). Adicione um container ao projeto "
                    + "antes de publicar.");
                reajustar();
                return;
            }
            aoEscolherOServico();
        }, falha -> {
            if (jaFechou()) {
                return;
            }
            mostrarSituacao(null);
            dizerErro(problemasDosCampos, falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            reajustar();
        });
    }

    private ComposeComEdge.Servico servicoEscolhido() {
        int i = caixaDeServicos.getSelectedIndex();
        return i >= 0 && i < servicos.size() ? servicos.get(i) : null;
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
            // a sugestão só entra se o usuário não escreveu o dele
            String containerSugerido = s.containerName() != null ? s.containerName() : PublicadorNaInternet.sugerirContainer(motor.projeto(), s.nome());
            if (campoContainer.getText().isEmpty() || campoContainer.getText().equals(ultimoContainerSugerido)) {
                campoContainer.setText(containerSugerido);
            }
            ultimoContainerSugerido = containerSugerido;
            campoContainer.setEnabled(s.containerName() == null && !ocupado);
            campoContainer.setToolTipText(s.containerName() == null ? null : "O serviço já tem este container_name no compose: o app usa o que o arquivo diz.");
            if (s.portaSugerida() != null) {
                campoPorta.setText(String.valueOf(s.portaSugerida()));
            }
            String nomeSugerido = NomeDoEndereco.sugerir(motor.prefixo(), motor.projeto());
            if (campoNome.getText().isEmpty() || campoNome.getText().equals(ultimoNomeSugerido)) {
                campoNome.setText(nomeSugerido);
            }
            ultimoNomeSugerido = nomeSugerido;
        } finally {
            ajustando = false;
        }
        aoMudarOsCampos();
    }

    private void aoMudarOsCampos() {
        if (ajustando || ocupado) {
            return;
        }
        atrasoDaPrevia.restart();
        previaAtual = null;
        publicar.setEnabled(false);
        geracao.incrementAndGet();
        String nome = campoNome.getText();
        NomeDoEndereco.Resultado r = NomeDoEndereco.conferir(nome);
        enderecoFinal.setText(r.valido() ? "Endereço público: " + NomeDoEndereco.urlPublica(nome + "." + motor.zona()) : "");
        enderecoFinal.setVisible(r.valido());
        dizerErro(problemasDosCampos, problemaDosCampos());
        reajustar();
    }

    /** A frase de um campo que não vale ({@code ""} se estão todos certos ou ainda vazios). */
    private String problemaDosCampos() {
        List<String> l = new ArrayList<>();
        String nome = campoNome.getText();
        NomeDoEndereco.Resultado r = NomeDoEndereco.conferir(nome);
        if (!r.valido() && !r.mensagem().isEmpty()) {
            l.add("Nome do endereço: " + r.mensagem());
        }
        String c = campoContainer.getText();
        if (!c.isEmpty() && !c.matches("^[a-z0-9][a-z0-9_.-]{0,40}$")) {
            l.add("Nome do container: letras minúsculas, números, ponto, hífen ou sublinhado, até 41 caracteres.");
        }
        String p = campoPorta.getText().strip();
        if (!p.isEmpty() && numero(p) == null) {
            l.add("A porta é um número de 1 a 65535.");
        }
        return String.join("\n", l);
    }

    private static Integer numero(String t) {
        try {
            int n = Integer.parseInt(t.strip());
            return n >= 1 && n <= 65535 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private PublicadorNaInternet.Pedido montarOPedido() {
        ComposeComEdge.Servico s = servicoEscolhido();
        Integer porta = numero(campoPorta.getText());
        if (s == null || porta == null || !NomeDoEndereco.valido(campoNome.getText()) || !campoContainer.getText().matches("^[a-z0-9][a-z0-9_.-]{0,40}$")) {
            return null;
        }
        return new PublicadorNaInternet.Pedido(motor.projeto(), s.nome(), campoContainer.getText(), porta, campoNome.getText(), recriar.isSelected() && recriar.isVisible());
    }

    private void calcularAPrevia() {
        if (ocupado || jaFechou()) {
            return;
        }
        atrasoDaPrevia.stop();
        PublicadorNaInternet.Pedido p = montarOPedido();
        previaAtual = null;
        publicar.setEnabled(false);
        if (p == null) {
            mostrarSituacao(null);
            esconderAPrevia();
            return;
        }
        int minha = geracao.incrementAndGet();
        mostrarSituacao("Lendo o compose, as regras do túnel e o DNS (só leitura)…");
        Tarefas.rodar(() -> motor.preparar(p), previa -> {
            if (minha != geracao.get() || jaFechou()) {
                return;
            }
            mostrarSituacao(null);
            mostrarAPrevia(previa);
        }, falha -> {
            if (minha != geracao.get() || jaFechou()) {
                return;
            }
            mostrarSituacao(null);
            esconderAPrevia();
            dizerErro(bloqueioDaPrevia, falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            reajustar();
        });
    }

    private void mostrarSituacao(String texto) {
        situacao.setText(texto == null ? "" : texto);
        situacao.setVisible(texto != null);
    }

    private void esconderAPrevia() {
        for (JComponent c : List.of(tituloDoPasso, passos, tituloDoCompose, rolagemDoCompose, tituloDoTunel, rolagemDoTunel, avisosDaPrevia, tituloDoBloco, rolagemDoBloco, copiarBloco)) {
            c.setVisible(false);
        }
        dizerErro(bloqueioDaPrevia, "");
        reajustar();
    }

    private void mostrarAPrevia(PublicadorNaInternet.Previa previa) {
        previaAtual = previa;
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (String p : previa.passos()) {
            sb.append(n++).append(". ").append(p).append('\n');
        }
        passos.setText(sb.toString().stripTrailing());
        tituloDoPasso.setVisible(true);
        passos.setVisible(true);
        boolean temCompose = !previa.diferencaDoCompose().isEmpty();
        areaDoCompose.setText(previa.diferencaDoCompose());
        areaDoCompose.setCaretPosition(0);
        tituloDoCompose.setVisible(temCompose);
        rolagemDoCompose.setVisible(temCompose);
        boolean temTunel = !previa.diferencaDoTunel().isEmpty();
        tituloDoTunel.setText("Túnel: o que muda " + previa.ondeEstaORegistro());
        areaDoTunel.setText(previa.diferencaDoTunel());
        areaDoTunel.setCaretPosition(0);
        tituloDoTunel.setVisible(temTunel);
        rolagemDoTunel.setVisible(temTunel);
        avisosDaPrevia.setText(String.join("\n", previa.avisos().stream().map(a -> "• " + a).toList()));
        avisosDaPrevia.setVisible(!previa.avisos().isEmpty());
        boolean manual = previa.compose() == ComposeComEdge.Situacao.MANUAL && !previa.blocoParaCopiar().isEmpty(); // sem bloco (network_mode) só o motivo aparece
        areaDoBloco.setText(manual ? previa.blocoParaCopiar() : "");
        areaDoBloco.setCaretPosition(0);
        tituloDoBloco.setVisible(manual);
        rolagemDoBloco.setVisible(manual);
        copiarBloco.setVisible(manual);
        dizerErro(bloqueioDaPrevia, previa.bloqueio() == null ? "" : previa.bloqueio());
        publicar.setEnabled(previa.podePublicar() && !ocupado);
        rolarParaOTopo();
        reajustar();
    }

    // =====================================================================================================
    // Executar (só depois do clique)
    // =====================================================================================================

    private void publicar() {
        if (ocupado || previaAtual == null || !previaAtual.podePublicar() || jaFechou()) {
            return;
        }
        PublicadorNaInternet.Previa previa = previaAtual;
        ocupado = true;
        pediuParar = false;
        cancelamento = new Cancelamento();
        Cancelamento meu = cancelamento;
        publicar.definirOcupado(true);
        conferir.setEnabled(false);
        travarOsCampos(true);
        cancelar.setText("Parar");
        cancelar.setEnabled(true);
        blocoDoResultado.setVisible(false);
        for (Rotulo r : linhasDeEtapa.values()) {
            r.setIcon(Icones.colorido("relogio", 14, Tokens.TEXTO3));
            r.setText(r.getText().split(" — ")[0]);
            r.corDoToken(Tokens.TEXTO3);
        }
        blocoDasEtapas.setVisible(true);
        for (JComponent c : List.of(tituloDoPasso, passos)) {
            c.setVisible(false);
        }
        reajustar();
        ObservadorDaEtapa obs = (etapa, estado, frase) -> Fundo.naEdt(() -> mostrarEtapa(etapa, estado, frase));
        Tarefas.rodar(() -> motor.executar(previa, obs, meu), r -> aoTerminar(previa, r), falha -> aoTerminar(previa, new PublicadorNaInternet.Resultado(PublicadorNaInternet.Desfecho.FALHOU,
            previa.hostname(), previa.url(), null, MensagensDeErro.de(falha), null, List.of(), List.of(), List.of(), List.of())));
    }

    private void mostrarEtapa(Etapa etapa, ObservadorDaEtapa.Estado estado, String frase) {
        Rotulo r = linhasDeEtapa.get(etapa);
        if (r == null) {
            return;
        }
        String nome = r.getText().split(" — ")[0];
        String icone = switch (estado) {
            case ANDANDO -> "relogio";
            case FEITO -> "ok-circulo";
            case PULADO -> "avancar";
            case FALHOU -> "erro";
            case DESFEITO -> "desfazer";
        };
        String token = switch (estado) {
            case ANDANDO -> Tokens.TEXTO2;
            case FEITO -> Tokens.OK;
            case PULADO -> Tokens.TEXTO3;
            case FALHOU -> Tokens.PERIGO;
            case DESFEITO -> Tokens.ATENCAO;
        };
        r.setIcon(Icones.colorido(icone, 14, token));
        r.setText(nome + (frase == null || frase.isEmpty() ? "" : " — " + resumir(frase)));
        r.setToolTipText(frase);
        r.corDoToken(estado == ObservadorDaEtapa.Estado.FALHOU ? Tokens.PERIGO : estado == ObservadorDaEtapa.Estado.ANDANDO ? Tokens.TEXTO : Tokens.TEXTO2);
        reajustar();
    }

    private void aoTerminar(PublicadorNaInternet.Previa previa, PublicadorNaInternet.Resultado r) {
        ocupado = false;
        publicar.definirOcupado(false);
        resultado = r;
        boolean publicado = r.desfecho() == PublicadorNaInternet.Desfecho.PUBLICADO;
        if (publicado) {
            aoPublicar.run();
        }
        if (jaFechou()) {
            // o usuário fechou a janela com a execução em andamento: o resultado chega pelos avisos
            if (publicado) {
                String teste = r.teste() == null ? "" : " " + r.teste().frase();
                avisos.ok("Endereço publicado: " + r.url() + teste);
            } else if (r.desfecho() == PublicadorNaInternet.Desfecho.CANCELADO) {
                avisos.info("Publicar " + previa.hostname() + " foi parado e o que já estava feito foi desfeito." + (r.naoConsegui().isEmpty() ? "" : " Atenção: " + String.join(" ", r.naoConsegui())));
            } else {
                avisos.erro("Publicar " + previa.hostname() + " falhou: " + r.falha() + (r.naoConsegui().isEmpty() ? "" : " Atenção: " + String.join(" ", r.naoConsegui())));
            }
            soltarOMotor();
            return;
        }
        mostrarOResultado(previa, r);
    }

    private void mostrarOResultado(PublicadorNaInternet.Previa previa, PublicadorNaInternet.Resultado r) {
        cancelar.setText("Fechar");
        cancelar.setEnabled(true);
        publicar.setVisible(false);
        conferir.setVisible(false);
        blocoDoResultado.setVisible(true);
        desfeitoNoResultado.setVisible(false);
        pendenciasNoResultado.setVisible(false);
        abrirEndereco.setVisible(false);
        testarDeNovo.setVisible(false);
        switch (r.desfecho()) {
            case PUBLICADO -> {
                TestadorDeUrl.Resultado t = r.teste();
                boolean noAr = t != null && t.noAr();
                boolean duvida = t != null && t.estado() == TestadorDeUrl.Estado.EM_DUVIDA;
                seloDoResultado.atualizar(noAr ? "NO AR" : duvida ? "EM DÚVIDA" : "PUBLICADO, AINDA SEM RESPOSTA", noAr ? Gravidade.OK : Gravidade.ATENCAO, null);
                textoDoResultado.setText(r.url() + "\n" + (t == null ? "" : t.frase()) + (noAr ? "" : "\nNada foi desfeito: o endereço, a regra e o compose estão no lugar."
                    + " Se o DNS é novo, espere alguns minutos e use \"Testar de novo\"."));
                abrirEndereco.setVisible(true);
                testarDeNovo.setVisible(!noAr);
                if (!r.copias().isEmpty()) {
                    desfeitoNoResultado.setText("Cópias datadas guardadas: " + String.join(", ", r.copias()));
                    desfeitoNoResultado.setVisible(true);
                }
            }
            case CANCELADO -> {
                seloDoResultado.atualizar("PARADO", Gravidade.NEUTRO, null);
                textoDoResultado.setText("Você mandou parar. O que já estava feito foi desfeito.");
                mostrarDesfeitoEPendencias(r);
            }
            case FALHOU -> {
                seloDoResultado.atualizar("NÃO PUBLICOU", Gravidade.PERIGO, null);
                textoDoResultado.setText((r.etapaDaFalha() == null ? "" : "No passo \"" + r.etapaDaFalha().rotulo() + "\": ") + (r.falha() == null ? "" : r.falha()));
                mostrarDesfeitoEPendencias(r);
            }
        }
        reajustar();
    }

    private void mostrarDesfeitoEPendencias(PublicadorNaInternet.Resultado r) {
        if (!r.desfeito().isEmpty()) {
            // cada item é uma frase "Desfiz X.": tira o "Desfiz " e o ponto final de cada uma e junta com ponto e vírgula (sem mexer nos pontos de dentro, como o de docker-compose.yml)
            List<String> itens = r.desfeito().stream().map(f -> f.startsWith("Desfiz ") ? f.substring("Desfiz ".length()) : f).map(f -> f.endsWith(".") ? f.substring(0, f.length() - 1) : f).toList();
            desfeitoNoResultado.setText("Desfeito: " + String.join("; ", itens) + ".");
            desfeitoNoResultado.setVisible(true);
        }
        if (!r.naoConsegui().isEmpty()) {
            pendenciasNoResultado.setText("ATENÇÃO, fique de olho: " + String.join("\n", r.naoConsegui()));
            pendenciasNoResultado.setVisible(true);
        }
    }

    private void testarOutraVez() {
        if (resultado == null || ocupado) {
            return;
        }
        String host = resultado.hostname();
        ocupado = true;
        testarDeNovo.definirOcupado(true);
        cancelamento = new Cancelamento();
        Cancelamento meu = cancelamento;
        Tarefas.rodar(() -> motor.testarDeNovo(host, meu), t -> {
            ocupado = false;
            testarDeNovo.definirOcupado(false);
            if (jaFechou()) {
                return;
            }
            boolean noAr = t.noAr();
            seloDoResultado.atualizar(noAr ? "NO AR" : t.estado() == TestadorDeUrl.Estado.EM_DUVIDA ? "EM DÚVIDA" : "PUBLICADO, AINDA SEM RESPOSTA", noAr ? Gravidade.OK : Gravidade.ATENCAO, null);
            textoDoResultado.setText(resultado.url() + "\n" + t.frase());
            testarDeNovo.setVisible(!noAr);
            reajustar();
        }, falha -> {
            ocupado = false;
            testarDeNovo.definirOcupado(false);
        });
    }

    private void travarOsCampos(boolean travar) {
        caixaDeServicos.setEnabled(!travar);
        campoPorta.setEnabled(!travar);
        campoContainer.setEnabled(!travar && (servicoEscolhido() == null || servicoEscolhido().containerName() == null));
        campoNome.setEnabled(!travar);
        recriar.setEnabled(!travar && motor.podeRecriar());
    }

    // =====================================================================================================
    // Fechar (nunca preso)
    // =====================================================================================================

    /** A frase de um passo cabe numa linha (o texto inteiro vai na dica e, se falhou, no resultado): uma frase longa não alarga a janela. */
    private static String resumir(String frase) {
        String f = frase.replace('\n', ' ').strip();
        return f.length() <= 90 ? f : f.substring(0, 89).stripTrailing() + "…";
    }

    @Override
    protected void aoCancelar() {
        if (!ocupado) {
            fechar();
            return;
        }
        if (!pediuParar) {
            pediuParar = true;
            cancelamento.cancelar();
            cancelar.setText("Fechar mesmo assim");
            mostrarSituacao("Parando: o app termina o passo em andamento e desfaz o que já fez…");
            reajustar();
            return;
        }
        avisos.info("A publicação foi mandada parar e termina em segundo plano (o que já estava feito é desfeito). O resultado aparece aqui em seguida.");
        fechar();
    }

    @Override
    protected void aoFechar() {
        atrasoDaPrevia.stop();
        geracao.incrementAndGet();
        if (!ocupado) {
            soltarOMotor();
        } // com a execução em andamento o motor fica aberto: quem o solta é o fim dela (aoTerminar, já com a janela fechada)
    }

    private void soltarOMotor() {
        if (!motorSolto) {
            motorSolto = true;
            motor.encerrar(); // zera a cópia do token que o cliente da Cloudflare guardava
        }
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public boolean ocupado() {
        return ocupado;
    }

    public boolean fechou() {
        return jaFechou();
    }

    public JComboBox<String> caixaDeServicos() {
        return caixaDeServicos;
    }

    public JTextField campoPorta() {
        return campoPorta;
    }

    public JTextField campoContainer() {
        return campoContainer;
    }

    public JTextField campoNome() {
        return campoNome;
    }

    public JCheckBox caixaDeRecriar() {
        return recriar;
    }

    public Botao botaoPublicar() {
        return publicar;
    }

    public Botao botaoConferir() {
        return conferir;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoAbrirEndereco() {
        return abrirEndereco;
    }

    public Botao botaoTestarDeNovo() {
        return testarDeNovo;
    }

    public Botao botaoCopiarBloco() {
        return copiarBloco;
    }

    public String textoDosProblemas() {
        return problemasDosCampos.isVisible() ? problemasDosCampos.getText() : "";
    }

    public String textoDaSituacao() {
        return situacao.isVisible() ? situacao.getText() : "";
    }

    public String textoDosPassos() {
        return passos.isVisible() ? passos.getText() : "";
    }

    public String textoDoCompose() {
        return rolagemDoCompose.isVisible() ? areaDoCompose.getText() : "";
    }

    public String textoDoTunel() {
        return rolagemDoTunel.isVisible() ? areaDoTunel.getText() : "";
    }

    public String textoDosAvisos() {
        return avisosDaPrevia.isVisible() ? avisosDaPrevia.getText() : "";
    }

    public String textoDoBloqueio() {
        return bloqueioDaPrevia.isVisible() ? bloqueioDaPrevia.getText() : "";
    }

    public String textoDoBloco() {
        return rolagemDoBloco.isVisible() ? areaDoBloco.getText() : "";
    }

    public String enderecoMostrado() {
        return enderecoFinal.isVisible() ? enderecoFinal.getText() : "";
    }

    public String seloDoResultado() {
        return blocoDoResultado.isVisible() ? seloDoResultado.texto() : "";
    }

    public String textoDoResultado() {
        return blocoDoResultado.isVisible() ? textoDoResultado.getText() : "";
    }

    public String textoDoDesfeito() {
        return desfeitoNoResultado.isVisible() ? desfeitoNoResultado.getText() : "";
    }

    public String textoDasPendencias() {
        return pendenciasNoResultado.isVisible() ? pendenciasNoResultado.getText() : "";
    }

    /** O texto de cada passo, como o usuário o lê ("Regra no túnel — Regra gravada..."). */
    public Map<Etapa, String> linhasDosPassos() {
        Map<Etapa, String> m = new EnumMap<>(Etapa.class);
        linhasDeEtapa.forEach((e, r) -> m.put(e, r.getText()));
        return m;
    }

    public boolean passosVisiveis() {
        return blocoDasEtapas.isVisible();
    }

    public PublicadorNaInternet.Previa previa() {
        return previaAtual;
    }
}
