package br.com.labdesktop.registro;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a saída dos comandos fixos do registro e a transforma em {@link LinhaDeRegistro}. PURO (sem rede, sem Swing; o relógio e o fuso entram por parâmetro). A saída é um texto que
 * VEM DO SERVIDOR e por isso não é confiável: cada linha passa por {@link TextoSeguro} (controles e bidi saem, um {@code <html} é neutralizado) e pelo {@link Redator} (nenhum token
 * de acesso aparece), é cortada em {@value #LIMITE_DA_LINHA} caracteres, e o resultado é só texto para um componente que não interpreta HTML.
 *
 * <p>Entende três formatos: {@code journalctl -o short-iso} (e o syslog moderno com data ISO), o syslog tradicional ({@code Oct 28 14:10:01 host proc[1]: ...}, sem ano nem fuso: lido no
 * fuso do computador do usuário, com o ano de {@code agora}) e o {@code docker logs --timestamps} (UTC, sem programa: a origem é o nome do container). Linhas de cabeçalho do journal
 * ({@code -- No entries --}, {@code -- Boot ... --}) saem. Uma linha que não traz hora (continuação) entra sem hora, para nenhuma informação sumir.
 */
public final class InterpretadorDeRegistro {

    /** O tamanho máximo de uma linha (o resto vira reticências). */
    public static final int LIMITE_DA_LINHA = 2000;
    /** O máximo de linhas que uma leitura devolve (o diário pede 200; o {@code tail -c} dos arquivos pode trazer mais; isto é a cerca de dentro, e o que fica são as mais novas). */
    public static final int LIMITE_DE_LINHAS = 500;

    private static final Pattern ISO = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2}))\\s+(\\S+)\\s+(.*)$");
    private static final Pattern TRADICIONAL = Pattern.compile("^([A-Z][a-z]{2})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s+(\\S+)\\s+(.*)$");
    private static final Pattern DOCKER = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)\\s(.*)$");
    private static final Pattern PROGRAMA = Pattern.compile("^([^\\s:\\[\\]]{1,64})(?:\\[\\d+\\])?:\\s?(.*)$");
    private static final Map<String, Integer> MESES = Map.ofEntries(Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
        Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private InterpretadorDeRegistro() { }

    /** O retrato de uma leitura: as linhas ORDENADAS por hora (a MAIS ANTIGA primeiro), se havia mais do que o limite e se alguma hora veio SEM fuso (syslog tradicional: lida no fuso deste computador). */
    public record Leitura(List<LinhaDeRegistro> linhas, boolean cortada, boolean horaSemFuso) {

        public Leitura(List<LinhaDeRegistro> linhas, boolean cortada) {
            this(linhas, cortada, false);
        }
    }

    /**
     * A saída de {@code journalctl -r -n 200 --no-pager -o short-iso} (mais nova primeiro) ou de um {@code tail -c} de {@code /var/log/syslog} (mais antiga primeiro), de qualquer um dos dois
     * jeitos de escrever a data. A ordem do servidor não importa: as linhas saem ordenadas por hora, e passando de {@value #LIMITE_DE_LINHAS} ficam as MAIS NOVAS ({@code cortada}).
     */
    public static Leitura doSistema(String saida, Instant agora, ZoneId fuso) {
        List<LinhaDeRegistro> l = new ArrayList<>();
        boolean semFuso = false;
        for (String bruta : linhas(saida)) {
            String t = bruta.strip();
            if (t.isEmpty() || t.startsWith("-- ")) {
                continue; // "-- No entries --", "-- Boot 0f1c... --", "-- Journal begins at ... --"
            }
            LinhaDeRegistro linha = iso(bruta);
            if (linha == null) {
                linha = tradicional(bruta, agora, fuso);
                semFuso |= linha != null;
            }
            l.add(linha != null ? linha : semHora(bruta));
        }
        ordenarPorHora(l);
        boolean cortada = false;
        if (l.size() > LIMITE_DE_LINHAS) {
            l = new ArrayList<>(l.subList(l.size() - LIMITE_DE_LINHAS, l.size())); // fica o FIM: as mais novas
            cortada = true;
        }
        return new Leitura(List.copyOf(l), cortada, semFuso);
    }

    /** Tira a última linha se a saída foi cortada no teto (ela pode estar pela metade). Sem corte, devolve igual. */
    public static String semUltimaLinhaCortada(String saida, boolean truncada) {
        if (!truncada || saida == null) {
            return saida;
        }
        int i = Math.max(saida.lastIndexOf('\n'), saida.lastIndexOf('\r'));
        return i < 0 ? "" : saida.substring(0, i);
    }

    /** Tira a primeira linha (a de um {@code tail -c} costuma começar no meio dela). */
    public static String semPrimeiraLinhaCortada(String saida) {
        if (saida == null) {
            return null;
        }
        int i = saida.indexOf('\n');
        return i < 0 ? "" : saida.substring(i + 1);
    }

    /** A saída de {@code docker logs --tail 200 --timestamps <container>}: {@code AAAA-MM-DDTHH:MM:SS.nnnnnnnnnZ mensagem}; a origem é o nome do container. */
    public static Leitura doDocker(String saida, String container) {
        List<LinhaDeRegistro> l = new ArrayList<>();
        for (String bruta : linhas(saida)) {
            if (bruta.isBlank()) {
                continue;
            }
            Matcher m = DOCKER.matcher(bruta);
            if (m.matches()) {
                Instant hora = instante(m.group(1));
                l.add(montar(hora, container, m.group(2)));
            } else {
                l.add(montar(null, container, bruta));
            }
        }
        ordenarPorHora(l);
        boolean cortada = false;
        if (l.size() > LIMITE_DE_LINHAS) {
            l = new ArrayList<>(l.subList(l.size() - LIMITE_DE_LINHAS, l.size()));
            cortada = true;
        }
        return new Leitura(List.copyOf(l), cortada);
    }

    /** A MESMA leitura, com as linhas da MAIS NOVA para a mais antiga (a ordem que a tela mostra: mais novas em cima). */
    public static List<LinhaDeRegistro> maisNovasEmCima(List<LinhaDeRegistro> maisAntigaPrimeiro) {
        List<LinhaDeRegistro> r = new ArrayList<>(maisAntigaPrimeiro);
        java.util.Collections.reverse(r);
        return List.copyOf(r);
    }

    // ---------------------------------------------------------------------------------------------

    private static List<String> linhas(String saida) {
        if (saida == null || saida.isEmpty()) {
            return List.of();
        }
        return List.of(saida.split("\\R", -1));
    }

    private static LinhaDeRegistro iso(String bruta) {
        Matcher m = ISO.matcher(bruta);
        if (!m.matches()) {
            return null;
        }
        Instant hora = instante(m.group(1));
        if (hora == null) {
            return null;
        }
        return comProgramaEMensagem(hora, m.group(3));
    }

    private static LinhaDeRegistro tradicional(String bruta, Instant agora, ZoneId fuso) {
        Matcher m = TRADICIONAL.matcher(bruta);
        if (!m.matches()) {
            return null;
        }
        Integer mes = MESES.get(m.group(1).toLowerCase(Locale.ROOT));
        if (mes == null) {
            return null;
        }
        try {
            int ano = agora.atZone(fuso).getYear();
            LocalDateTime t = LocalDateTime.of(ano, mes, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
            Instant hora = t.atZone(fuso).toInstant();
            if (hora.isAfter(agora.plusSeconds(86_400))) {
                hora = t.minusYears(1).atZone(fuso).toInstant(); // "Dec 31" lido em janeiro é do ano passado
            }
            return comProgramaEMensagem(hora, m.group(7));
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return null; // "Feb 30" e afins: não é hora
        }
    }

    private static LinhaDeRegistro comProgramaEMensagem(Instant hora, String resto) {
        Matcher p = PROGRAMA.matcher(resto);
        if (p.matches()) {
            return montar(hora, p.group(1), p.group(2));
        }
        return montar(hora, "", resto);
    }

    private static LinhaDeRegistro semHora(String bruta) {
        return montar(null, "", bruta);
    }

    private static LinhaDeRegistro montar(Instant hora, String origem, String mensagem) {
        String o = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(origem)), 64);
        String m = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(mensagem)), LIMITE_DA_LINHA);
        return new LinhaDeRegistro(hora, o, m, ClassificadorDeSeveridade.de(m));
    }

    private static Instant instante(String texto) {
        try {
            return OffsetDateTime.parse(normalizar(texto)).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code -0300} vira {@code -03:00}, {@code ,123} vira {@code .123}: o que o {@code OffsetDateTime} aceita. */
    private static String normalizar(String t) {
        String s = t.replace(',', '.');
        Matcher m = Pattern.compile("([+-])(\\d{2})(\\d{2})$").matcher(s);
        return m.find() ? s.substring(0, m.start()) + m.group(1) + m.group(2) + ":" + m.group(3) : s;
    }

    /** Ordem de tempo estável; as linhas sem hora ficam junto da anterior (não saltam para o começo). */
    private static void ordenarPorHora(List<LinhaDeRegistro> l) {
        boolean desordenada = false;
        Instant anterior = null;
        for (LinhaDeRegistro x : l) {
            if (x.hora() != null) {
                if (anterior != null && x.hora().isBefore(anterior)) {
                    desordenada = true;
                    break;
                }
                anterior = x.hora();
            }
        }
        if (!desordenada) {
            return;
        }
        // relógio fora de ordem (ou as duas saídas do docker misturadas): ordena só as que têm hora, estável; as sem hora acompanham a de cima
        List<List<LinhaDeRegistro>> grupos = new ArrayList<>();
        for (LinhaDeRegistro x : l) {
            if (x.hora() != null || grupos.isEmpty()) {
                grupos.add(new ArrayList<>(List.of(x)));
            } else {
                grupos.get(grupos.size() - 1).add(x);
            }
        }
        grupos.sort(Comparator.comparing((List<LinhaDeRegistro> g) -> g.get(0).hora() == null ? Instant.MIN : g.get(0).hora()));
        l.clear();
        grupos.forEach(l::addAll);
    }
}
