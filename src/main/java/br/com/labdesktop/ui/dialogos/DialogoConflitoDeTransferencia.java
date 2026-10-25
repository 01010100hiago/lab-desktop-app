package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.FilaDeTransferencias.Conflito;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Decisao;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Escolha;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;

/**
 * "Já existe": o destino de um envio ou download já tem um item com este nome. Quatro respostas, com a que não perde nada
 * ("Manter os dois") em destaque: Substituir, Pular, Manter os dois e Cancelar. Uma caixa faz a resposta valer para os
 * próximos conflitos da MESMA transferência (aplicar a todos). Fechar a janela é Cancelar.
 */
public final class DialogoConflitoDeTransferencia extends Dialogo {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"));

    private final Consumer<Decisao> aoDecidir;
    private final Botao substituir = Botoes.perigo("Substituir", null);
    private final Botao pular = Botoes.secundario("Pular", null);
    private final Botao manterOsDois = Botoes.primario("Manter os dois", null);
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final JCheckBox aplicarATodos = new JCheckBox("Fazer o mesmo nos próximos conflitos desta transferência");
    private boolean decidiu;

    public DialogoConflitoDeTransferencia(Conflito c, ZoneId fuso, Consumer<Decisao> aoDecidir) {
        super("Já existe um item com este nome");
        this.aoDecidir = aoDecidir;
        boolean envio = c.tipo() == Tipo.ENVIO;
        String nome = TextoSeguro.limparLinha(c.nome());
        String ondeEsta = envio ? "No servidor" : "No seu Mac";
        String outro = envio ? "O que você está enviando" : "O que você está baixando";
        JComponent corpo = Caixa.coluna(12,
            cabecalho("alerta", "\"" + encurtar(nome) + "\" já existe"),
            paragrafo("O destino já tem um item com este nome. O que fazer?"),
            Caixa.coluna(4,
                linha(ondeEsta, c.tamanhoDoDestino(), c.modificadoDoDestino(), fuso),
                linha(outro, c.tamanhoDaOrigem(), c.modificadoDaOrigem(), fuso)),
            dicaCorrida("Manter os dois guarda os dois, e o novo ganha um número no nome: " + nomeComNumero(nome) + "."),
            aplicarATodos,
            rodape(null, cancelar, pular, substituir, manterOsDois));
        aplicarATodos.setOpaque(false);
        aplicarATodos.putClientProperty("html.disable", Boolean.TRUE);
        conteudo(corpo);
        botaoPadrao(manterOsDois);
        substituir.addActionListener(e -> decidir(Escolha.SUBSTITUIR));
        pular.addActionListener(e -> decidir(Escolha.PULAR));
        manterOsDois.addActionListener(e -> decidir(Escolha.MANTER_OS_DOIS));
        cancelar.addActionListener(e -> aoCancelar());
    }

    private static JComponent linha(String quem, long tamanho, Instant data, ZoneId fuso) {
        String detalhe = (tamanho >= 0 ? Formato.fmtBytes(tamanho) : "") + (data == null ? "" : (tamanho >= 0 ? ", " : "") + "mudado em " + DATA.format(data.atZone(fuso)));
        return Caixa.linha(8, new Rotulo(quem + ":").negrito().tamanho(12.5f), new Rotulo(detalhe).corDoToken(Tokens.TEXTO2).tamanho(12.5f));
    }

    static String nomeComNumero(String nome) {
        int p = nome.lastIndexOf('.');
        return p <= 0 ? nome + " (2)" : nome.substring(0, p) + " (2)" + nome.substring(p);
    }

    private static String encurtar(String t) {
        return t.length() <= 44 ? t : t.substring(0, 20) + "…" + t.substring(t.length() - 20);
    }

    public Botao botaoSubstituir() {
        return substituir;
    }

    public Botao botaoPular() {
        return pular;
    }

    public Botao botaoManterOsDois() {
        return manterOsDois;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public JCheckBox caixaDeAplicarATodos() {
        return aplicarATodos;
    }

    @Override
    protected void aoCancelar() {
        decidir(Escolha.CANCELAR);
    }

    private void decidir(Escolha e) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        boolean todos = aplicarATodos.isSelected() && e != Escolha.CANCELAR;
        fechar();
        aoDecidir.accept(new Decisao(e, todos));
    }

    @Override
    protected void aoFechar() {
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(new Decisao(Escolha.CANCELAR, false));
        }
    }
}
