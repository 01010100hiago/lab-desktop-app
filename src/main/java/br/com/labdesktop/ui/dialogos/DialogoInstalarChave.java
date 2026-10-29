package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.acesso.InstaladorDeChave;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.servidor.AssistenteDeServidor.EtapaDoPrimeiroAcesso;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.FichaRotulada;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Fundo;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPasswordField;

/**
 * "Instalar a chave com a senha": o conserto de um servidor que JÁ está cadastrado e que o lab recusa ("Chave recusada"). É o primeiro acesso por senha, sobre o
 * cadastro que existe: o usuário vê o servidor, a impressão digital dele que o app guardou e a impressão digital da chave que será instalada; digita a senha do usuário UMA vez;
 * o app confere que o servidor é o mesmo de antes (se mudou, PARA e não manda a senha), instala a chave pública do cofre, PROVA numa conexão nova só por chave e manda
 * reconectar. No fim não pergunta nada sobre desligar a senha: só lembra onde isso se faz. A senha é a peça {@link EntradaDaSenha} (char[] zerado, nunca guardado, nunca em log).
 *
 * <p>Nada de rede na EDT. Enquanto o app fala com o servidor, Esc e o X não fecham a janela: parar no meio deixaria uma operação rodando sem ninguém olhando.
 */
public final class DialogoInstalarChave extends Dialogo {

    /** O que o diálogo precisa do domínio. Bloqueia (rede): só roda fora da EDT. O {@code char[]} é de quem o recebe zerar. */
    @FunctionalInterface
    public interface Motor {
        InstaladorDeChave.Resultado reparar(char[] senha, Consumer<EtapaDoPrimeiroAcesso> progresso) throws Exception;
    }

    /**
     * De que servidor se trata, sem nenhum segredo.
     *
     * @param impressoesDoServidor as impressões digitais do host que o usuário confirmou (vazio: o servidor ainda não foi conferido)
     */
    public record Alvo(String nome, String usuario, String host, int porta, List<Pin> impressoesDoServidor, String nomeDaChave, String impressaoDaChave,
                       String chavePublica) {

        public static Alvo de(ConfigServidor config, InfoChave chave) {
            boolean semPortaAberta = config.transporte().ehTunel(); // o endereço é o destino do túnel (instância ou hostname), sem porta de SSH
            return new Alvo(config.nome(), config.usuario(), semPortaAberta ? config.enderecoParaMostrar() : config.host(), semPortaAberta ? 0 : config.porta(), config.pins(),
                chave.nome(), chave.impressao(), chave.publica());
        }

        String endereco() {
            return porta > 0 ? usuario + "@" + host + ":" + porta : usuario + "@" + host;
        }
    }

    public static final String TEXTO_DA_SENHA = "O app entra uma vez com a senha, instala a chave deste computador no servidor e confere que ela entra numa conexão nova. "
        + "A senha não é guardada em lugar nenhum e é apagada do campo assim que você clica.";

    public static final String TEXTO_DA_IMPRESSAO = "O app só envia a senha se o servidor tiver exatamente esta impressão digital, a que você confirmou antes. "
        + "Se ela mudou, o app para e não envia nada.";

    public static final String TEXTO_SEM_IMPRESSAO = br.com.labdesktop.servidor.AssistenteDeServidor.TEXTO_SEM_IMPRESSAO;

    /** O que o usuário lê no fim: o app não pergunta de desligar a senha sozinho, só diz onde se faz. */
    public static final String TEXTO_DO_FIM = "Para desligar o login por senha, use Servidor > Segurança.";

    public static final String TEXTO_DE_PRONTO = "A chave foi instalada e o app entrou numa conexão nova só com a chave. O servidor está reconectando.";
    public static final String TEXTO_DE_JA_ESTAVA = "A chave já estava no servidor e o app confirmou, numa conexão nova, que ela entra. O servidor está reconectando.";

    private static final String SEM_IMPRESSAO = "sem impressão digital confirmada";

