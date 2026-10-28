package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.Navegador;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * O que as abas de UM projeto compartilham: quem são (servidor e projeto), com o que agem ({@link FluxosDeProjeto},
 * navegador, avisos, área de transferência) e o último retrato que a {@link TelaProjeto} recebeu (estado da conexão e
 * detalhe). Só a EDT mexe aqui: a tela troca os campos e manda cada aba se redesenhar; as abas só LEEM. Uma instância por
 * página de projeto aberta.
 */
final class ContextoDoProjeto {

    final ServidorLab servidor;
    final String projeto;
    final FluxosDeProjeto fluxos;
    final Navegador navegador;
    final Notificacoes avisos;
    final CopiadorDeTexto copiador;
    /** O endereço de login do Claude, se a lista de projetos (que o traz) já o viu; o detalhe não traz. */
    final Supplier<String> urlDeLogin;
    /** Abre outra aba da página (um indicador, um botão que leva a outro assunto). */
    final Consumer<TelaProjeto.Aba> navegar;
    final Runnable aoAtualizar;
    /** Por onde abrir terminais e quantos ainda cabem no servidor; {@code null} onde não há servidor vivo (testes de tela). */
    ContextoDeTerminal terminal;
    /** A pasta do projeto no servidor (para o shell abrir nela), ou {@code null} se não der para montar; nunca bloqueia. */
    Supplier<String> pastaDoProjeto = () -> null;
    /** Abre a aba Terminal já na sessão do Claude. */
    Runnable irParaOClaude = () -> { };
    /** O que o botão "Adicionar container" faz (abre o diálogo; quem sabe montá-lo é a janela). */
    Runnable adicionarContainer = () -> { };
    /** O servidor sabe escrever no compose deste projeto (o agente do lab, ou este computador) e as ações estão liberadas? */
    Supplier<Boolean> podeAdicionarContainer = () -> false;

    EstadoServidor estado;
    DetalheProjeto detalhe;
    /** O erro da primeira leitura (que não é de rede), enquanto ainda não há detalhe nenhum para mostrar. */
    ErroApi erroSemDados;
    Instant ultimaLeitura;
    Supplier<Instant> relogio = Instant::now;
    ZoneId fuso = ZoneId.systemDefault();

    ContextoDoProjeto(ServidorLab servidor, String projeto, FluxosDeProjeto fluxos, Navegador navegador, Notificacoes avisos,
                      CopiadorDeTexto copiador, Supplier<String> urlDeLogin, Consumer<TelaProjeto.Aba> navegar,
                      Runnable aoAtualizar) {
        this.servidor = servidor;
        this.projeto = projeto;
        this.fluxos = fluxos;
        this.navegador = navegador;
        this.avisos = avisos;
        this.copiador = copiador;
        this.urlDeLogin = urlDeLogin;
        this.navegar = navegar;
        this.aoAtualizar = aoAtualizar;
    }

    /** O detalhe já tratado, ou {@code null} antes da primeira leitura. */
    ModeloDoProjeto modelo() {
        return detalhe == null ? null : ModeloDoProjeto.de(detalhe, estado == null ? null : estado.temClaude());
    }

    /**
     * O servidor tem sessões do Claude: o que o agente diz em {@code recursos.claude} de {@code /api/versao}; sem isso (agente antigo), a
     * presença do objeto {@code sessao} no detalhe. Desconhecido (sem recursos e sem detalhe) vale como NÃO: o app mostra só o shell, que
     * é SSH puro e nunca depende de saber.
     */
    boolean temClaude() {
        Boolean doAgente = estado == null ? null : estado.temClaude(); // recursos.claude de /api/versao, quando o agente diz
        if (doAgente != null) {
            return doAgente;
        }
        return detalhe != null && detalhe.sessao() != null; // agente antigo: como antes, pela presença da sessão no detalhe
    }

    Instant agora() {
        return relogio.get();
    }

    /** As ações de escrita estão ligadas (padrão; -Dlabdesktop.acoes=false desliga): senão a página só lê. */
    boolean acoesLiberadas() {
        return fluxos != null && fluxos.liberadas();
    }
}
