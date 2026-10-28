package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.SecaoRecolhivel;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.InformacoesDoApp;
import br.com.labdesktop.util.LicencasDeTerceiros;
import br.com.labdesktop.util.LicencasDeTerceiros.Licenca;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * "Sobre o Lab Desktop": o nome e a versão do app (a mesma do {@code --versao}), o commit do agente que vai dentro dele, o Java, o sistema e a arquitetura, a pasta de dados
 * (com o botão Copiar, e a pasta do usuário trocada por {@code ~}), o texto "software livre e gratuito, licença MIT, sem garantia", o texto oficial da licença e a tabela de
 * licenças de terceiros. Todo o texto de licença sai do jar (nada de rede). "Copiar informações" põe a versão, o sistema, o Java e o agente na área de transferência para o
 * usuário colar num chamado. Aparece como seção da tela Ajustes e, quando não há janela principal (cofre bloqueado), no {@code DialogoSobre}.
 */
public final class PainelSobre extends Painel {

    private static final long serialVersionUID = 1L;

    private final InformacoesDoApp info;
    private final Botao copiarInformacoes;
    private final Botao copiarPasta;
    private final SecaoRecolhivel textoDaLicenca;
    private final SecaoRecolhivel terceiros;
    private final List<Licenca> licencas;

    public PainelSobre(InformacoesDoApp info, CopiadorDeTexto copiador, Notificacoes avisos) {
        super(new BorderLayout());
        this.info = info;
        this.licencas = LicencasDeTerceiros.doJar();
        Rotulo marca = new Rotulo().icone(Icones.colorido("logo", 28, Tokens.DESTAQUE));
        Rotulo nome = Rotulo.titulo(info.nome()).tamanho(20f);
        Rotulo versao = new Rotulo("Versão " + info.versao()).corDoToken(Tokens.TEXTO2);
        JComponent cabecalho = Caixa.linha(12, marca, Caixa.coluna(2, nome, versao), Caixa.mola());

        copiarInformacoes = BotaoCopiar.criar("Copiar informações", info::comoTexto, copiador, avisos);
        copiarInformacoes.setToolTipText("Copia a versão, o sistema, o Java e o agente, para você colar num chamado");
        copiarPasta = BotaoCopiar.criar("Copiar", info::pastaDeDados, copiador, avisos);
        copiarPasta.setToolTipText("Copia o caminho da pasta de dados (a sua pasta de usuário aparece como ~)");

        JPanel ficha = new Painel(new GridBagLayout());
        ficha.setOpaque(false);
        linhaDaFicha(ficha, 0, "Versão do app", info.versao(), null);
        linhaDaFicha(ficha, 1, "Agente empacotado", info.agenteVersao(), null);
        linhaDaFicha(ficha, 2, "Commit do agente", info.agenteCommit(), null);
        linhaDaFicha(ficha, 3, "Java", info.versaoDoJava(), null);
        linhaDaFicha(ficha, 4, "Sistema", info.sistema().strip(), null);
        linhaDaFicha(ficha, 5, "Arquitetura", info.arquitetura(), null);
        linhaDaFicha(ficha, 6, "Pasta de dados", info.pastaDeDados(), copiarPasta);

        TextoCorrido resumo = new TextoCorrido(LicencasDeTerceiros.resumoEmPortugues(), Paginas.LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO).tamanho(13.5f);
        AreaMonoespacada licenca = new AreaMonoespacada("Texto da licença MIT", true);
        licenca.setText(LicencasDeTerceiros.textoDaLicenca());
        licenca.setRows(18);
        textoDaLicenca = new SecaoRecolhivel("Ver o texto da licença MIT", "Esconder o texto da licença", AreaMonoespacada.emRolagem(licenca), this::refazer);
        terceiros = new SecaoRecolhivel("Ver as licenças de terceiros (" + licencas.size() + ")", "Esconder as licenças de terceiros", tabelaDeTerceiros(), this::refazer);

        add(Paginas.coluna(12,
            cabecalho,
            Paginas.cartao(ficha, Caixa.linha(8, copiarInformacoes, Caixa.mola())),
            Paginas.grupo("Software livre e gratuito"),
            Paginas.cartao(resumo, new Rotulo("Copyright (c) 2026 Thiago Bezerra").corDoToken(Tokens.TEXTO2).tamanho(12.5f), textoDaLicenca),
            Paginas.grupo("Licenças de terceiros"),
            Paginas.ajuda("O app usa estas bibliotecas livres. Cada uma segue a licença dela."),
            Paginas.cartao(terceiros)), BorderLayout.NORTH);
        getAccessibleContext().setAccessibleName("Sobre o " + info.nome());
    }

    private static void linhaDaFicha(JPanel ficha, int linha, String rotulo, String valor, JComponent acao) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = linha;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 14);
        c.gridx = 0;
        ficha.add(new Rotulo(rotulo).corDoToken(Tokens.TEXTO2).tamanho(13f), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        // o valor QUEBRA em linhas (uma pasta de dados comprida, o nome do sistema): nunca é cortado com reticências, e dá para selecionar e copiar
        TextoCorrido v = new TextoCorrido(valor, 320).corDoToken(Tokens.TEXTO).tamanho(13f);
        ficha.add(v, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(3, 6, 3, 0);
        if (acao != null) {
            ficha.add(acao, c);
        }
    }

    private JComponent tabelaDeTerceiros() {
        JPanel grade = new Painel(new GridBagLayout());
        grade.setOpaque(false);
        int y = 0;
        for (String titulo : new String[] {"BIBLIOTECA", "VERSÃO", "LICENÇA"}) {
            GridBagConstraints c = coluna(y == 0 ? 0 : y == 1 ? 1 : 2, 0);
            grade.add(new Rotulo(titulo).corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f), c);
            y++;
        }
        int linha = 1;
        for (Licenca l : licencas) {
            // as três colunas QUEBRAM em linhas (nome e licença compridos): nada é cortado com reticências
            TextoCorrido nome = new TextoCorrido(l.nome(), 190).corDoToken(Tokens.TEXTO).tamanho(13f);
            grade.add(nome, coluna(0, linha));
            grade.add(new TextoCorrido(l.versao(), 90).corDoToken(Tokens.TEXTO2).tamanho(13f), coluna(1, linha));
            grade.add(new TextoCorrido(l.licenca(), 260).corDoToken(Tokens.TEXTO).tamanho(13f), coluna(2, linha));
            linha++;
        }
        return grade;
    }

    private static GridBagConstraints coluna(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = x == 0 ? 0.3 : x == 1 ? 0.15 : 0.55;
        c.insets = new Insets(3, 0, 3, 14);
        return c;
    }

    private void refazer() {
        revalidate();
        repaint();
    }

    // ---- para os testes ----

    public InformacoesDoApp informacoes() {
        return info;
    }

    public Botao botaoCopiarInformacoes() {
        return copiarInformacoes;
    }

    public Botao botaoCopiarPasta() {
        return copiarPasta;
    }

    public SecaoRecolhivel secaoDaLicenca() {
        return textoDaLicenca;
    }

    public SecaoRecolhivel secaoDeTerceiros() {
        return terceiros;
    }

    public List<Licenca> licencas() {
        return licencas;
    }
}
