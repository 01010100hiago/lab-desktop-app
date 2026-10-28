package br.com.labdesktop.local;

import java.util.List;

/**
 * O que o app PROPÕE para instalar um requisito que falta: a explicação, os comandos EXATOS (que o app roda só depois do clique e da tela de
 * confirmação), ou uma página oficial para abrir e passos para o usuário seguir. É só um retrato: criar um plano nunca roda nada.
 *
 * @param id                  identificador estável ({@code docker-brew}, {@code docker-winget}, {@code docker-pagina}...)
 * @param titulo              o que o botão diz ("Instalar o Docker Desktop")
 * @param resumo              uma frase do que acontece
 * @param comandos            os comandos que o app roda, um por item, cada um uma lista de argumentos (vazio = o app não roda nada)
 * @param paginaParaAbrir     página oficial para abrir no navegador, ou {@code null}
 * @param passosManuais       o que o usuário faz depois (ou no lugar), em frases
 * @param comandoParaCopiar   um comando que o usuário (ou o administrador) roda por conta própria, ou {@code null}
 * @param pedeSenhaDoSistema  o sistema pode pedir a senha de administrador no meio (é do sistema; o app nunca a vê)
 * @param precisaReiniciar    pode ser preciso reiniciar o computador
 * @param recomendado         é a opção que o app recomenda (a primeira)
 */
public record PlanoDeInstalacao(String id, String titulo, String resumo, List<List<String>> comandos, String paginaParaAbrir,
                                List<String> passosManuais, String comandoParaCopiar, boolean pedeSenhaDoSistema, boolean precisaReiniciar,
                                boolean recomendado) {

    public PlanoDeInstalacao {
        comandos = comandos.stream().map(List::copyOf).toList();
        passosManuais = List.copyOf(passosManuais);
    }

    /** O app consegue rodar este plano sozinho (depois do clique)? Senão é só orientação. */
    public boolean executavelPeloApp() {
        return !comandos.isEmpty();
    }

    /** Cada comando como texto para a tela (os argumentos com espaço entre aspas). Só para MOSTRAR: nunca é executado como texto. */
    public List<String> comandosParaTela() {
        return comandos.stream().map(PlanoDeInstalacao::comoTexto).toList();
    }

    public static String comoTexto(List<String> argumentos) {
        StringBuilder sb = new StringBuilder();
        for (String a : argumentos) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a.isEmpty() || a.contains(" ") ? "\"" + a + "\"" : a);
        }
        return sb.toString();
    }
}
