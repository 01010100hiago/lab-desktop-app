package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.api.modelo.RespostaLimite;
import br.com.labdesktop.api.modelo.RespostaNovoProjeto;
import br.com.labdesktop.api.modelo.RespostaRetomar;
import br.com.labdesktop.api.modelo.ResultadoAcao;
import br.com.labdesktop.api.modelo.SessaoAberta;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.local.ProjetosLocais;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * As ações sobre o servidor (iniciar, parar, abrir sessão, limite...) com a regra do front: ao terminar,
 * força o refresh só dos tópicos afetados e de {@code LIMITES} (equivalente ao {@code atualizarTudo(true)}).
 * O refresh acontece também quando a ação FALHA: um {@code 409}, um prazo estourado ou um erro de rede deixam
 * o resultado incerto, e a tela precisa olhar o estado de verdade. POST e PUT nunca são reenviados sozinhos.
 *
 * <p>Todos os métodos BLOQUEIAM (uma ação pode segurar até 12 min): chame de thread de fundo, nunca da EDT.
 */
public final class AcoesDoServidor {

    private final Supplier<ClienteApi> api;
    private final Sondador sondador;
    /** Os projetos de "Este computador" (o agente local não os conhece): iniciar, parar, reiniciar e reiniciar container vão por aqui; {@code null} no servidor remoto. */
    private final ProjetosLocais locais;

    AcoesDoServidor(Supplier<ClienteApi> api, Sondador sondador) {
        this(api, sondador, null);
    }

    AcoesDoServidor(Supplier<ClienteApi> api, Sondador sondador, ProjetosLocais locais) {
        this.api = api;
        this.sondador = sondador;
        this.locais = locais;
    }

    @FunctionalInterface
    private interface ChamadaLocal<T> {
        T executar() throws IOException, InterruptedException;
    }

