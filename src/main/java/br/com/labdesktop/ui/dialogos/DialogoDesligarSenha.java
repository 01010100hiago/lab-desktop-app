package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.acesso.DesligadorDeSenha;
import br.com.labdesktop.acesso.DesligadorDeSenha.Avaliacao;
import br.com.labdesktop.acesso.DesligadorDeSenha.Desfecho;
import br.com.labdesktop.acesso.DesligadorDeSenha.Estado;
import br.com.labdesktop.acesso.DesligadorDeSenha.Motivo;
import br.com.labdesktop.acesso.DesligadorDeSenha.Passo;
import br.com.labdesktop.acesso.DesligadorDeSenha.Resultado;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JTextField;

/**
 * "Desligar o login por senha neste servidor". Nunca automático: o usuário chega aqui pelo botão "Desligar a senha…" do último passo do
 * assistente ou da aba Segurança, e o diálogo faz três coisas, nesta ordem:
 * <ol>
 *   <li><b>Só lê</b> (ao abrir): a permissão do usuário, a configuração efetiva do sshd e os arquivos. Mostra em palavras simples o que será feito, o que
 *       encontrou e os RISCOS (outros que entram por senha deixam de entrar; se a chave do cofre se perder só se entra pelo painel do provedor; o app não
 *       religa a senha), e o comando para reverter. Se não há permissão (nem root, nem {@code sudo -n}), NÃO tenta e NÃO pede senha de sudo: mostra o
 *       comando exato para o usuário rodar. Se algo não é seguro (métodos combinados, arquivo anterior que manda mais), diz por quê e não oferece o botão.</li>
 *   <li><b>Confirma</b>: o botão leva o nome do servidor ("Desligar a senha em lab") e só vale depois de o usuário digitar o nome do servidor.</li>
 *   <li><b>Executa</b> os passos (cópia datada, gravar, validar com {@code sshd -t}, recarregar, provar a chave, conferir) e mostra o resultado REAL. Se a prova
 *       da chave falha, o próprio passo desfaz o que fez e diz o que aconteceu.</li>
 * </ol>
 * Nada de rede na EDT. Enquanto executa, Esc, o X e o botão não fecham a janela: parar no meio poderia deixar a configuração pela metade.
 */
