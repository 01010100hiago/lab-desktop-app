package br.com.labdesktop.ui;

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
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.EstadoServidor;
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
import br.com.labdesktop.ui.dialogos.DialogoDoAgente;
import br.com.labdesktop.ui.dialogos.DialogoEscolherServidor;
import br.com.labdesktop.ui.dialogos.DialogoPrepararComputador;
import br.com.labdesktop.ui.dialogos.DialogoEditarServidor;
import br.com.labdesktop.ui.dialogos.DialogoRegistrarToken;
import br.com.labdesktop.ui.dialogos.DialogoRemoverServidor;
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
            CopiadorDeTexto.doSistema(), this::instalarAgente));
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
        instalarAgente(lab, () -> { });
    }

    /** Como {@link #instalarAgente(ServidorLab)}, e {@code aoInstalar} roda (na EDT) se o agente ficou instalado (o assistente de adicionar servidor continua). */
    public void instalarAgente(ServidorLab lab, Runnable aoInstalar) {
        if (lab.ehLocal()) {
            prepararEsteComputador(lab); // em Este computador o agente nasce de "Preparar este computador"
            return;
        }
        abrirDialogoDoAgente(lab, DialogoDoAgente.Acao.INSTALAR, aoInstalar);
    }

    /** "Atualizar agente": a confirmação; instala a versão nova do pacote do app por cima, mantendo os dados e o token. */
    public void atualizarAgente(ServidorLab lab) {
        abrirDialogoDoAgente(lab, DialogoDoAgente.Acao.ATUALIZAR, () -> { });
    }

    /** "Remover agente": a confirmação; tira do servidor o que o app instalou (o Docker fica). */
    public void removerAgente(ServidorLab lab) {
        abrirDialogoDoAgente(lab, DialogoDoAgente.Acao.REMOVER, () -> { });
    }

    private void abrirDialogoDoAgente(ServidorLab lab, DialogoDoAgente.Acao acao, Runnable aoMudar) {
        EstadoServidor e = lab.estado();
        // O pacote vem do jar (leitura de recurso): fora da EDT.
        Tarefas.rodar(PacoteDoAgente::doClasspath, pacote -> {
            DialogoDoAgente.Alvo alvo = new DialogoDoAgente.Alvo(e.nome(), lab.config().portaAgente(),
                e.versao() == null ? null : e.versao().agente());
            DialogoDoAgente dialogo = new DialogoDoAgente(acao, alvo, pacote, p -> lab.instaladorDoAgente(p, pacote), () -> {
                lab.aoMudarOAgenteNoServidor();
                aoMudar.run();
            }, avisos, CopiadorDeTexto.doSistema());
            apresentador.mostrar(dialogo);
        }, this::falhaDoPacoteDoAgente);
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
        apresentador.mostrar(new DialogoEditarServidor(lab.config(), dados -> gerenciador.atualizar(lab.id(), dados), avisos));
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
            avisos));
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
        String endereco = cfg.host() + ":" + cfg.porta();
        avisos.info("Consultando a chave do servidor em " + endereco + "…");
        Tarefas.rodar(() -> assistente.sondar(cfg.host(), cfg.porta()), resultado ->
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
            case INSTALAR_AGENTE -> instalarAgente(lab);
            case PREPARAR_COMPUTADOR -> prepararEsteComputador(lab);
        }
    }
}
