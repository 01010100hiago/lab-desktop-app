package br.com.labdesktop.ui;

import br.com.labdesktop.acesso.DesligadorDeSenha;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.RespostaNovoProjeto;
import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.instalador.Cancelamento;
import br.com.labdesktop.instalador.ObservadorDaInstalacao;
import br.com.labdesktop.local.InstaladorDeRequisitos;
import br.com.labdesktop.local.LaboratorioLocal;
import br.com.labdesktop.local.ParametrosDoLaboratorio;
import br.com.labdesktop.local.PlanoDeInstalacao;
import br.com.labdesktop.local.RelatorioDoComputador;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.local.VerificacoesDoComputador;
import br.com.labdesktop.projetos.AgenteDeProjetos;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.CriadorDeProjeto;
import br.com.labdesktop.projetos.CriadorNoAmbiente;
import br.com.labdesktop.projetos.CriadorPeloAgente;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.FiltroDeEnvio;
import br.com.labdesktop.projetos.FluxoDeCriacao;
import br.com.labdesktop.projetos.ImportadorDeProjeto;
import br.com.labdesktop.projetos.UrlDeGit;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ProtecaoDoServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.Configurar2fa;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor;
import br.com.labdesktop.ui.dialogos.DialogoConfiarHost;
import br.com.labdesktop.ui.dialogos.DialogoDesligarSenha;
import br.com.labdesktop.ui.dialogos.DialogoDoAgente;
import br.com.labdesktop.ui.dialogos.DialogoEscolherServidor;
import br.com.labdesktop.ui.dialogos.DialogoPrepararComputador;
import br.com.labdesktop.ui.dialogos.DialogoEditarServidor;
import br.com.labdesktop.ui.dialogos.DialogoInstalarChave;
import br.com.labdesktop.ui.dialogos.DialogoRegistrarToken;
import br.com.labdesktop.ui.dialogos.DialogoNovoProjeto;
import br.com.labdesktop.ui.dialogos.DialogoRemoverServidor;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import java.util.List;
import java.util.function.Consumer;

/**
 * O que a interface faz com um SERVIDOR (e não com um projeto), num lugar só: o menu de contexto da barra de
 * servidores, o menu do app, a faixa de aviso e a tela de servidores chamam estes mesmos métodos. Cada um abre o
 * diálogo certo ou dispara a tarefa; o trabalho de rede e de SSH roda fora da EDT ({@link Tarefas}).
 *
 * <p>O "Configurar 2FA" vive no menu de contexto do servidor, no menu do app e na tela Servidor (ARQUITETURA 8.3).
 */
public final class AcoesDaInterface {

    private final GerenciadorServidores gerenciador;
    private final AssistenteDeServidor assistente;
    private final Notificacoes avisos;
    private final Apresentador apresentador;
    private final Consumer<ServidorLab> aoSelecionar;
    private final Navegador navegador;
    private final ServicosDeArquivos servicosDeArquivos;
    private volatile ProtecaoDeContainers protecaoDeContainers = ProtecaoDeContainers.NENHUMA;
    private volatile EscolhedorLocal escolhedor = EscolhedorLocal.doSistema(() -> null);
    /**
     * A trava das ações de escrita ({@code -Dlabdesktop.acoes=true}), conferida AQUI também (defesa em profundidade): os botões já somem sem ela, mas Publicar e Remover escrevem
     * no servidor e na Cloudflare e não podem depender só de a tela ter escondido o botão. A lista de projetos entrega a dela ({@link #usarTrava}); o padrão é a propriedade.
     */
    private volatile java.util.function.BooleanSupplier trava = br.com.labdesktop.ui.telas.FluxosDeProjeto::liberadasPorPropriedade;

    /**
     * @param aoSelecionar chamado na EDT depois de um servidor ser adicionado, para a janela mostrá-lo
     */
    public AcoesDaInterface(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, Notificacoes avisos,
                            Apresentador apresentador, Consumer<ServidorLab> aoSelecionar) {
        this(gerenciador, assistente, avisos, apresentador, aoSelecionar, null, ServicosDeArquivos.descartaveis());
    }

    /**
     * @param navegador          abre as páginas oficiais de download de "Preparar este computador" ({@code null}: não abre)
     * @param servicosDeArquivos onde mora a pasta dos projetos locais (a memória de caminhos)
     */
    public AcoesDaInterface(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, Notificacoes avisos,
                            Apresentador apresentador, Consumer<ServidorLab> aoSelecionar, Navegador navegador, ServicosDeArquivos servicosDeArquivos) {
        this.gerenciador = gerenciador;
        this.assistente = assistente;
        this.avisos = avisos;
        this.apresentador = apresentador;
        this.aoSelecionar = aoSelecionar;
        this.navegador = navegador;
        this.servicosDeArquivos = servicosDeArquivos;
        if (gerenciador != null && avisos != null) {
            // O app viu o agente do lab instalado fora dele e marcou o servidor como protegido: o usuário é avisado UMA vez (o gerenciador só avisa quando a marca mudou).
            gerenciador.observarProtecaoAutomatica(lab -> avisos.info(ProtecaoDoServidor.avisoDaMarcaAutomatica(lab.estado().nome())));
        }
    }

    /**
     * "Adicionar servidor": a primeira pergunta, <b>Este computador</b> ou <b>Outro servidor (SSH)</b>. Nada é criado nem instalado por
     * escolher: Este computador leva à tela "Preparar este computador" (só confere e pergunta); o outro, ao assistente de sempre.
     */
    public void adicionarServidor() {
        boolean temLocal = gerenciador.servidores().stream().anyMatch(ServidorLab::ehLocal);
        apresentador.mostrar(new DialogoEscolherServidor(temLocal, () -> prepararEsteComputador(null), this::adicionarServidorPorSsh));
    }

    /** O assistente de servidor remoto por SSH. Na primeira vez os campos vêm vazios, com a porta SSH 22. */
    public void adicionarServidorPorSsh() {
        boolean primeiro = gerenciador.servidores().stream().noneMatch(s -> !s.ehLocal());
        apresentador.mostrar(new DialogoAdicionarServidor(assistente, primeiro, avisos, aoSelecionar, null, null,
            CopiadorDeTexto.doSistema(), this::instalarAgente, this::desligarSenha));
    }

    /**
     * "Desligar a senha…" (a pergunta opcional do fim do assistente e o botão da aba Segurança): abre a CONFIRMAÇÃO, que só LÊ ao abrir e só escreve depois do
     * clique e do nome do servidor digitado. Só o clique do usuário chega aqui; nada desliga a senha sozinho.
     *
     * <p>Fora da trava {@code labdesktop.acoes}, como o assistente de servidor, a instalação do agente e a remoção do servidor (que também escrevem no servidor):
     * desligar a senha só faz sentido no app instalado, que não liga essa trava, e a própria confirmação (o que será feito, os riscos, o nome do servidor digitado, a prova da chave
     * e o desfazer) já é mais forte que um interruptor de desenvolvedor.
     *
     * @param depois roda (na EDT) quando a janela fecha depois de o usuário ter agido
     */
    public void desligarSenha(ServidorLab lab, Runnable depois) {
        if (lab.ehLocal()) {
            avisos.info("Este computador não entra por SSH, então não há login por senha para desligar.");
            return;
        }
        if (recusarSeProtegido(lab)) {
            return; // servidor protegido: o app não muda a senha de acesso (nem a pergunta do assistente leva até aqui)
        }
        if (!lab.estado().conexao().permiteExec()) {
            avisos.atencao("O app precisa da conexão por chave com o servidor para desligar a senha com segurança. Conecte primeiro.");
            return;
        }
        DesligadorDeSenha desligador = lab.desligadorDeSenha();
        DialogoDesligarSenha.Motor motor = new DialogoDesligarSenha.Motor() {
            @Override
            public DesligadorDeSenha.Avaliacao avaliar() throws Exception {
                return desligador.avaliar();
            }

            @Override
            public DesligadorDeSenha.Resultado desligar(DesligadorDeSenha.Avaliacao avaliacao, DesligadorDeSenha.Observador observador) {
                return desligador.desligar(avaliacao, observador);
            }
        };
        apresentador.mostrar(new DialogoDesligarSenha(lab.estado().nome(), motor, CopiadorDeTexto.doSistema(), avisos, depois == null ? () -> { } : depois));
    }