public final class DialogoDesligarSenha extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        /** Só lê. */
        Avaliacao avaliar() throws Exception;

        /** Escreve: só depois do clique e do nome digitado. */
        Resultado desligar(Avaliacao avaliacao, DesligadorDeSenha.Observador observador);
    }

    private static final int LARGURA = 520;

    private final String nomeDoServidor;
    private final Motor motor;
    private final Notificacoes avisos;
    private final Runnable aoAgir;
    private final Rotulo leitura = new Rotulo("Lendo a configuração do servidor (só leitura)…").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido bloqueio = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO).tamanho(13f);
    private final Rotulo tituloDoComando = new Rotulo("Rode no servidor, como administrador").negrito();
    private final TextoCorrido comandoManual = new TextoCorrido("", LARGURA).mono(12f);
    private final Botao copiarComandoManual;
    private final Rotulo tituloDoQueFaz = new Rotulo("O que o app vai fazer").negrito();
    private final TextoCorrido oQueFaz = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Rotulo tituloDoEncontrado = new Rotulo("O que o app encontrou").negrito();
    private final TextoCorrido encontrado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido avisosDaLeitura = new TextoCorrido("", LARGURA).corDoToken(Tokens.ATENCAO).tamanho(12.5f);
    private final Rotulo tituloDosRiscos = new Rotulo("O que você precisa saber").negrito();
    private final TextoCorrido riscos = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Rotulo tituloDoReverter = new Rotulo("Como reverter (guarde este comando)").negrito();
    private final TextoCorrido comandoDeReverter = new TextoCorrido("", LARGURA).mono(12f);
    private final Botao copiarComandoDeReverter;
    private final Rotulo pedidoDeConfirmacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JTextField campoDaConfirmacao = Campos.mono(30, "digite o nome do servidor");
    private final Botao desligar;
    private final Botao fechar = Botoes.primario("Cancelar", null);
    private final Map<Passo, Rotulo> linhas = new EnumMap<>(Passo.class);
    private final JComponent blocoDosPassos;
    private final Selo selo = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido textoDoResultado = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO).tamanho(13f);
    private final TextoCorrido detalhes = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JComponent blocoDoResultado;
    private final JComponent blocoDoReverter;
    private final JComponent blocoDoComandoManual;
    private final JComponent blocoDaPrevia;
    private Avaliacao avaliacao;
    private boolean ocupado;
    private boolean agiu;
    private Resultado resultado;

    /**
     * @param aoAgir roda (na EDT) quando o diálogo fecha depois de o usuário ter mandado desligar (ou ter visto o comando); o assistente o usa para concluir
     */
    public DialogoDesligarSenha(String nomeDoServidor, Motor motor, CopiadorDeTexto copiador, Notificacoes avisos, Runnable aoAgir) {
        super("Desligar o login por senha");
        this.nomeDoServidor = nomeDoServidor;
        this.motor = motor;
        this.avisos = avisos;
        this.aoAgir = aoAgir;
        this.desligar = Botoes.perigo("Desligar a senha em " + nomeDoServidor, null);
        this.copiarComandoManual = BotaoCopiar.criar("Copiar comando", comandoManual::getText, copiador, avisos);
        this.copiarComandoDeReverter = BotaoCopiar.criar("Copiar comando", comandoDeReverter::getText, copiador, avisos);
        desligar.setEnabled(false);
        desligar.setVisible(false);
        campoDaConfirmacao.putClientProperty("html.disable", Boolean.TRUE);
        campoDaConfirmacao.getAccessibleContext().setAccessibleName("Digite o nome do servidor para confirmar");
        pedidoDeConfirmacao.setText("Para confirmar, digite o nome do servidor: " + nomeDoServidor);

        oQueFaz.setText(textoDoQueFaz());
        riscos.setText(textoDosRiscos());
        comandoDeReverter.setText(ComandosDoAcesso.comandoParaReverter());
        blocoDoReverter = Caixa.coluna(6, tituloDoReverter, comandoDeReverter, Caixa.linha(0, copiarComandoDeReverter, Caixa.mola()));
        blocoDoComandoManual = Caixa.coluna(6, tituloDoComando, comandoManual, Caixa.linha(0, copiarComandoManual, Caixa.mola()));
        // O BoxLayout deixa o espaço de um componente escondido: em vez de "espaço entre" (que sobra quando um bloco some), cada bloco leva o seu recuo em cima.
        blocoDaPrevia = Caixa.coluna(0, recuo(tituloDoQueFaz, 0), recuo(oQueFaz, 4), recuo(tituloDoEncontrado, 12), recuo(encontrado, 4), recuo(avisosDaLeitura, 10),
            recuo(tituloDosRiscos, 12), recuo(riscos, 4), recuo(blocoDoReverter, 12), recuo(pedidoDeConfirmacao, 14), recuo(Caixa.linha(0, campoDaConfirmacao, Caixa.mola()), 4));
        blocoDosPassos = Caixa.coluna(4);
        for (Passo p : Passo.values()) {
            Rotulo r = new Rotulo(p.rotulo()).corDoToken(Tokens.TEXTO3).tamanho(12.5f);
            r.setIcon(Icones.colorido("relogio", 14, Tokens.TEXTO3));
            r.setIconTextGap(8);
            linhas.put(p, r);
            blocoDosPassos.add(r);
        }
        blocoDoResultado = Caixa.coluna(0, Caixa.linha(8, selo, Caixa.mola()), recuo(textoDoResultado, 8), recuo(detalhes, 8));
        for (JComponent c : List.of(blocoDaPrevia, bloqueio, blocoDoComandoManual, blocoDosPassos, blocoDoResultado)) {
            c.setVisible(false);
        }
        JComponent corpo = Caixa.coluna(0,
            cabecalho("chave", "Desligar o login por senha"),
            recuo(new Rotulo(nomeDoServidor).mono(13.5f).negrito(), 6),
            recuo(leitura, 12), recuo(bloqueio, 12), recuo(blocoDoComandoManual, 14), recuo(blocoDaPrevia, 14), recuo(blocoDosPassos, 14), recuo(blocoDoResultado, 14));
        conteudoComRodape(corpo, rodape(null, desligar, fechar));
        botaoPadrao(fechar); // o Enter aciona o seguro, nunca o que muda o servidor
        fechar.addActionListener(e -> aoCancelar());
        desligar.addActionListener(e -> desligarAgora());
        campoDaConfirmacao.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(this::conferirAConfirmacao));
        ler();
    }

    /** O componente com um recuo em cima (some junto com ele quando escondido, sem deixar vão). */
    private static <T extends JComponent> T recuo(T c, int topo) {
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder(topo, 0, 0, 0));
        return c;
    }

    /** A lista numerada do que o app vai fazer, na ordem em que faz. */
    public static String textoDoQueFaz() {
        return "1. Faz uma cópia datada do arquivo de configuração do SSH do servidor.\n"
            + "2. Cria o arquivo " + ComandosDoAcesso.ARQUIVO_PROPRIO + " com duas linhas: PasswordAuthentication no e KbdInteractiveAuthentication no.\n"
            + "3. Confere a configuração (sshd -t) antes de aplicar.\n"
            + "4. Recarrega o SSH, sem reiniciar: a sua conexão atual continua aberta.\n"
            + "5. Abre uma conexão nova só com a chave. Se ela não entrar, o app desfaz tudo sozinho e conta o que aconteceu.";
    }

    /** Os riscos, em linguagem simples. */
    public static String textoDosRiscos() {
        return "• Outras pessoas e outros computadores que entram neste servidor por senha deixam de conseguir entrar.\n"
            + "• Se você perder a chave deste app (ela fica no cofre), só dá para entrar pelo painel do provedor ou ao lado do servidor.\n"
            + "• Depois que der certo, o app NÃO liga a senha de novo sozinho. Para voltar atrás, rode o comando de reverter abaixo no servidor.\n"
            + "• Servidores gerenciados por outra ferramenta (cloud-init, painel do provedor) podem desfazer esta mudança sozinhos.";
    }

    // ---------------------------------------------------------------------------------------------
    // Ler (só leitura)
    // ---------------------------------------------------------------------------------------------

    private void ler() {
        Tarefas.rodar(motor::avaliar, a -> {
            if (jaFechou()) {
                return;
            }
            avaliacao = a;
            leitura.setVisible(false);
            mostrarAAvaliacao(a);
        }, falha -> {
            if (jaFechou()) {
                return;
            }
            leitura.setVisible(false);
            bloqueio.setText("Não consegui ler a configuração do servidor: " + MensagensDeErro.de(falha) + " Nada foi alterado.");
            bloqueio.setVisible(true);
            fechar.setText("Fechar");
            reajustar();
        });
    }

    private void mostrarAAvaliacao(Avaliacao a) {
        fechar.setText("Cancelar");
        if (a.bloqueio() != null) {
            fechar.setText("Fechar");
            bloqueio.setText(a.bloqueio().texto());
            bloqueio.setVisible(true);
            if (a.bloqueio().motivo() == Motivo.SEM_PERMISSAO) {
                comandoManual.setText(ComandosDoAcesso.comandoParaOsDonoRodar());
                blocoDoComandoManual.setVisible(true);
                blocoDoReverter.setVisible(true);
                encontrado.setText("");
                tituloDoQueFaz.setVisible(false);
                oQueFaz.setVisible(false);
                tituloDoEncontrado.setVisible(false);
                encontrado.setVisible(false);
                avisosDaLeitura.setVisible(false);
                tituloDosRiscos.setVisible(true);
                riscos.setVisible(true);
                pedidoDeConfirmacao.setVisible(false);
                campoDaConfirmacao.setVisible(false);
                blocoDaPrevia.setVisible(true);
                agiu = true; // o usuário viu o comando e vai rodá-lo sozinho: o assistente pode concluir
            } else if (!a.achados().isEmpty()) {
                encontrado.setText(marcadores(a.achados()));
                tituloDoQueFaz.setVisible(false);
                oQueFaz.setVisible(false);
                tituloDosRiscos.setVisible(false);
                riscos.setVisible(false);
                blocoDoReverter.setVisible(false);
                pedidoDeConfirmacao.setVisible(false);
                campoDaConfirmacao.setVisible(false);
                avisosDaLeitura.setVisible(false);
                blocoDaPrevia.setVisible(true);
            }
            reajustar();
            return;
        }
        encontrado.setText(marcadores(a.achados()));
        avisosDaLeitura.setText(marcadores(a.avisos()));
        avisosDaLeitura.setVisible(!a.avisos().isEmpty());
        blocoDaPrevia.setVisible(true);
        desligar.setVisible(true);
        conferirAConfirmacao();
        reajustar();
    }

    private static String marcadores(List<String> itens) {
        return String.join("\n", itens.stream().map(i -> "• " + i).toList());
    }

    private void conferirAConfirmacao() {
        boolean digitouCerto = campoDaConfirmacao.getText().strip().equals(nomeDoServidor);
        desligar.setEnabled(!ocupado && avaliacao != null && avaliacao.podeDesligar() && digitouCerto && resultado == null);
    }

    // ---------------------------------------------------------------------------------------------
    // Executar (só depois do clique e do nome digitado)
    // ---------------------------------------------------------------------------------------------

    private void desligarAgora() {
        if (ocupado || avaliacao == null || !avaliacao.podeDesligar() || !desligar.isEnabled() || jaFechou()) {
            return;
        }
        Avaliacao a = avaliacao;
        ocupado = true;
        agiu = true;
        desligar.definirOcupado(true);
        campoDaConfirmacao.setEnabled(false);
        fechar.setEnabled(false);
        blocoDaPrevia.setVisible(false);
        blocoDosPassos.setVisible(true);
        reajustar();
        DesligadorDeSenha.Observador obs = (passo, estado, frase) -> Fundo.naEdt(() -> mostrarPasso(passo, estado, frase));
        Tarefas.rodar(() -> motor.desligar(a, obs), this::aoTerminar,
            falha -> aoTerminar(new Resultado(Desfecho.PRECISA_DE_AJUDA, "Aconteceu um erro inesperado: " + MensagensDeErro.de(falha)
                + " Confira no servidor se o arquivo " + ComandosDoAcesso.ARQUIVO_PROPRIO + " existe e, se não era para existir, apague-o.", List.of(),
                null, ComandosDoAcesso.comandoParaReverter(), null)));
    }

    private void mostrarPasso(Passo passo, Estado estado, String frase) {
        Rotulo r = linhas.get(passo);
        String icone = switch (estado) {
            case ANDANDO -> "relogio";
            case FEITO -> "ok-circulo";
            case FALHOU -> "erro";
            case DESFEITO -> "desfazer";
        };
        String token = switch (estado) {
            case ANDANDO -> Tokens.TEXTO2;
            case FEITO -> Tokens.OK;
            case FALHOU -> Tokens.PERIGO;
            case DESFEITO -> Tokens.ATENCAO;
        };
        r.setIcon(Icones.colorido(icone, 14, token));
        r.setText(passo.rotulo() + (frase == null || frase.isEmpty() ? "" : " — " + resumir(frase)));
        r.setToolTipText(frase);
        reajustar();
    }

    private void aoTerminar(Resultado r) {
        ocupado = false;
        resultado = r;
        desligar.definirOcupado(false);
        desligar.setVisible(false);
        fechar.setEnabled(true);
        fechar.setText("Fechar");
        blocoDoResultado.setVisible(true);
        switch (r.desfecho()) {
            case DESLIGADA -> selo.atualizar("SENHA DESLIGADA", Gravidade.OK, null);
            case JA_ESTAVA_DESLIGADA -> selo.atualizar("JÁ ESTAVA DESLIGADA", Gravidade.OK, null);
            case NAO_APLICADA -> selo.atualizar("NADA FOI ALTERADO", Gravidade.ATENCAO, null);
            case FALHOU_SEM_EFEITO -> selo.atualizar("NADA MUDOU", Gravidade.ATENCAO, null);
            case DESFEITA -> selo.atualizar("DESFEITO", Gravidade.ATENCAO, null);
            case PRECISA_DE_AJUDA -> selo.atualizar("PRECISA DE AJUDA", Gravidade.PERIGO, null);
        }
        textoDoResultado.setText(r.resumo());
        detalhes.setText(marcadores(r.detalhes()));
        detalhes.setVisible(!r.detalhes().isEmpty());
        boolean mostrarOReverter = r.desfecho() == Desfecho.DESLIGADA || r.desfecho() == Desfecho.PRECISA_DE_AJUDA;
        blocoDoReverter.setVisible(mostrarOReverter);
        if (mostrarOReverter) {
            comandoDeReverter.setText(r.comandoParaReverter());
            blocoDoResultado.add(recuo(blocoDoReverter, 12));
        }
        if (jaFechou()) {
            avisos.info(r.resumo());
            return;
        }
        reajustar();
    }

    private static String resumir(String frase) {
        String f = frase.replace('\n', ' ').strip();
        return f.length() <= 90 ? f : f.substring(0, 89).stripTrailing() + "…";
    }

    @Override
    protected void aoCancelar() {
        if (ocupado) {
            avisos.info("Espere terminar: parar no meio poderia deixar a configuração do servidor pela metade.");
            return;
        }
        fechar();
    }

    @Override
    protected void aoFechar() {
        if (agiu) {
            aoAgir.run();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public boolean ocupado() {
        return ocupado;
    }

    public boolean fechou() {
        return jaFechou();
    }

    public JTextField campoDaConfirmacao() {
        return campoDaConfirmacao;
    }

    public Botao botaoDesligar() {
        return desligar;
    }

    public Botao botaoFechar() {
        return fechar;
    }

    public Botao botaoCopiarComandoManual() {
        return copiarComandoManual;
    }

    public Botao botaoCopiarComandoDeReverter() {
        return copiarComandoDeReverter;
    }

    public Avaliacao avaliacao() {
        return avaliacao;
    }

    public String textoDoBloqueio() {
        return bloqueio.isVisible() ? bloqueio.getText() : "";
    }

    public String comandoParaODonoRodar() {
        return blocoDoComandoManual.isVisible() ? comandoManual.getText() : "";
    }

    public String textoDoQueFazMostrado() {
        return oQueFaz.isVisible() && blocoDaPrevia.isVisible() ? oQueFaz.getText() : "";
    }

    public String textoDoEncontrado() {
        return encontrado.isVisible() && blocoDaPrevia.isVisible() ? encontrado.getText() : "";
    }

    public String textoDosAvisos() {
        return avisosDaLeitura.isVisible() && blocoDaPrevia.isVisible() ? avisosDaLeitura.getText() : "";
    }

    public String textoDosRiscosMostrado() {
        return riscos.isVisible() && blocoDaPrevia.isVisible() ? riscos.getText() : "";
    }

    public String comandoDeReverterMostrado() {
        return blocoDoReverter.isVisible() ? comandoDeReverter.getText() : "";
    }

    public String seloDoResultado() {
        return blocoDoResultado.isVisible() ? selo.texto() : "";
    }

    public String textoDoResultado() {
        return blocoDoResultado.isVisible() ? textoDoResultado.getText() : "";
    }

    public String textoDosDetalhes() {
        return blocoDoResultado.isVisible() && detalhes.isVisible() ? detalhes.getText() : "";
    }

    public Map<Passo, String> linhasDosPassos() {
        Map<Passo, String> m = new EnumMap<>(Passo.class);
        linhas.forEach((p, r) -> m.put(p, r.getText()));
        return m;
    }

    public Resultado resultado() {
        return resultado;
    }
}
