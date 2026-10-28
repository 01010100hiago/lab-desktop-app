package br.com.labdesktop.projetos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lê o {@code docker-compose.yml} de um projeto só o bastante para saber se o app pode completá-lo sozinho, e faz o acréscimo TEXTUAL de um
 * {@link BlocoDeServico}. Regra: o compose dele nunca é reescrito. Por isso só há dois casos:
 * <ul>
 *   <li>{@link Situacao#ESQUELETO}: o arquivo só tem {@code name:}, comentários e {@code services: {}} (o que o app e o agente escrevem ao criar
 *       um projeto): a linha {@code services: {}} vira {@code services:} mais o serviço, os volumes entram no fim, e tudo o mais (comentários,
 *       {@code name:}) fica como estava;</li>
 *   <li>{@link Situacao#COMPLEXO}: qualquer outra coisa (já tem serviços, redes, {@code volumes:}, âncoras, CRLF, tabulação): o app NÃO escreve, só
 *       mostra o bloco para o usuário copiar.</li>
 * </ul>
 * Um projeto sem compose ({@link Situacao#AUSENTE}) é completado com o esqueleto do {@link GabaritoDoProjeto}. Sem biblioteca de YAML de propósito:
 * uma biblioteca que carrega e reescreve o arquivo perderia comentários e a ordem do usuário.
 */
public final class ComposeDoProjeto {

    /** O que o app sabe fazer com o arquivo. */
    public enum Situacao { AUSENTE, ESQUELETO, COMPLEXO }

    private static final Pattern NOME = Pattern.compile("^name:[ \\t]*\\S.*$");
    private static final Pattern SERVICOS = Pattern.compile("^services:[ \\t]*(\\{[ \\t]*\\})?[ \\t]*(#.*)?$");

    private ComposeDoProjeto() { }

    /** A situação do arquivo ({@code null}: ele não existe). */
    public static Situacao situacaoDe(String texto) {
        if (texto == null) {
            return Situacao.AUSENTE;
        }
        if (texto.indexOf('\r') >= 0 || texto.indexOf('\t') >= 0 || texto.indexOf('\0') >= 0) {
            return Situacao.COMPLEXO;
        }
        int servicos = 0;
        int nomes = 0;
        for (String linha : texto.split("\n", -1)) {
            String t = linha.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (linha.charAt(0) == ' ') {
                return Situacao.COMPLEXO; // algo recuado: já há conteúdo sob alguma chave
            }
            if (SERVICOS.matcher(linha).matches()) {
                servicos++;
            } else if (NOME.matcher(linha).matches()) {
                nomes++;
            } else {
                return Situacao.COMPLEXO;
            }
        }
        return servicos == 1 && nomes <= 1 ? Situacao.ESQUELETO : Situacao.COMPLEXO;
    }

    /**
     * O arquivo com o bloco acrescentado, ou vazio se o app não pode escrever neste arquivo ({@link Situacao#COMPLEXO}).
     *
     * @param original o texto de hoje, ou {@code null} se o arquivo não existe
     * @param nomeDoProjeto usado só quando o arquivo não existe (o {@code name:} do esqueleto)
     */
    public static Optional<String> acrescentar(String original, String nomeDoProjeto, BlocoDeServico bloco) {
        Situacao s = situacaoDe(original);
        if (s == Situacao.COMPLEXO) {
            return Optional.empty();
        }
        String base = s == Situacao.AUSENTE ? GabaritoDoProjeto.composeVazio(nomeDoProjeto) : original;
        List<String> saida = new ArrayList<>();
        for (String linha : base.split("\n", -1)) {
            if (SERVICOS.matcher(linha).matches()) {
                saida.add("services:");
                saida.addAll(bloco.linhasDoServico());
            } else {
                saida.add(linha);
            }
        }
        // o texto termina em "\n": o split deixa um último item vazio, que fica por último
        if (!saida.isEmpty() && saida.get(saida.size() - 1).isEmpty()) {
            saida.remove(saida.size() - 1);
        }
        if (bloco.temVolumes()) {
            saida.add("");
            saida.add("volumes:");
            saida.addAll(bloco.linhasDeVolumes());
        }
        return Optional.of(String.join("\n", saida) + "\n");
    }
}
