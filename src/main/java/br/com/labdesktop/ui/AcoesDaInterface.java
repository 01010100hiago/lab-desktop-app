package br.com.labdesktop.ui;

import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.Configurar2fa;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarServidor;
import br.com.labdesktop.ui.dialogos.DialogoConfiarHost;
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

    /**
     * @param aoSelecionar chamado na EDT depois de um servidor ser adicionado, para a janela mostrá-lo
     */
    public AcoesDaInterface(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, Notificacoes avisos,
                            Apresentador apresentador, Consumer<ServidorLab> aoSelecionar) {
        this.gerenciador = gerenciador;
        this.assistente = assistente;
        this.avisos = avisos;
        this.apresentador = apresentador;
        this.aoSelecionar = aoSelecionar;
    }

    /** "Adicionar servidor": o assistente. Na primeira vez os campos vêm vazios, com a porta SSH 22. */
    public void adicionarServidor() {
        boolean primeiro = gerenciador.servidores().isEmpty();
        apresentador.mostrar(new DialogoAdicionarServidor(assistente, primeiro, avisos, aoSelecionar));
    }

    /**
     * "Editar": nome, endereço, porta e usuário do servidor. Salvar grava no cofre fora da EDT e reconecta; mudar o endereço
     * ou a porta apaga a impressão digital guardada (o diálogo avisa).
     */
    public void editar(ServidorLab lab) {
        apresentador.mostrar(new DialogoEditarServidor(lab.config(), dados -> gerenciador.atualizar(lab.id(), dados), avisos));
    }

    /**
     * "Remover": o diálogo com as duas escolhas (apagar a chave do cofre, revogar o acesso no servidor). A remoção de fato
     * roda fora da EDT; as telas se atualizam sozinhas pela lista de servidores, e sem nenhum servidor sobra o convite para
     * adicionar.
     */
    public void remover(ServidorLab lab) {
        EstadoServidor e = lab.estado();
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
        }
    }
}