    /** {@link #desligarSenha(ServidorLab, Runnable)} sem nada a fazer depois (o botão da aba Segurança). */
    public void desligarSenha(ServidorLab lab) {
        desligarSenha(lab, () -> { });
    }

    // =====================================================================================================
    // Novo projeto
    // =====================================================================================================

    /** O que a janela faz depois que o projeto está pronto: abrir a página dele (aba Terminal) e, se o usuário pediu, a sessão do Claude. */
    public interface DepoisDeCriar {
        /** Seleciona o projeto na lista, leva à página dele e à aba Terminal (o shell já na pasta). */
        void abrirNoTerminal(String projeto);

        /** A sessão do Claude do projeto acabou de abrir: leva à aba Terminal, no Claude. */
        void abrirNoClaude(String projeto);
    }

    /** Troca quem diz se as ações de escrita estão liberadas (a lista de projetos passa a dela; os testes, a que querem). */
    public void usarTrava(java.util.function.BooleanSupplier nova) {
        this.trava = nova == null ? br.com.labdesktop.ui.telas.FluxosDeProjeto::liberadasPorPropriedade : nova;
    }

    private boolean recusarSemAsAcoes() {
        if (trava.getAsBoolean()) {
            return false;
        }
        avisos.atencao("As ações que escrevem no servidor estão desligadas nesta versão do app. Nada foi feito.");
        return true;
    }

    /** Quem escolhe a pasta do computador a importar (o seletor do sistema, sobre a janela do app). */
    public void usarEscolhedor(EscolhedorLocal novo) {
        this.escolhedor = novo == null ? EscolhedorLocal.doSistema(() -> null) : novo;
    }

    /**
     * O servidor deste computador, ou um servidor com o agente do lab, sabe criar projeto? Só então o botão e o item de menu existem. Servidor
     * sem agente, ou com o agente do perfil monitor, não: o texto da aba já diz que falta o agente.
     */
    public static boolean podeCriarProjeto(ServidorLab lab) {
        return lab != null && (lab.ehLocal() || lab.estado().agenteCriaProjetos());
    }

    /** A pasta dos projetos locais como texto do sistema (a lembrada, ou {@code <pasta do usuário>/LabLocal/projects}). */
    public String pastaDosProjetosLocais(ServidorLab lab) {
        return pastaDosProjetosLocais(lab, CaminhosLocais.doSistema());
    }

    /**
     * A raiz dos projetos no caminho do app (com barras). No computador local, a pasta do laboratório. Num servidor com o agente, a que o AGENTE usa
     * (a pasta padrão dos projetos do agente): o agente não informa esse caminho em nenhuma rota (nem em {@code /api/versao}),
     * então vale o padrão dele, e NÃO o que o gerenciador de arquivos lembra (que o usuário pode ter apontado para outra pasta só para navegar).
     */
    private String raizDosProjetosNoApp(ServidorLab lab) {
        if (lab.ehLocal()) {
            CaminhosLocais c = CaminhosLocais.doSistema();
            return c.doTextoDoSistema(pastaDosProjetosLocais(lab, c));
        }
        return MemoriaDeCaminhos.RAIZ_DOS_PROJETOS_PADRAO;
    }

    /**
     * "Novo projeto": abre o diálogo (criar do zero, importar). Só o CLIQUE do usuário chega aqui (botão da lista, convite da lista vazia, item de
     * menu); nada é criado antes do clique em "Criar projeto" ou "Importar projeto" lá dentro. O que fazer ao terminar é de quem chamou.
     */
    public void novoProjeto(ServidorLab lab, DepoisDeCriar depois) {
        if (!podeCriarProjeto(lab)) {
            avisos.atencao("Este servidor ainda não tem o agente do lab, que é quem cria projetos. Instale o agente ou use um servidor com ele.");
            return;
        }
        String raiz = raizDosProjetosNoApp(lab);
        AmbienteDeProjetos ambiente = lab.ambienteDeProjetos(raiz);
        EstadoServidor e = lab.estado();
        boolean guiaIa = !lab.ehLocal() && Boolean.TRUE.equals(e.temClaude()) && e.entendeGuiaIa();
        CriadorDeProjeto criador = lab.ehLocal() ? new CriadorNoAmbiente(ambiente) : new CriadorPeloAgente(agenteDeProjetos(lab));
        DialogoNovoProjeto.Motor motor = new MotorDeProjetos(lab, ambiente, criador, raiz, guiaIa);
        apresentador.mostrar(new DialogoNovoProjeto(motor, avisos, CopiadorDeTexto.doSistema(), c -> aoConcluirNovoProjeto(lab, c, depois)));
    }

    private static AgenteDeProjetos agenteDeProjetos(ServidorLab lab) {
        return new AgenteDeProjetos() {
            @Override
            public boolean conectado() {
                return lab.acoes().conectado();
            }

            @Override
            public RespostaNovoProjeto criar(String nome, Boolean guiaIa) throws ErroApi {
                return lab.acoes().criarProjeto(nome, guiaIa);
            }

            @Override
            public List<String> nomes() throws ErroApi {
                return lab.acoes().nomesDosProjetos();
            }
        };
    }

    private void aoConcluirNovoProjeto(ServidorLab lab, DialogoNovoProjeto.Concluido c, DepoisDeCriar depois) {
        lab.atualizarProjetosAgora(); // a lista não espera o ciclo de 10 s
        if (c.talvez()) {
            avisos.atencao(c.mensagem());
            return;
        }
        boolean claude = !lab.ehLocal() && Boolean.TRUE.equals(lab.estado().temClaude());
        if (claude) {
            avisos.comAcao(c.mensagem(), "Abrir o Claude neste projeto", () -> abrirOClaudeDoProjeto(lab, c.nome(), depois));
        } else {
            avisos.ok(c.mensagem());
        }
        c.avisos().forEach(avisos::atencao);
        depois.abrirNoTerminal(c.nome());
    }

    /** O botão (nunca automático) "Abrir o Claude neste projeto": abre a sessão e leva à aba Terminal, no Claude. */
    private void abrirOClaudeDoProjeto(ServidorLab lab, String projeto, DepoisDeCriar depois) {
        Tarefas.rodar(() -> lab.acoes().abrirSessao(projeto), sessao -> depois.abrirNoClaude(projeto),
            falha -> avisos.erro("Não consegui abrir o Claude em " + projeto + ": " + MensagensDeErro.de(falha)));
    }

