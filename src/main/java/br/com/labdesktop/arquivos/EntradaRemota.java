package br.com.labdesktop.arquivos;

import java.time.Instant;
import java.util.Locale;

/**
 * Um item de uma pasta do servidor, como a tela o desenha. Imutável: a tela recebe listas destes e atualiza por diferença.
 *
 * @param nome            o nome, sem a pasta (texto do servidor: nunca HTML; a tela o limpa para exibir)
 * @param caminho         o caminho completo, canônico
 * @param tipo            pasta, arquivo, link ou outro (socket, dispositivo)
 * @param link            é um link simbólico (o {@code tipo} diz para o que ele aponta)
 * @param tamanho         bytes ({@code -1} se o servidor não disse)
 * @param modificadoEm    quando mudou pela última vez; {@code null} se o servidor não disse
 * @param permissoes      os 12 bits de permissão ({@code 0755}); {@code -1} se o servidor não disse
 * @param dono            o usuário dono ({@code ""} se não disse)
 * @param grupo           o grupo ({@code ""} se não disse)
 * @param destinoDoLink   para onde o link aponta, em texto ({@code null} se não é link ou não deu para ler)
 */
public record EntradaRemota(String nome, String caminho, Tipo tipo, boolean link, long tamanho, Instant modificadoEm,
                            int permissoes, String dono, String grupo, String destinoDoLink) {

    /** O que o item é, já vendo através do link. */
    public enum Tipo {
        PASTA, ARQUIVO, OUTRO,
        /** Link que não leva a lugar nenhum. */
        LINK_QUEBRADO
    }

    public EntradaRemota {
        dono = dono == null ? "" : dono;
        grupo = grupo == null ? "" : grupo;
    }

    public boolean ehPasta() {
        return tipo == Tipo.PASTA;
    }

    public boolean ehArquivo() {
        return tipo == Tipo.ARQUIVO;
    }

    /** Escondido pela regra do Unix: o nome começa com ponto. */
    public boolean oculto() {
        return nome.startsWith(".");
    }

    /** A extensão em minúsculas, sem o ponto ({@code ""} se não tem). */
    public String extensao() {
        int i = nome.lastIndexOf('.');
        return i <= 0 || i == nome.length() - 1 ? "" : nome.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Copia com o tipo resolvido (o link que a listagem descobriu apontar para pasta, por exemplo). */
    public EntradaRemota comTipo(Tipo novo, String destino) {
        return new EntradaRemota(nome, caminho, novo, link, tamanho, modificadoEm, permissoes, dono, grupo, destino);
    }
}
