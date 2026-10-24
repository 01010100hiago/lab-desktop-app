package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JTextField;

/**
 * "Definir limite" de um projeto ({@code UI.limite.abrirDefinir} do painel web): o teto da semana, de 1 a 100
 * pontos percentuais da semana do plano, ou "Sem limite". Quando a fatia estimada do projeto chega ao teto, a sessão
 * do Claude é pausada e só volta com Retomar. A dica diz, em texto, quando o valor digitado já está abaixo do uso
 * (a sessão pausaria no ciclo seguinte do guarda, em até 1 min).
 *
 * <p>O {@link Salvador} roda numa thread de fundo; um erro do agente aparece DENTRO do diálogo, que continua aberto.
 */
public final class DialogoLimite extends Dialogo {

    /** Grava o teto no agente ({@code PUT /api/projetos/:p/limite}). Bloqueia: roda fora da EDT. */
    @FunctionalInterface
    public interface Salvador {
        void salvar(Integer tetoPct) throws ErroApi;
    }

    private final Limite atual;
    private final Salvador salvador;
    private final Consumer<Integer> aoSalvar;
    private final JTextField valor = Campos.mono(6, "ex.: 20");
    private final JCheckBox semLimite = new JCheckBox("Sem limite (remove o teto deste projeto)");
    private final TextoCorrido dicaTexto = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Rotulo erro = erro();
    private final Botao salvar = Botoes.primario("Salvar", null);
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private Runnable desfazerOcupado = () -> { };

    /**
     * @param projeto  nome do projeto ({@code lab} vira "sessão do lab" no título)
     * @param atual    o limite de hoje (pode ser {@code null})
     * @param aoSalvar roda na EDT depois de o agente aceitar, com o teto gravado ({@code null} = limite removido)
     */
    public DialogoLimite(String projeto, Limite atual, Salvador salvador, Consumer<Integer> aoSalvar) {
        super("Definir limite");
        this.atual = atual;
        this.salvador = salvador;
        this.aoSalvar = aoSalvar;

        TextoCorrido explicacao = paragrafo("Teto de consumo, em pontos percentuais da semana do plano Max (a conta inteira "
            + "vale 100%). Quando a fatia estimada deste projeto chegar nele, a sessão do Claude é pausada e só volta "
            + "com Retomar.");

        conteudo(Caixa.coluna(14,
            cabecalho("moeda", "Limite de " + TextosDeLimite.nomeAmigavel(projeto)),
            explicacao,
            Caixa.coluna(6, Rotulo.rotuloDeCampo("Teto da semana (%)"), valor, semLimite),
            dicaTexto,
            erro,
            rodape(null, cancelar, salvar)));
        botaoPadrao(salvar);

        if (atual != null && atual.tetoPct() != null) {
            valor.setText(String.valueOf(atual.tetoPct()));
        }
        valor.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            erro.setText("");
            atualizarDica();
        }));
        semLimite.addActionListener(e -> {
            valor.setEnabled(!semLimite.isSelected());
            if (semLimite.isSelected()) {
                valor.setText("");
                erro.setText("");
            }
            atualizarDica();
        });
        salvar.addActionListener(e -> salvar());
        valor.addActionListener(e -> salvar());
        cancelar.addActionListener(e -> aoCancelar());
        atualizarDica();
    }

    public JTextField campoDoValor() {
        return valor;
    }

    public JCheckBox caixaSemLimite() {
        return semLimite;
    }

    public Botao botaoSalvar() {
        return salvar;
    }

    public String textoDoErro() {
        return erro.getText();
    }

    public String textoDaDica() {
        return dicaTexto.getText();
    }

    /** O que o usuário digitou como inteiro de 1 a 100; {@code null} se não vale. */
    static Integer lerTeto(String digitado) {
        if (digitado == null || digitado.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(digitado.strip().replace(',', '.'));
            if (Double.isNaN(v) || Math.floor(v) != v || v < 1 || v > 100) {
                return null;
            }
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void atualizarDica() {
        StringBuilder sb = new StringBuilder();
        if (atual != null) {
            sb.append("Uso estimado agora: ").append(TextosDeLimite.fmtUsado(atual)).append(" da semana");
            if (atual.tetoPct() != null) {
                sb.append(" · teto atual: ").append(Formato.fmtNum(atual.tetoPct())).append('%');
            }
            sb.append('.');
        }
        Integer v = lerTeto(valor.getText());
        if (!semLimite.isSelected() && atual != null && v != null && atual.usadoPp() != null && atual.usadoPp() >= v
            && !Boolean.TRUE.equals(atual.liberado())) {
            sb.append(sb.length() > 0 ? " " : "")
                .append("Atenção: esse valor já está abaixo do uso — a sessão pausa no próximo ciclo (até 1 min).");
        }
        if (semLimite.isSelected()) {
            sb.append(sb.length() > 0 ? " " : "").append("Sem teto próprio, o projeto só pausa pelo freio global.");
        }
        dicaTexto.setText(sb.toString());
        reajustar();
    }

    private void salvar() {
        if (!salvar.isEnabled()) {
            return;
        }
        Integer teto;
        if (semLimite.isSelected()) {
            teto = null;
        } else {
            teto = lerTeto(valor.getText());
            if (teto == null) {
                erro.setText("Informe um número inteiro de 1 a 100 — ou marque \"Sem limite\".");
                valor.requestFocusInWindow();
                reajustar();
                return;
            }
        }
        erro.setText("");
        desfazerOcupado = salvar.ocupar();
        Tarefas.rodar(() -> salvador.salvar(teto), () -> {
            desfazerOcupado.run();
            fechar();
            aoSalvar.accept(teto);
        }, falha -> {
            desfazerOcupado.run();
            erro.setText(MensagensDeErro.de(falha));
            reajustar();
        });
    }

    @Override
    protected void antesDeMostrar() {
        valor.requestFocusInWindow();
        valor.selectAll();
    }
}
