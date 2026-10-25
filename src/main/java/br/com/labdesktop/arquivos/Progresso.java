package br.com.labdesktop.arquivos;

/** Quem recebe o andamento de um arquivo que está sendo enviado ou baixado. Chamado na thread que transfere. */
@FunctionalInterface
public interface Progresso {

    Progresso NENHUM = (feitos, total) -> { };

    /**
     * @param feitos bytes já transferidos deste arquivo
     * @param total  bytes do arquivo ({@code -1} se não se sabe)
     */
    void andamento(long feitos, long total);
}
