package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.ErroApi.NaoEncontrado;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.api.modelo.Detalhe;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.ClienteSftpLocal;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.AgenteInstalado;
import br.com.labdesktop.cofre.CofreBloqueadoException;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.local.ConexaoLocal;
import br.com.labdesktop.local.ExecutorDeLinhaFixa;
import br.com.labdesktop.local.LaboratorioLocal;
import br.com.labdesktop.local.ParametrosDoLaboratorio;
import br.com.labdesktop.local.ProjetosLocais;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.instalador.ExecutorSsh;
import br.com.labdesktop.instalador.GanchosDaInstalacao;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.ssh.ConexaoSsh;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.InfoConexao;
import br.com.labdesktop.ssh.PoliticaDeBackoff;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.SegredoTotp;
import br.com.labdesktop.ssh.SondaDoAgente;
import br.com.labdesktop.terminal.TransporteDeTerminal;
import br.com.labdesktop.terminal.TransporteLocal;
import br.com.labdesktop.terminal.TransporteSsh;
import br.com.labdesktop.ssh.PontaDoAgente;
import br.com.labdesktop.ssh.ConexaoDoServidor;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Fundo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Um servidor VIVO: a fachada que a interface usa (sem Swing aqui). Junta a {@link ConexaoSsh} (SSH, túnel,
 * heartbeat, sono), o {@link ClienteApi} da geração atual do túnel, o {@link Sondador} (polling por tópicos) e
 * as operações de provisionamento (token e 2FA por SSH {@code exec}).
 *
 * <h2>Como as peças se encaixam</h2>
 * <ul>
 *   <li>A conexão sobe o SSH e o túnel e chama {@link #sondar}: um {@code GET /api/versao}. O {@link ClienteApi}
 *       nasce aí, por geração de túnel, e é descartado quando o túnel morre. Só com a conexão em
 *       {@code CONECTADO} o Sondador recebe a API (e consulta); em qualquer outro estado ele para.</li>
 *   <li>Erro de rede numa consulta pede {@code verificarAgora()} à conexão; 401 vira {@code TOKEN_RECUSADO} ou
 *       {@code AGUARDANDO_2FA}.</li>
 *   <li>O código do autenticador é pedido pelo {@link PedidoDeCodigo2fa}, que o {@code ClienteApi} chama numa
 *       thread virtual (nunca na EDT).</li>
 *   <li>{@code LIMITES} está sempre assinado aqui dentro: alimenta a barra lateral, a faixa de crédito, o ícone
 *       da barra de menus e as notificações, mesmo com a janela escondida.</li>
 * </ul>
 *
 * <p>Tudo que vai à tela chega por snapshots imutáveis ({@link EstadoServidor} e os DTOs) pelo despachante de
 * entrega ({@code Fundo.naEdt}). Os métodos que dizem "bloqueia" fazem rede: chame de thread de fundo.
 */
public final class ServidorLab implements AutoCloseable, OperacoesDe2fa {

    private static final Logger LOG = LoggerFactory.getLogger(ServidorLab.class);

    /** Quanto o teste do token novo insiste enquanto o agente (cache de 2 s) ainda não o enxerga. */
    private static final Duration PRAZO_PADRAO_DO_TESTE_DO_TOKEN = Duration.ofSeconds(5);
    private static final long ESPERA_ENTRE_TESTES_MS = 500;

    private final String id;
    private final AcessoAoCofre cofre;
    private final PedidoDeCodigo2fa pedidoDeCodigo;
    private final Executor entrega;

    private final ConexaoDoServidor conexao;
    /** A mesma conexão, quando é por SSH (terminal e arquivos do servidor remoto); {@code null} em "Este computador". */
    private final ConexaoSsh conexaoSsh;
    /** "Este computador": sem SSH, sem túnel, o agente em 127.0.0.1. */
    private final boolean local;
    private final ServicosLocais locais;
    private final Sondador sondador;
    private final AcoesDoServidor acoes;
    /** Os projetos de "Este computador" (as pastas e o Docker); {@code null} no servidor remoto, onde quem sabe é o agente do lab. */
    private final ProjetosLocais projetosLocais;
    /** A pasta dos projetos locais como texto do sistema; a interface a troca pela lembrada (o usuário pode mudá-la em Preparar este computador). */
    private volatile Supplier<String> pastaDosProjetosLocais;
    /** A leitura de segurança (SSH exec, somente leitura) deste servidor, com o tópico que a entrega às telas. */
    private final Topico<ResumoDeSeguranca> topicoDeSeguranca;
    /** O registro geral do servidor: por SSH num servidor remoto, ou o do container do agente em "Este computador". */
    private final Topico<br.com.labdesktop.registro.ResultadoDoRegistro> topicoDeRegistro;
    private final OuvinteDeTopico<LimitesConta> ouvinteDeLimites;
    private final Executor trabalho;
    /** Sob "trava": a assinatura de {@code LIMITES}, que só existe enquanto o servidor tem Claude (ou ainda não disse que não). */
    private Inscricao assinaturaDeLimites;
    private final AtomicBoolean diagnosticando = new AtomicBoolean();
    private final Runnable desinscreverConexao;

    private final List<Consumer<EstadoServidor>> observadores = new CopyOnWriteArrayList<>();
    private final Object trava = new Object();
    // sob "trava":
    private ClienteApi api;
    private long geracaoDaApi = -1;
    private EstadoServidor estado;
    private boolean encerrado;
    private boolean removidoDoCofre;
    /** A última configuração lida do cofre: o que as telas ainda desenham depois de o servidor sair dele (ver {@link #config()}). */
    private volatile ConfigServidor ultimaConfig;
    private volatile Duration prazoDoTesteDoToken = PRAZO_PADRAO_DO_TESTE_DO_TOKEN;
    /** A fachada da Cloudflare, criada na primeira vez que alguém a pede (sob "this"). */
    private br.com.labdesktop.cloudflare.ServicoCloudflare cloudflare;

    /** Para produção: tempos do SSH padrão, agendador do {@code Fundo} e entrega pela EDT. */
    public ServidorLab(String servidorId, AcessoAoCofre cofre, PedidoDeCodigo2fa pedidoDeCodigo) {
        this(servidorId, cofre, pedidoDeCodigo, ConfigSsh.padrao());
    }

    public ServidorLab(String servidorId, AcessoAoCofre cofre, PedidoDeCodigo2fa pedidoDeCodigo, ConfigSsh cfgSsh) {
        this(servidorId, cofre, pedidoDeCodigo, cfgSsh, Agendador.doFundo(), Fundo::naEdt, Fundo.ioVirtual());
    }

    /** Com tudo injetável (testes). */
    public ServidorLab(String servidorId, AcessoAoCofre cofre, PedidoDeCodigo2fa pedidoDeCodigo, ConfigSsh cfgSsh,
                       Agendador agendador, Executor entrega, Executor trabalho) {
        this(servidorId, cofre, pedidoDeCodigo, cfgSsh, agendador, entrega, trabalho, ServicosLocais.padrao());
    }

    /** Com os serviços do computador local também injetáveis (testes do servidor "Este computador"). */
    public ServidorLab(String servidorId, AcessoAoCofre cofre, PedidoDeCodigo2fa pedidoDeCodigo, ConfigSsh cfgSsh,
                       Agendador agendador, Executor entrega, Executor trabalho, ServicosLocais locais) {
        this.id = servidorId;
        this.locais = locais;
        this.cofre = cofre;
        this.pedidoDeCodigo = pedidoDeCodigo == null ? PedidoDeCodigo2fa.NUNCA : pedidoDeCodigo;
        this.entrega = entrega;
        this.trabalho = trabalho;
        ConfigServidor config = cofre.servidor(servidorId)
            .orElseThrow(() -> new IllegalArgumentException("Servidor desconhecido no cofre: " + servidorId));
        this.ultimaConfig = config;
        this.estado = new EstadoServidor(config.id(), config.nome(),
            br.com.labdesktop.ssh.EstadoConexao.DESCONECTADO, null, 0, null, null, 0,
            config.estado2fa(), config.nomeDoDispositivo(), null, null, SituacaoDoAgente.DESCONHECIDA, config.ehLocal());

        this.sondador = new Sondador(agendador, trabalho, entrega, this::aoErroDeConsulta);
        this.local = config.ehLocal();
        if (local) {
            this.conexaoSsh = null;
            this.conexao = new ConexaoLocal(servidorId, cofre, locais.config(), p -> sondarLocal(p), agendador,
                new ExecutorDeLinhaFixa(locais.executor()));
        } else {
            this.conexaoSsh = new ConexaoSsh(servidorId, cofre, cfgSsh, t -> sondar(t), agendador,
                new PoliticaDeBackoff(cfgSsh.escalaBackoff(), Math::random));
            this.conexao = conexaoSsh;
        }
        this.pastaDosProjetosLocais = () -> locais.plataforma().pastaDeProjetosPadrao();
        this.projetosLocais = local ? new ProjetosLocais(() -> Path.of(pastaDosProjetosLocais.get()), locais.executor()) : null;
        this.acoes = new AcoesDoServidor(this::apiAtual, sondador, projetosLocais);
        this.topicoDeSeguranca = Topico.seguranca(local
            ? () -> new ResumoDeSeguranca(Instant.now(), Leitura.indisponivel(), Leitura.indisponivel(), Leitura.indisponivel(),
                Leitura.indisponivel(), Leitura.indisponivel()) // a leitura de segurança é do SSH de um servidor remoto; a aba nem aparece aqui
            : new LeitorDeSegurancaPorSsh(conexao::executar, Instant::now));
        this.topicoDeRegistro = Topico.registro(local
            ? new br.com.labdesktop.registro.LeitorDeRegistroLocal(new br.com.labdesktop.local.ExecutorSoDeLeitura(locais.executor()), () -> comoFalarComOAgente().container())
            : new br.com.labdesktop.registro.LeitorDeRegistroPorSsh(conexao::executar, () -> ultimaConfig.usuario(), Instant::now, java.time.ZoneId.systemDefault()));
        this.desinscreverConexao = conexao.observar(this::aoMudarConexao);
        this.ouvinteDeLimites = new OuvinteDeTopico<LimitesConta>() {
            @Override
            public void aoAtualizar(LimitesConta limites) {
                atualizarEstado(e -> e.comLimites(limites));
            }
        };
        this.assinaturaDeLimites = sondador.assinar(Topico.LIMITES, ouvinteDeLimites);
    }

    // =====================================================================================================
    // Leitura
    // =====================================================================================================

    public String id() {
        return id;
    }

    /**
     * A configuração atual do servidor no cofre (sem segredo). Depois de o servidor ser FECHADO e removido do cofre
     * ("Remover servidor"), devolve a última que se leu: um retrato que já estava na fila da EDT ainda vai desenhar o
     * cartão dele, e isso não pode estourar uma exceção na tela.
     */
    public ConfigServidor config() {
        ConfigServidor atual = cofre.servidor(id).orElse(null);
        if (atual != null) {
            ultimaConfig = atual;
            return atual;
        }
        boolean fechado;
        synchronized (trava) {
            fechado = encerrado || removidoDoCofre;
        }
        if (fechado) {
            return ultimaConfig;
        }
        throw new IllegalStateException("Servidor removido do cofre: " + id);
    }

    /**
     * O cofre acabou de esquecer este servidor, mas a conexão segue de pé por mais um instante (a revogação no servidor
     * precisa dela): até ser fechada, {@link #config()} devolve a última leitura em vez de estourar.
     */
    void aoSairDoCofre() {
        synchronized (trava) {
            removidoDoCofre = true;
        }
    }

    /** O retrato atual. */
    public EstadoServidor estado() {
        synchronized (trava) {
            return estado;
        }
    }

    /**
     * Passa a receber cada mudança de {@link EstadoServidor} pelo despachante de entrega (a EDT). O retrato atual
     * chega logo em seguida.
     */
    public Inscricao observarEstado(Consumer<EstadoServidor> ouvinte) {
        observadores.add(ouvinte);
        EstadoServidor atual = estado();
        entrega.execute(() -> ouvinte.accept(atual));
        return () -> observadores.remove(ouvinte);
    }

    /** Assina um tópico (a tela ao ficar visível; solta ao esconder). */
    public <T> Inscricao assinar(Topico<T> topico, OuvinteDeTopico<T> ouvinte) {
        return sondador.assinar(topico, ouvinte);
    }

    /**
     * Assina a leitura de segurança (a Visão geral do servidor, ao ficar visível). Cadência lenta, nunca na EDT, nunca
     * sem tela visível; só roda com a conexão de pé.
     */
    public Inscricao assinarSeguranca(OuvinteDeTopico<ResumoDeSeguranca> ouvinte) {
        return sondador.assinar(topicoDeSeguranca, ouvinte);
    }

    /**
     * Assina o registro geral do servidor (a caixa do Resumo e a janela "Ver tudo", ao ficarem visíveis). A cada 15 s, só com a janela ativa; nada de rede ou processo na EDT.
     */
    public Inscricao assinarRegistro(OuvinteDeTopico<br.com.labdesktop.registro.ResultadoDoRegistro> ouvinte) {
        return assinarRegistro(ouvinte, true);
    }

    /** Com a escolha de receber (ou não) o último registro guardado ao assinar: a leitura avulsa de uma caixa em pausa só quer o resultado NOVO. */
    public Inscricao assinarRegistro(OuvinteDeTopico<br.com.labdesktop.registro.ResultadoDoRegistro> ouvinte, boolean repetirUltimo) {
        return sondador.assinar(topicoDeRegistro, ouvinte, repetirUltimo);
    }

    /** O tópico do registro (só para os testes conferirem a cadência). */
    Topico<br.com.labdesktop.registro.ResultadoDoRegistro> topicoDoRegistroParaTeste() {
        return topicoDeRegistro;
    }

    /** Pede já uma leitura do registro (o botão Atualizar da caixa). */
    public void atualizarRegistroAgora() {
        sondador.atualizarAgora(topicoDeRegistro);
    }

    /** O tópico da lista de projetos: a do agente do lab ({@code /api/projetos}) ou, em "Este computador", a das pastas e do Docker. */
    public Topico<VisaoProjetos> topicoDeProjetos() {
        return local ? Topico.projetosLocais(projetosLocais) : Topico.PROJETOS;
    }

    /** O tópico do detalhe de um projeto (do agente, ou do computador local). */
    public Topico<Detalhe> topicoDeDetalhe(String projeto) {
        return local ? Topico.detalheLocal(projetosLocais, projeto) : Topico.detalhe(projeto);
    }

    /** Pede já a lista de projetos (depois de criar, importar ou escrever num compose): a tela não espera o ciclo de 10 s. */
    public void atualizarProjetosAgora() {
        sondador.atualizarAgora(Topico.PROJETOS);
    }

    /** Pede já a lista e o detalhe de UM projeto (depois de escrever no compose dele). */
    public void atualizarProjetoAgora(String projeto) {
        sondador.atualizarAgora(Topico.PROJETOS, Topico.detalhe(projeto));
    }

    /**
     * A pasta dos projetos locais, como texto do sistema, lida a cada consulta (o usuário pode trocá-la em Preparar este computador). Só vale em
     * "Este computador"; o padrão é {@code <pasta do usuário>/LabLocal/projects}.
     */
    public void definirPastaDosProjetosLocais(Supplier<String> pasta) {
        this.pastaDosProjetosLocais = pasta;
    }

    /**
     * Onde os projetos moram e como mexer neles (para Novo projeto, importar e Adicionar container): neste computador, arquivos por
     * {@code java.nio} e programas por processo; num servidor, SFTP e linha de comando por SSH. Só monta (nada roda).
     *
     * @param raizDosProjetos a pasta dos projetos no caminho do app (a que o gerenciador de arquivos lembra)
     */
    public AmbienteDeProjetos ambienteDeProjetos(String raizDosProjetos) {
        if (local) {
            return AmbienteDeProjetos.local(raizDosProjetos, fabricaDeArquivos(), locais.executor(), CaminhosLocais.doSistema());
        }
        return AmbienteDeProjetos.remoto(raizDosProjetos, fabricaDeArquivos(), conexao::executar);
    }

    /**
     * A Cloudflare deste servidor: o token e a escolha no cofre, a API, publicar e remover endereços. Só MONTA a fachada (nada roda, nada é lido ao montar);
     * quem a usa para escrever são os diálogos, depois do clique de confirmação.
     */
    public synchronized br.com.labdesktop.cloudflare.ServicoCloudflare cloudflare() {
        if (cloudflare == null) {
            cloudflare = new br.com.labdesktop.cloudflare.ServicoCloudflare(cofre, id, local, this::ambienteDeProjetos,
                br.com.labdesktop.cloudflare.ClienteCloudflare::urlBase, br.com.labdesktop.cloudflare.ClienteCloudflare.Opcoes.padrao(),
                br.com.labdesktop.cloudflare.TestadorDeUrl.Https::new, java.time.Clock.systemDefaultZone(), d -> Thread.sleep(d));
        }
        return cloudflare;
    }

    /**
     * Troca a fachada da Cloudflare (só os testes de tela usam: ligam a Cloudflare FALSA e o servidor simulado, para nada falar com a conta nem com o lab de verdade).
     */
    public synchronized void definirCloudflare(br.com.labdesktop.cloudflare.ServicoCloudflare servico) {
        this.cloudflare = servico;
    }

    /** As ações (iniciar, parar, abrir sessão, limite...). */
    public AcoesDoServidor acoes() {
        return acoes;
    }

    /** O túnel atual (porta local efêmera), se houver. */
    public Optional<? extends PontaDoAgente> tunel() {
        return conexao.pontaDoAgente();
    }

    // =====================================================================================================
    // Ciclo de vida e janela
    // =====================================================================================================

    /** Começa a conectar. */
    public void iniciar() {
        conexao.iniciar();
    }

    /** "Tentar agora": pula a espera do backoff (e é o que se faz depois de confiar no host). */
    public void tentarAgora() {
        atualizarEstado(e -> e.comSituacaoDoAgente(SituacaoDoAgente.DESCONHECIDA)); // o que se sabia do agente pode ter mudado
        conexao.tentarAgora();
    }

    /**
     * O botão "Digitar código" da faixa de aviso: repete o teste do agente, e o {@code ClienteApi} pergunta o
     * código pelo {@link PedidoDeCodigo2fa}. Só tem efeito em {@code AGUARDANDO_2FA}.
     */
    public void digitarCodigo2fa() {
        conexao.tentarAgora();
    }

    /** Atualização manual (F5, "atualizar"): consulta já tudo o que as telas visíveis assinaram. */
    public void atualizarAgora() {
        sondador.atualizarTudoAgora();
    }

    public void definirJanela(EstadoJanela janela) {
        sondador.definirJanela(janela);
    }

    public void definirCadenciaCheiaSemFoco(boolean ligada) {
        sondador.definirCadenciaCheiaSemFoco(ligada);
    }

    /** O Mac vai dormir. */
    public void aoDormir() {
        conexao.aoDormir();
        sondador.definirSuspenso(true);
    }

    /** O Mac acordou: a conexão reconecta e o Sondador volta quando ela estiver {@code CONECTADO}. */
    public void aoAcordar() {
        conexao.aoAcordar();
        sondador.definirSuspenso(false);
    }

    /**
     * O usuário editou o servidor (nome, endereço, porta, usuário) e o cofre já tem os dados novos: mostra o nome novo e
     * reconecta do zero com eles. Se o endereço mudou, os pins já foram apagados no cofre e a conexão para em "Confirmar
     * servidor" até o usuário conferir a impressão digital de novo.
     */
    void aoEditarNoCofre() {
        ConfigServidor nova = config();
        atualizarEstado(e -> e.comNome(nova.nome()));
        conexao.reconectar();
    }

    /** O cofre foi bloqueado: fecha sessão e túnel e para de consultar. Depois, {@link #fechar()}. */
    public void bloquear() {
        conexao.bloquear();
        sondador.definirApi(null);
        descartarApi();
    }

    /** Encerra tudo (SSH, túnel, API, Sondador). Idempotente. */
    public CompletableFuture<Void> fechar() {
        synchronized (trava) {
            encerrado = true;
        }
        soltarLimites();
        sondador.close();
        descartarApi();
        CompletableFuture<Void> pronto = conexao.fechar();
        desinscreverConexao.run();
        return pronto;
    }

    @Override
    public void close() {
        fechar();
    }

    // =====================================================================================================
    // Sonda e API por geração de túnel
    // =====================================================================================================

    /** Roda em thread virtual, chamada pela {@link ConexaoSsh}: o {@code GET /api/versao} que decide CONECTADO. */
    private SondaDoAgente.Resultado sondar(PontaDoAgente tunel) {
        try {
            ClienteApi a = apiParaTunel(tunel);
            VersaoAgente v = a.versao();
            aoReceberVersao(v);
            return SondaDoAgente.Resultado.OK;
        } catch (NaoAutorizado e) {
            return e.totpRequerido() ? SondaDoAgente.Resultado.REQUER_2FA : SondaDoAgente.Resultado.TOKEN_RECUSADO;
        } catch (NaoEncontrado e) {
            // O agente de antes do app nativo não tem a rota (404 em texto puro): não é "agente fora".
            LOG.info("O agente de {} não tem /api/versao: {}", id, e.mensagem());
            return SondaDoAgente.Resultado.AGENTE_DESATUALIZADO;
        } catch (ErroApi e) {
            LOG.info("O agente de {} não respondeu à sonda: {}", id, e.mensagem());
            diagnosticarOAgente(); // uma LEITURA por SSH: o agente está instalado? (a tela diferencia "não instalado" de "fora")
            return SondaDoAgente.Resultado.AGENTE_FORA;
        } catch (CofreBloqueadoException e) {
            return SondaDoAgente.Resultado.AGENTE_FORA; // o cofre fechou no meio; a conexão trata o bloqueio
        }
    }

    /** A sonda do agente local: a mesma de {@link #sondar}, sem túnel. */
    private SondaDoAgente.Resultado sondarLocal(PontaDoAgente ponta) {
        return sondar(ponta);
    }

    private ClienteApi apiParaTunel(PontaDoAgente tunel) {
        synchronized (trava) {
            if (api != null && geracaoDaApi == tunel.geracao()) {
                return api;
            }
            fecharApiSobTrava();
            TokenAgente token = cofre.tokenDoServidor(id).orElse(null);
            api = new ClienteApi(estado.nome(), tunel.portaLocal(), token, pedidoDeCodigo);
            geracaoDaApi = tunel.geracao();
            return api;
        }
    }

    private ClienteApi apiAtual() {
        synchronized (trava) {
            return api;
        }
    }

    private void descartarApi() {
        synchronized (trava) {
            fecharApiSobTrava();
        }
    }

    private void fecharApiSobTrava() {
        if (api != null) {
            api.close();
            api = null;
            geracaoDaApi = -1;
        }
    }

    private void aoMudarConexao(InfoConexao info) {
        atualizarEstado(e -> e.comConexao(info));
        if (info.estado() == br.com.labdesktop.ssh.EstadoConexao.SSH_OK_SEM_AGENTE
            && (info.falha() == null || (info.falha().tipo() != br.com.labdesktop.ssh.Falha.Tipo.AGENTE_DESATUALIZADO
                && info.falha().tipo() != br.com.labdesktop.ssh.Falha.Tipo.LOCAL_AGENTE_DESATUALIZADO))) {
            diagnosticarOAgente();
        }
        switch (info.estado()) {
            case CONECTADO -> sondador.definirApi(apiAtual());
            default -> {
                sondador.definirApi(null);
                if (info.portaLocal() == null) {
                    descartarApi(); // o túnel morreu: a API dele também
                }
            }
        }
    }

    private void aoReceberVersao(VersaoAgente v) {
        Estado2fa lido = Estado2fa.deRotulo(v.totp());
        atualizarEstado(e -> e.comVersao(v, lido));
        ajustarLimites(v);
        try {
            if (cofre.servidor(id).map(ConfigServidor::estado2fa).orElse(lido) != lido) {
                cofre.gravarEstado2fa(id, lido); // só uma dica: a fonte da verdade é o agente
            }
        } catch (CofreException e) {
            LOG.warn("Não consegui guardar a dica do estado do 2FA: {}", Redator.redigir(e));
        } catch (CofreBloqueadoException e) {
            // cofre fechado no meio: sem problema, é só uma dica
        }
    }

    /** Roda no Sondador (thread virtual): decide o que o erro de uma consulta significa para a conexão. */
    private void aoErroDeConsulta(ErroApi erro) {
        if (erro instanceof ErroRede) {
            conexao.verificarAgora(); // o SSH ainda vive? o agente ainda responde?
        } else if (erro instanceof NaoAutorizado n) {
            if (n.totpRequerido()) {
                conexao.informarCodigo2faPendente();
            } else if (n.tokenRecusado()) {
                conexao.informarTokenRecusado();
            }
        }
    }

    private void atualizarEstado(UnaryOperator<EstadoServidor> mudanca) {
        synchronized (trava) {
            if (encerrado) {
                return;
            }
            EstadoServidor novo = mudanca.apply(estado);
            if (novo.equals(estado)) {
                return;
            }
            estado = novo;
            // Dentro da trava, para a ordem de entrega ser a ordem das mudanças.
            for (Consumer<EstadoServidor> o : observadores) {
                entrega.execute(() -> o.accept(novo));
            }
        }
    }

    /**
     * Servidor sem Claude (agente do perfil monitor) não tem {@code /api/limites}: o tópico deixa de ser consultado (senão daria 404 a
     * cada minuto) e os limites em memória somem. Desconhecido ({@code recursos} ausente: agente antigo) mantém o de antes.
     */
    private void ajustarLimites(VersaoAgente v) {
        Boolean claude = v.temClaude();
        if (Boolean.FALSE.equals(claude)) {
            soltarLimites();
            atualizarEstado(e -> e.comLimites(null));
            return;
        }
        synchronized (trava) {
            if (assinaturaDeLimites == null && !encerrado) {
                assinaturaDeLimites = sondador.assinar(Topico.LIMITES, ouvinteDeLimites);
            }
        }
    }

    private void soltarLimites() {
        Inscricao antiga;
        synchronized (trava) {
            antiga = assinaturaDeLimites;
            assinaturaDeLimites = null;
        }
        if (antiga != null) {
            antiga.close();
        }
    }

    // =====================================================================================================
    // O agente existe? (uma LEITURA por SSH, nunca escrita)
    // =====================================================================================================

    /**
     * O SSH está de pé e o agente não responde: pergunta ao servidor, por UMA leitura fixa, se o agente está instalado. O resultado
     * vai para o estado ({@link SituacaoDoAgente}) e é o que faz o app mostrar "agente não instalado" com o botão Instalar agente,
     * em vez de "agente fora". Só lê: instalar é sempre do usuário, com clique e confirmação.
     */
    private void diagnosticarOAgente() {
        diagnosticarOAgente(true);
    }

    /**
     * @param repetirSeFaltou a leitura pode terminar ANTES de a conexão virar {@code SSH_OK_SEM_AGENTE} (a sonda que falhou dispara a leitura
     *                        ainda em {@code CONECTADO}, e o resultado seria descartado): se ao fim o estado já é "sem agente" e a situação
     *                        continua desconhecida, lê mais uma vez
     */
    private void diagnosticarOAgente(boolean repetirSeFaltou) {
        if (estado().situacaoDoAgente() != SituacaoDoAgente.DESCONHECIDA || !diagnosticando.compareAndSet(false, true)) {
            return;
        }
        trabalho.execute(() -> {
            try {
                AgenteInstalado como = comoFalarComOAgente();
                SituacaoDoAgente lida = local ? new LeitorDaSituacaoLocal(locais.executor(), como.container()).ler()
                    : new LeitorDaSituacaoDoAgente(conexao::executar, como.container(), como.docker()).ler();
                if (lida == SituacaoDoAgente.NAO_INSTALADO || lida == SituacaoDoAgente.LOCAL_NAO_CRIADO) {
                    esquecerComoFalarComOAgente(); // foi removido (ou nunca esteve): o que se lembrava dele não vale mais
                }
                atualizarEstado(e -> e.conexao() == br.com.labdesktop.ssh.EstadoConexao.CONECTADO
                    ? e : e.comSituacaoDoAgente(lida));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warn("Não consegui descobrir se o agente de {} está instalado: {}", id, Redator.redigir(e));
            } finally {
                diagnosticando.set(false);
            }
            EstadoServidor agora = estado();
            if (repetirSeFaltou && agora.conexao() == br.com.labdesktop.ssh.EstadoConexao.SSH_OK_SEM_AGENTE
                && agora.situacaoDoAgente() == SituacaoDoAgente.DESCONHECIDA
                && (agora.falha() == null || (agora.falha().tipo() != br.com.labdesktop.ssh.Falha.Tipo.AGENTE_DESATUALIZADO
                && agora.falha().tipo() != br.com.labdesktop.ssh.Falha.Tipo.LOCAL_AGENTE_DESATUALIZADO))) {
                diagnosticarOAgente(false);
            }
        });
    }

    // =====================================================================================================
    // Instalar, atualizar e remover o agente (só pelo clique do usuário; bloqueia: SSH)
    // =====================================================================================================

    /**
     * Monta o instalador do agente deste servidor. NÃO instala nada: quem o aciona é a tela de confirmação, depois do clique do usuário.
     * Os ganchos ligam o instalador ao túnel (sondar o agente) e ao cofre (registrar o token deste Mac).
     */
    public InstaladorDeAgente instaladorDoAgente(ParametrosDaInstalacao parametros, PacoteDoAgente pacote) {
        if (local) {
            throw new IllegalStateException("Em Este computador o agente se cria por \"Preparar este computador\".");
        }
        return new InstaladorDeAgente(new ExecutorSsh(conexaoSsh), parametros, pacote, ganchosDeInstalacao());
    }

    /** O instalador terminou (instalou, atualizou ou removeu): repete o teste do agente para o estado refletir o que há no servidor. */
    public void aoMudarOAgenteNoServidor() {
        atualizarEstado(e -> e.comSituacaoDoAgente(SituacaoDoAgente.DESCONHECIDA));
        if (estado().conexao() == br.com.labdesktop.ssh.EstadoConexao.CONECTADO) {
            // Atualizou ou removeu com o agente respondendo: o token e a API continuam (os dados ficam) e a conexão confere agora se o
            // agente ainda responde (se sumiu, vai para "agente fora" e a leitura diz que não está instalado).
            conexao.verificarAgora();
        } else {
            descartarApi(); // o agente é outro: a próxima API nasce limpa
            conexao.tentarAgora();
        }
    }

    // =====================================================================================================
    // Como falar com o agente (container e sudo), lembrado no cofre
    // =====================================================================================================

    /**
     * O container e o comando do Docker do agente que este app instalou aqui (lembrados no cofre, ao lado do servidor); sem registro, o
     * padrão: {@code docker exec -i painel-lab} (o agente do lab). É o que faz o token, o 2FA, a rotação e o diagnóstico falarem com o
     * container certo e, com um usuário que só chega ao Docker por {@code sudo -n}, usarem o sudo (antes só a instalação o usava).
     */
    private AgenteInstalado comoFalarComOAgente() {
        try {
            return cofre.agenteInstalado(id).orElse(AGENTE_PADRAO);
        } catch (CofreBloqueadoException e) {
            return AGENTE_PADRAO;
        }
    }

    private static final AgenteInstalado AGENTE_PADRAO = new AgenteInstalado(ParametrosDaInstalacao.CONTAINER_PADRAO, false);

    private void lembrarComoFalarComOAgente(ParametrosDaInstalacao parametros, String docker) {
        try {
            cofre.gravarAgenteInstalado(id, new AgenteInstalado(parametros.container(), docker != null && docker.startsWith("sudo")));
        } catch (CofreException e) {
            LOG.warn("Não consegui guardar como falar com o agente de {}: {}", id, Redator.redigir(e));
        } catch (CofreBloqueadoException | IllegalArgumentException e) {
            // cofre fechado no meio, ou servidor que já saiu dele: sem problema
        }
    }

    private void esquecerComoFalarComOAgente() {
        try {
            if (cofre.agenteInstalado(id).isPresent()) {
                cofre.gravarAgenteInstalado(id, null);
            }
        } catch (CofreException e) {
            LOG.warn("Não consegui apagar o registro do agente de {}: {}", id, Redator.redigir(e));
        } catch (CofreBloqueadoException | IllegalArgumentException e) {
            // sem problema
        }
    }

    private ProvisionadorRemoto provisionadorAtual() {
        AgenteInstalado como = comoFalarComOAgente();
        return ProvisionadorRemoto.para(conexao::executar, como.container(), como.docker());
    }

    private GanchosDaInstalacao ganchosDeInstalacao() {
        return new GanchosDaInstalacao() {
            @Override
            public Sonda sondar() {
                PontaDoAgente tunel = conexao.pontaDoAgente().orElse(null);
                if (tunel == null) {
                    return Sonda.NAO_RESPONDE;
                }
                try {
                    VersaoAgente v = apiParaTunel(tunel).versao();
                    aoReceberVersao(v);
                    return Sonda.RESPONDE;
                } catch (NaoAutorizado e) {
                    return e.totpRequerido() ? Sonda.RESPONDE : Sonda.TOKEN_RECUSADO;
                } catch (ErroApi e) {
                    return Sonda.NAO_RESPONDE;
                } catch (CofreBloqueadoException e) {
                    return Sonda.NAO_RESPONDE;
                }
            }

            @Override
            public boolean registrarToken(ParametrosDaInstalacao parametros, String docker) throws ErroProvisionamento, CofreException {
                lembrarComoFalarComOAgente(parametros, docker); // o container subiu e responde: daqui em diante o app o chama pelo nome e do jeito que ele foi instalado
                Sonda agora = sondar();
                if (agora == Sonda.RESPONDE && !estado().semToken()) {
                    return false; // o token do app já vale neste agente (atualização: os dados e o token ficaram)
                }
                String nome = nomeDoDispositivoAtual();
                if (nome == null) {
                    nome = ValidadorDeNomes.sugerirNomeDeDispositivo(br.com.labdesktop.util.NomeDoMac.hostname());
                }
                ProvisionadorRemoto prov = ProvisionadorRemoto.para(conexao::executar, parametros.container(), docker);
                reprovisionarTokenCom(prov, nome);
                return true;
            }
        };
    }

    // =====================================================================================================
    // 2FA (bloqueia: SSH exec e rede)
    // =====================================================================================================

    /** Consulta {@code GET /api/versao} agora (por exemplo, ao abrir "Configurar 2FA") e atualiza o estado. */
    @Override
    public VersaoAgente consultarVersao() throws ErroApi {
        PontaDoAgente tunel = conexao.pontaDoAgente().orElseThrow(() -> new ErroRede("Sem túnel para o agente.", null));
        ClienteApi a = apiParaTunel(tunel);
        VersaoAgente v = a.versao();
        aoReceberVersao(v);
        return v;
    }

    /**
     * "Configurar 2FA", passo 1: gera o segredo no servidor (o 2FA fica {@code pendente} e
     * não exige nada). O {@link SegredoTotp} volta UMA vez; quem o recebe o zera ao fechar o diálogo.
     */
    @Override
    public SegredoTotp iniciarConfiguracao2fa() throws ErroProvisionamento {
        return provisionadorAtual().totpIniciar();
    }

    /**
     * Passo 2: confirma o primeiro código. Este Mac fica confirmado por 30 dias (a
     * linha 2 leva o nome do dispositivo do token do app), sem pedir um segundo código logo em seguida.
     *
     * @return até quando este Mac ficou confirmado
     */
    @Override
    public Optional<Instant> confirmarConfiguracao2fa(String codigo) throws ErroProvisionamento {
        Optional<Instant> ate = provisionadorAtual().totpConfirmar(codigo, nomeDoDispositivoAtual());
        marcarEstado2fa(Estado2fa.ATIVO);
        conexao.tentarAgora(); // se estava aguardando o código, o teste do agente repete (e passa)
        return ate;
    }

    /** "Desativar 2FA": exige um código válido. */
    @Override
    public void desativar2fa(String codigo) throws ErroProvisionamento {
        provisionadorAtual().totpDesativar(codigo);
        marcarEstado2fa(Estado2fa.DESLIGADO);
    }

    private void marcarEstado2fa(Estado2fa novo) {
        atualizarEstado(e -> e.comEstado2fa(novo));
        try {
            cofre.gravarEstado2fa(id, novo);
        } catch (CofreException e) {
            LOG.warn("Não consegui guardar a dica do estado do 2FA: {}", Redator.redigir(e));
        } catch (CofreBloqueadoException e) {
            // sem problema
        }
    }

    private String nomeDoDispositivoAtual() {
        try {
            return cofre.servidor(id).map(ConfigServidor::nomeDoDispositivo).orElse(null);
        } catch (CofreBloqueadoException e) {
            return null;
        }
    }

    // =====================================================================================================
    // Token (bloqueia: SSH exec e rede)
    // =====================================================================================================

    /**
     * Gera o token deste Mac, registra o acesso no agente (por SSH, com os dados só por
     * stdin), TESTA com {@code GET /api/versao} (com o 2FA ativo, o teste pede o código pelo
     * {@link PedidoDeCodigo2fa}) e só então grava no cofre (CONTRATO-API seção 6). Se o teste falha, tenta revogar o
     * registro para não deixar um nome órfão que bloquearia a próxima tentativa.
     *
     * <p>Exige a conexão SSH de pé (qualquer estado com sessão: serve no {@code TOKEN_RECUSADO}, quando o agente
     * não aceita o token antigo).
     *
     * @param nomeDoDispositivo {@code ^[a-z0-9-]{1,40}$}; por exemplo {@code ValidadorDeNomes.sugerirNomeDeDispositivo(NomeDoMac.hostname())}
     */
    public void registrarToken(String nomeDoDispositivo) throws ErroProvisionamento, CofreException {
        registrarTokenCom(provisionadorAtual(), nomeDoDispositivo);
    }

    private void registrarTokenCom(ProvisionadorRemoto prov, String nomeDoDispositivo) throws ErroProvisionamento, CofreException {
        if (!ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido (a-z, 0-9 e hífen, até 40).");
        }
        PontaDoAgente tunel = tunelParaRegistrarToken();
        TokenAgente novo = TokenAgente.gerar();
        boolean registrado = false;
        boolean entregueAoCofre = false;
        try {
            prov.registrarToken(novo.hashSha256Hex(), nomeDoDispositivo, Instant.now());
            registrado = true;
            testarToken(tunel, novo, nomeDoDispositivo);
            cofre.gravarToken(id, novo, nomeDoDispositivo); // o cofre passa a ser o responsável pelo token
            entregueAoCofre = true;
        } finally {
            if (!entregueAoCofre) {
                novo.zerar();
                if (registrado) {
                    revogarSemFalhar(prov, nomeDoDispositivo);
                }
            }
        }
        atualizarEstado(e -> e.comDispositivo(nomeDoDispositivo));
        descartarApi(); // a próxima API nasce com o token novo
        // Em TOKEN_RECUSADO, SSH_OK_SEM_AGENTE e AGUARDANDO_2FA a conexão repete a sonda por conta própria.
        conexao.tentarAgora();
        // Em CONECTADO (agente que ainda aceita chamadas sem a credencial) ela não repete nada, e o Sondador
        // ainda segurava a API que acabou de ser fechada: refaz aqui, já com o token, e entrega a API nova.
        if (conexao.info().estado() == br.com.labdesktop.ssh.EstadoConexao.CONECTADO) {
            renovarApiDeUmaConexaoDePe();
        }
    }

    /**
     * Reprovisiona o token deste Mac: o agente JÁ tem uma entrada com este nome de dispositivo e o app já não tem
     * o token dela (cofre recriado ou token perdido, que aparece como {@code TOKEN_RECUSADO}, ou o
     * {@code token_conflito} de {@link #registrarToken}). Revoga a entrada pelo nome (revogar o acesso; "não
     * existe" não é erro) e registra um token novo, com o mesmo teste e a mesma ordem de gravação do registro.
     *
     * <p>Só chame DEPOIS de o usuário confirmar: a entrada antiga deixa de valer na hora. Se o registro falhar depois
     * da revogação, o estado é o mesmo de "token perdido" e o usuário pode tentar de novo.
     */
    public void reprovisionarToken(String nomeDoDispositivo) throws ErroProvisionamento, CofreException {
        reprovisionarTokenCom(provisionadorAtual(), nomeDoDispositivo);
    }

    private void reprovisionarTokenCom(ProvisionadorRemoto prov, String nomeDoDispositivo) throws ErroProvisionamento, CofreException {
        if (!ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido (a-z, 0-9 e hífen, até 40).");
        }
        tunelParaRegistrarToken(); // sem sessão SSH, nem revoga: não se deixa o usuário sem token por nada
        try {
            prov.revogarToken(nomeDoDispositivo);
        } catch (ErroProvisionamento e) {
            if (!ErroProvisionamento.TOKEN_INEXISTENTE.equals(e.codigo())) {
                throw e;
            }
        }
        registrarTokenCom(prov, nomeDoDispositivo);
    }

    private PontaDoAgente tunelParaRegistrarToken() throws ErroProvisionamento {
        return conexao.pontaDoAgente().orElseThrow(() -> new ErroProvisionamento(
            ErroProvisionamento.SEM_SESSAO, "Sem conexão SSH com o servidor para registrar o token."));
    }

    /** Nova {@link ClienteApi} com o token do cofre para o túnel atual; atualiza o que o agente diz de nós. */
    private void renovarApiDeUmaConexaoDePe() {
        PontaDoAgente atual = conexao.pontaDoAgente().orElse(null);
        if (atual == null) {
            return; // a conexão caiu agora: o próximo CONECTADO entrega a API certa
        }
        try {
            ClienteApi nova = apiParaTunel(atual);
            aoReceberVersao(nova.versao());
            sondador.definirApi(nova);
            if (conexao.info().estado() != br.com.labdesktop.ssh.EstadoConexao.CONECTADO) {
                sondador.definirApi(null); // a conexão mudou no meio: quem manda é ela (aoMudarConexao)
            }
        } catch (NaoAutorizado e) {
            // O token acabou de ser testado e gravado: um 401 agora é fato novo (2FA ligado no meio, revogação).
            if (e.totpRequerido()) {
                conexao.informarCodigo2faPendente();
            } else {
                conexao.informarTokenRecusado();
            }
        } catch (ErroApi e) {
            LOG.info("Não consegui reler a versão do agente de {} depois de registrar o token: {}", id, e.mensagem());
        } catch (CofreBloqueadoException e) {
            // cofre bloqueado no meio: a conexão trata
        }
    }

    /** Insiste por até 5 s: o agente pode demorar alguns segundos para passar a reconhecer a credencial nova. */
    private void testarToken(PontaDoAgente tunel, TokenAgente novo, String nome) throws ErroProvisionamento {
        long limite = System.nanoTime() + prazoDoTesteDoToken.toNanos();
        String ultimoMotivo = "o agente não respondeu";
        try (ClienteApi teste = new ClienteApi(estado().nome(), tunel.portaLocal(), novo, pedidoDeCodigo)) {
            while (true) {
                try {
                    VersaoAgente v = teste.versao();
                    if (nome.equals(v.cliente())) {
                        return;
                    }
                    ultimoMotivo = "o agente respondeu como outro dispositivo";
                } catch (NaoAutorizado e) {
                    if (e.totpRequerido()) {
                        throw new ErroProvisionamento("totp_requerido",
                            "O código do autenticador é necessário para testar o token novo.", e);
                    }
                    ultimoMotivo = "o agente ainda não reconhece o token";
                } catch (ErroApi e) {
                    ultimoMotivo = e.mensagem();
                }
                if (System.nanoTime() > limite) {
                    throw new ErroProvisionamento("token_nao_validou",
                        "O token foi registrado, mas o teste falhou (" + ultimoMotivo + ").");
                }
                try {
                    Thread.sleep(ESPERA_ENTRE_TESTES_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ErroProvisionamento(ErroProvisionamento.SEM_RESPOSTA, "Interrompido.", e);
                }
            }
        }
    }

    private void revogarSemFalhar(ProvisionadorRemoto prov, String nome) {
        try {
            prov.revogarToken(nome);
        } catch (ErroProvisionamento e) {
            LOG.warn("Não consegui revogar o registro órfão '{}': {}", nome, e.getMessage());
        }
    }

    // =====================================================================================================
    // Revogar o acesso do app (bloqueia: SSH exec)
    // =====================================================================================================

    /**
     * O que a revogação conseguiu fazer no servidor. {@code avisos} diz em português o que NÃO deu certo (ou não se aplicou),
     * para o diálogo contar ao usuário: a revogação é melhor esforço e nunca impede a remoção.
     */
    public record ResultadoDaRevogacao(boolean tokenRevogado, boolean chaveTiradaDoServidor, List<String> avisos) {

        public ResultadoDaRevogacao {
            avisos = List.copyOf(avisos);
        }
    }

    /**
     * O que a revogação precisa saber do servidor, lido do cofre ANTES de ele esquecer o servidor: depois da remoção a
     * chave pública, a origem dela e o nome do dispositivo já não estão lá, e a revogação roda por último.
     *
     * @param nomeDoDispositivo {@code null} se o token deste Mac nunca foi registrado
     * @param chavePublica      {@code null} se a chave já não estava no cofre
     * @param origemDaChave     quem criou a chave: só a que o app criou é tirada do servidor
     */
    public record AcessoDoApp(String servidor, String usuario, String nomeDoDispositivo, String chavePublica,
                              InfoChave.Origem origemDaChave) { }

    /** Lê do cofre o que a revogação vai precisar, ou vazio se o servidor já não está lá. Não mexe em nada. */
    public Optional<AcessoDoApp> lerAcessoDoApp() {
        try {
            ConfigServidor cfg = config();
            InfoChave chave = cofre.infoDaChave(cfg.chaveId()).orElse(null);
            return Optional.of(new AcessoDoApp(cfg.nome(), cfg.usuario(), cfg.nomeDoDispositivo(),
                chave == null ? null : chave.publica(), chave == null ? InfoChave.Origem.DESCONHECIDA : chave.origem()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * "Revogar o acesso deste app no servidor", ao remover o servidor: apaga o token deste Mac no agente
     * (revogar o acesso) e, se pedido, tira a chave pública do {@code authorized_keys} do usuário (o que de fato
     * fecha a porta do SSH). Só funciona com a conexão SSH de pé. NUNCA lança: o que falhar vira aviso e a remoção
     * segue (o usuário continua podendo pedir a quem administra o servidor que tire a chave).
     *
     * <p>A chave só é tirada do servidor se foi o PRÓPRIO app que a criou. Uma chave importada de um arquivo do usuário é
     * (quase sempre) a mesma que ele usa no terminal e em outras máquinas: tirá-la o deixaria sem acesso por fora do app.
     * Chave de origem desconhecida (cofre de uma versão antiga) recebe o mesmo cuidado.
     *
     * @param acesso                o que {@link #lerAcessoDoApp()} leu antes de o cofre esquecer o servidor
     * @param tirarAChaveDoServidor {@code false} quando a chave é de outro servidor também (tirá-la quebraria o outro)
     */
    public ResultadoDaRevogacao revogarAcessoDoApp(AcessoDoApp acesso, boolean tirarAChaveDoServidor) {
        List<String> avisos = new ArrayList<>();
        if (acesso == null) {
            return new ResultadoDaRevogacao(false, false, List.of("Não consegui ler os dados do servidor no cofre."));
        }
        if (!conexao.info().estado().permiteExec()) {
            avisos.add("O app não estava conectado a " + acesso.servidor() + ", então o acesso dele não foi revogado no servidor. "
                + "Quem administra o servidor pode tirar a chave do acesso do usuário " + acesso.usuario() + ".");
            return new ResultadoDaRevogacao(false, false, avisos);
        }
        boolean token = false;
        String dispositivo = acesso.nomeDoDispositivo();
        if (dispositivo != null) {
            try {
                provisionadorAtual().revogarToken(dispositivo);
                token = true;
            } catch (ErroProvisionamento e) {
                if (ErroProvisionamento.TOKEN_INEXISTENTE.equals(e.codigo())) {
                    token = true; // já não existia no agente: o resultado é o mesmo
                } else {
                    LOG.warn("Não consegui revogar o token de {}: {}", id, e.getMessage());
                    avisos.add("Não consegui apagar o token deste Mac no painel do servidor (" + e.getMessage() + ").");
                }
            }
        }
        boolean chave = false;
        if (tirarAChaveDoServidor) {
            chave = tirarAChaveDoAuthorizedKeys(acesso, avisos);
        }
        return new ResultadoDaRevogacao(token, chave, avisos);
    }

    private boolean tirarAChaveDoAuthorizedKeys(AcessoDoApp acesso, List<String> avisos) {
        if (acesso.chavePublica() == null) {
            avisos.add("A chave deste servidor já não estava no cofre, então não há o que tirar do servidor.");
            return false;
        }
        if (!acesso.origemDaChave().podeSerTiradaDoServidor()) {
            avisos.add("A chave deste servidor não foi criada pelo app (veio de um arquivo seu), então o app NÃO a tirou do "
                + "acesso do usuário " + acesso.usuario() + " no servidor: você pode estar usando a mesma chave fora do app. "
                + "Se quiser, tire-a à mão.");
            return false;
        }
        try {
            switch (provisionadorAtual().removerChaveAutorizada(acesso.chavePublica())) {
                case REMOVIDA -> {
                    return true;
                }
                case MANTIDA_POR_SER_A_UNICA -> avisos.add("A chave deste app é a única no acesso do usuário " + acesso.usuario()
                    + " no servidor, então ela ficou lá para você não perder o acesso. Se quiser, tire-a à mão.");
            }
        } catch (ErroProvisionamento | IllegalArgumentException e) {
            LOG.warn("Não consegui tirar a chave de {} do servidor: {}", id, e.getMessage());
            avisos.add("Não consegui tirar a chave deste app do acesso do usuário " + acesso.usuario() + " no servidor ("
                + e.getMessage() + ").");
        }
        return false;
    }

    // =====================================================================================================
    // Para testes e para a casca
    // =====================================================================================================

    /** Só para teste: encurta o prazo em que o teste do token novo insiste. */
    void definirPrazoDoTesteDoToken(Duration prazo) {
        this.prazoDoTesteDoToken = prazo;
    }

    // =====================================================================================================
    // O que muda entre um servidor remoto (SSH) e "Este computador": o terminal, os arquivos e o sudo
    // =====================================================================================================

    /** O transporte do terminal deste servidor: um canal SSH com PTY (remoto) ou um PTY do próprio computador (local). */
    public TransporteDeTerminal transporteDeTerminal(BooleanSupplier donoExclusivo) {
        return local ? TransporteLocal.doSistema(conexao) : new TransporteSsh(conexaoSsh, donoExclusivo);
    }

    /** Como o gerenciador de arquivos abre o cliente de arquivos deste servidor. */
    public FabricaDeClienteSftp fabricaDeArquivos() {
        return local ? ClienteSftpLocal::doSistema : FabricaDeClienteSftp.doSftp(conexaoSsh::abrirSftp);
    }

    /** Roda o comando do "Salvar com sudo" do editor de arquivos; {@code null} onde não existe sudo (o computador local). */
    public ProvisionadorRemoto.Executor executorDeSudo() {
        return local ? null : conexao::executar;
    }

    /** É o servidor "Este computador" (sem SSH)? */
    public boolean ehLocal() {
        return local;
    }

    /** Os serviços do computador (processos, plataforma, sonda): o que "Preparar este computador" usa. */
    public ServicosLocais servicosLocais() {
        return locais;
    }

    /**
     * Monta "Criar meu laboratório local" (e "Remover"). NÃO cria nada: quem o aciona é a tela de confirmação, depois do clique do usuário. Os
     * ganchos ligam os passos ao agente (sondar) e ao cofre (registrar o token deste app).
     */
    public LaboratorioLocal laboratorioLocal(ParametrosDoLaboratorio parametros, PacoteDoAgente pacote) {
        if (!local) {
            throw new IllegalStateException("O laboratório local é só do servidor Este computador.");
        }
        return new LaboratorioLocal(locais.executor(), parametros, locais.plataforma(), pacote, ganchosDeInstalacao()).comSondaDaPorta(this::sondarPortaLocal);
    }

    /**
     * Pergunta ao agente local, na porta PEDIDA, se ele responde (o cofre ainda guarda a porta de antes: só se grava a nova depois que ele
     * responde). Sem token: o agente local já exige o token, então "token recusado" também quer dizer que ele está de pé.
     */
    private GanchosDaInstalacao.Sonda sondarPortaLocal(int porta) {
        try (ClienteApi a = new ClienteApi(estado.nome(), porta, null, PedidoDeCodigo2fa.NUNCA)) {
            a.versao();
            return GanchosDaInstalacao.Sonda.RESPONDE;
        } catch (NaoAutorizado e) {
            return e.totpRequerido() ? GanchosDaInstalacao.Sonda.RESPONDE : GanchosDaInstalacao.Sonda.TOKEN_RECUSADO;
        } catch (ErroApi e) {
            return GanchosDaInstalacao.Sonda.NAO_RESPONDE;
        }
    }

    /** O provisionador de comandos do agente (para a casca e os testes). */
    public ProvisionadorRemoto provisionador() {
        return provisionadorAtual();
    }

    /** Roda um comando na sessão SSH atual (usado pelos diálogos de provisionamento). */
    public ConexaoDoServidor conexao() {
        return conexao;
    }
}