    private final Alvo alvo;
    private final Motor motor;
    private final Notificacoes avisos;
    private final EntradaDaSenha entrada = new EntradaDaSenha();
    private final TextoCorrido abertura;
    private final FichaRotulada ficha = new FichaRotulada(1, 0);
    private final TextoCorrido dicaDaImpressao = new TextoCorrido(TEXTO_DA_IMPRESSAO, LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao copiarChave;
    private final TextoCorrido erro = erroCorrido();
    private final Selo selo = new Selo("", Gravidade.OK);
    private final TextoCorrido textoDoFim = new TextoCorrido("", LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO).tamanho(13f);
    private final TextoCorrido ondeDesligarASenha = new TextoCorrido(TEXTO_DO_FIM, LARGURA_DO_TEXTO).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JComponent blocoDoFim;
    private final JComponent blocoDaSenha;
    private final Botao instalar = Botoes.primario("Instalar a chave", "chave");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Botao fechar = Botoes.primario("Fechar", "ok");
    private boolean ocupado;
    private boolean parado;
    private boolean pronto;
    private Runnable desfazerOcupado = () -> { };

    public DialogoInstalarChave(Alvo alvo, Motor motor, CopiadorDeTexto copiador, Notificacoes avisos) {
        super("Instalar a chave com a senha");
        this.alvo = alvo;
        this.motor = motor;
        this.avisos = avisos;
        this.copiarChave = BotaoCopiar.criar("Copiar a chave pública", alvo::chavePublica, copiador, avisos);
        this.abertura = paragrafo("O servidor " + alvo.nome() + " recusou a chave deste app. Digite a senha do usuário " + alvo.usuario()
            + " no servidor, uma vez: o app instala a chave deste computador e volta a entrar só pela chave.");
        entrada.rotularPara(alvo.usuario());
        List<FichaRotulada.Item> itens = new java.util.ArrayList<>();
        itens.add(FichaRotulada.Item.de("Servidor", alvo.endereco()));
        itens.addAll(itensDasImpressoes());
        itens.add(FichaRotulada.Item.de("Chave deste app (a que será instalada)", alvo.nomeDaChave() + "  " + alvo.impressaoDaChave()));
        ficha.definir(itens);
        blocoDaSenha = entrada.bloco(TEXTO_DA_SENHA);
        selo.setVisible(true);
        blocoDoFim = Caixa.coluna(8, selo, textoDoFim, ondeDesligarASenha);
        blocoDoFim.setVisible(false);
        fechar.setVisible(false);
        instalar.setEnabled(alvo.impressoesDoServidor() != null && !alvo.impressoesDoServidor().isEmpty());

        conteudoComRodape(Caixa.coluna(12,
            cabecalho("chave", "Instalar a chave com a senha"),
            abertura,
            ficha,
            dicaDaImpressao,
            Caixa.linha(0, copiarChave, Caixa.mola()),
            blocoDaSenha,
            erro,
            blocoDoFim), rodape(null, fechar, cancelar, instalar));
        botaoPadrao(instalar);
        instalar.addActionListener(e -> instalar());
        entrada.campo().addActionListener(e -> instalar());
        cancelar.addActionListener(e -> aoCancelar());
        fechar.addActionListener(e -> fechar());
        if (!instalar.isEnabled()) {
            pararCom(TEXTO_SEM_IMPRESSAO);
        }
    }

    /**
     * UMA linha por impressão digital guardada, cada uma com o seu algoritmo: o servidor pode ter mais de uma (uma por tipo de chave) e a conexão vale se bater com QUALQUER delas,
     * então o usuário precisa ver todas, não só a primeira.
     */
    private List<FichaRotulada.Item> itensDasImpressoes() {
        List<Pin> pins = alvo.impressoesDoServidor();
        if (pins == null || pins.isEmpty()) {
            return List.of(FichaRotulada.Item.de("Impressão digital do servidor", SEM_IMPRESSAO));
        }
        List<FichaRotulada.Item> itens = new java.util.ArrayList<>();
        for (Pin p : pins) {
            itens.add(FichaRotulada.Item.de("Impressão digital do servidor (" + p.algoritmo() + ", a que você confirmou)", p.impressao()));
        }
        return itens;
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks para os testes
    // ---------------------------------------------------------------------------------------------

    public JPasswordField campoDaSenha() {
        return entrada.campo();
    }

    public Botao botaoInstalar() {
        return instalar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoFechar() {
        return fechar;
    }

    public Botao botaoCopiarChave() {
        return copiarChave;
    }

    public String textoDoErro() {
        return erro.isVisible() ? erro.getText() : "";
    }

    public String andamento() {
        return entrada.andamento();
    }

    public String rotuloDoCampo() {
        return entrada.rotuloDoCampo();
    }

    public String textoDoFim() {
        return blocoDoFim.isVisible() ? textoDoFim.getText() + " " + ondeDesligarASenha.getText() : "";
    }

    public String seloDoFim() {
        return blocoDoFim.isVisible() ? selo.texto() : "";
    }

    public boolean pronto() {
        return pronto;
    }

    /** O diálogo parou: o caminho não é digitar a senha de novo (o host mudou, não há impressão confirmada, o servidor não aceita senha). */
    public boolean parado() {
        return parado;
    }

    public boolean ocupado() {
        return ocupado;
    }

    /** Esc, como o usuário o aperta (para os testes). */
    public void aoEsc() {
        aoCancelar();
    }

    /** A janela já fechou (para os testes). */
    public boolean jaFechouParaTeste() {
        return jaFechou();
    }

    public FichaRotulada ficha() {
        return ficha;
    }

    // ---------------------------------------------------------------------------------------------
    // Comportamento
    // ---------------------------------------------------------------------------------------------

    private void instalar() {
        if (!instalar.isEnabled() || ocupado || parado || pronto) {
            return;
        }
        if (entrada.digitados() == 0) {
            dizerErro(erro, "Digite a senha do usuário " + alvo.usuario() + " no servidor.");
            entrada.focar();
            reajustar();
            return;
        }
        dizerErro(erro, "");
        char[] senha = entrada.entregar(); // uma cópia (o campo já foi apagado): do domínio em diante é dele zerá-la
        entrada.mostrarAndamento(EtapaDoPrimeiroAcesso.ENTRANDO);
        ocupar(true);
        reajustar();
        Tarefas.rodar(() -> {
            try {
                return motor.reparar(senha, etapa -> Fundo.naEdt(() -> mostrarAndamento(etapa)));
            } finally {
                Zeragem.zerar(senha);
            }
        }, resultado -> {
            ocupar(false);
            entrada.esconderAndamento();
            if (jaFechou()) {
                return;
            }
            terminar(resultado);
        }, falha -> {
            ocupar(false);
            entrada.esconderAndamento();
            if (jaFechou()) {
                return;
            }
            aoFalhar(falha);
        });
    }

    private void mostrarAndamento(EtapaDoPrimeiroAcesso etapa) {
        if (jaFechou()) {
            return;
        }
        entrada.mostrarAndamento(etapa);
        reajustar();
    }

    private void terminar(InstaladorDeChave.Resultado resultado) {
        pronto = true;
        if (resultado == InstaladorDeChave.Resultado.ADICIONADA_APESAR_DE_LINHA_INUTIL) {
            avisos.atencao("A chave já aparecia no authorized_keys só comentada ou com uma restrição (command= ou from=). O app acrescentou uma linha própria e deixou a antiga como estava.");
        }
        selo.atualizar("CHAVE INSTALADA", Gravidade.OK, null);
        textoDoFim.setText(resultado == InstaladorDeChave.Resultado.JA_EXISTIA ? TEXTO_DE_JA_ESTAVA : TEXTO_DE_PRONTO);
        blocoDoFim.setVisible(true);
        blocoDaSenha.setVisible(false);
        abertura.setVisible(false);
        dicaDaImpressao.setVisible(false);
        instalar.setVisible(false);
        cancelar.setVisible(false);
        fechar.setVisible(true);
        botaoPadrao(fechar);
        avisos.ok("Chave instalada em " + alvo.nome() + ". Reconectando…");
        reajustar();
    }

    /** A frase do motivo; se o caminho não é digitar de novo (host que mudou, sem senha no servidor), o diálogo para e só oferece fechar. */
    private void aoFalhar(Throwable falha) {
        EntradaDaSenha.Reacao reacao = EntradaDaSenha.Reacao.de(falha);
        if (reacao.parar()) {
            pararCom(reacao.mensagem());
            return;
        }
        dizerErro(erro, reacao.mensagem());
        if (reacao.focarNaSenha()) {
            entrada.focar();
        }
        reajustar();
    }

    private void pararCom(String mensagem) {
        parado = true;
        dizerErro(erro, mensagem);
        entrada.habilitar(false);
        blocoDaSenha.setVisible(false); // sem senha para digitar: só a frase do que fazer e o botão Fechar
        instalar.setVisible(false);
        cancelar.setVisible(false);
        fechar.setVisible(true);
        botaoPadrao(fechar);
        reajustar();
    }

    private void ocupar(boolean sim) {
        ocupado = sim;
        entrada.habilitar(!sim && !parado);
        cancelar.setEnabled(!sim);
        if (sim) {
            desfazerOcupado = instalar.ocupar();
        } else {
            desfazerOcupado.run();
            desfazerOcupado = () -> { };
            instalar.setEnabled(!parado);
        }
    }

    @Override
    protected void aoCancelar() {
        if (ocupado) {
            avisos.info("Espere terminar: o app está falando com o servidor. Leva só alguns segundos.");
            return;
        }
        fechar();
    }

    @Override
    protected void aoFechar() {
        entrada.apagar();
    }

    @Override
    protected void antesDeMostrar() {
        if (!parado) {
            entrada.focar();
        }
    }
}
