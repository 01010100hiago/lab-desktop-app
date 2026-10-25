package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.Container;
import br.com.labdesktop.util.Formato;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Os textos da página do projeto em palavras simples (o usuário não é técnico): o estado de um container em português, as portas
 * sem o jargão do Docker ("5440 → 5432" no lugar de "127.0.0.1:5440->5432/tcp"), "criado há 3 d", o tamanho de um arquivo.
 * Puro e sem estado. O texto original (que o servidor mandou) vai inteiro na dica, nunca se perde.
 */
public final class FormatosDoProjeto {

    /** O que se mostra quando o dado não veio. */
    public static final String SEM_DADO = Formato.SEM_DADO;

    /** {@code 127.0.0.1:5440->5432/tcp}, {@code 0.0.0.0:80->80/tcp}, {@code :::8080->8080/tcp} ou só {@code 5432/tcp}. */
    private static final Pattern PORTA = Pattern.compile("(?:[^\\s,]*:)?(\\d+)->(\\d+)/(?:tcp|udp)");

    private FormatosDoProjeto() { }

    /** O estado do Docker em português: {@code running} vira "rodando". Estado desconhecido volta como veio. */
    public static String estadoDoContainer(Container c) {
        String e = c.estado();
        if (e == null || e.isBlank()) {
            return SEM_DADO;
        }
        return switch (e) {
            case "running" -> "rodando";
            case "exited", "dead" -> "parado";
            case "paused" -> "em pausa";
            case "restarting" -> "reiniciando";
            case "created" -> "criado";
            case "removing" -> "removendo";
            default -> e;
        };
    }

    /**
     * As portas que o container abre para fora, de um jeito curto: {@code 5440 → 5432} (porta na máquina → porta no container),
     * no máximo duas e "+n". Sem porta publicada, traço. Texto que não segue o formato do Docker volta como veio.
     */
    public static String portas(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return SEM_DADO;
        }
        List<String> pares = new ArrayList<>();
        Matcher m = PORTA.matcher(bruto);
        while (m.find()) {
            String par = m.group(1).equals(m.group(2)) ? m.group(1) : m.group(1) + " → " + m.group(2);
            if (!pares.contains(par)) { // 0.0.0.0 e :: repetem a mesma porta
                pares.add(par);
            }
        }
        if (pares.isEmpty()) {
            return bruto.strip();
        }
        String texto = String.join(", ", pares.subList(0, Math.min(2, pares.size())));
        return pares.size() > 2 ? texto + " +" + (pares.size() - 2) : texto;
    }

    /** {@code há 3 d}, ou traço. */
    public static String criado(Instant criado, Instant agora) {
        return criado == null ? SEM_DADO : Formato.fmtHa(criado, agora);
    }

    /** Tamanho de arquivo ou pasta, ou traço (o agente de hoje não manda o tamanho dos itens da raiz). */
    public static String tamanho(Long bytes) {
        return bytes == null ? SEM_DADO : Formato.fmtBytes(bytes);
    }

    /** {@code pasta} e {@code arquivo} por extenso, para o usuário. */
    public static String tipoDeItem(String tipo) {
        if (tipo == null || tipo.isBlank()) {
            return SEM_DADO;
        }
        return switch (tipo) {
            case "pasta" -> "Pasta";
            case "arquivo" -> "Arquivo";
            default -> tipo;
        };
    }

    /** Tokens de saída e respostas em uma linha: {@code 135 mil de saída · 251 respostas}. */
    public static String volume(Long saida, Long respostas) {
        List<String> partes = new ArrayList<>();
        partes.add(Formato.fmtCompacto(saida == null ? 0 : saida) + " de saída");
        partes.add(Formato.fmtNum(respostas == null ? 0 : respostas, 0) + (respostas != null && respostas == 1 ? " resposta" : " respostas"));
        return String.join(" · ", partes);
    }
}