    /**
     * "Adicionar container" na página de um projeto: abre o diálogo (modelo, a diferença do compose, gravar). Só o clique do usuário chega aqui, e nada é
     * escrito antes do clique em "Gravar no docker-compose.yml"; "Iniciar" é outro clique.
     *
     * @param iniciar o que "Iniciar o projeto" faz (o mesmo botão da página do projeto); {@code null} não mostra o botão
     */
    public void adicionarContainer(ServidorLab lab, String projeto, Runnable iniciar) {
        if (!podeCriarProjeto(lab)) {
            avisos.atencao("Este servidor ainda não tem o agente do lab, então não dá para acrescentar containers por aqui.");
            return;
        }
        br.com.labdesktop.projetos.EscritorDeCompose escritor =
            new br.com.labdesktop.projetos.EscritorDeCompose(lab.ambienteDeProjetos(raizDosProjetosNoApp(lab)));
        br.com.labdesktop.ui.dialogos.DialogoAdicionarContainer.Motor motor = new br.com.labdesktop.ui.dialogos.DialogoAdicionarContainer.Motor() {
            @Override
            public br.com.labdesktop.projetos.EscritorDeCompose.Previa preparar(br.com.labdesktop.projetos.BlocoDeServico bloco) throws FalhaDoProjeto {
                return escritor.preparar(projeto, bloco);
            }

            @Override
            public br.com.labdesktop.projetos.EscritorDeCompose.Gravado gravar(br.com.labdesktop.projetos.EscritorDeCompose.Previa previa) throws FalhaDoProjeto {
                return escritor.gravar(previa);
            }
        };
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoAdicionarContainer(projeto, motor, () -> lab.atualizarProjetoAgora(projeto), iniciar, avisos,
            CopiadorDeTexto.doSistema()));
    }

    // =====================================================================================================
    // Git do projeto
    // =====================================================================================================

    /**
     * "Criar repositório Git" na página de um projeto: abre a janela de CONFERÊNCIA (só lê o projeto e mostra tudo antes de qualquer escrita). Só o clique do usuário chega aqui, e nada é criado
     * antes do clique em "Criar repositório em &lt;projeto&gt; (&lt;servidor&gt;)" lá dentro. Criar escreve no projeto e fica atrás da trava das ações ({@code labdesktop.acoes}): conferida AQUI
     * também (defesa em profundidade; a janela já mostra o botão desligado com a razão). Vale também em servidor protegido, como Novo projeto: criar a pasta .git de um projeto não instala nada.
     *
     * @param depois      roda (na EDT) quando o repositório foi criado, inteiro ou pela metade: a página lê o Git de novo
     * @param verCommits  leva à aba Git (o botão do fim)
     */
    public void criarRepositorioGit(ServidorLab lab, String projeto, Runnable depois, Runnable verCommits) {
        if (!lab.ehLocal() && !lab.estado().conexao().permiteExec()) {
            avisos.atencao("O app precisa da conexão com o servidor para conferir e criar o repositório. Conecte primeiro.");
            return;
        }
        String pasta = raizDosProjetosNoApp(lab) + "/" + projeto;
        br.com.labdesktop.git.OperacoesDeGit operacoes;
        try {
            operacoes = lab.operacoesDeGit(pasta);
        } catch (IllegalArgumentException e) {
            avisos.atencao("Não consegui montar o caminho do projeto " + projeto + " para o Git.");
            return;
        }
        boolean liberada = trava.getAsBoolean();
        boolean protegido = !lab.ehLocal() && lab.protegido();
        br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio.Motor motor = new br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio.Motor() {
            @Override
            public br.com.labdesktop.git.ConferenciaDoRepositorio conferir() throws Exception {
                return operacoes.conferirCriacao();
            }

            @Override
            public br.com.labdesktop.git.ResultadoDaCriacao criar(br.com.labdesktop.git.PedidoDeCriacao pedido, br.com.labdesktop.git.OperacoesDeGit.Observador observador,
                                                                       br.com.labdesktop.arquivos.Cancelamento cancelamento) throws Exception {
                if (!trava.getAsBoolean()) {
                    return new br.com.labdesktop.git.ResultadoDaCriacao(false, false,
                        br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio.RAZAO_DA_TRAVA + " Nada foi criado.", List.of(), List.of(), null, 0, "", null);
                }
                return operacoes.criarRepositorio(pedido, observador, cancelamento);
            }
        };
        br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio[] janela = new br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio[1];
        janela[0] = new br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio(lab.estado().nome(), projeto, motor, liberada, protegido,
            () -> instalarGit(lab, () -> janela[0].conferir()), verCommits, depois, avisos, CopiadorDeTexto.doSistema());
        apresentador.mostrar(janela[0]);
    }

    // =====================================================================================================
    // Cloudflare
    // =====================================================================================================

    /**
     * "Conectar a Cloudflare" (e "Trocar a conta"): abre o diálogo do token. Conectar só LÊ da Cloudflare (verifica o token, lista domínios e túneis) e grava no cofre local; nada é
     * escrito na Cloudflare nem no servidor. O que fazer depois de salvar é de quem chamou (a tela se atualiza).
     */
    public void conectarCloudflare(ServidorLab lab, Runnable depois) {
        br.com.labdesktop.cloudflare.ServicoCloudflare servico = lab.cloudflare();
        if (servico.ehLocal()) {
            avisos.atencao("Publicar na internet pede um servidor com o cloudflared (o túnel). Este computador não tem um túnel configurado pelo app.");
            return;
        }
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoConectarCloudflare(new MotorDaConexaoCloudflare(servico), avisos, CopiadorDeTexto.doSistema(),
            depois == null ? () -> { } : depois));
    }

    /** O que o diálogo "Conectar a Cloudflare" usa do domínio. */
    private final class MotorDaConexaoCloudflare implements br.com.labdesktop.ui.dialogos.DialogoConectarCloudflare.Motor {
        private final br.com.labdesktop.cloudflare.ServicoCloudflare servico;

        MotorDaConexaoCloudflare(br.com.labdesktop.cloudflare.ServicoCloudflare servico) {
            this.servico = servico;
        }

        @Override
        public java.util.Optional<br.com.labdesktop.cofre.ConfiguracaoCloudflare> escolhaAtual() {
            return servico.escolha();
        }

        @Override
        public br.com.labdesktop.cloudflare.ServicoCloudflare.Descoberta descobrir(char[] token) throws br.com.labdesktop.cloudflare.ErroCloudflare {
            return servico.descobrir(token);
        }

        @Override
        public List<br.com.labdesktop.cloudflare.TunelCf> tuneis(br.com.labdesktop.cloudflare.ServicoCloudflare.Descoberta d, String contaId)
            throws br.com.labdesktop.cloudflare.ErroCloudflare {
            return servico.tuneisDaConta(d, contaId);
        }

        @Override
        public br.com.labdesktop.cloudflare.TunelPorArquivo.Conferencia conferirServidor(String caminhoDoConfig, String container) {
            return servico.conferirServidor(caminhoDoConfig, container);
        }

        @Override
        public void salvar(br.com.labdesktop.cloudflare.ServicoCloudflare.Descoberta d, br.com.labdesktop.cofre.ConfiguracaoCloudflare escolha)
            throws br.com.labdesktop.cofre.CofreException {
            servico.salvar(d, escolha);
        }

        @Override
        public void abrirPagina(String url) {
            if (navegador != null) {
                navegador.abrirPaginaDaCloudflare(url);
            }
        }
    }

    /**
     * "Desconectar a Cloudflare": pergunta antes (o botão repete o que faz) e só então apaga o token do cofre (zerado) e a escolha. Não mexe na Cloudflare nem nos endereços já
     * publicados: eles continuam no ar, e o app só deixa de poder criar e remover até conectar de novo.
     */
    public void desconectarCloudflare(ServidorLab lab, Runnable depois) {
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoConfirmar("Desconectar a Cloudflare", "link",
            "O app apaga o token do cofre e esquece o domínio e o túnel escolhidos. Os endereços já publicados continuam no ar (nada muda na Cloudflare nem no servidor); "
                + "para criar ou remover endereços de novo, conecte outra vez.", "Desconectar", true, sim -> {
                    if (!sim) {
                        return;
                    }
                    Tarefas.rodar(() -> {
                        lab.cloudflare().desconectar();
                        return Boolean.TRUE;
                    }, ok -> {
                        avisos.ok("Cloudflare desconectada: o token foi apagado do cofre.");
                        if (depois != null) {
                            depois.run();
                        }
                    }, falha -> avisos.erro("Não consegui apagar do cofre: " + MensagensDeErro.de(falha)));
                }));
    }

    /** O endereço público no navegador do sistema (só um endereço de UM nível do domínio que o usuário escolheu). */
    public void abrirEnderecoPublico(ServidorLab lab, String hostname) {
        java.util.Optional<br.com.labdesktop.cofre.ConfiguracaoCloudflare> cfg = lab.cloudflare().escolha();
        if (cfg.isEmpty() || navegador == null) {
            return;
        }
        navegador.abrirEnderecoPublico(cfg.get().zonaNome(), hostname);
    }

    /**
     * "Publicar na internet" na página de um projeto: abre o diálogo (serviço, porta, nome, e a prévia do que será feito). Só o CLIQUE do usuário chega aqui; nada é escrito antes do
     * clique em "Publicar" lá dentro.
     */
    public void publicarNaInternet(ServidorLab lab, String projeto, Runnable depois) {
        if (recusarSemAsAcoes()) {
            return;
        }
        abrirPublicacao(lab, projeto, depois, null, null);
    }

    /**
     * "Adicionar domínio": o assistente em passos curtos (conta, domínio, endereço, serviço e, no fim, a prévia do que será criado e a confirmação). Sem Cloudflare conectada, abre PRIMEIRO o
     * "Conectar a Cloudflare" e, depois de salvar, volta e abre o assistente. Mesma trava das outras ações de escrita; só o clique do usuário chega aqui, e nada é escrito antes do "Publicar"
     * do último passo.
     */
    public void adicionarDominio(ServidorLab lab, String projeto, Runnable depois) {
        if (recusarSemAsAcoes()) {
            return;
        }
        br.com.labdesktop.cloudflare.ServicoCloudflare servico = lab.cloudflare();
        if (servico.ehLocal()) {
            avisos.atencao("Adicionar domínio pede um servidor com o cloudflared (o túnel). Este computador não tem um túnel configurado pelo app.");
            return;
        }
        if (!servico.conectado()) {
            conectarCloudflare(lab, () -> {
                if (servico.conectado()) {
                    abrirAssistenteDeDominio(lab, projeto, null, depois);
                }
            });
            return;
        }
        abrirAssistenteDeDominio(lab, projeto, null, depois);
    }

    /**
     * "Adicionar domínio" a partir do servidor (a aba Cloudflare, que não é de um projeto só): pergunta primeiro PARA QUAL PROJETO e segue para o assistente. Sem projetos, avisa.
     */
    public void adicionarDominioEscolhendoOProjeto(ServidorLab lab, List<String> projetos, Runnable depois) {
        if (recusarSemAsAcoes()) {
            return;
        }
        if (projetos.isEmpty()) {
            avisos.atencao("Este servidor ainda não tem projetos. Crie ou importe um projeto (Novo projeto) e depois dê um domínio a ele.");
            return;
        }
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoEscolherProjeto(projetos, escolhido -> adicionarDominio(lab, escolhido, depois)));
    }

    private void abrirAssistenteDeDominio(ServidorLab lab, String projeto, br.com.labdesktop.ui.dialogos.DialogoPublicar.Predefinicao inicial, Runnable depois) {
        br.com.labdesktop.cloudflare.ServicoCloudflare servico = lab.cloudflare();
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoAdicionarDominio(new br.com.labdesktop.ui.dialogos.DialogoAdicionarDominio.Motor() {
            @Override
            public String projeto() {
                return projeto;
            }

            @Override
            public java.util.Optional<br.com.labdesktop.ui.dialogos.DialogoAdicionarDominio.Conexao> conexao() {
                if (!servico.conectado()) {
                    return java.util.Optional.empty();
                }
                return servico.escolha().map(c -> new br.com.labdesktop.ui.dialogos.DialogoAdicionarDominio.Conexao(c.contaNome(), c.zonaNome(), c.tunelNome(), c.prefixo()));
            }

            @Override
            public void conectar(Runnable aoFechar) {
                conectarCloudflare(lab, aoFechar);
            }

            @Override
            public List<br.com.labdesktop.cloudflare.ComposeComEdge.Servico> servicos() throws FalhaDoProjeto {
                return servico.servicosDoProjeto(projeto);
            }

            @Override
            public boolean podeRecriar() {
                return podeCriarProjeto(lab);
            }
        }, inicial, pre -> abrirPublicacao(lab, projeto, depois, pre, atual -> abrirAssistenteDeDominio(lab, projeto, atual, depois)), null));
    }

    /** Abre o diálogo de publicar (a prévia e a confirmação), já preenchido pelo assistente, ou em branco no "Publicar na internet" de sempre. */
    private void abrirPublicacao(ServidorLab lab, String projeto, Runnable depois, br.com.labdesktop.ui.dialogos.DialogoPublicar.Predefinicao pre,
                                  java.util.function.Consumer<br.com.labdesktop.ui.dialogos.DialogoPublicar.Predefinicao> voltar) {
        br.com.labdesktop.cloudflare.ServicoCloudflare servico = lab.cloudflare();
        if (servico.ehLocal()) {
            avisos.atencao("Publicar na internet pede um servidor com o cloudflared (o túnel). Este computador não tem um túnel configurado pelo app.");
            return;
        }
        if (!servico.conectado()) {
            avisos.atencao("Conecte a Cloudflare primeiro (aba Cloudflare do servidor).");
            return;
        }
        br.com.labdesktop.cloudflare.PublicadorNaInternet publicador;
        try {
            publicador = servico.publicador(p -> {
                lab.acoes().iniciar(p);
            });
        } catch (FalhaDoProjeto f) {
            avisos.erro(f.mensagem());
            return;
        }
        String zona = servico.escolha().map(br.com.labdesktop.cofre.ConfiguracaoCloudflare::zonaNome).orElse("");
        String prefixo = servico.escolha().map(br.com.labdesktop.cofre.ConfiguracaoCloudflare::prefixo).orElse("lab");
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoPublicar(new MotorDaPublicacao(lab, servico, publicador, projeto, zona, prefixo), avisos, CopiadorDeTexto.doSistema(),
            depois == null ? () -> { } : depois, pre, voltar));
    }

    private static final String OCUPADO = "Já há uma publicação ou remoção de endereço em andamento neste servidor. Espere ela terminar e tente de novo.";

    /** O que o diálogo "Publicar na internet" usa do domínio, sobre o servidor de verdade. */
    private final class MotorDaPublicacao implements br.com.labdesktop.ui.dialogos.DialogoPublicar.Motor {
        private final ServidorLab lab;
        private final br.com.labdesktop.cloudflare.ServicoCloudflare servico;
        private final br.com.labdesktop.cloudflare.PublicadorNaInternet publicador;
        private final String projeto;
        private final String zona;
        private final String prefixo;

        MotorDaPublicacao(ServidorLab lab, br.com.labdesktop.cloudflare.ServicoCloudflare servico, br.com.labdesktop.cloudflare.PublicadorNaInternet publicador, String projeto,
                          String zona, String prefixo) {
            this.lab = lab;
            this.servico = servico;
            this.publicador = publicador;
            this.projeto = projeto;
            this.zona = zona;
            this.prefixo = prefixo;
        }

        @Override
        public String projeto() {
            return projeto;
        }

        @Override
        public String zona() {
            return zona;
        }

        @Override
        public String prefixo() {
            return prefixo;
        }

        @Override
        public boolean podeRecriar() {
            return podeCriarProjeto(lab);
        }

        @Override
        public List<br.com.labdesktop.cloudflare.ComposeComEdge.Servico> servicos() throws FalhaDoProjeto {
            return servico.servicosDoProjeto(projeto);
        }

        @Override
        public br.com.labdesktop.cloudflare.PublicadorNaInternet.Previa preparar(br.com.labdesktop.cloudflare.PublicadorNaInternet.Pedido pedido) throws FalhaDoProjeto {
            return publicador.preparar(pedido);
        }

        @Override
        public br.com.labdesktop.cloudflare.PublicadorNaInternet.Resultado executar(br.com.labdesktop.cloudflare.PublicadorNaInternet.Previa previa,
                                                                                         br.com.labdesktop.cloudflare.ObservadorDaEtapa observador,
                                                                                         br.com.labdesktop.arquivos.Cancelamento cancelamento) {
            return servico.comTravaDeEscrita(() -> publicador.executar(previa, observador, cancelamento),
                () -> new br.com.labdesktop.cloudflare.PublicadorNaInternet.Resultado(br.com.labdesktop.cloudflare.PublicadorNaInternet.Desfecho.FALHOU, previa.hostname(),
                    previa.url(), null, OCUPADO, null, List.of(), List.of(), List.of(), previa.avisos()));
        }

        @Override
        public br.com.labdesktop.cloudflare.TestadorDeUrl.Resultado testarDeNovo(String hostname, br.com.labdesktop.arquivos.Cancelamento cancelamento) {
            return servico.testar(hostname, cancelamento);
        }

        @Override
        public void abrirEndereco(String hostname) {
            abrirEnderecoPublico(lab, hostname);
        }

        @Override
        public void encerrar() {
            publicador.close();
        }
    }

    /** "Remover" um endereço que o app criou: abre o diálogo (a prévia só lê; remover exige digitar o endereço). */
    public void removerEnderecoDaInternet(ServidorLab lab, String hostname, Runnable depois) {
        if (recusarSemAsAcoes()) {
            return;
        }
        br.com.labdesktop.cloudflare.ServicoCloudflare servico = lab.cloudflare();
        br.com.labdesktop.cloudflare.RemovedorDeEndereco removedor;
        try {
            removedor = servico.removedor();
        } catch (FalhaDoProjeto f) {
            avisos.erro(f.mensagem());
            return;
        }
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoRemoverEndereco(hostname, new br.com.labdesktop.ui.dialogos.DialogoRemoverEndereco.Motor() {
            @Override
            public br.com.labdesktop.cloudflare.RemovedorDeEndereco.Previa preparar(String h) throws FalhaDoProjeto {
                return removedor.preparar(h);
            }

            @Override
            public br.com.labdesktop.cloudflare.RemovedorDeEndereco.Resultado executar(br.com.labdesktop.cloudflare.RemovedorDeEndereco.Previa previa,
                                                                                            br.com.labdesktop.cloudflare.ObservadorDaEtapa observador,
                                                                                            br.com.labdesktop.arquivos.Cancelamento cancelamento) {
                return servico.comTravaDeEscrita(() -> removedor.executar(previa, observador, cancelamento),
                    () -> new br.com.labdesktop.cloudflare.RemovedorDeEndereco.Resultado(false, OCUPADO, List.of(), List.of(), List.of()));
            }

            @Override
            public void encerrar() {
                removedor.close();
            }
        }, avisos, depois == null ? () -> { } : depois));
    }

    // =====================================================================================================
    // Encerrar e reiniciar processos
    // =====================================================================================================

    /** De onde vêm os containers que o usuário marcou como protegidos (a janela liga às preferências ao abrir). */
    public void usarProtecaoDeContainers(ProtecaoDeContainers protecao) {
        this.protecaoDeContainers = protecao == null ? ProtecaoDeContainers.NENHUMA : protecao;
    }

    /**
     * "Encerrar" ou "Reiniciar" um processo da lista: abre a confirmação, que só LÊ ao abrir (a identidade real do processo) e só age depois do clique. Só o CLIQUE do usuário chega
     * aqui, e a mesma trava das outras ações de escrita ({@code labdesktop.acoes}) vale também por dentro (defesa em profundidade: os botões já aparecem desligados sem ela).
     * Protegido (PID 1, sshd, o agente, o Docker, o próprio app, o sistema) nunca abre.
     */
    public void encerrarOuReiniciarProcesso(ServidorLab lab, br.com.labdesktop.processos.ProcessoAlvo alvo, String origem, br.com.labdesktop.processos.Acao acao,
                                            Runnable depois) {
        lab.definirProtegidosDoDono(() -> protecaoDeContainers.marcados(lab.id()));
        br.com.labdesktop.processos.ControleDeProcessos controle = lab.controleDeProcessos();
        br.com.labdesktop.processos.Oferta oferta = controle.avaliar(alvo);
        if (oferta.protegido()) {
            avisos.atencao(oferta.protecao());
            return;
        }
        // "Sem permissão" só MOSTRA um comando para o usuário rodar sozinho (nada é enviado): vale mesmo com as ações de escrita desligadas; o resto passa pela trava
        if (oferta.situacao() != br.com.labdesktop.processos.Oferta.Situacao.SEM_PERMISSAO && recusarSemAsAcoes()) {
            return;
        }
        if (acao == br.com.labdesktop.processos.Acao.REINICIAR && !oferta.reiniciarLigado()) {
            avisos.info(br.com.labdesktop.processos.AvaliadorDeProcessos.SEM_REINICIAR);
            return;
        }
        boolean windows = controle.ambiente().tipo() == br.com.labdesktop.processos.AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS;
        boolean administrador = controle.ambiente().usuarioDoAppEhAdministrador() && !lab.ehLocal();
        br.com.labdesktop.ui.dialogos.DialogoProcesso dialogo = new br.com.labdesktop.ui.dialogos.DialogoProcesso(alvo, oferta, acao, origem,
            windows ? "Forçar (taskkill /F)" : "Forçar (SIGKILL)", administrador,
            new br.com.labdesktop.ui.dialogos.DialogoProcesso.Motor() {
                @Override
                public br.com.labdesktop.processos.Previa preparar() throws br.com.labdesktop.processos.FalhaDeProcesso, InterruptedException {
                    return controle.preparar(alvo);
                }

                @Override
                public br.com.labdesktop.processos.Resultado encerrar(br.com.labdesktop.processos.Previa previa, br.com.labdesktop.processos.ObservadorDeProcesso obs,
                                                                           br.com.labdesktop.arquivos.Cancelamento cancelamento) throws InterruptedException {
                    return controle.encerrar(previa, obs, cancelamento);
                }

                @Override
                public br.com.labdesktop.processos.Resultado forcar(br.com.labdesktop.processos.Previa previa, br.com.labdesktop.processos.ObservadorDeProcesso obs,
                                                                         br.com.labdesktop.arquivos.Cancelamento cancelamento) throws InterruptedException {
                    return controle.forcar(previa, obs, cancelamento);
                }

                @Override
                public br.com.labdesktop.processos.Resultado reiniciar(br.com.labdesktop.processos.Previa previa, br.com.labdesktop.processos.ObservadorDeProcesso obs,
                                                                            br.com.labdesktop.arquivos.Cancelamento cancelamento) throws InterruptedException {
                    return controle.reiniciar(previa, obs, cancelamento);
                }
            }, CopiadorDeTexto.doSistema(), avisos, () -> {
                lab.atualizarAgora(); // a lista se atualiza na hora, com o que houver
                if (depois != null) {
                    depois.run();
                }
            });
        if (alvo.ehDeContainer()) {
            dialogo.permitirProteger(() -> {
                protecaoDeContainers.marcar(lab.id(), alvo.container(), true);
                avisos.ok("O container " + alvo.container() + " agora é protegido neste servidor: o app não o para nem o reinicia. Para liberar, tire a marca em Ajustes, Segurança.");
            });
        }
        apresentador.mostrar(dialogo);
    }

    /**
     * "Instalar o Git" num servidor: abre a confirmação, que só LÊ ao abrir e mostra os comandos exatos; só roda depois do clique em "Instalar o Git" (e só se o usuário é root ou tem sudo sem
     * senha; senão mostra o comando para o usuário rodar no Terminal). Em "Este computador" leva a "Preparar este computador", que já confere o git e propõe como instalar.
     *
     * <p>CONFERIR e COPIAR o comando valem sempre; EXECUTAR a instalação é uma ação de escrita no servidor e fica atrás da trava {@code labdesktop.acoes}, como as outras: sem ela o diálogo
     * mostra o comando para o usuário rodar no Terminal, e o motor recusa mesmo que alguém o chame.
     *
     * @param depois roda (na EDT) quando o git passou a estar lá (a tela que abriu relê o estado)
     */
    public void instalarGit(ServidorLab lab, Runnable depois) {
        if (lab.ehLocal()) {
            prepararEsteComputador(lab);
            return;
        }
        if (recusarSeProtegido(lab)) {
            return;
        }
        if (!lab.estado().conexao().permiteExec()) {
            avisos.atencao("O app precisa da conexão por chave com o servidor para instalar o git. Conecte primeiro.");
            return;
        }
        br.com.labdesktop.instalador.InstaladorDeGit git = lab.instaladorDeGit();
        apresentador.mostrar(new br.com.labdesktop.ui.dialogos.DialogoInstalarGit(lab.estado().nome(), trava.getAsBoolean(), new br.com.labdesktop.ui.dialogos.DialogoInstalarGit.Motor() {
            @Override
            public br.com.labdesktop.instalador.InstaladorDeGit.Previa conferir() throws Exception {
                return git.conferir();
            }

            @Override
            public br.com.labdesktop.instalador.InstaladorDeGit.Resultado rodar(br.com.labdesktop.instalador.InstaladorDeGit.Previa previa,
                                                                                         br.com.labdesktop.instalador.InstaladorDeGit.Observador observador,
                                                                                         br.com.labdesktop.arquivos.Cancelamento cancelamento) throws InterruptedException {
                if (!trava.getAsBoolean()) {
                    return new br.com.labdesktop.instalador.InstaladorDeGit.Resultado(false, previa.estado(),
                        "As ações de escrita estão desligadas nesta versão do app. Nada foi instalado: rode o comando no Terminal.", "");
                }
                return git.instalar(previa, observador, cancelamento);
            }

            @Override
            public br.com.labdesktop.instalador.InstaladorDeGit.Estado conferirDeNovo() throws Exception {
                return git.conferirOGit();
            }
        }, CopiadorDeTexto.doSistema(), avisos, depois));
    }

    /** O que o diálogo "Novo projeto" usa do domínio, sobre o servidor de verdade. */
    private final class MotorDeProjetos implements DialogoNovoProjeto.Motor {
        private final ServidorLab lab;
        private final AmbienteDeProjetos ambiente;
        private final CriadorDeProjeto criador;
        private final String raiz;
        private final boolean guiaIa;

        MotorDeProjetos(ServidorLab lab, AmbienteDeProjetos ambiente, CriadorDeProjeto criador, String raiz, boolean guiaIa) {
            this.lab = lab;
            this.ambiente = ambiente;
            this.criador = criador;
            this.raiz = raiz;
            this.guiaIa = guiaIa;
        }

        @Override
        public boolean local() {
            return lab.ehLocal();
        }

        @Override
        public boolean guiaIaDisponivel() {
            return guiaIa;
        }

        @Override
        public String pastaDosProjetos() {
            return paraExibir(raiz);
        }

        @Override
        public String caminhoDoProjeto(String nome) {
            return paraExibir(raiz + (raiz.endsWith("/") ? "" : "/") + nome);
        }

        private String paraExibir(String caminhoDoApp) {
            if (!lab.ehLocal()) {
                return caminhoDoApp;
            }
            try {
                return CaminhosLocais.doSistema().paraTextoDoSistema(caminhoDoApp);
            } catch (IllegalArgumentException e) {
                return caminhoDoApp;
            }
        }

        @Override
        public FluxoDeCriacao.Desfecho criar(String nome, Boolean guia) {
            return FluxoDeCriacao.criar(criador, nome, guia);
        }

        @Override
        public ImportadorDeProjeto.Resultado clonar(UrlDeGit url, String nome, ImportadorDeProjeto.Observador obs,
                                                    br.com.labdesktop.arquivos.Cancelamento cancelamento) throws FalhaDoProjeto, InterruptedException {
            return new ImportadorDeProjeto(ambiente).clonar(url, nome, obs, cancelamento);
        }

        @Override
        public ImportadorDeProjeto.Resultado importarPasta(java.nio.file.Path pasta, String nome, FiltroDeEnvio filtro, ImportadorDeProjeto.Observador obs,
                                                           br.com.labdesktop.arquivos.Cancelamento cancelamento) throws FalhaDoProjeto {
            return new ImportadorDeProjeto(ambiente).importarPasta(pasta, nome, filtro, obs, cancelamento);
        }

        @Override
        public List<ImportadorDeProjeto.Candidata> candidatas() throws FalhaDoProjeto {
            return new ImportadorDeProjeto(ambiente).candidatasParaAdotar();
        }

        @Override
        public ImportadorDeProjeto.Resultado adotar(String origem, String nome, ImportadorDeProjeto.Modo modo, ImportadorDeProjeto.Observador obs,
                                                    br.com.labdesktop.arquivos.Cancelamento cancelamento) throws FalhaDoProjeto, InterruptedException {
            return new ImportadorDeProjeto(ambiente).adotar(origem, nome, modo, obs, cancelamento);
        }

        @Override
        public java.util.Optional<java.nio.file.Path> escolherPasta() {
            return escolhedor.escolherPastaParaImportar();
        }

        @Override
        public java.util.Optional<Boolean> gitInstalado() {
            try {
                if (lab.ehLocal()) {
                    // o mesmo critério da conferência de "Preparar este computador": no Mac o /usr/bin/git sem as ferramentas da Apple é só um atalho, e não conta
                    return java.util.Optional.of(br.com.labdesktop.local.GitLocal.versao(lab.servicosLocais().sonda(), lab.servicosLocais().executor(),
                        lab.servicosLocais().plataforma().sistema()).isPresent());
                }
                return java.util.Optional.of(lab.instaladorDeGit().conferirOGit().instalado());
            } catch (java.io.IOException | RuntimeException e) {
                return java.util.Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return java.util.Optional.empty();
            }
        }

        @Override
        public boolean instalarGit(Runnable depois) {
            AcoesDaInterface.this.instalarGit(lab, depois);
            return true;
        }

        @Override
        public boolean servidorProtegido() {
            return lab.protegido();
        }
    }

    // =====================================================================================================
    // Este computador
    // =====================================================================================================

    /**
     * "Preparar este computador": cadastra "Este computador" se ainda não está na lista (sem SSH, sem chave, só uma linha no cofre) e abre a
     * tela que CONFERE (só leitura) e pergunta. Nada é instalado nem criado sem o clique e a confirmação do usuário.
     *
     * @param lab o servidor local, ou {@code null} para cadastrá-lo agora
     */
    public void prepararEsteComputador(ServidorLab lab) {
        if (lab != null) {
            abrirDialogoLocal(lab, DialogoPrepararComputador.Acao.PREPARAR);
            return;
        }
        Tarefas.rodar(() -> {
            ServidorLab existente = gerenciador.servidores().stream().filter(ServidorLab::ehLocal).findFirst().orElse(null);
            if (existente != null) {
                return existente;
            }
            ServidorLab novo = assistente.cadastrarLocal("Este computador", ConfigServidor.PORTA_AGENTE_LOCAL_PADRAO);
            lembrarAPastaDosProjetosLocais(novo, null);
            novo.iniciar();
            return novo;
        }, novo -> {
            aoSelecionar.accept(novo);
            abrirDialogoLocal(novo, DialogoPrepararComputador.Acao.PREPARAR);
        }, falha -> avisos.erro(MensagensDeErro.de(falha)));
    }

    /** "Remover laboratório local": confirma pelo nome do agente; tira só o agente, os dados dele e a rede, nunca os projetos. */
    public void removerLaboratorioLocal(ServidorLab lab) {
        abrirDialogoLocal(lab, DialogoPrepararComputador.Acao.REMOVER);
    }

    /** O pacote do agente falta neste app: é um aviso em palavras simples, não um erro. Qualquer outra falha segue como erro. */
    private void falhaDoPacoteDoAgente(Throwable falha) {
        if (falha instanceof br.com.labdesktop.instalador.PacoteDoAgenteAusenteException) {
            avisos.atencao(falha.getMessage());
        } else {
            avisos.erro(MensagensDeErro.de(falha));
        }
    }

    private void abrirDialogoLocal(ServidorLab lab, DialogoPrepararComputador.Acao acao) {
        Tarefas.rodar(PacoteDoAgente::doClasspath, pacote -> {
            ConfigServidor cfg = lab.config();
            CaminhosLocais caminhos = CaminhosLocais.doSistema();
            String pasta = pastaDosProjetosLocais(lab, caminhos);
            String container = ParametrosDoLaboratorio.CONTAINER_PADRAO;
            DialogoPrepararComputador.Alvo alvo = new DialogoPrepararComputador.Alvo(cfg.nome(), cfg.portaAgente(), pasta, container, pacote.versao(),
                lab.estado().versao() != null && lab.estado().versao().perfilMonitor());
            apresentador.mostrar(new DialogoPrepararComputador(acao, alvo, new MotorLocal(lab, pacote, caminhos), lab::aoMudarOAgenteNoServidor, avisos,
                CopiadorDeTexto.doSistema()));
        }, this::falhaDoPacoteDoAgente);
    }

    /** A pasta dos projetos locais como texto do sistema (a lembrada, ou {@code <pasta do usuário>/LabLocal/projects}). */
    private String pastaDosProjetosLocais(ServidorLab lab, CaminhosLocais caminhos) {
        String lembrada = servicosDeArquivos.memoria().raizDosProjetos(lab.id());
        if (lembrada.equals(MemoriaDeCaminhos.RAIZ_DOS_PROJETOS_PADRAO)) {
            return lab.servicosLocais().plataforma().pastaDeProjetosPadrao();
        }
        try {
            return caminhos.paraTextoDoSistema(lembrada);
        } catch (IllegalArgumentException e) {
            return lab.servicosLocais().plataforma().pastaDeProjetosPadrao();
        }
    }

    private void lembrarAPastaDosProjetosLocais(ServidorLab lab, String pastaDoSistema) {
        try {
            String pasta = pastaDoSistema != null ? pastaDoSistema : lab.servicosLocais().plataforma().pastaDeProjetosPadrao();
            servicosDeArquivos.memoria().definirRaizDosProjetos(lab.id(), CaminhosLocais.doSistema().doTextoDoSistema(pasta));
        } catch (ErroSftp | RuntimeException e) {
            avisos.atencao("Não consegui guardar a pasta dos projetos locais: " + MensagensDeErro.de(e));
        }
    }

    /** O que a tela "Preparar este computador" usa do domínio, sobre o servidor local de verdade. */
    private final class MotorLocal implements DialogoPrepararComputador.Motor {
        private final ServidorLab lab;
        private final PacoteDoAgente pacote;
        private final CaminhosLocais caminhos;

        MotorLocal(ServidorLab lab, PacoteDoAgente pacote, CaminhosLocais caminhos) {
            this.lab = lab;
            this.pacote = pacote;
            this.caminhos = caminhos;
        }

        @Override
        public RelatorioDoComputador verificar(String pastaDosProjetos, int porta) throws Exception {
            ServicosLocais s = lab.servicosLocais();
            return new VerificacoesDoComputador(s.executor(), s.sonda(), s.plataforma()).executar(pastaDosProjetos, porta);
        }

        @Override
        public InstaladorDeRequisitos.Resultado instalar(PlanoDeInstalacao plano, ObservadorDaInstalacao observador, Cancelamento cancelamento) {
            return new InstaladorDeRequisitos(lab.servicosLocais().executor()).instalar(plano, observador, cancelamento);
        }

        @Override
        public boolean abrirODocker(ObservadorDaInstalacao observador, Cancelamento cancelamento) throws InterruptedException {
            ServicosLocais s = lab.servicosLocais();
            boolean desktop = s.plataforma().caminhosDoDockerDesktop().stream().anyMatch(s.sonda()::existe);
            boolean colima = s.sonda().localizarPrograma("colima").isPresent();
            return new InstaladorDeRequisitos(s.executor()).abrirEEsperar(s.plataforma().comandoParaAbrirODocker(desktop, colima),
                java.time.Duration.ofMinutes(4), java.time.Duration.ofSeconds(2), observador, cancelamento);
        }

        @Override
        public String problemaDaPasta(String pasta) {
            var p = lab.servicosLocais().plataforma();
            return br.com.labdesktop.local.ValidadorDaPastaDosProjetos.problema(p.sistema(), p.casa(), pasta).orElse(null);
        }

        @Override
        public LaboratorioLocal laboratorio(ParametrosDoLaboratorio parametros) {
            return lab.laboratorioLocal(parametros, pacote);
        }

        @Override
        public void abrirPagina(String url) {
            if (navegador != null) {
                navegador.abrirPaginaDeDownload(url);
            }
        }

        @Override
        public void guardarEscolhas(int porta, String pastaDosProjetos) throws Exception {
            if (porta != lab.config().portaAgente()) {
                gerenciador.atualizarPortaDoLocal(lab.id(), porta);
            }
            servicosDeArquivos.memoria().definirRaizDosProjetos(lab.id(), caminhos.doTextoDoSistema(pastaDosProjetos));
        }
    }

    /**
     * "Instalar agente": abre a CONFIRMAÇÃO (com o nome do servidor no botão). Só o clique do usuário chega aqui: o app nunca instala,
     * atualiza nem remove o agente sozinho, nem ao conectar, nem ao abrir, nem em segundo plano.
     */
    public void instalarAgente(ServidorLab lab) {
        instalarAgente(lab, () -> { }, () -> avisos.info(lab.estado().nome() + " fica só por SSH: terminal, Segurança e Arquivos continuam funcionando. "
            + "Quando quiser instalar, o botão Instalar agente está no cartão e na página do servidor."));
    }

    /**
     * Como {@link #instalarAgente(ServidorLab)}, e {@code aoInstalar} roda (na EDT) se algo ficou instalado (o assistente de adicionar servidor continua),
     * e {@code aoSoSsh} roda se o usuário escolheu "Só SSH, não instalar nada" na tela "O que instalar" (o assistente conclui como "Agora não").
     */
    public void instalarAgente(ServidorLab lab, Runnable aoInstalar, Runnable aoSoSsh) {
        if (lab.ehLocal()) {
            prepararEsteComputador(lab); // em Este computador o agente nasce de "Preparar este computador"
            return;
        }
        abrirDialogoDoAgente(lab, DialogoDoAgente.Acao.INSTALAR, aoInstalar, aoSoSsh);
    }

    /** "Atualizar agente": a confirmação; instala a versão nova do pacote do app por cima, mantendo os dados e o token. */
    public void atualizarAgente(ServidorLab lab) {
        abrirDialogoDoAgente(lab, DialogoDoAgente.Acao.ATUALIZAR, () -> { }, () -> { });
    }

    /** "Remover agente": a confirmação; tira do servidor o que o app instalou (o Docker fica). */
    public void removerAgente(ServidorLab lab) {
        abrirDialogoDoAgente(lab, DialogoDoAgente.Acao.REMOVER, () -> { }, () -> { });
    }

    /**
     * O ponto único por onde instalar, atualizar e remover o agente chegam à confirmação (as três ações públicas acima, o menu, os botões do cartão e da página, os
     * avisos e o último passo do assistente). Servidor protegido: recusa aqui, antes de abrir qualquer diálogo, e {@code ServidorLab.instaladorDoAgente}
     * recusa de novo, caso alguma tela chegue por outro caminho.
     */
    private void abrirDialogoDoAgente(ServidorLab lab, DialogoDoAgente.Acao acao, Runnable aoMudar, Runnable aoSoSsh) {
        if (recusarSeProtegido(lab)) {
            return;
        }
        EstadoServidor e = lab.estado();
        // O pacote vem do jar (leitura de recurso): fora da EDT.
        Tarefas.rodar(PacoteDoAgente::doClasspath, pacote -> {
            DialogoDoAgente.Alvo alvo = new DialogoDoAgente.Alvo(e.nome(), lab.config().portaAgente(),
                e.versao() == null ? null : e.versao().agente());
            DialogoDoAgente dialogo;
            try {
                dialogo = new DialogoDoAgente(acao, alvo, pacote, p -> lab.instaladorDoAgente(p, pacote), () -> {
                    lab.aoMudarOAgenteNoServidor();
                    aoMudar.run();
                }, aoSoSsh, avisos, CopiadorDeTexto.doSistema());
            } catch (ProtecaoDoServidor.ServidorProtegidoException protegido) {
                avisos.atencao(protegido.getMessage()); // o servidor foi protegido entre o clique e a abertura
                return;
            }
            apresentador.mostrar(dialogo);
        }, this::falhaDoPacoteDoAgente);
    }

    /** Servidor protegido: diz a recusa (em português simples, sem a palavra erro) e devolve {@code true}; nada é aberto nem escrito. */
    private boolean recusarSeProtegido(ServidorLab lab) {
        if (!lab.protegido()) {
            return false;
        }
        avisos.atencao(ProtecaoDoServidor.RECUSA);
        return true;
    }

    /**
     * "Editar": nome, endereço, porta e usuário do servidor. Salvar grava no cofre fora da EDT e reconecta; mudar o endereço
     * ou a porta apaga a impressão digital guardada (o diálogo avisa).
     */
    public void editar(ServidorLab lab) {
        if (lab.ehLocal()) {
            avisos.info("Este computador não tem endereço, porta de SSH nem usuário. A porta do agente e a pasta dos projetos ficam em "
                + "Preparar este computador, nas opções avançadas.");
            return;
        }
        // a seção "Acesso" mostra a chave que o app usa neste servidor; sem a chave no cofre ela some (o resto da edição segue igual)
        DialogoEditarServidor.Acesso acesso = (assistente == null ? java.util.Optional.<AssistenteDeServidor.AcessoDoServidor>empty() : assistente.acessoDo(lab)).map(a -> new DialogoEditarServidor.Acesso(a.chave().nome(), a.chave().impressao(), a.chave().publica(),
            lab.protegido() ? null : () -> instalarChaveComSenha(lab))).orElse(null); // protegido: o botão da chave nem aparece
        apresentador.mostrar(new DialogoEditarServidor(lab.config(), dados -> gerenciador.atualizar(lab.id(), dados), avisos, acesso, CopiadorDeTexto.doSistema(),
            assistente == null ? null : assistente::conferirFerramentas));
    }

    /**
     * "Instalar a chave com a senha…": o conserto de um servidor SSH que já está cadastrado e que o lab recusa ("Chave recusada"): abre o diálogo que pede a senha
     * do usuário UMA vez, confere o host contra a impressão digital guardada, instala a chave do cofre, prova numa conexão nova e manda reconectar. Só o clique do usuário chega
     * aqui. Fora da trava {@code labdesktop.acoes}, como o assistente de adicionar servidor, a instalação do agente e a remoção do servidor: é gerenciar o acesso ao servidor,
     * e o usuário que ficou sem entrar precisa disto no app que baixou.
     */
    public void instalarChaveComSenha(ServidorLab lab) {
        if (lab.ehLocal()) {
            avisos.info("Este computador não usa SSH nem chave: não há o que instalar.");
            return;
        }
        if (recusarSeProtegido(lab)) {
            return; // servidor protegido: o app não grava chave nenhuma no authorized_keys dele
        }
        var acesso = assistente == null ? java.util.Optional.<AssistenteDeServidor.AcessoDoServidor>empty() : assistente.acessoDo(lab);
        if (acesso.isEmpty()) {
            avisos.erro(frasePorFaltaDeAcesso(assistente != null && assistente.cadastroDe(lab).isPresent()));
            return;
        }
        apresentador.mostrar(new DialogoInstalarChave(DialogoInstalarChave.Alvo.de(acesso.get().config(), acesso.get().chave()),
            (senha, progresso) -> assistente.repararAChaveComSenha(lab, senha, progresso), CopiadorDeTexto.doSistema(), avisos));
    }

    /**
     * Por que não há o que instalar: o servidor saiu da lista, ou (cofre com defeito, que a API do cofre não deixa acontecer) o cadastro existe e a chave sumiu dele. São dois
     * problemas com dois caminhos, e a frase não pode misturá-los.
     */
    static String frasePorFaltaDeAcesso(boolean cadastroExiste) {
        return cadastroExiste ? "A chave deste servidor não está mais no cofre. Remova o servidor e adicione-o de novo." : "Esse servidor não está mais na lista.";
    }

    /**
     * "Remover": o diálogo com as duas escolhas (apagar a chave do cofre, revogar o acesso no servidor). A remoção de fato
     * roda fora da EDT; as telas se atualizam sozinhas pela lista de servidores, e sem nenhum servidor sobra o convite para
     * adicionar.
     */
    public void remover(ServidorLab lab) {
        EstadoServidor e = lab.estado();
        if (lab.ehLocal()) {
            apresentador.mostrar(DialogoRemoverServidor.paraEsteComputador(e.nome(), e.conexao().permiteExec(),
                opcoes -> gerenciador.removerServidor(lab.id(), opcoes), avisos));
            return;
        }
        List<String> outrosQueUsamAChave = gerenciador.outrosQueUsamAChaveDe(lab.id());
        apresentador.mostrar(new DialogoRemoverServidor(e.nome(), lab.config().usuario(), e.conexao().permiteExec(),
            outrosQueUsamAChave, gerenciador.origemDaChaveDe(lab.id()), opcoes -> gerenciador.removerServidor(lab.id(), opcoes),
            avisos, lab.protegido()));
    }

    /** "Configurar 2FA" (ou "Desativar", conforme o estado que o agente informa). */
    public void configurar2fa(ServidorLab lab) {
        EstadoServidor e = lab.estado();
        apresentador.mostrar(new Configurar2fa(e.nome(), e.estado2fa(), lab, avisos));
    }

    /**
     * "Digitar código": repete o teste do agente; o {@code ClienteApi} pergunta o código pelo diálogo (um pedido por
     * servidor, nunca na EDT).
     */
    public void digitarCodigo(ServidorLab lab) {
        lab.digitarCodigo2fa();
    }

    /** "Tentar agora" (ou "Conectar", se estava parado). */
    public void tentarAgora(ServidorLab lab) {
        if (lab.estado().conexao() == EstadoConexao.DESCONECTADO) {
            lab.iniciar();
        } else {
            lab.tentarAgora();
        }
    }

    /** F5, "Atualizar": consulta já o que as telas visíveis assinaram. */
    public void atualizar(ServidorLab lab) {
        lab.atualizarAgora();
    }

    /**
     * Conferir a impressão digital do host de um servidor que ainda não tem pin: a sondagem (sem autenticar) e o diálogo
     * de confirmação; confirmado, o pin vai para o cofre e a conexão tenta de novo.
     */
    public void conferirImpressao(ServidorLab lab) {
        ConfigServidor cfg = lab.config();
        String endereco = cfg.enderecoParaMostrar();
        avisos.info("Consultando a chave do servidor em " + endereco + "…");
        Tarefas.rodar(() -> assistente.sondar(cfg.transporte(), cfg.host(), cfg.porta()), resultado ->
            apresentador.mostrar(new DialogoConfiarHost(cfg.nome(), endereco, resultado, avisos, confiou -> {
                if (!confiou) {
                    return;
                }
                Tarefas.rodar(() -> {
                    assistente.confiarNoHost(lab, resultado.pin());
                    return Boolean.TRUE;
                }, ok -> avisos.ok("Servidor confirmado. Conectando…"), falha -> avisos.erro(MensagensDeErro.de(falha)));
            })), falha -> avisos.erro(MensagensDeErro.de(falha)));
    }

    /**
     * Registrar (ou substituir) o token deste Mac. Com o token recusado, o diálogo já abre em "substituir": a entrada
     * antiga do agente é revogada, e isso só acontece depois de o usuário confirmar.
     */
    public void registrarToken(ServidorLab lab) {
        EstadoServidor e = lab.estado();
        boolean substituir = e.conexao() == EstadoConexao.TOKEN_RECUSADO;
        Tarefas.rodar(AssistenteDeServidor::sugerirNomeDoDispositivo, sugerido -> {
            String nome = e.nomeDoDispositivo() != null ? e.nomeDoDispositivo() : sugerido;
            apresentador.mostrar(new DialogoRegistrarToken(e.nome(), nome, substituir, lab::registrarToken,
                lab::reprovisionarToken, () -> { }, avisos));
        }, falha -> avisos.erro(MensagensDeErro.de(falha)));
    }

    /** O botão de uma faixa de conexão. */
    public void executar(ServidorLab lab, Acao acao) {
        switch (acao) {
            case TENTAR_AGORA, CONECTAR -> tentarAgora(lab);
            case DIGITAR_CODIGO -> digitarCodigo(lab);
            case CONFERIR_IMPRESSAO -> conferirImpressao(lab);
            case REGISTRAR_TOKEN -> registrarToken(lab);
            case REMOVER_SERVIDOR -> remover(lab);
            case INSTALAR_CHAVE -> instalarChaveComSenha(lab);
            case INSTALAR_AGENTE -> instalarAgente(lab);
            case PREPARAR_COMPUTADOR -> prepararEsteComputador(lab);
        }
    }
}
