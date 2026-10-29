package br.com.labdesktop.git;

import java.util.List;

/**
 * Como terminou "Criar repositório Git": o que foi feito, o que NÃO foi, e (se algo ficou pela metade) o comando que o USUÁRIO roda para desfazer. O app nunca apaga o {@code .git} sozinho.
 *
 * @param criou              o repositório está pronto, com o primeiro commit
 * @param repositorioCriado  o {@code git init} chegou a rodar (a pasta tem um {@code .git} que o app fez)
 * @param mensagem           a frase para o usuário
 * @param feito              o que o app fez, em ordem
 * @param naoFeito           o que ficou sem fazer
 * @param comandoParaDesfazer o comando que o usuário pode rodar para voltar ao que era (só se algo foi criado e não terminou); senão {@code null}
 * @param arquivosNoCommit   quantos arquivos entraram no primeiro commit
 * @param hashCurto          o hash curto do primeiro commit, ou vazio
 * @param falha              a causa, quando não deu certo; senão {@code null}
 */
public record ResultadoDaCriacao(boolean criou, boolean repositorioCriado, String mensagem, List<String> feito, List<String> naoFeito, String comandoParaDesfazer, int arquivosNoCommit,
                                 String hashCurto, FalhaDoGit falha) {

    public ResultadoDaCriacao {
        feito = List.copyOf(feito);
        naoFeito = List.copyOf(naoFeito);
        hashCurto = hashCurto == null ? "" : hashCurto;
    }
}
