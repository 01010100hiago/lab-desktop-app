package br.com.labdesktop.servidor;

import br.com.labdesktop.api.ClienteApi;
import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.modelo.RespostaLimite;
import br.com.labdesktop.api.modelo.RespostaRetomar;
import br.com.labdesktop.api.modelo.ResultadoAcao;
import br.com.labdesktop.api.modelo.SessaoAberta;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
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

    AcoesDoServidor(Supplier<ClienteApi> api, Sondador sondador) {
        this.api = api;
        this.sondador = sondador;
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

    public ResultadoAcao iniciar(String projeto) throws ErroApi {
        return rodar(projeto, a -> a.iniciar(projeto));
    }

    public ResultadoAcao parar(String projeto) throws ErroApi {
        return rodar(projeto, a -> a.parar(projeto));
    }

    public ResultadoAcao reiniciar(String projeto) throws ErroApi {
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
