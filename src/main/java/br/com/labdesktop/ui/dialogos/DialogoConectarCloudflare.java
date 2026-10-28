package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.cloudflare.ContaCf;
import br.com.labdesktop.cloudflare.ErroCloudflare;
import br.com.labdesktop.cloudflare.NomeDoEndereco;
import br.com.labdesktop.cloudflare.ServicoCloudflare;
import br.com.labdesktop.cloudflare.TunelCf;
import br.com.labdesktop.cloudflare.TunelPorArquivo;
import br.com.labdesktop.cloudflare.ZonaCf;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.seguranca.ListaPermitidaDeUrls;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * "Conectar a Cloudflare": o passo a passo para o usuário criar o token de API (com as permissões MÍNIMAS e o link da página oficial), colar o token (com o aviso de que ele vale
 * como senha), e escolher o domínio, o túnel e, no túnel por arquivo, onde está o {@code config.yml} e qual container roda o cloudflared. Duas páginas:
 * <ol>
 *   <li><b>O token</b>: instruções, o campo de senha e "Verificar o token" (só LEITURA: {@code /user/tokens/verify}, contas, domínios);</li>
 *   <li><b>A escolha</b>: domínio, túnel (da lista da Cloudflare ou pelo UUID), o arquivo e o container (com "Conferir no servidor", só leitura) e o começo dos nomes; "Salvar e
 *       conectar" grava o token no COFRE (cifrado) e a escolha ao lado dele.</li>
 * </ol>
 * O token digitado é um {@code char[]} do campo de senha, entregue à verificação e zerado em seguida; o campo é limpo, e a tela nunca mostra o token de volta. Fechar o diálogo
 * (por qualquer caminho) zera o que ele guarda. Nada de rede, disco ou SSH na EDT.
 */
