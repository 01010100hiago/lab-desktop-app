package br.com.labdesktop.git;

import java.util.List;

/**
 * Tudo o que a página do projeto LÊ do git de um projeto: a linha "Código" da Visão geral e a aba Git. SÓ leitura (a escrita mora em {@link OperacoesDeGit}). Cada método bloqueia (rede ou
 * processo): thread de fundo, nunca a EDT. Nunca lança por causa do git: a falha vem explicada em {@link ResultadoDoGit.Falhou}.
 */
public interface LeitorDeGit {

    /** O git responde? ({@code git version 2.43.0}) */
    ResultadoDoGit<String> versao() throws InterruptedException;

    /** Em que situação a pasta está (sem Git, com Git, Git ausente, dono diferente...), com branch, último commit e alterações. */
    ResultadoDoGit<ResumoDoRepositorio> resumo() throws InterruptedException;

    /** Uma fatia do log a partir de um branch ({@code null}: a cabeça atual): {@code quantos} commits depois de pular {@code pular}. */
    ResultadoDoGit<PaginaDeCommits> commits(String branch, int pular, int quantos) throws InterruptedException;

    /** O cabeçalho (mensagem inteira) e os arquivos de um commit. */
    ResultadoDoGit<DetalheDoCommit> detalhe(String hash) throws InterruptedException;

    /** O diff de um arquivo de um commit. */
    ResultadoDoGit<DiffDoArquivo> diffDoCommit(String hash, ArquivoDoCommit arquivo) throws InterruptedException;

    /** Os branches locais. */
    ResultadoDoGit<List<Branch>> branches() throws InterruptedException;

    /** O {@code status} inteiro: branch e as alterações ainda não commitadas. */
    ResultadoDoGit<StatusDoGit> status() throws InterruptedException;

    /** O diff de uma alteração ainda não commitada ({@code noIndice}: o que já está na área de preparo; senão o que ainda não está). */
    ResultadoDoGit<DiffDoArquivo> diffPendente(Pendencia pendencia, boolean noIndice) throws InterruptedException;
}
