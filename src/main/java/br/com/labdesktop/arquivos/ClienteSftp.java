package br.com.labdesktop.arquivos;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * O que o gerenciador de arquivos pede ao servidor, por SFTP. É uma interface para a tela e o domínio não conhecerem o
 * MINA (e para os testes poderem trocar o servidor). TODO método bloqueia (rede): chame de thread de fundo, nunca da EDT.
 * Todo erro sai como {@link ErroSftp}, já com a frase para o usuário.
 *
 * <p>Os caminhos aqui já foram validados por quem chama (o {@link ServicoDeArquivos} confere o {@link Escopo}); a
 * implementação ainda recusa caractere de controle, porque esta é a última barreira antes da rede.
 */
public interface ClienteSftp extends AutoCloseable {

    /**
     * Lê uma pasta: guarda até {@code limite} itens (sem {@code .} e {@code ..}) e conta os demais. Links ganham o tipo do
     * destino ({@link EntradaRemota.Tipo}).
     *
     * @param nomeAceito só entram (e só contam) os itens cujo nome passa; {@code n -> true} lista tudo
     */
    ListagemDePasta listar(String caminho, int limite, Predicate<String> nomeAceito) throws ErroSftp;

    default ListagemDePasta listar(String caminho, int limite) throws ErroSftp {
        return listar(caminho, limite, n -> true);
    }

    /** Os dados de UM item, sem seguir o link (um link é um link; o tipo mostra para onde ele leva). */
    EntradaRemota estatisticas(String caminho) throws ErroSftp;

    /** {@code true} se há algo neste caminho (um link quebrado conta: ele existe). */
    boolean existe(String caminho) throws ErroSftp;

    /** Lê até {@code limite} bytes do começo de um arquivo (segue links). */
    byte[] lerArquivo(String caminho, int limite) throws ErroSftp;

    /**
     * Troca o conteúdo de um arquivo de uma vez: grava num temporário na MESMA pasta, ajusta as permissões e renomeia por cima.
     * Quem lê o arquivo vê o antigo inteiro ou o novo inteiro, nunca metade. Recusa ({@code OUTRO_DONO}) se o arquivo é de outro
     * usuário (o novo seria do usuário do app) e deixa {@code SEM_PERMISSAO} se a pasta não é gravável.
     *
     * @param permissoes os 12 bits que o arquivo novo leva ({@code 0644}); {@code -1} usa o padrão do servidor (arquivo novo)
     */
    void gravarAtomico(String caminho, byte[] conteudo, int permissoes) throws ErroSftp;

    /**
     * Envia UM arquivo local. Grava num temporário, confere o tamanho e renomeia por cima do destino (se já existe, a decisão
     * de substituir foi de quem chama); cancelar apaga o temporário e deixa o destino como estava.
     */
    void enviar(Path local, String remoto, Progresso progresso, Cancelamento cancelamento) throws ErroSftp;

    /** Baixa UM arquivo. Grava num parcial ao lado do destino e só o põe no lugar com o tamanho conferido. */
    void baixar(String remoto, Path local, Progresso progresso, Cancelamento cancelamento) throws ErroSftp;

    /** Cria uma pasta; {@code JA_EXISTE} se já há algo com o nome. */
    void criarPasta(String caminho) throws ErroSftp;

    /** Cria um arquivo vazio; {@code JA_EXISTE} se já há algo com o nome (nunca zera um arquivo existente). */
    void criarArquivoVazio(String caminho) throws ErroSftp;

    /** Renomeia ou move (o mesmo pedido ao SFTP). {@code JA_EXISTE} se o destino existe: nunca sobrescreve. */
    void renomear(String de, String para) throws ErroSftp;

    /**
     * Apaga. Um link simbólico é apagado como link (o destino fica); uma pasta só com {@code recursivo}, e dentro dela nenhum
     * link é seguido.
     */
    void apagar(String caminho, boolean recursivo) throws ErroSftp;

    /** Conta o que há dentro (sem seguir links), até um teto de itens; para a confirmação de apagar. */
    ResumoDeTamanho resumir(String caminho, Cancelamento cancelamento) throws ErroSftp;

    /** Para onde um link aponta, em texto. */
    String lerLink(String caminho) throws ErroSftp;

    /** O caminho como o servidor o resolve ({@code .} é a pasta pessoal do usuário). */
    String caminhoCanonico(String caminho) throws ErroSftp;

    /** Fecha o canal. Idempotente. */
    @Override
    void close();
}