    /** Uma ação do computador local: sem agente no meio; o resto é igual (a lista e o detalhe são relidos ao fim, com sucesso ou não). */
    private <T> T rodarLocal(String projeto, ChamadaLocal<T> chamada) throws ErroApi {
        try {
            return chamada.executar();
        } catch (ProjetosLocais.ProjetoNaoExisteException e) {
            throw new ErroApi.NaoEncontrado(null, e.getMessage());
        } catch (IOException e) {
            throw new ErroApi.ServidorErro(500, null, e.getMessage() == null ? "O Docker não conseguiu." : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroApi.ServidorErro(500, null, "A ação foi interrompida.");
        } catch (IllegalArgumentException e) {
            // por exemplo um name: do docker-compose.yml que o Docker não aceita (mais de 63 caracteres): o usuário precisa corrigir o arquivo
            throw new ErroApi.ServidorErro(400, null, "O docker-compose.yml deste projeto tem um nome (name:) ou um arquivo que o app não passa ao Docker: "
                + "o nome usa letras minúsculas, números, hífen e sublinhado, até 63 caracteres. Corrija o arquivo e tente de novo.");
        } finally {
            sondador.atualizarAgora(Topico.PROJETOS, Topico.detalhe(projeto), Topico.LIMITES);
        }
    }

    @FunctionalInterface
    private interface Chamada<T> {
        T executar(ClienteApi api) throws ErroApi;
    }

    private <T> T rodar(String projeto, Chamada<T> chamada) throws ErroApi {
        ClienteApi a = api.get();
        if (a == null) {
            throw new ErroRede("Sem conexão com o servidor.", null);
        }
        try {
            return chamada.executar(a);
        } finally {
            sondador.atualizarAgora(Topico.PROJETOS, Topico.detalhe(projeto), Topico.LIMITES);
        }
    }

    /** Há uma conexão com o agente agora? ({@code false}: nada seria enviado). */
    public boolean conectado() {
        return api.get() != null;
    }

    /**
     * Cria um projeto novo pelo agente ({@code POST /api/projetos/novo}). Ao terminar, com sucesso ou não, a lista de projetos é consultada de
     * novo: um prazo estourado deixa o resultado incerto, e a tela precisa olhar o que há de verdade. NUNCA é repetida sozinha.
     *
     * @param guiaIa {@code null} omite o campo (o agente cria com guia, como sempre); {@code false} pede sem {@code CLAUDE.md}
     */
    public RespostaNovoProjeto criarProjeto(String nome, Boolean guiaIa) throws ErroApi {
        return rodar(nome, a -> a.criarProjeto(nome, guiaIa));
    }

    /** Os nomes dos projetos que o agente lista agora (releitura depois de um prazo estourado). */
    public List<String> nomesDosProjetos() throws ErroApi {
        return ler(a -> a.projetos().projetos().stream().map(Projeto::nome).toList());
    }

    public ResultadoAcao iniciar(String projeto) throws ErroApi {
        if (locais != null) {
            return rodarLocal(projeto, () -> new ResultadoAcao(true, locais.iniciar(projeto)));
        }
        return rodar(projeto, a -> a.iniciar(projeto));
    }

    public ResultadoAcao parar(String projeto) throws ErroApi {
        if (locais != null) {
            return rodarLocal(projeto, () -> new ResultadoAcao(true, locais.parar(projeto)));
        }
        return rodar(projeto, a -> a.parar(projeto));
    }

    public ResultadoAcao reiniciar(String projeto) throws ErroApi {
        if (locais != null) {
            return rodarLocal(projeto, () -> new ResultadoAcao(true, locais.reiniciar(projeto)));
        }
        return rodar(projeto, a -> a.reiniciar(projeto));
    }

    /** Abre a sessão do Claude (idempotente). 409 = pausada pelo limite. */
    public SessaoAberta abrirSessao(String projeto) throws ErroApi {
        return rodar(projeto, a -> a.abrirSessao(projeto));
    }

    public void fecharSessao(String projeto) throws ErroApi {
        rodar(projeto, a -> {
            a.fecharSessao(projeto);
            return null;
        });
    }

    public SessaoAberta reabrirSessao(String projeto) throws ErroApi {
        return rodar(projeto, a -> a.reabrirSessao(projeto));
    }

    /** Envia o código de login do Claude para o terminal da sessão do projeto. */
    public void enviarCodigoDeLogin(String projeto, String codigo) throws ErroApi {
        rodar(projeto, a -> {
            a.enviarCodigoDeLogin(projeto, codigo);
            return null;
        });
    }

    /** Define (1 a 100) ou remove ({@code null}) o teto do projeto. */
    public RespostaLimite definirLimite(String projeto, Integer tetoPct) throws ErroApi {
        return rodar(projeto, a -> a.definirLimite(projeto, tetoPct));
    }

    /** Tira a pausa e reabre a sessão. 409 com números = "ainda acima do limite". */
    public RespostaRetomar retomar(String projeto, boolean liberar, boolean reabrir) throws ErroApi {
        return rodar(projeto, a -> a.retomar(projeto, liberar, reabrir));
    }

    /**
     * {@code docker restart} de UM container do projeto. Ao terminar (com sucesso ou não), consulta de novo o detalhe do projeto
     * e a lista, para a tabela mostrar o estado de verdade.
     */
    public void reiniciarContainer(String projeto, String container) throws ErroApi {
        if (locais != null) {
            rodarLocal(projeto, () -> {
                locais.reiniciarContainer(projeto, container);
                return Boolean.TRUE;
            });
            return;
        }
        rodar(projeto, a -> {
            a.reiniciarContainer(projeto, container);
            return null;
        });
    }

    /** Escreve uma mensagem na sessão do Claude e dá Enter; em seguida pede a tela de novo (o usuário quer ver o efeito). */
    public void enviarMensagem(String projeto, String texto) throws ErroApi {
        enviarParaATela(projeto, a -> a.enviarMensagem(projeto, texto));
    }

    /** Envia uma tecla (Enter, Escape...) à sessão do Claude e pede a tela de novo. */
    public void enviarTecla(String projeto, String tecla) throws ErroApi {
        enviarParaATela(projeto, a -> a.enviarTecla(projeto, tecla));
    }

    private void enviarParaATela(String projeto, ChamadaSemResultado chamada) throws ErroApi {
        ClienteApi a = api.get();
        if (a == null) {
            throw new ErroRede("Sem conexão com o servidor.", null);
        }
        try {
            chamada.executar(a);
        } finally {
            sondador.atualizarAgora(Topico.tela(projeto));
        }
    }

    @FunctionalInterface
    private interface ChamadaSemResultado {
        void executar(ClienteApi api) throws ErroApi;
    }

    // ---------------------------------------------------------------------------------------------
    // Leituras avulsas (sob demanda, sem tópico): bloqueiam, chame de thread de fundo
    // ---------------------------------------------------------------------------------------------

    /** Os logs de um container, uma vez (abrir a aba, Atualizar, trocar de container). */
    public String logs(String projeto, String container, int linhas) throws ErroApi {
        return ler(a -> a.logs(projeto, container, linhas));
    }

    /** A tela do Claude, uma vez. */
    public TelaClaude tela(String projeto) throws ErroApi {
        return ler(a -> a.telaDoClaude(projeto));
    }

    /** O consumo do projeto, uma vez. */
    public TokensDoProjeto consumo(String projeto) throws ErroApi {
        return ler(a -> a.tokensDoProjeto(projeto));
    }

    private <T> T ler(Chamada<T> chamada) throws ErroApi {
        ClienteApi a = api.get();
        if (a == null) {
            throw new ErroRede("Sem conexão com o servidor.", null);
        }
        return chamada.executar(a);
    }
}
