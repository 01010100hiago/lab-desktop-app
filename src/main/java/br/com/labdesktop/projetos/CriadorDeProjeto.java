package br.com.labdesktop.projetos;

import java.util.List;
import java.util.Optional;

/**
 * Cria um projeto do zero. Duas implementações, e a tela não sabe qual usa: {@link CriadorPeloAgente} (um servidor com o agente de perfil
 * {@code lab}: {@code POST /api/projetos/novo}) e {@link CriadorNoAmbiente} (o computador local, que não tem agente de projetos: escreve a pasta
 * e os mesmos arquivos do agente). Tudo bloqueia: thread de fundo, nunca a EDT. NUNCA repete sozinho um pedido (o resultado de uma falha
 * de rede é incerto): ver {@link #existe}.
 */
public interface CriadorDeProjeto {

    /** O que deu certo: o nome criado e os avisos (o que o usuário deve saber, sem ser erro). */
    record Resultado(String nome, List<String> avisos) {
        public Resultado {
            avisos = List.copyOf(avisos);
        }
    }

    /**
     * Cria o projeto {@code nome} (já conferido por {@link NomeDoProjeto}).
     *
     * @param guiaIa {@code true} ou {@code false} quando o usuário escolheu na caixa "Incluir guia para o Claude"; {@code null} quando a caixa não
     *               foi oferecida (o servidor decide o de sempre)
     * @throws FalhaDoProjeto o motivo, em frase para o usuário; {@link FalhaDoProjeto.Tipo#INCERTO} quer dizer "pode ter sido criado"
     */
    Resultado criar(String nome, Boolean guiaIa) throws FalhaDoProjeto;

    /**
     * Relê o servidor: o projeto existe? Vazio quando não deu para saber (sem resposta de novo). É o que a tela usa depois de um prazo estourado,
     * em vez de repetir o pedido.
     */
    Optional<Boolean> existe(String nome);
}
