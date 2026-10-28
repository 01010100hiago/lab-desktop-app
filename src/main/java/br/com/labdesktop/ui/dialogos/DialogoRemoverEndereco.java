package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.Etapa;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.RemovedorDeEndereco;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * "Remover endereço": mostra, só por leitura, o que o app tiraria (a regra do túnel e o registro de DNS que ELE criou) e só remove depois que o usuário DIGITA o endereço inteiro
 * para confirmar (a ação destrutiva repete o alvo). Uma regra ou um registro que não leva a marca do app não é apagado, e o diálogo diz por quê. Se um passo falha, o que
 * já tinha sido tirado volta. Nunca fica preso: Esc, o X e o botão primeiro PEDEM para parar (o botão vira "Fechar mesmo assim"); o segundo clique fecha e a tarefa termina em segundo
 * plano, com o resultado nos avisos. Nada de rede, disco ou SSH na EDT.
 */
public final class DialogoRemoverEndereco extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        RemovedorDeEndereco.Previa preparar(String hostname) throws FalhaDoProjeto;

        RemovedorDeEndereco.Resultado executar(RemovedorDeEndereco.Previa previa, ObservadorDaEtapa observador, Cancelamento cancelamento);

        /** O diálogo fechou: solta o que o motor guardava (o token do cliente da Cloudflare, que é zerado). */
        void encerrar();
    }

    private static final int LARGURA = 520;

    private final String hostname;
    private final Motor motor;
    private final Notificacoes avisos;
    private final Runnable aoRemover;
    private final Rotulo situacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido passos = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final AreaMonoespacada areaDoTunel = new AreaMonoespacada("O que sai do túnel", false);
    private final JScrollPane rolagemDoTunel = AreaMonoespacada.emRolagem(areaDoTunel);
    private final Rotulo tituloDoTunel = new Rotulo("Túnel: o que sai").negrito();
    private final TextoCorrido avisosDaPrevia = new TextoCorrido("", LARGURA).corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final TextoCorrido bloqueio = erroCorrido();
    private final JTextField campoDaConfirmacao = Campos.mono(34, "digite o endereço");
    private final Rotulo pedidoDeConfirmacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao remover;
    private final Botao cancelar = Botoes.primario("Cancelar", null);
    private final Map<Etapa, Rotulo> linhas = new EnumMap<>(Etapa.class);
    private final JComponent blocoDasEtapas;
    private final Selo selo = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido textoDoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido pendencias = new TextoCorrido("", LARGURA).corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final JComponent blocoDoResultado;
    private volatile Cancelamento cancelamento = new Cancelamento();
    private RemovedorDeEndereco.Previa previa;
    private boolean ocupado;
    private boolean pediuParar;
    private boolean motorSolto;

    public DialogoRemoverEndereco(String hostname, Motor motor, Notificacoes avisos, Runnable aoRemover) {
        super("Remover endereço");
        this.hostname = hostname;
        this.motor = motor;
        this.avisos = avisos;
        this.aoRemover = aoRemover;
        this.remover = Botoes.perigo("Remover " + hostname, "lixeira");
        remover.setEnabled(false);
        campoDaConfirmacao.putClientProperty("html.disable", Boolean.TRUE);
        campoDaConfirmacao.getAccessibleContext().setAccessibleName("Digite o endereço para confirmar");
        pedidoDeConfirmacao.setText("Para confirmar, digite o endereço: " + hostname);
        for (JComponent c : List.of(passos, tituloDoTunel, rolagemDoTunel, avisosDaPrevia, bloqueio)) {
            c.setVisible(false);
        }
        rolagemDoTunel.setPreferredSize(new java.awt.Dimension(LARGURA, 120));
        blocoDasEtapas = Caixa.coluna(4);
        blocoDasEtapas.setVisible(false);
        for (Etapa e : List.of(Etapa.REMOVER_REGRA, Etapa.CONFERIR_E_REINICIAR, Etapa.REMOVER_DNS)) {
            Rotulo r = new Rotulo(e.rotulo()).corDoToken(Tokens.TEXTO3).tamanho(12.5f);
            r.setIcon(Icones.colorido("relogio", 14, Tokens.TEXTO3));
            r.setIconTextGap(8);
            linhas.put(e, r);
            blocoDasEtapas.add(r);
        }
        blocoDoResultado = Caixa.coluna(6, Caixa.linha(8, selo, Caixa.mola()), textoDoResultado, pendencias);
        blocoDoResultado.setVisible(false);
        pendencias.setVisible(false);
        JComponent corpo = Caixa.coluna(10,
            cabecalho("lixeira", "Remover endereço"),
            new Rotulo(hostname).mono(13.5f).negrito(),
            situacao, passos, tituloDoTunel, rolagemDoTunel, avisosDaPrevia, bloqueio,
            pedidoDeConfirmacao, campoDaConfirmacao,
            blocoDasEtapas, blocoDoResultado);
        conteudoComRodape(corpo, rodape(null, remover, cancelar));
        botaoPadrao(cancelar); // o Enter aciona o seguro, nunca o destrutivo
        cancelar.addActionListener(e -> aoCancelar());
        remover.addActionListener(e -> remover());
        campoDaConfirmacao.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(this::conferirAConfirmacao));
        carregar();
    }

    private void carregar() {
        situacao.setText("Lendo o túnel e o DNS (só leitura)…");
        Tarefas.rodar(() -> motor.preparar(hostname), p -> {
            if (jaFechou()) {
                soltarOMotor();
                return;
            }
            previa = p;
            situacao.setText("");
            situacao.setVisible(false);
            StringBuilder sb = new StringBuilder();
            int n = 1;
            for (String s : p.passos()) {
                sb.append(n++).append(". ").append(s).append('\n');
            }
            passos.setText(sb.toString().stripTrailing());
            passos.setVisible(!p.passos().isEmpty());
            areaDoTunel.setText(p.diferencaDoTunel());
            areaDoTunel.setCaretPosition(0);
            boolean temDiferenca = !p.diferencaDoTunel().isEmpty();
            tituloDoTunel.setVisible(temDiferenca);
            rolagemDoTunel.setVisible(temDiferenca);
            avisosDaPrevia.setText(String.join("\n", p.avisos().stream().map(a -> "• " + a).toList()));
            avisosDaPrevia.setVisible(!p.avisos().isEmpty());
            dizerErro(bloqueio, p.bloqueio() == null ? "" : p.bloqueio());
            conferirAConfirmacao();
            reajustar();
        }, falha -> {
            if (jaFechou()) {
                soltarOMotor();
                return;
            }
            situacao.setVisible(false);
            dizerErro(bloqueio, falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            reajustar();
        });
    }

    private void conferirAConfirmacao() {
        boolean digitouCerto = campoDaConfirmacao.getText().strip().toLowerCase(Locale.ROOT).equals(hostname.toLowerCase(Locale.ROOT));
        remover.setEnabled(!ocupado && previa != null && previa.podeRemover() && digitouCerto);
    }

    private void remover() {
        if (ocupado || previa == null || !previa.podeRemover() || !remover.isEnabled() || jaFechou()) {
            return;
        }
        RemovedorDeEndereco.Previa p = previa;
        ocupado = true;
        pediuParar = false;
        cancelamento = new Cancelamento();
        Cancelamento meu = cancelamento;
        remover.definirOcupado(true);
        campoDaConfirmacao.setEnabled(false);
        cancelar.setText("Parar");
        blocoDasEtapas.setVisible(true);
        passos.setVisible(false);
        reajustar();
        ObservadorDaEtapa obs = (etapa, estado, frase) -> Fundo.naEdt(() -> mostrarEtapa(etapa, estado, frase));
        Tarefas.rodar(() -> motor.executar(p, obs, meu), r -> aoTerminar(r),
            falha -> aoTerminar(new RemovedorDeEndereco.Resultado(false, MensagensDeErro.de(falha), List.of(), List.of(), List.of())));
    }

    private void mostrarEtapa(Etapa etapa, ObservadorDaEtapa.Estado estado, String frase) {
        Rotulo r = linhas.get(etapa);
        if (r == null) {
            return;
        }
        String nome = etapa.rotulo();
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
        reajustar();
    }

    private void aoTerminar(RemovedorDeEndereco.Resultado r) {
        ocupado = false;
        remover.definirOcupado(false);
        if (r.removido()) {
            aoRemover.run();
        }
        if (jaFechou()) {
            if (r.removido()) {
                avisos.ok("Endereço removido: " + hostname + ".");
            } else {
                avisos.erro("Remover " + hostname + " não deu certo: " + r.falha() + (r.naoConsegui().isEmpty() ? "" : " Atenção: " + String.join(" ", r.naoConsegui())));
            }
            soltarOMotor();
            return;
        }
        cancelar.setText("Fechar");
        remover.setVisible(false);
        blocoDoResultado.setVisible(true);
        if (r.removido()) {
            selo.atualizar("REMOVIDO", Gravidade.OK, null);
            textoDoResultado.setText(hostname + " saiu do túnel e do DNS. O compose e o container do projeto continuam como estavam."
                + (r.copias().isEmpty() ? "" : "\nCópia datada guardada: " + String.join(", ", r.copias())));
        } else {
            selo.atualizar("NÃO REMOVEU", Gravidade.PERIGO, null);
            textoDoResultado.setText(r.falha() + (r.desfeito().isEmpty() ? "" : "\n" + String.join(" ", r.desfeito())));
            if (!r.naoConsegui().isEmpty()) {
                pendencias.setText("ATENÇÃO, fique de olho: " + String.join("\n", r.naoConsegui()));
                pendencias.setVisible(true);
            }
        }
        reajustar();
    }

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
            reajustar();
            return;
        }
        avisos.info("A remoção foi mandada parar e termina em segundo plano. O resultado aparece aqui em seguida.");
        fechar();
    }

    @Override
    protected void aoFechar() {
        if (!ocupado) {
            soltarOMotor();
        }
    }

    private void soltarOMotor() {
        if (!motorSolto) {
            motorSolto = true;
            motor.encerrar();
        }
    }

    // ---- para os testes ----

    public boolean ocupado() {
        return ocupado;
    }

    public boolean fechou() {
        return jaFechou();
    }

    public JTextField campoDaConfirmacao() {
        return campoDaConfirmacao;
    }

    public Botao botaoRemover() {
        return remover;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public String textoDosPassos() {
        return passos.isVisible() ? passos.getText() : "";
    }

    public String textoDoTunel() {
        return rolagemDoTunel.isVisible() ? areaDoTunel.getText() : "";
    }

    public String textoDosAvisos() {
        return avisosDaPrevia.isVisible() ? avisosDaPrevia.getText() : "";
    }

    public String textoDoBloqueio() {
        return bloqueio.isVisible() ? bloqueio.getText() : "";
    }

    public String seloDoResultado() {
        return blocoDoResultado.isVisible() ? selo.texto() : "";
    }

    public String textoDoResultado() {
        return blocoDoResultado.isVisible() ? textoDoResultado.getText() : "";
    }

    public String textoDasPendencias() {
        return pendencias.isVisible() ? pendencias.getText() : "";
    }

    public Map<Etapa, String> linhasDosPassos() {
        Map<Etapa, String> m = new EnumMap<>(Etapa.class);
        linhas.forEach((e, r) -> m.put(e, r.getText()));
        return m;
    }

    public RemovedorDeEndereco.Previa previa() {
        return previa;
    }
}
