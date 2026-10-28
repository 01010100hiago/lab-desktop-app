package br.com.labdesktop.cloudflare;

import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.AgenteInstalado;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tudo o que um servidor faz com a Cloudflare, atrás de uma fachada (a que o {@code ServidorLab} entrega às telas): o token e a escolha do usuário no cofre, a conversa com
 * a API e as operações (descobrir, listar, publicar, remover). Sem Swing.
 *
 * <p><b>O token</b> vive só no cofre. Aqui ele só sai do cofre como uma CÓPIA entregue a um {@link ClienteCloudflare}, que a zera ao fechar; nunca vai ao servidor, a log,
 * a arquivo, a URL nem a mensagem de erro. <b>Leituras</b> (descobrir, listar) podem rodar sem o usuário pedir; as <b>escritas</b> só nascem do publicador e do removedor,
 * que as telas só montam depois do clique.
 *
 * <p>No computador local o app não monta túnel nenhum: {@link #ehLocal()} diz isso às telas, que explicam em português.
 */
public final class ServicoCloudflare {

    private final AcessoAoCofre cofre;
    private final String servidorId;
    private final boolean local;
    private final Function<String, AmbienteDeProjetos> ambientes;
    private final Supplier<URI> urlBase;
    private final ClienteCloudflare.Opcoes opcoes;
    private final Supplier<TestadorDeUrl> testador;
    private final Clock relogio;
    private final TunelPorArquivo.Esperador esperador;

    /**
     * @param ambientes devolve o ambiente (arquivos e comandos) do servidor, dado o caminho da pasta dos projetos
     * @param urlBase   o endereço da API da Cloudflare (a propriedade de teste, ou o da Cloudflare)
     */
    public ServicoCloudflare(AcessoAoCofre cofre, String servidorId, boolean local, Function<String, AmbienteDeProjetos> ambientes, Supplier<URI> urlBase,
                             ClienteCloudflare.Opcoes opcoes, Supplier<TestadorDeUrl> testador, Clock relogio, TunelPorArquivo.Esperador esperador) {
        this.cofre = cofre;
        this.servidorId = servidorId;
        this.local = local;
        this.ambientes = ambientes;
        this.urlBase = urlBase;
        this.opcoes = opcoes;
        this.testador = testador;
        this.relogio = relogio;
        this.esperador = esperador;
    }

    /** A raiz dos projetos que o agente do lab usa. */
    public static final String RAIZ_DOS_PROJETOS = "/srv/lab/projects";

    /** É "Este computador"? (o app não monta túnel: publicar na internet pede um servidor com o cloudflared). */
    public boolean ehLocal() {
        return local;
    }

    /** O usuário já conectou a Cloudflare neste servidor (há token e escolha no cofre)? */
    public boolean conectado() {
        return cofre.tokenCloudflare(servidorId).isPresent() && cofre.configuracaoCloudflare(servidorId).isPresent();
    }

    /** A escolha do usuário (conta, domínio, túnel), se conectou. */
    public Optional<ConfiguracaoCloudflare> escolha() {
        return cofre.configuracaoCloudflare(servidorId);
    }

    private boolean comSudo() {
        return cofre.agenteInstalado(servidorId).map(AgenteInstalado::comSudo).orElse(false);
    }

    private ClienteCloudflare novoCliente(TokenCloudflare token) {
        return new ClienteCloudflare(urlBase.get(), token, opcoes);
    }

    // =====================================================================================================
    // Conectar: descobrir (só leitura) e salvar
    // =====================================================================================================

    /**
     * O que o token enxerga. {@link #close()} zera a cópia do token que ela guarda (a tela chama ao fechar o diálogo, com ou sem salvar).
     *
     * @param zonas  os domínios que o token enxerga
     * @param contas as contas (as que a API devolveu; se o token não enxerga contas, as dos domínios)
     * @param avisos o que o usuário precisa saber (permissão que falta, token sem contas...)
     */
    public static final class Descoberta implements AutoCloseable {
        private final TokenCloudflare token;
        private final TokenVerificado verificado;
        private final List<ZonaCf> zonas;
        private final List<ContaCf> contas;
        private final List<String> avisos;

        Descoberta(TokenCloudflare token, TokenVerificado verificado, List<ZonaCf> zonas, List<ContaCf> contas, List<String> avisos) {
            this.token = token;
            this.verificado = verificado;
            this.zonas = List.copyOf(zonas);
            this.contas = List.copyOf(contas);
            this.avisos = List.copyOf(avisos);
        }

        public TokenVerificado verificado() {
            return verificado;
        }

        public List<ZonaCf> zonas() {
            return zonas;
        }

        public List<ContaCf> contas() {
            return contas;
        }

        public List<String> avisos() {
            return avisos;
        }

        TokenCloudflare token() {
            return token;
        }

        @Override
        public void close() {
            token.zerar();
        }

        @Override
        public String toString() {
            return "Descoberta[" + zonas.size() + " domínios, token=redigido]";
        }
    }

    /**
     * Confere o token colado e lista o que ele enxerga: {@code /user/tokens/verify}, as contas e os domínios. NÃO salva nada e NÃO escreve nada na Cloudflare. O
     * {@code char[]} não é zerado aqui: quem o digitou o zera (o diálogo, logo depois da chamada).
     *
     * @throws ErroCloudflare token com forma errada, recusado, sem permissão de ver domínios, ou sem rede
     */
    public Descoberta descobrir(char[] colado) throws ErroCloudflare {
        TokenCloudflare token;
        try {
            token = TokenCloudflare.deChars(colado);
        } catch (TokenCloudflare.FormatoInvalidoException e) {
            throw new ErroCloudflare(ErroCloudflare.Tipo.TOKEN_INVALIDO, e.getMessage());
        }
        boolean ok = false;
        try (ClienteCloudflare api = novoCliente(token)) {
            TokenVerificado v = api.verificarToken();
            List<String> avisos = new ArrayList<>();
            List<ContaCf> contas = new ArrayList<>();
            try {
                contas.addAll(api.contas());
            } catch (ErroCloudflare e) {
                if (e.tipo() == ErroCloudflare.Tipo.SEM_PERMISSAO || e.tipo() == ErroCloudflare.Tipo.NAO_ENCONTRADO) {
                    avisos.add("O token não enxerga a lista de contas (um token só de domínio não precisa). O app usa a conta dos domínios.");
                } else {
                    throw e;
                }
            }
            List<ZonaCf> zonas;
            try {
                zonas = api.zonas();
            } catch (ErroCloudflare e) {
                if (e.tipo() == ErroCloudflare.Tipo.SEM_PERMISSAO) {
                    throw new ErroCloudflare(ErroCloudflare.Tipo.SEM_PERMISSAO, "O token está ativo, mas não pode ver os domínios. Falta a permissão \"Zona > Zona > Ler\" "
                        + "(além de \"Zona > DNS > Editar\"). Crie o token de novo com as permissões do passo a passo.", 403, e.codigoDaCloudflare(), false, e);
                }
                throw e;
            }
            if (zonas.isEmpty()) {
                throw new ErroCloudflare(ErroCloudflare.Tipo.NAO_ENCONTRADO, "O token está ativo, mas não enxerga nenhum domínio. Ao criar o token, em \"Recursos da zona\" escolha \"Incluir > "
                    + "Zona específica\" e o seu domínio.");
            }
            if (contas.isEmpty()) {
                Map<String, ContaCf> dasZonas = new LinkedHashMap<>();
                for (ZonaCf z : zonas) {
                    if (!z.contaId().isEmpty()) {
                        dasZonas.putIfAbsent(z.contaId(), new ContaCf(z.contaId(), z.contaNome()));
                    }
                }
                contas.addAll(dasZonas.values());
            }
            ok = true;
            return new Descoberta(token, v, zonas, contas, avisos);
        } finally {
            if (!ok) {
                token.zerar();
            }
        }
    }

    /**
     * Os túneis de uma conta. {@link ErroCloudflare.Tipo#SEM_PERMISSAO} quer dizer que o token não lê túneis: o usuário ainda pode escolher o túnel por arquivo, informando o
     * UUID que está no config.yml.
     */
    public List<TunelCf> tuneisDaConta(Descoberta d, String contaId) throws ErroCloudflare {
        try (ClienteCloudflare api = novoCliente(d.token())) {
            return api.tuneis(contaId);
        }
    }

    /** Confere, só por leitura, o túnel por arquivo: o config.yml, o túnel que ele diz e o container do cloudflared. */
    public TunelPorArquivo.Conferencia conferirServidor(String caminhoDoConfig, String container) {
        return TunelPorArquivo.conferirServidor(ambientes.apply(RAIZ_DOS_PROJETOS), caminhoDoConfig, container, comSudo());
    }

    /**
     * Guarda o token e a escolha no cofre (criptografado). O cofre fica com uma CÓPIA do token; a {@code Descoberta} continua de quem a abriu (e a zera ao fechar).
     *
     * @throws CofreException o disco falhou (nada mudou)
     */
    public void salvar(Descoberta d, ConfiguracaoCloudflare escolha) throws CofreException {
        cofre.gravarCloudflare(servidorId, d.token().copia(), escolha);
    }

    /** Troca só a escolha (por exemplo, o prefixo); o token fica. */
    public void salvarEscolha(ConfiguracaoCloudflare escolha) throws CofreException {
        cofre.gravarConfiguracaoCloudflare(servidorId, escolha);
    }

    /** Desconecta: apaga o token (zerado) e a escolha do cofre. Não mexe na Cloudflare nem no servidor. */
    public void desconectar() throws CofreException {
        cofre.removerCloudflare(servidorId);
    }

    // =====================================================================================================
    // Com a conta conectada
    // =====================================================================================================

    private ConfiguracaoCloudflare exigirEscolha() throws FalhaDoProjeto {
        return cofre.configuracaoCloudflare(servidorId).orElseThrow(
            () -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "A Cloudflare não está conectada neste servidor. Conecte na tela Cloudflare do servidor."));
    }

    private ClienteCloudflare clienteDoCofre() throws FalhaDoProjeto {
        TokenCloudflare t = cofre.tokenCloudflare(servidorId).orElseThrow(
            () -> new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "A Cloudflare não está conectada neste servidor. Conecte na tela Cloudflare do servidor."));
        return novoCliente(t);
    }

    private Tunel tunelDe(ConfiguracaoCloudflare cfg, AmbienteDeProjetos ambiente, ApiCloudflare api) {
        return cfg.porArquivo() ? new TunelPorArquivo(ambiente, cfg, comSudo(), relogio, esperador) : new TunelPelaApi(api, cfg);
    }

    /**
     * Os serviços do {@code docker-compose.yml} do projeto (só LEITURA, por SFTP), para a tela escolher o que vai para a internet. Vazio se o arquivo não existe, não é UTF-8 ou
     * não está no formato de blocos que o app lê.
     */
    public List<ComposeComEdge.Servico> servicosDoProjeto(String projeto) throws FalhaDoProjeto {
        if (local) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Publicar na internet pede um servidor com o cloudflared (o túnel). Este computador não tem um túnel configurado pelo app.");
        }
        if (!br.com.labdesktop.seguranca.ValidadorDeNomes.projetoValido(projeto)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Nome de projeto inválido.");
        }
        AmbienteDeProjetos ambiente = ambientes.apply(RAIZ_DOS_PROJETOS);
        try (br.com.labdesktop.arquivos.ClienteSftp c = ambiente.abrirArquivos()) {
            String arquivo = br.com.labdesktop.arquivos.CaminhoRemoto.juntar(br.com.labdesktop.arquivos.CaminhoRemoto.juntar(ambiente.raizDosProjetos(), projeto),
                PublicadorNaInternet.ARQUIVO_COMPOSE);
            byte[] bytes = TunelPorArquivo.lerBytes(c, arquivo);
            String texto = TunelPorArquivo.decodificar(bytes);
            return texto == null ? List.of() : ComposeComEdge.servicos(texto);
        } catch (br.com.labdesktop.arquivos.ErroSftp e) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_CONEXAO, "Não consegui ler o docker-compose.yml no servidor: " + e.mensagemSimples(), null, e);
        }
    }

    /** Confere o token guardado (só leitura): ainda vale? */
    public TokenVerificado conferirToken() throws ErroCloudflare, FalhaDoProjeto {
        try (ClienteCloudflare api = clienteDoCofre()) {
            return api.verificarToken();
        }
    }

    /**
     * Os endereços que o app criou (só LEITURA), todos ou só os de um projeto. Pode rodar ao abrir a tela.
     *
     * @param projeto {@code null} para todos
     */
    public List<EnderecoDoApp> enderecos(String projeto) throws FalhaDoProjeto {
        ConfiguracaoCloudflare cfg = exigirEscolha();
        try (ClienteCloudflare api = clienteDoCofre()) {
            return new ListadorDeEnderecos(api, cfg, cfg.porArquivo() ? ambientes.apply(RAIZ_DOS_PROJETOS) : null).listar(projeto);
        }
    }

    /**
     * Monta o publicador (NÃO escreve nada; o publicador só escreve em {@code executar}, que a tela chama depois do clique). Quem o recebe o fecha ({@code close} zera a cópia do
     * token).
     */
    public PublicadorNaInternet publicador(PublicadorNaInternet.AplicadorNoProjeto aplicador) throws FalhaDoProjeto {
        if (local) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Publicar na internet pede um servidor com o cloudflared (o túnel). Este computador não tem um túnel configurado pelo app.");
        }
        ConfiguracaoCloudflare cfg = exigirEscolha();
        ClienteCloudflare api = clienteDoCofre();
        AmbienteDeProjetos ambiente = ambientes.apply(RAIZ_DOS_PROJETOS);
        return new PublicadorNaInternet(ambiente, api, cfg, tunelDe(cfg, ambiente, api), aplicador, testador.get(), relogio);
    }

    /** Monta o removedor (NÃO escreve nada). Quem o recebe o fecha. */
    public RemovedorDeEndereco removedor() throws FalhaDoProjeto {
        if (local) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Este computador não tem túnel configurado pelo app.");
        }
        ConfiguracaoCloudflare cfg = exigirEscolha();
        ClienteCloudflare api = clienteDoCofre();
        AmbienteDeProjetos ambiente = ambientes.apply(RAIZ_DOS_PROJETOS);
        return new RemovedorDeEndereco(api, cfg, tunelDe(cfg, ambiente, api));
    }
}
