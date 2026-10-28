package br.com.labdesktop.cloudflare;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê e edita, como TEXTO, o {@code config.yml} do cloudflared (túnel gerido por arquivo): acrescenta uma regra ANTES da regra final
 * ({@code http_status:404}) e tira uma regra que o próprio app acrescentou. Sem biblioteca de YAML, de propósito: uma biblioteca que carrega e
 * reescreve o arquivo perderia os comentários e a ordem do usuário. Só acrescenta ou apaga linhas; o resto do arquivo fica byte a byte como estava.
 *
 * <p>Casos tratados: arquivo sem a regra final (o app a acrescenta, porque o cloudflared a exige), vazio ou só de comentários, com CRLF (o fim de
 * linha é mantido), com comentários entre as regras (um comentário logo acima da regra final fica com ela), regra que já existe (idempotente se é
 * do app e igual; recusa se é de outro serviço) e recuo diferente de dois espaços (copia o que o arquivo já usa). O que o app NÃO sabe editar com
 * segurança (formato antigo com {@code url:}, regras entre chaves, tabulação, fim de linha misturado, {@code ingress} duas vezes) vira
 * {@link Problema} com a frase do porquê, e nada é escrito.
 *
 * <p>A marca do app é um comentário na linha de cima da regra: {@code # lab-desktop: <hostname> (<observação>)}. Só uma regra com a marca do
 * próprio hostname é "do app" e só ela pode ser removida por aqui.
 */
public final class ConfigYmlDoCloudflared {

    private static final Pattern INGRESS = Pattern.compile("^ingress:[ ]*(#.*)?$");
    private static final Pattern CHAVE_DE_CIMA = Pattern.compile("^[A-Za-z_\"'][^\\s]*\\s*:.*$");
    private static final Pattern TUNEL = Pattern.compile("^tunnel:\\s*(.*?)\\s*(#.*)?$");
    private static final Pattern CHAVE_DA_REGRA = Pattern.compile("^(hostname|service|path)\\s*:\\s*(.*?)\\s*$");
    private static final Pattern MARCA = Pattern.compile("^\\s*#\\s*lab-desktop:\\s*(\\S+).*$");

    /** O que o app não consegue fazer neste arquivo, e por quê. */
    public static final class Problema extends Exception {
        private static final long serialVersionUID = 1L;

        /** Os casos que a tela trata de jeitos diferentes. */
        public enum Tipo { FORMATO_NAO_SUPORTADO, REGRA_JA_EXISTE, NAO_ENCONTRADA, NAO_E_DO_APP }

        private final Tipo tipo;

        public Problema(Tipo tipo, String mensagem) {
            super(mensagem);
            this.tipo = tipo;
        }

        public Tipo tipo() {
            return tipo;
        }
    }

    /**
     * Uma regra de entrada.
     *
     * @param hostname      o endereço ({@code ""} na regra final, que casa com tudo)
     * @param servico       para onde vai ({@code http://app:3000}, {@code http_status:404})
     * @param inicio        a linha (a partir de 0) do traço da regra
     * @param fim           a linha depois da última linha da regra (sem as em branco do fim)
     * @param marcadaPeloApp tem, logo acima, a marca {@code # lab-desktop: <este hostname>}
     */
    public record Regra(String hostname, String servico, int inicio, int fim, boolean marcadaPeloApp) {
        public boolean ehFinal() {
            return hostname.isEmpty();
        }
    }

    /** O que o app enxerga do arquivo. */
    public record Analise(boolean temIngress, List<Regra> regras, boolean temRegraFinal, String tunel, boolean fimDeLinhaCrlf) {
        public Analise {
            regras = List.copyOf(regras);
        }

        public Optional<Regra> regraDe(String hostname) {
            return regras.stream().filter(r -> r.hostname().equalsIgnoreCase(hostname)).findFirst();
        }
    }

    /** O arquivo editado e o que se deve dizer ao usuário sobre a edição. */
    public record Edicao(String texto, boolean jaEstava, List<String> avisos) {
        public Edicao {
            avisos = List.copyOf(avisos);
        }
    }

    private ConfigYmlDoCloudflared() { }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    private record Texto(List<String> linhas, boolean crlf, boolean terminaEmEol) {
        String montar(List<String> novas) {
            String eol = crlf ? "\r\n" : "\n";
            return String.join(eol, novas) + (terminaEmEol ? eol : "");
        }
    }

    private static Texto repartir(String texto) throws Problema {
        boolean temCrlf = texto.contains("\r\n");
        String semCrlf = texto.replace("\r\n", "");
        if (temCrlf && (semCrlf.indexOf('\n') >= 0 || semCrlf.indexOf('\r') >= 0)) {
            throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "O config.yml mistura fins de linha (uns do Windows, outros do Linux). O app não mexe num arquivo assim "
                + "para não bagunçar o resto; deixe todas as linhas do mesmo tipo e tente de novo.");
        }
        if (!temCrlf && texto.indexOf('\r') >= 0) {
            throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "O config.yml tem fim de linha antigo (só CR). O app não mexe nele.");
        }
        String eol = temCrlf ? "\r\n" : "\n";
        boolean termina = texto.endsWith(eol);
        String corpo = termina ? texto.substring(0, texto.length() - eol.length()) : texto;
        List<String> linhas = new ArrayList<>(List.of(corpo.split(Pattern.quote(eol), -1)));
        if (texto.isEmpty()) {
            linhas = new ArrayList<>();
        }
        for (String l : linhas) {
            int i = 0;
            while (i < l.length() && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) {
                if (l.charAt(i) == '\t') {
                    throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "O config.yml tem tabulação no recuo (o YAML só aceita espaços). Corrija o arquivo e tente de novo.");
                }
                i++;
            }
        }
        return new Texto(linhas, temCrlf, termina);
    }

    private static int recuo(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean vazia(String l) {
        return l.isBlank();
    }

    private static boolean comentario(String l) {
        return l.stripLeading().startsWith("#");
    }

    private static boolean conteudo(String l) {
        return !vazia(l) && !comentario(l);
    }

    private static String semAspas(String v) {
        String t = v.strip();
        int c = t.indexOf(" #");
        if (c > 0 && !(t.startsWith("\"") || t.startsWith("'"))) {
            t = t.substring(0, c).strip();
        }
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** O bloco do {@code ingress}: a linha do {@code ingress:} e a linha DEPOIS do fim (a próxima chave de cima, ou o fim do arquivo), ou {@code null}. */
    private static int[] blocoDoIngress(Texto t) throws Problema {
        int onde = -1;
        for (int i = 0; i < t.linhas().size(); i++) {
            String l = t.linhas().get(i);
            if (recuo(l) == 0 && l.startsWith("ingress")) {
                if (INGRESS.matcher(l).matches()) {
                    if (onde >= 0) {
                        throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "O config.yml tem \"ingress:\" duas vezes. O app não mexe num arquivo assim.");
                    }
                    onde = i;
                } else if (l.matches("^ingress\\s*:.*")) {
                    throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "A seção ingress deste config.yml está escrita em uma linha só (entre colchetes ou chaves). O app só edita o formato "
                        + "de lista com um traço por regra.");
                }
            }
        }
        if (onde < 0) {
            return null;
        }
        int fim = t.linhas().size();
        for (int i = onde + 1; i < t.linhas().size(); i++) {
            String l = t.linhas().get(i);
            if (conteudo(l) && recuo(l) == 0 && !l.startsWith("-")) {
                fim = i;
                break;
            }
        }
        return new int[] {onde, fim};
    }

    private record Itens(List<Regra> regras, int recuoDosTracos) { }

    private static Itens itensDo(Texto t, int[] bloco) throws Problema {
        List<Integer> inicios = new ArrayList<>();
        int recuoDosTracos = -1;
        for (int i = bloco[0] + 1; i < bloco[1]; i++) {
            String l = t.linhas().get(i);
            if (!conteudo(l)) {
                continue;
            }
            String s = l.stripLeading();
            if (s.equals("-") || s.startsWith("- ")) {
                if (recuoDosTracos < 0) {
                    recuoDosTracos = recuo(l);
                }
                if (recuo(l) == recuoDosTracos) {
                    inicios.add(i);
                }
            }
        }
        List<Regra> regras = new ArrayList<>();
        for (int k = 0; k < inicios.size(); k++) {
            int ini = inicios.get(k);
            int limite = k + 1 < inicios.size() ? inicios.get(k + 1) : bloco[1];
            int fimDaRegra = ini + 1;
            for (int j = ini + 1; j < limite; j++) {
                String l = t.linhas().get(j);
                if (vazia(l)) {
                    continue;
                }
                if (recuo(l) <= recuoDosTracos) {
                    break; // outro traço, ou um comentário no recuo das regras: já é da próxima
                }
                fimDaRegra = j + 1;
            }
            String hostname = "";
            String servico = "";
            int colunaDasChaves = -1;
            for (int j = ini; j < fimDaRegra; j++) {
                String l = t.linhas().get(j);
                String corpo;
                if (j == ini) {
                    String resto = l.stripLeading().substring(1);
                    corpo = resto.strip();
                    if (corpo.startsWith("{")) {
                        throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "Este config.yml tem regras escritas entre chaves (uma regra por linha). O app só edita o formato "
                            + "com uma chave por linha.");
                    }
                    colunaDasChaves = corpo.isEmpty() ? -1 : recuo(l) + 1 + (resto.length() - resto.stripLeading().length());
                } else {
                    if (!conteudo(l)) {
                        continue;
                    }
                    if (colunaDasChaves < 0) {
                        colunaDasChaves = recuo(l); // o traço estava sozinho na linha: as chaves começam na linha de baixo
                    }
                    if (recuo(l) != colunaDasChaves) {
                        continue; // mais fundo (originRequest e afins): não é o hostname nem o service da regra
                    }
                    corpo = l.strip();
                }
                Matcher m = CHAVE_DA_REGRA.matcher(corpo);
                if (m.matches()) {
                    if (m.group(1).equals("hostname")) {
                        hostname = semAspas(m.group(2));
                    } else if (m.group(1).equals("service")) {
                        servico = semAspas(m.group(2));
                    }
                }
            }
            boolean marcada = false;
            if (ini > bloco[0] + 1) {
                Matcher mm = MARCA.matcher(t.linhas().get(ini - 1));
                marcada = mm.matches() && !hostname.isEmpty() && mm.group(1).equalsIgnoreCase(hostname);
            }
            regras.add(new Regra(hostname.toLowerCase(Locale.ROOT), servico, ini, fimDaRegra, marcada));
        }
        return new Itens(regras, recuoDosTracos < 0 ? 2 : recuoDosTracos);
    }

    /** Lê o arquivo. @throws Problema o formato não é um que o app edita com segurança (a frase diz por quê) */
    public static Analise analisar(String texto) throws Problema {
        Texto t = repartir(texto == null ? "" : texto);
        String tunel = "";
        for (String l : t.linhas()) {
            if (recuo(l) == 0) {
                Matcher m = TUNEL.matcher(l);
                if (m.matches()) {
                    tunel = semAspas(m.group(1));
                }
            }
        }
        int[] bloco = blocoDoIngress(t);
        if (bloco == null) {
            if (t.linhas().stream().anyMatch(ConfigYmlDoCloudflared::conteudo)) {
                return new Analise(false, List.of(), false, tunel, t.crlf());
            }
            return new Analise(false, List.of(), false, "", t.crlf());
        }
        Itens itens = itensDo(t, bloco);
        boolean temFinal = !itens.regras().isEmpty() && itens.regras().get(itens.regras().size() - 1).ehFinal();
        return new Analise(true, itens.regras(), temFinal, tunel, t.crlf());
    }

    // ---------------------------------------------------------------------------------------------
    // Acrescentar
    // ---------------------------------------------------------------------------------------------

    /**
     * Acrescenta a regra {@code hostname -> servico}, com a marca do app, ANTES da regra final.
     *
     * @param observacao texto curto que acompanha a marca (por exemplo {@code projeto demo, 2026-10-28})
     * @return o texto novo; se a regra já é a do app e igual, {@link Edicao#jaEstava()} e o texto não muda
     * @throws Problema o formato não é suportado, ou o endereço já existe e não é do app (ou aponta para outro lugar)
     */
    public static Edicao acrescentar(String texto, String hostname, String servico, String observacao) throws Problema {
        String h = hostname.toLowerCase(Locale.ROOT);
        Texto t = repartir(texto == null ? "" : texto);
        List<String> avisos = new ArrayList<>();
        int[] bloco = blocoDoIngress(t);
        if (bloco == null) {
            if (t.linhas().stream().anyMatch(ConfigYmlDoCloudflared::conteudo)) {
                throw new Problema(Problema.Tipo.FORMATO_NAO_SUPORTADO, "Este config.yml não tem a seção \"ingress:\" (é o formato antigo, com url:). O app não o reescreve: "
                    + "troque para o formato com ingress e uma regra final, ou acrescente a regra à mão.");
            }
            List<String> novas = new ArrayList<>(t.linhas());
            novas.add("ingress:");
            novas.addAll(regra(2, h, servico, observacao));
            novas.add("  - service: http_status:404");
            avisos.add("O config.yml estava vazio: o app escreveu só a seção ingress (a regra e a regra final). O cloudflared também precisa das linhas tunnel: e credentials-file: "
                + "para subir, e elas não foram escritas.");
            return new Edicao(new Texto(novas, t.crlf(), true).montar(novas), false, avisos);
        }
        Itens itens = itensDo(t, bloco);
        Optional<Regra> existente = itens.regras().stream().filter(r -> r.hostname().equals(h)).findFirst();
        if (existente.isPresent()) {
            Regra r = existente.get();
            if (r.marcadaPeloApp() && r.servico().equals(servico)) {
                return new Edicao(texto, true, List.of());
            }
            throw new Problema(Problema.Tipo.REGRA_JA_EXISTE, "O endereço " + h + " já está no config.yml e aponta para " + (r.servico().isEmpty() ? "outro lugar" : r.servico())
                + (r.marcadaPeloApp() ? "" : ", e a regra não foi criada por este app") + ". O app não mexe nela: escolha outro nome.");
        }
        List<String> novas = new ArrayList<>(t.linhas());
        List<String> bloco3 = regra(itens.recuoDosTracos(), h, servico, observacao);
        List<Regra> regras = itens.regras();
        boolean temFinal = !regras.isEmpty() && regras.get(regras.size() - 1).ehFinal();
        if (regras.isEmpty()) {
            // ingress: sem nenhuma regra: a regra e a final entram logo depois
            List<String> tudo = new ArrayList<>(bloco3);
            tudo.add(" ".repeat(itens.recuoDosTracos()) + "- service: http_status:404");
            novas.addAll(bloco[0] + 1, tudo);
            avisos.add("A seção ingress estava sem regras: o app acrescentou a regra e a regra final (http_status:404), que o cloudflared exige.");
        } else if (temFinal) {
            int p = regras.get(regras.size() - 1).inicio();
            // um comentário no recuo das regras logo acima da regra final pertence a ela: a nova entra antes dele
            while (p - 1 > bloco[0] && comentario(t.linhas().get(p - 1)) && recuo(t.linhas().get(p - 1)) == itens.recuoDosTracos() && !MARCA.matcher(t.linhas().get(p - 1)).matches()) {
                p--;
            }
            novas.addAll(p, bloco3);
        } else {
            Regra ultima = regras.get(regras.size() - 1);
            List<String> tudo = new ArrayList<>(bloco3);
            tudo.add(" ".repeat(itens.recuoDosTracos()) + "- service: http_status:404");
            novas.addAll(ultima.fim(), tudo);
            avisos.add("O config.yml não tinha a regra final (http_status:404), que o cloudflared exige: o app a acrescentou depois da nova regra.");
        }
        return new Edicao(new Texto(novas, t.crlf(), t.terminaEmEol() || t.linhas().isEmpty()).montar(novas), false, avisos);
    }

    private static List<String> regra(int recuoDoTraco, String hostname, String servico, String observacao) {
        String e = " ".repeat(recuoDoTraco);
        List<String> l = new ArrayList<>();
        String obs = observacao == null || observacao.isBlank() ? "" : " (" + observacao.replaceAll("[\\r\\n]+", " ").strip() + ")";
        l.add(e + "# lab-desktop: " + hostname + obs);
        l.add(e + "- hostname: " + hostname);
        l.add(e + "  service: " + servico);
        return l;
    }

    // ---------------------------------------------------------------------------------------------
    // Remover
    // ---------------------------------------------------------------------------------------------

    /**
     * Tira a regra do endereço, com a marca dela, SÓ se ela tem a marca do app. Nunca mexe na regra final nem em regra do usuário.
     *
     * @throws Problema não achou a regra, ou ela não é do app, ou o formato não é suportado
     */
    public static String remover(String texto, String hostname) throws Problema {
        String h = hostname.toLowerCase(Locale.ROOT);
        Texto t = repartir(texto == null ? "" : texto);
        int[] bloco = blocoDoIngress(t);
        if (bloco == null) {
            throw new Problema(Problema.Tipo.NAO_ENCONTRADA, "O config.yml não tem a seção ingress, então não há regra de " + h + " para tirar.");
        }
        Itens itens = itensDo(t, bloco);
        Regra r = itens.regras().stream().filter(x -> x.hostname().equals(h)).findFirst()
            .orElseThrow(() -> new Problema(Problema.Tipo.NAO_ENCONTRADA, "O config.yml não tem a regra de " + h + "."));
        if (!r.marcadaPeloApp()) {
            throw new Problema(Problema.Tipo.NAO_E_DO_APP, "A regra de " + h + " no config.yml não foi criada por este app (não tem a marca \"# lab-desktop:\"). O app não a remove: "
                + "tire-a à mão se quiser.");
        }
        List<String> novas = new ArrayList<>(t.linhas());
        for (int i = r.fim() - 1; i >= r.inicio() - 1; i--) { // a marca é a linha de cima
            novas.remove(i);
        }
        return new Texto(novas, t.crlf(), t.terminaEmEol()).montar(novas);
    }
}