public final class DialogoConectarCloudflare extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        /** Já há uma conta conectada (o diálogo vira "Trocar a conta")? */
        Optional<ConfiguracaoCloudflare> escolhaAtual();

        ServicoCloudflare.Descoberta descobrir(char[] token) throws ErroCloudflare;

        List<TunelCf> tuneis(ServicoCloudflare.Descoberta d, String contaId) throws ErroCloudflare;

        TunelPorArquivo.Conferencia conferirServidor(String caminhoDoConfig, String container);

        void salvar(ServicoCloudflare.Descoberta d, ConfiguracaoCloudflare escolha) throws CofreException;

        /** Abre uma página oficial da Cloudflare no navegador do sistema (só as da lista). */
        void abrirPagina(String url);
    }

    /** As páginas do diálogo. */
    public enum Pagina { TOKEN, ESCOLHA }

    /** As permissões que o app precisa no token, uma por linha (o texto que o usuário copia para conferir no painel da Cloudflare). */
    public static final List<String> PERMISSOES_DO_TOKEN = List.of(
        "Zona > Zona > Ler                    (Zone > Zone > Read)",
        "Zona > DNS > Editar                  (Zone > DNS > Edit)",
        "Conta > Cloudflare Tunnel > Editar   (Account > Tunnel > Edit)",
        "Recursos da zona: Incluir > Zona específica > o seu domínio");

    private static final int LARGURA = 520;
    private static final String PADRAO_DO_ARQUIVO = "/srv/lab/infra/cloudflared/config.yml";
    private static final String PADRAO_DO_CONTAINER = "cloudflared";
    private static final String OUTRO = "Outro túnel (digitar o UUID)";

    private record ItemDeTunel(TunelCf tunel, String texto) {
        @Override
        public String toString() {
            return texto;
        }
    }

    private final Motor motor;
    private final Notificacoes avisos;
    private final Runnable aoConectar;
    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private final Botao voltar = Botoes.fantasma("Voltar", "voltar");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final Botao principal = Botoes.primario("Verificar o token", "ok");
    private Pagina pagina = Pagina.TOKEN;
    private boolean ocupado;
    private ServicoCloudflare.Descoberta descoberta;
    private TunelPorArquivo.Conferencia conferencia;
    private final AtomicInteger geracaoDosTuneis = new AtomicInteger();

    // ---- o token ----
    private final JPasswordField campoDoToken = Campos.senha(34);
    private final TextoCorrido erroDoToken = erroCorrido();
    private final TextoCorrido situacaoDoToken = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao abrirPaginaDosTokens = Botoes.secundario("Abrir a página dos tokens da Cloudflare", "externo");
    private final Botao abrirGuia = Botoes.fantasma("Guia oficial", "externo");

    // ---- a escolha ----
    private final JComboBox<ZonaCf> caixaDeZonas = new JComboBox<>();
    private final TextoCorrido contaDaZona = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final DefaultComboBoxModel<ItemDeTunel> modeloDeTuneis = new DefaultComboBoxModel<>();
    private final JComboBox<ItemDeTunel> caixaDeTuneis = new JComboBox<>(modeloDeTuneis);
    private final TextoCorrido situacaoDosTuneis = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final JTextField campoUuid = Campos.mono(36, "11111111-2222-3333-4444-555555555555");
    private final Rotulo rotuloDoUuid = new Rotulo("UUID do túnel (a linha tunnel: do config.yml)").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JPanel blocoDoArquivo;
    private final JTextField campoCaminho = Campos.mono(36, PADRAO_DO_ARQUIVO);
    private final JTextField campoContainer = Campos.mono(20, PADRAO_DO_CONTAINER);
    private final JTextField campoCaminhoNoContainer = Campos.mono(36, "/etc/cloudflared/config.yml (vazio: o app descobre)");
    private final Botao conferirNoServidor = Botoes.secundario("Conferir no servidor", "ok");
    private final TextoCorrido resultadoDaConferencia = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido notaDaApi = new TextoCorrido("O app grava as regras direto na Cloudflare, e ela as entrega ao cloudflared sozinha. Só uma premissa: o cloudflared deste túnel "
        + "precisa estar na rede Docker \"edge\" do servidor, para achar os containers pelo nome. O teste do endereço, no fim, diz se não achar.", LARGURA)
        .corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final JTextField campoPrefixo = Campos.mono(10, ConfiguracaoCloudflare.PREFIXO_PADRAO);
    private final TextoCorrido erroDaEscolha = erroCorrido();
    private final JPanel blocoDoUuid;

    public DialogoConectarCloudflare(Motor motor, Notificacoes avisos, CopiadorDeTexto copiador, Runnable aoConectar) {
        super(motor.escolhaAtual().isPresent() ? "Trocar a conta da Cloudflare" : "Conectar a Cloudflare");
        this.motor = motor;
        this.avisos = avisos;
        this.aoConectar = aoConectar;
        campoDoToken.getAccessibleContext().setAccessibleName("Token de API da Cloudflare");
        campoDoToken.putClientProperty("html.disable", Boolean.TRUE);
        campoPrefixo.getAccessibleContext().setAccessibleName("Começo do nome dos endereços");
        campoUuid.getAccessibleContext().setAccessibleName("UUID do túnel");
        campoCaminho.getAccessibleContext().setAccessibleName("Caminho do config.yml no servidor");
        campoContainer.getAccessibleContext().setAccessibleName("Nome do container do cloudflared");
        campoCaminhoNoContainer.getAccessibleContext().setAccessibleName("Caminho do config.yml dentro do container");
        caixaDeZonas.setName("zonas");
        caixaDeTuneis.setName("tuneis");
        caixaDeZonas.setRenderer((lista, v, i, sel, foco) -> {
            Rotulo r = new Rotulo(v == null ? "" : v.nome() + (v.ativa() ? "" : "  (ainda não ativo na Cloudflare)"));
            r.setOpaque(true);
            r.setBackground(sel ? lista.getSelectionBackground() : lista.getBackground());
            r.setForeground(sel ? lista.getSelectionForeground() : lista.getForeground());
            r.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return r;
        });
        campoCaminho.setText(motor.escolhaAtual().map(ConfiguracaoCloudflare::caminhoDoConfig).orElse(PADRAO_DO_ARQUIVO));
        campoContainer.setText(motor.escolhaAtual().map(ConfiguracaoCloudflare::containerDoCloudflared).orElse(PADRAO_DO_CONTAINER));
        campoPrefixo.setText(motor.escolhaAtual().map(ConfiguracaoCloudflare::prefixo).orElse(ConfiguracaoCloudflare.PREFIXO_PADRAO));
        situacaoDoToken.setVisible(false);
        resultadoDaConferencia.setVisible(false);
        situacaoDosTuneis.setVisible(false);
        contaDaZona.setVisible(false);

        blocoDoUuid = Caixa.coluna(4, rotuloDoUuid, campoUuid);
        blocoDoUuid.setVisible(false);
        blocoDoArquivo = Caixa.coluna(6,
            new Rotulo("O túnel é gerido por um arquivo no servidor").negrito(),
            dicaCorrida("O app edita o config.yml por SSH (com cópia datada), confere as regras com o cloudflared e reinicia só o container dele."),
            new Rotulo("config.yml no servidor").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoCaminho,
            new Rotulo("Container do cloudflared").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoContainer,
            new Rotulo("Onde o container enxerga o config.yml (opcional)").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoCaminhoNoContainer,
            Caixa.linha(8, conferirNoServidor, Caixa.mola()), resultadoDaConferencia);
        blocoDoArquivo.setVisible(false);
        notaDaApi.setVisible(false);

        corpo.add(montarPaginaDoToken(copiador, avisos), Pagina.TOKEN.name());
        corpo.add(montarPaginaDaEscolha(), Pagina.ESCOLHA.name());
        conteudoComRodape(Caixa.coluna(10, cabecalho("link", getTituloDaJanela()), corpo), rodape(voltar, cancelar, principal));
        botaoPadrao(principal);

        voltar.addActionListener(e -> irPara(Pagina.TOKEN));
        cancelar.addActionListener(e -> aoCancelar());
        principal.addActionListener(e -> aoClicarNoPrincipal());
        abrirPaginaDosTokens.addActionListener(e -> motor.abrirPagina(ListaPermitidaDeUrls.PAGINA_DOS_TOKENS));
        abrirGuia.addActionListener(e -> motor.abrirPagina(ListaPermitidaDeUrls.GUIA_DO_TOKEN));
        conferirNoServidor.addActionListener(e -> conferirOServidor());
        campoDoToken.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
            dizerErro(erroDoToken, "");
            validarEntradaDoToken();
        }));
        caixaDeZonas.addActionListener(e -> aoEscolherAZona());
        caixaDeTuneis.addActionListener(e -> aoEscolherOTunel());
        SimplesOuvinteDeDocumento mudou = new SimplesOuvinteDeDocumento(this::validarEscolha);
        for (JTextField c : List.of(campoUuid, campoCaminho, campoContainer, campoCaminhoNoContainer, campoPrefixo)) {
            c.getDocument().addDocumentListener(mudou);
        }
        for (JTextField c : List.of(campoCaminho, campoContainer, campoCaminhoNoContainer)) {
            c.getDocument().addDocumentListener(new SimplesOuvinteDeDocumento(() -> {
                conferencia = null; // o que foi conferido era de outro caminho
                resultadoDaConferencia.setVisible(false);
            }));
        }
        irPara(Pagina.TOKEN);
    }

    private String getTituloDaJanela() {
        return titulo();
    }

    // =====================================================================================================
    // Montagem
    // =====================================================================================================

    private JComponent montarPaginaDoToken(CopiadorDeTexto copiador, Notificacoes avisos) {
        AreaPermissoes permissoes = new AreaPermissoes();
        Botao copiarPermissoes = BotaoCopiar.criar("Copiar as permissões", () -> String.join("\n", PERMISSOES_DO_TOKEN), copiador, avisos);
        TextoCorrido aviso = new TextoCorrido("Este token vale como uma senha do DNS do seu domínio: quem o tiver mexe nos endereços dele. Cole só aqui. Ele fica no cofre cifrado "
            + "deste app e some desta tela; nunca vai ao servidor, a arquivo nem a log. Se desconfiar de algo, apague o token no painel da Cloudflare.", LARGURA)
            .corDoToken(Tokens.ATENCAO).tamanho(12.5f);
        return Caixa.coluna(8,
            paragrafo("O app fala com a Cloudflare direto do seu computador, com um token de API que você mesmo cria. Nada de senha da conta e nada de cloudflared login."),
            new Rotulo("Passo a passo").negrito(),
            passo("1.", "Abra a página dos tokens da Cloudflare (Meu perfil > Tokens de API)."),
            Caixa.linha(8, abrirPaginaDosTokens, abrirGuia, Caixa.mola()),
            passo("2.", "Clique em \"Criar token\" e em \"Criar token personalizado\"."),
            passo("3.", "Dê SÓ estas permissões (as mínimas):"),
            permissoes.rolavel(),
            dicaCorrida("A terceira só é necessária se o seu túnel é gerido pela Cloudflare (o painel dela chama de \"gerido remotamente\"): para só listar os túneis, \"Ler\" basta. "
                + "Escolha a zona do seu domínio em \"Recursos da zona\", e não \"Todas as zonas\"."),
            Caixa.linha(8, copiarPermissoes, Caixa.mola()),
            passo("4.", "Crie o token e copie o valor: a Cloudflare o mostra uma vez só."),
            Caixa.espacoVertical(2),
            new Rotulo("Cole o token aqui").negrito(),
            campoDoToken, erroDoToken, situacaoDoToken,
            aviso);
    }

    private static JComponent passo(String numero, String texto) {
        Rotulo n = new Rotulo(numero).negrito().corDoToken(Tokens.TEXTO2);
        TextoCorrido t = new TextoCorrido(texto, LARGURA - 28).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
        JPanel p = new Painel(new BorderLayout(8, 0));
        p.add(n, BorderLayout.WEST);
        p.add(t, BorderLayout.CENTER);
        return p;
    }

    /** O bloco das permissões, em fonte de código, só para ler e copiar. */
    private static final class AreaPermissoes {
        private final br.com.labdesktop.ui.componentes.AreaMonoespacada area = new br.com.labdesktop.ui.componentes.AreaMonoespacada("Permissões do token", true);
        private final javax.swing.JScrollPane rolagem;

        AreaPermissoes() {
            area.setText(String.join("\n", PERMISSOES_DO_TOKEN));
            rolagem = br.com.labdesktop.ui.componentes.AreaMonoespacada.emRolagem(area);
            rolagem.setPreferredSize(new java.awt.Dimension(LARGURA, 112));
        }

        JComponent rolavel() {
            return rolagem;
        }
    }

    private JComponent montarPaginaDaEscolha() {
        return Caixa.coluna(8,
            situacaoDoToken2(),
            new Rotulo("Domínio").negrito(), caixaDeZonas, contaDaZona,
            new Rotulo("Túnel").negrito(), caixaDeTuneis, situacaoDosTuneis, blocoDoUuid,
            blocoDoArquivo, notaDaApi,
            new Rotulo("Começo do nome dos endereços").negrito(), campoPrefixo,
            dicaCorrida("O nome de um endereço será <começo>-<projeto> (por exemplo lab-meu-projeto.example.com). Você ainda pode mudar o nome de cada endereço ao publicar."),
            erroDaEscolha);
    }

    private JComponent situacaoDoToken2() {
        return paragrafo("O token está ativo. Escolha o domínio e o túnel que o app vai usar neste servidor.");
    }

    // =====================================================================================================
    // Navegação
    // =====================================================================================================

    private void irPara(Pagina nova) {
        pagina = nova;
        cartoes.show(corpo, nova.name());
        voltar.setVisible(nova == Pagina.ESCOLHA);
        principal.setText(nova == Pagina.TOKEN ? "Verificar o token" : "Salvar e conectar");
        if (nova == Pagina.TOKEN) {
            validarEntradaDoToken();
        } else {
            validarEscolha();
        }
        rolarParaOTopo();
        reajustar();
    }

    private void aoClicarNoPrincipal() {
        if (ocupado || !principal.isEnabled()) {
            return;
        }
        if (pagina == Pagina.TOKEN) {
            verificar();
        } else {
            salvar();
        }
    }

    @Override
    protected void aoCancelar() {
        // a verificação e a gravação são curtas e não deixam nada pela metade (só leitura, e o cofre grava de uma vez): fechar é sempre seguro
        fechar();
    }

    @Override
    protected void aoFechar() {
        campoDoToken.setText("");
        geracaoDosTuneis.incrementAndGet();
        if (descoberta != null) {
            descoberta.close(); // zera a cópia do token que a descoberta guardava (o cofre já tem a dele, se o usuário salvou)
            descoberta = null;
        }
    }

    private void definirOcupado(boolean sim, String texto) {
        ocupado = sim;
        principal.definirOcupado(sim);
        voltar.setEnabled(!sim);
        campoDoToken.setEnabled(!sim);
        abrirPaginaDosTokens.setEnabled(!sim);
        if (pagina == Pagina.TOKEN) {
            situacaoDoToken.setText(texto == null ? "" : texto);
            situacaoDoToken.setVisible(sim && texto != null && !texto.isEmpty());
        }
        reajustar();
    }

    // =====================================================================================================
    // O token
    // =====================================================================================================

    private void validarEntradaDoToken() {
        if (pagina == Pagina.TOKEN && !ocupado) {
            principal.setEnabled(campoDoToken.getDocument().getLength() > 0); // só olha o tamanho: nenhuma cópia do que foi digitado
        }
    }

    private void verificar() {
        char[] colado = campoDoToken.getPassword();
        dizerErro(erroDoToken, "");
        definirOcupado(true, "Conferindo o token com a Cloudflare…");
        Tarefas.rodar(() -> {
            try {
                return motor.descobrir(colado);
            } finally {
                Zeragem.zerar(colado); // o token digitado não fica em lugar nenhum da memória do app, além da cópia do cofre
            }
        }, d -> {
            definirOcupado(false, "");
            campoDoToken.setText("");
            if (jaFechou()) {
                d.close();
                return;
            }
            aoDescobrir(d);
        }, falha -> {
            definirOcupado(false, "");
            if (jaFechou()) {
                return;
            }
            dizerErro(erroDoToken, falha instanceof ErroCloudflare e ? e.mensagem() : MensagensDeErro.de(falha));
            validarEntradaDoToken();
        });
    }

    private void aoDescobrir(ServicoCloudflare.Descoberta d) {
        if (descoberta != null) {
            descoberta.close();
        }
        descoberta = d;
        List<ZonaCf> ativas = d.zonas().stream().filter(ZonaCf::ativa).toList();
        List<ZonaCf> usaveis = ativas.isEmpty() ? d.zonas() : ativas;
        caixaDeZonas.removeAllItems();
        for (ZonaCf z : usaveis) {
            caixaDeZonas.addItem(z);
        }
        String atual = motor.escolhaAtual().map(ConfiguracaoCloudflare::zonaId).orElse("");
        for (ZonaCf z : usaveis) {
            if (z.id().equals(atual)) {
                caixaDeZonas.setSelectedItem(z);
            }
        }
        irPara(Pagina.ESCOLHA);
        aoEscolherAZona();
    }

    // =====================================================================================================
    // A escolha
    // =====================================================================================================

    private ZonaCf zonaEscolhida() {
        return (ZonaCf) caixaDeZonas.getSelectedItem();
    }

    private ItemDeTunel tunelEscolhido() {
        return (ItemDeTunel) caixaDeTuneis.getSelectedItem();
    }

    private void aoEscolherAZona() {
        ZonaCf z = zonaEscolhida();
        if (z == null || descoberta == null) {
            return;
        }
        String nomeDaConta = descoberta.contas().stream().filter(c -> c.id().equals(z.contaId())).map(ContaCf::nome).findFirst().orElse(z.contaNome());
        contaDaZona.setText("Conta: " + (nomeDaConta.isEmpty() ? z.contaId() : nomeDaConta));
        contaDaZona.setVisible(true);
        int minha = geracaoDosTuneis.incrementAndGet();
        modeloDeTuneis.removeAllElements();
        situacaoDosTuneis.setText("Procurando os túneis da conta…");
        situacaoDosTuneis.setVisible(true);
        ServicoCloudflare.Descoberta d = descoberta;
        Tarefas.rodar(() -> motor.tuneis(d, z.contaId()), lista -> {
            if (minha != geracaoDosTuneis.get() || jaFechou()) {
                return;
            }
            preencherOsTuneis(lista, null);
        }, falha -> {
            if (minha != geracaoDosTuneis.get() || jaFechou()) {
                return;
            }
            String motivo = falha instanceof ErroCloudflare e && e.tipo() == ErroCloudflare.Tipo.SEM_PERMISSAO
                ? "O token não lê a lista de túneis (falta \"Conta > Cloudflare Tunnel > Ler\"). Sem problema para um túnel gerido por arquivo: escolha \"" + OUTRO + "\" e informe o UUID."
                : (falha instanceof ErroCloudflare e ? e.mensagem() : MensagensDeErro.de(falha));
            preencherOsTuneis(List.of(), motivo);
        });
    }

    private void preencherOsTuneis(List<TunelCf> lista, String motivoDeNaoListar) {
        modeloDeTuneis.removeAllElements();
        String atual = motor.escolhaAtual().map(ConfiguracaoCloudflare::tunelId).orElse("");
        ItemDeTunel selecionar = null;
        for (TunelCf t : lista) {
            ItemDeTunel item = new ItemDeTunel(t, TextoSeguro.limparLinha(t.nome()) + "   —   " + (t.origem() == ConfiguracaoCloudflare.Tipo.API ? "gerido pela Cloudflare" : "gerido por arquivo no servidor")
                + "   (" + t.estadoEmTexto() + ")");
            modeloDeTuneis.addElement(item);
            if (t.id().equals(atual)) {
                selecionar = item;
            }
        }
        modeloDeTuneis.addElement(new ItemDeTunel(null, OUTRO));
        if (selecionar != null) {
            caixaDeTuneis.setSelectedItem(selecionar);
        } else {
            caixaDeTuneis.setSelectedIndex(0);
        }
        if (lista.isEmpty() && motivoDeNaoListar == null) {
            situacaoDosTuneis.setText("A conta não tem túneis ainda. Criar o túnel (e subir o cloudflared) ainda é feito fora do app: o passo a passo está na documentação da Cloudflare. "
                + "Se o seu túnel já existe, informe o UUID dele.");
            situacaoDosTuneis.setVisible(true);
        } else {
            situacaoDosTuneis.setText(motivoDeNaoListar == null ? "" : motivoDeNaoListar);
            situacaoDosTuneis.setVisible(motivoDeNaoListar != null);
        }
        aoEscolherOTunel();
    }

    private void aoEscolherOTunel() {
        ItemDeTunel t = tunelEscolhido();
        boolean manual = t != null && t.tunel() == null;
        blocoDoUuid.setVisible(manual);
        boolean porArquivo = manual || (t != null && t.tunel().origem() == ConfiguracaoCloudflare.Tipo.ARQUIVO);
        blocoDoArquivo.setVisible(t != null && porArquivo);
        notaDaApi.setVisible(t != null && !porArquivo);
        validarEscolha();
        reajustar();
    }

    private boolean tunelPorArquivo() {
        ItemDeTunel t = tunelEscolhido();
        return t != null && (t.tunel() == null || t.tunel().origem() == ConfiguracaoCloudflare.Tipo.ARQUIVO);
    }

    private String uuidEscolhido() {
        ItemDeTunel t = tunelEscolhido();
        if (t == null) {
            return "";
        }
        return t.tunel() == null ? campoUuid.getText().strip().toLowerCase(java.util.Locale.ROOT) : t.tunel().id();
    }

    private void conferirOServidor() {
        String caminho = campoCaminho.getText().strip();
        String container = campoContainer.getText().strip();
        if (!ValidadorDeNomes.caminhoSeguroValido(caminho) || !container.matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,40}$")) {
            conferencia = null;
            mostrarConferencia("O caminho do arquivo (absoluto, sem espaço nem símbolos) ou o nome do container não valem.", true);
            validarEscolha();
            return;
        }
        conferirNoServidor.definirOcupado(true);
        mostrarConferencia("Lendo o config.yml e perguntando ao Docker (só leitura)…", false);
        Tarefas.rodar(() -> motor.conferirServidor(caminho, container), c -> {
            conferirNoServidor.definirOcupado(false);
            if (jaFechou()) {
                return;
            }
            conferencia = c;
            mostrarResultadoDaConferencia(c);
            validarEscolha();
        }, falha -> {
            conferirNoServidor.definirOcupado(false);
            if (jaFechou()) {
                return;
            }
            conferencia = null;
            mostrarConferencia(MensagensDeErro.de(falha), true);
            validarEscolha();
        });
    }

    private void mostrarResultadoDaConferencia(TunelPorArquivo.Conferencia c) {
        List<String> linhas = new ArrayList<>();
        List<String> problemas = new ArrayList<>(c.problemas());
        String uuid = uuidEscolhido();
        if (c.arquivoExiste() && ValidadorDeNomes.uuidDoTunelValido(c.tunelDoArquivo())) {
            if (uuid.isEmpty() && tunelEscolhido() != null && tunelEscolhido().tunel() == null) {
                campoUuid.setText(c.tunelDoArquivo()); // o arquivo diz qual é o túnel: preenche
                uuid = c.tunelDoArquivo();
            }
            if (!uuid.isEmpty() && !uuid.equals(c.tunelDoArquivo())) {
                problemas.add("O config.yml é do túnel " + c.tunelDoArquivo() + ", e você escolheu outro (" + uuid + "). Escolha o túnel certo na lista.");
            }
        }
        if (problemas.isEmpty()) {
            linhas.add("Tudo certo: o config.yml existe e tem o formato que o app edita"
                + (ValidadorDeNomes.uuidDoTunelValido(c.tunelDoArquivo()) ? " (túnel " + c.tunelDoArquivo() + ")" : "") + ", e o container " + campoContainer.getText().strip()
                + " está rodando" + c.container().caminhoDoConfigNoContainer().map(p -> " e enxerga o arquivo em " + p).orElse("") + ".");
        }
        linhas.addAll(problemas);
        linhas.addAll(c.avisos());
        conferencia = problemas.size() == c.problemas().size() ? c : new TunelPorArquivo.Conferencia(c.arquivoExiste(), c.tunelDoArquivo(), c.container(), problemas, c.avisos());
        mostrarConferencia(String.join("\n", linhas), !problemas.isEmpty());
    }

    private void mostrarConferencia(String texto, boolean erro) {
        resultadoDaConferencia.corDoToken(erro ? Tokens.PERIGO : Tokens.TEXTO2);
        resultadoDaConferencia.setText(texto);
        resultadoDaConferencia.setVisible(true);
        reajustar();
    }

    private Optional<String> problemaDaEscolha() {
        if (zonaEscolhida() == null || tunelEscolhido() == null) {
            return Optional.of("");
        }
        String prefixo = campoPrefixo.getText().strip();
        if (!prefixo.isEmpty() && (!NomeDoEndereco.valido(prefixo) || prefixo.length() > 20)) {
            return Optional.of("O começo do nome usa letras minúsculas, números e hífen (até 20 caracteres).");
        }
        if (!ValidadorDeNomes.uuidDoTunelValido(uuidEscolhido())) {
            return Optional.of(tunelEscolhido().tunel() == null ? "Escreva o UUID do túnel (oito dígitos, hífen, e assim por diante)." : "");
        }
        if (tunelPorArquivo()) {
            if (conferencia == null) {
                return Optional.of("Clique em \"Conferir no servidor\" antes de salvar: o app só segue se achar o config.yml e o container do cloudflared.");
            }
            if (!conferencia.ok()) {
                return Optional.of("");
            }
        }
        return Optional.empty();
    }

    private void validarEscolha() {
        if (pagina != Pagina.ESCOLHA) {
            return;
        }
        Optional<String> p = problemaDaEscolha();
        dizerErro(erroDaEscolha, p.orElse(""));
        if (!ocupado) {
            principal.setEnabled(p.isEmpty());
        }
    }

    private void salvar() {
        ZonaCf z = zonaEscolhida();
        ItemDeTunel t = tunelEscolhido();
        if (z == null || t == null || problemaDaEscolha().isPresent() || descoberta == null) {
            return;
        }
        boolean arquivo = tunelPorArquivo();
        String contaNome = descoberta.contas().stream().filter(c -> c.id().equals(z.contaId())).map(ContaCf::nome).findFirst().orElse(z.contaNome());
        String nomeDoTunel = t.tunel() == null ? "túnel por UUID" : t.tunel().nome();
        String noContainer = campoCaminhoNoContainer.getText().strip();
        ConfiguracaoCloudflare escolha;
        try {
            escolha = new ConfiguracaoCloudflare(z.contaId(), contaNome, z.id(), z.nome(), uuidEscolhido(), nomeDoTunel,
                arquivo ? ConfiguracaoCloudflare.Tipo.ARQUIVO : ConfiguracaoCloudflare.Tipo.API, campoPrefixo.getText().strip(),
                arquivo ? campoCaminho.getText().strip() : null, arquivo ? campoContainer.getText().strip() : null,
                arquivo && !noContainer.isEmpty() ? noContainer : null);
        } catch (IllegalArgumentException e) {
            dizerErro(erroDaEscolha, e.getMessage());
            return;
        }
        ServicoCloudflare.Descoberta d = descoberta;
        definirOcupado(true, null);
        Tarefas.rodar(() -> {
            motor.salvar(d, escolha);
            return Boolean.TRUE;
        }, ok -> {
            definirOcupado(false, "");
            avisos.ok("Cloudflare conectada: domínio " + z.nome() + ", túnel " + nomeDoTunel + ". O token ficou no cofre, cifrado.");
            fechar();
            aoConectar.run();
        }, falha -> {
            definirOcupado(false, "");
            dizerErro(erroDaEscolha, "Não consegui guardar no cofre: " + MensagensDeErro.de(falha));
        });
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public Pagina paginaAtual() {
        return pagina;
    }

    public boolean ocupado() {
        return ocupado;
    }

    public boolean fechou() {
        return jaFechou();
    }

    public JPasswordField campoDoToken() {
        return campoDoToken;
    }

    public Botao botaoPrincipal() {
        return principal;
    }

    public Botao botaoVoltar() {
        return voltar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public Botao botaoAbrirPaginaDosTokens() {
        return abrirPaginaDosTokens;
    }

    public Botao botaoConferirNoServidor() {
        return conferirNoServidor;
    }

    public JComboBox<ZonaCf> caixaDeZonas() {
        return caixaDeZonas;
    }

    public JComboBox<?> caixaDeTuneis() {
        return caixaDeTuneis;
    }

    public JTextField campoUuid() {
        return campoUuid;
    }

    public JTextField campoCaminho() {
        return campoCaminho;
    }

    public JTextField campoContainer() {
        return campoContainer;
    }

    public JTextField campoCaminhoNoContainer() {
        return campoCaminhoNoContainer;
    }

    public JTextField campoPrefixo() {
        return campoPrefixo;
    }

    public boolean blocoDoArquivoVisivel() {
        return blocoDoArquivo.isVisible();
    }

    public boolean notaDaApiVisivel() {
        return notaDaApi.isVisible();
    }

    public boolean blocoDoUuidVisivel() {
        return blocoDoUuid.isVisible();
    }

    public String textoDoErroDoToken() {
        return erroDoToken.getText();
    }

    public String textoDaSituacaoDoToken() {
        return situacaoDoToken.isVisible() ? situacaoDoToken.getText() : "";
    }

    public String textoDoErroDaEscolha() {
        return erroDaEscolha.isVisible() ? erroDaEscolha.getText() : "";
    }

    public String textoDaConferencia() {
        return resultadoDaConferencia.isVisible() ? resultadoDaConferencia.getText() : "";
    }

    public String textoDosTuneis() {
        return situacaoDosTuneis.isVisible() ? situacaoDosTuneis.getText() : "";
    }

    public String textoDaConta() {
        return contaDaZona.isVisible() ? contaDaZona.getText() : "";
    }

    public List<String> itensDeTunel() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < modeloDeTuneis.getSize(); i++) {
            l.add(modeloDeTuneis.getElementAt(i).texto());
        }
        return l;
    }

    public void escolherOTunelNumero(int i) {
        caixaDeTuneis.setSelectedIndex(i);
    }
}
