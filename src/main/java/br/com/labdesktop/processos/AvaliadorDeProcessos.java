package br.com.labdesktop.processos;

import java.util.Optional;

/**
 * Decide, SEM rede e sem tocar em nada, o que se oferece para um processo da lista. É a regra que fecha os botões:
 * <ol>
 *   <li>protegido ({@link ListaDeProtecao}) nunca se oferece;</li>
 *   <li>processo de container: age-se no container ({@code docker stop} e {@code docker restart}), com Reiniciar;</li>
 *   <li>processo comum do mesmo usuário: só Encerrar (um sinal), e a tela diz por que não há Reiniciar;</li>
 *   <li>processo de outro usuário ou do administrador: "sem permissão", com o comando exato para o usuário rodar sozinho. O app NUNCA usa
 *       {@code sudo} nem digita senha.</li>
 * </ol>
 */
public final class AvaliadorDeProcessos {

    /** Por que um processo comum não tem Reiniciar. */
    public static final String SEM_REINICIAR = "Reiniciar só existe para containers e serviços: de um processo comum o app não sabe como religá-lo "
        + "(quem o abriu, um serviço ou um terminal, é quem sabe). Encerrar o fecha; para voltar, abra-o de novo do jeito de sempre.";

    /** Por que o à força é recusado sem o pedido educado antes. */
    public static final String FORCAR_SO_DEPOIS = "Primeiro peça ao programa para fechar (Encerrar). O à força só vale depois disso, para ele ter a chance de salvar o que está fazendo.";

    private AvaliadorDeProcessos() { }

    public static Oferta avaliar(ProcessoAlvo alvo, AmbienteDeProcessos ambiente) {
        Optional<String> protecao = ListaDeProtecao.motivo(alvo, ambiente);
        if (protecao.isPresent()) {
            return new Oferta(Oferta.Situacao.PROTEGIDO, null, false, protecao.get(), "", "", "");
        }
        if (alvo.ehDeContainer()) {
            String c = alvo.container();
            String onde = alvo.projeto() == null ? "" : " do projeto " + alvo.projeto();
            return new Oferta(Oferta.Situacao.LIBERADO, Via.CONTAINER, true, "",
                "Este programa roda dentro do container " + c + onde + ". O app age no container inteiro, não neste programa sozinho: Encerrar roda docker stop "
                    + "(o container e tudo o que roda nele param) e Reiniciar roda docker restart (para e sobe de novo).",
                "", "");
        }
        boolean windows = ambiente.tipo() == AmbienteDeProcessos.Tipo.COMPUTADOR_WINDOWS;
        boolean mesmo = ambiente.ehDoMesmoUsuario(alvo.usuario());
        if (!mesmo) {
            String quem = alvo.usuario().isBlank() ? "outro usuário" : alvo.usuario();
            ComandoParaODono comando = ComandosDeProcessos.paraODono(alvo.pid(), alvo.nome(), Sinal.TERM, ambiente.tipo());
            return new Oferta(Oferta.Situacao.SEM_PERMISSAO, Via.PROCESSO, false, "",
                "Este processo é de " + quem + ", e o app age com o usuário " + (ambiente.usuarioDoApp().isBlank() ? "da sua conexão" : ambiente.usuarioDoApp())
                    + ". O app não usa sudo nem digita senha: "
                    + (comando.temComando() ? "se você tem permissão, rode o comando abaixo no Terminal" + (windows ? " aberto como administrador." : ".") : comando.motivoSem()),
                AvaliadorDeProcessos.SEM_REINICIAR, comando.texto());
        }
        String explicacao = windows
            ? "O app pede ao programa para fechar, como quando você clica no X da janela. Ele pode levar alguns segundos. Se não fechar, "
                + "você decide se quer forçar, que encerra o programa na hora e não deixa ele salvar nada."
            : "O app pede ao programa para fechar (" + Sinal.TERM.rotulo() + "), como um Ctrl+C. Ele pode levar alguns segundos. Se não fechar, "
                + "você decide se quer forçar (" + Sinal.KILL.rotulo() + "), que mata na hora e não deixa o programa salvar nada.";
        return new Oferta(Oferta.Situacao.LIBERADO, Via.PROCESSO, false, "", explicacao, SEM_REINICIAR, "");
    }
}
