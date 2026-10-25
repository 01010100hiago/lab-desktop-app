package br.com.labdesktop.servidor;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ConfiguracaoDoSsh;
import br.com.labdesktop.servidor.ResumoDeSeguranca.EstadoFail2ban;
import br.com.labdesktop.servidor.ResumoDeSeguranca.EventoDeTentativa;
import br.com.labdesktop.servidor.ResumoDeSeguranca.LoginAceito;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Origem;
import br.com.labdesktop.servidor.ResumoDeSeguranca.PortaAberta;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ResumoDoFirewall;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.servidor.ResumoDeSeguranca.TipoDeTentativa;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Os parsers da saída dos comandos de segurança (somente leitura). Funções PURAS: texto entra, registro sai, o
 * relógio vem de fora. A saída vem do servidor e é tratada como não confiável: tamanhos limitados, nada vira
 * comando, e todo texto que irá à tela passa por {@link TextoSeguro}.
 */
public final class ParsersDeSeguranca {

    /** Janela de leitura do registro. */
    public static final Duration SETE_DIAS = Duration.ofDays(7);
    public static final Duration VINTE_E_QUATRO_HORAS = Duration.ofHours(24);
    /** Quantas origens a lista guarda. */
    public static final int MAX_ORIGENS = 10;
    /** Quantos eventos de tentativa a lista guarda (os mais recentes). */
    public static final int MAX_EVENTOS = 500;
    /** Quantos logins aceitos a lista guarda (os mais recentes). */
    public static final int MAX_LOGINS = 200;
    private static final int MAX_REGRAS = 30;

    private static final DateTimeFormatter ISO_SEM_DOIS_PONTOS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX");
    private static final String IP = "([0-9A-Fa-f:.]{2,45})";

    private static final Pattern FALHA_AUTENTICACAO =
        Pattern.compile("Failed (?:password|publickey|keyboard-interactive/pam|none) for (invalid user )?(\\S+) from " + IP + " port (\\d+)");
    private static final Pattern USUARIO_INVALIDO = Pattern.compile("Invalid user (\\S*) from " + IP + " port (\\d+)");
    private static final Pattern FECHOU_USUARIO_INVALIDO =
        Pattern.compile("Connection closed by invalid user (\\S*) " + IP + " port (\\d+)");
    private static final Pattern FECHOU_AUTENTICANDO =
        Pattern.compile("Connection closed by authenticating user (\\S*) " + IP + " port (\\d+)");
    private static final Pattern MAXIMO_DE_TENTATIVAS =
        Pattern.compile("maximum authentication attempts exceeded for (invalid user )?(\\S+) from " + IP + " port (\\d+)");
    private static final Pattern PROTOCOLO_ESTRANHO = Pattern.compile(
        "(?:Unable to negotiate|Did not receive identification string|banner exchange|Bad protocol version identification"
            + "|kex_exchange_identification).*?(?:with|from|by) " + IP + " port (\\d+)");
    private static final Pattern FECHOU_ANTES_DE_AUTENTICAR =
        Pattern.compile("Connection (?:closed|reset) by " + IP + " port (\\d+) \\[preauth\\]");
    private static final Pattern FALHA_PAM = Pattern.compile("authentication failure;.*?rhost=" + IP + "(?:\\s+user=(\\S+))?");
    private static final Pattern ACEITO = Pattern.compile(
        "Accepted (publickey|password|keyboard-interactive/pam) for (\\S+) from " + IP + " port (\\d+) ssh2(?:: (\\S+) (\\S+))?");

    private ParsersDeSeguranca() { }

    // =====================================================================================================
    // Registro do SSH
    // =====================================================================================================

    private static final class Conexao {
        final Instant quando;
        final String ip;
        TipoDeTentativa tipo;
        String usuario;

        Conexao(Instant quando, String ip, TipoDeTentativa tipo, String usuario) {
            this.quando = quando;
            this.ip = ip;
            this.tipo = tipo;
            this.usuario = usuario;
        }
    }

    private static int prioridade(TipoDeTentativa t) {
        return switch (t) {
            case USUARIO_INVENTADO -> 3;
            case ADIVINHAR_SENHA -> 2;
            case VARREDURA -> 1;
            case OUTROS -> 0;
        };
    }

    /**
     * Conta tentativas e lista os logins aceitos a partir do {@code journalctl -u ssh -o short-iso}. Linhas sem data
     * legível ou fora dos 7 dias são ignoradas; uma linha ilegível nunca derruba o resto.
     *
     * @param saida   o texto (só as linhas de interesse, ou o registro todo: o parser filtra)
     * @param agora   o relógio (injetado: os cortes de 24 h e 7 dias dependem dele)
     * @param parcial a saída foi cortada no teto de tamanho
     */
    public static Tentativas lerRegistroDoSsh(String saida, Instant agora, boolean parcial) {
        Map<String, Conexao> conexoes = new LinkedHashMap<>();
        List<Conexao> semPorta = new ArrayList<>();
        Set<String> ipsPorSegundo = new HashSet<>();
        List<LoginAceito> logins = new ArrayList<>();
        Instant corte7d = agora.minus(SETE_DIAS);
        Instant corte24h = agora.minus(VINTE_E_QUATRO_HORAS);

        for (String linha : saida == null ? new String[0] : saida.split("\\R")) {
            int espaco = linha.indexOf(' ');
            if (espaco <= 0) {
                continue;
            }
            Instant quando = lerInstante(linha.substring(0, espaco));
            if (quando == null || quando.isBefore(corte7d)) {
                continue;
            }
            String texto = linha.substring(espaco + 1);
            Matcher m;
            if ((m = ACEITO.matcher(texto)).find()) {
                String ip = m.group(3);
                String metodo = m.group(1).startsWith("publickey") ? "chave" : "senha";
                logins.add(new LoginAceito(quando, curto(m.group(2)), ip, Integer.parseInt(m.group(4)), metodo,
                    m.group(5) == null ? "" : curto(m.group(5)), m.group(6) == null ? "" : curto(m.group(6)), !redeLocal(ip)));
            } else if ((m = USUARIO_INVALIDO.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(2), m.group(3), TipoDeTentativa.USUARIO_INVENTADO, m.group(1));
            } else if ((m = FECHOU_USUARIO_INVALIDO.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(2), m.group(3), TipoDeTentativa.USUARIO_INVENTADO, m.group(1));
            } else if ((m = FALHA_AUTENTICACAO.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(3), m.group(4),
                    m.group(1) != null ? TipoDeTentativa.USUARIO_INVENTADO : TipoDeTentativa.ADIVINHAR_SENHA, m.group(2));
            } else if ((m = MAXIMO_DE_TENTATIVAS.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(3), m.group(4),
                    m.group(1) != null ? TipoDeTentativa.USUARIO_INVENTADO : TipoDeTentativa.ADIVINHAR_SENHA, m.group(2));
            } else if ((m = FECHOU_AUTENTICANDO.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(2), m.group(3), TipoDeTentativa.ADIVINHAR_SENHA, m.group(1));
            } else if ((m = PROTOCOLO_ESTRANHO.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(1), m.group(2), TipoDeTentativa.VARREDURA, null);
            } else if ((m = FECHOU_ANTES_DE_AUTENTICAR.matcher(texto)).find()) {
                registrar(conexoes, ipsPorSegundo, quando, m.group(1), m.group(2), TipoDeTentativa.VARREDURA, null);
            } else if ((m = FALHA_PAM.matcher(texto)).find()) {
                // Sem porta: acompanha um "Failed password" da mesma tentativa (mesmo IP, mesmo segundo). Conta só se sozinha.
                semPorta.add(new Conexao(quando, m.group(1), TipoDeTentativa.ADIVINHAR_SENHA, m.group(2)));
            }
        }

        List<Conexao> todas = new ArrayList<>(conexoes.values());
        Set<String> contadasSemPorta = new HashSet<>();
        for (Conexao c : semPorta) {
            String chave = c.ip + "@" + c.quando.getEpochSecond();
            if (!ipsPorSegundo.contains(chave) && contadasSemPorta.add(chave)) {
                todas.add(c);
            }
        }

        int em24h = 0;
        Map<TipoDeTentativa, Integer> porTipo = new EnumMap<>(TipoDeTentativa.class);
        for (TipoDeTentativa t : TipoDeTentativa.values()) {
            porTipo.put(t, 0);
        }
        Map<String, Integer> porIp = new HashMap<>();
        Map<String, Map<TipoDeTentativa, Integer>> tiposPorIp = new HashMap<>();
        Map<String, Instant> ultimaPorIp = new HashMap<>();
        for (Conexao c : todas) {
            if (!c.quando.isBefore(corte24h)) {
                em24h++;
            }
            porTipo.merge(c.tipo, 1, Integer::sum);
            porIp.merge(c.ip, 1, Integer::sum);
            tiposPorIp.computeIfAbsent(c.ip, k -> new EnumMap<>(TipoDeTentativa.class)).merge(c.tipo, 1, Integer::sum);
            ultimaPorIp.merge(c.ip, c.quando, (x, y) -> x.isAfter(y) ? x : y);
        }
        List<Origem> origens = porIp.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(MAX_ORIGENS)
            .map(e -> new Origem(e.getKey(), e.getValue(), redeLocal(e.getKey()), predominante(tiposPorIp.get(e.getKey())),
                ultimaPorIp.get(e.getKey())))
            .toList();
        List<EventoDeTentativa> eventos = todas.stream()
            .sorted(Comparator.comparing((Conexao c) -> c.quando).reversed())
            .limit(MAX_EVENTOS)
            .map(c -> new EventoDeTentativa(c.quando, c.tipo, c.ip, c.usuario))
            .toList();
        logins.sort(Comparator.comparing(LoginAceito::quando).reversed());
        int foraDaRede24h = (int) logins.stream().filter(l -> l.foraDaRedeLocal() && !l.quando().isBefore(corte24h)).count();
        List<LoginAceito> recentes = logins.size() > MAX_LOGINS ? List.copyOf(logins.subList(0, MAX_LOGINS)) : List.copyOf(logins);
        return new Tentativas(em24h, todas.size(), parcial, java.util.Collections.unmodifiableMap(porTipo), origens,
            porIp.size(), recentes, foraDaRede24h, eventos);
    }

    /** O tipo mais usado por um IP (em empate, o de maior prioridade). */
    private static TipoDeTentativa predominante(Map<TipoDeTentativa, Integer> contagem) {
        TipoDeTentativa melhor = TipoDeTentativa.OUTROS;
        int max = -1;
        for (Map.Entry<TipoDeTentativa, Integer> e : contagem.entrySet()) {
            if (e.getValue() > max || (e.getValue() == max && prioridade(e.getKey()) > prioridade(melhor))) {
                melhor = e.getKey();
                max = e.getValue();
            }
        }
        return melhor;
    }

    private static void registrar(Map<String, Conexao> conexoes, Set<String> ipsPorSegundo, Instant quando, String ip,
                                  String porta, TipoDeTentativa tipo, String usuario) {
        String chave = ip + ":" + porta;
        Conexao c = conexoes.get(chave);
        String nome = usuario == null || usuario.isBlank() ? null : curto(usuario);
        if (c == null) {
            conexoes.put(chave, new Conexao(quando, ip, tipo, nome));
        } else {
            if (prioridade(tipo) > prioridade(c.tipo)) {
                c.tipo = tipo;
            }
            if (c.usuario == null) {
                c.usuario = nome;
            }
        }
        ipsPorSegundo.add(ip + "@" + quando.getEpochSecond());
    }

    static Instant lerInstante(String texto) {
        try {
            return OffsetDateTime.parse(texto, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(texto, ISO_SEM_DOIS_PONTOS).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String curto(String texto) {
        return TextoSeguro.truncar(TextoSeguro.limparLinha(texto), 80);
    }

    /** Endereço da rede local (privado, loopback ou link-local), para destacar o que veio de FORA. */
    public static boolean redeLocal(String ip) {
        if (ip == null) {
            return false;
        }
        String s = ip.toLowerCase(Locale.ROOT);
        if (s.startsWith("::ffff:")) {
            s = s.substring(7);
        }
        Matcher m = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}").matcher(s);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
        }
        return s.equals("::1") || s.startsWith("fe80:") || s.startsWith("fc") || s.startsWith("fd");
    }

    // =====================================================================================================
    // Portas abertas (ss -tlnpH)
    // =====================================================================================================

    private static final Pattern PROCESSO_DO_SS = Pattern.compile("\\(\\(\"([^\"]+)\"");

    /** As portas que escutam fora do loopback, agrupadas por porta e em ordem crescente. */
    public static List<PortaAberta> lerPortas(String saida) {
        Map<Integer, String[]> porPorta = new TreeMap<>(); // porta -> {escopo, processo}
        for (String linha : saida == null ? new String[0] : saida.split("\\R")) {
            String[] campos = linha.strip().split("\\s+");
            int base = campos.length > 0 && campos[0].matches("[A-Za-z-]+") ? 1 : 0; // "LISTEN" some com -H em alguns ss
            if (campos.length < base + 4) {
                continue;
            }
            String local = campos[base + 2];
            int dois = local.lastIndexOf(':');
            if (dois <= 0) {
                continue;
            }
            String endereco = local.substring(0, dois).replace("[", "").replace("]", "");
            int pct = endereco.indexOf('%');
            if (pct >= 0) {
                endereco = endereco.substring(0, pct);
            }
            int porta;
            try {
                porta = Integer.parseInt(local.substring(dois + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (endereco.startsWith("127.") || endereco.equals("::1")) {
                continue;
            }
            boolean todas = endereco.equals("*") || endereco.equals("0.0.0.0") || endereco.equals("::");
            String escopo = todas ? "todas as interfaces" : "só em " + curto(endereco);
            String processo = "";
            Matcher m = PROCESSO_DO_SS.matcher(linha);
            if (m.find()) {
                processo = curto(m.group(1));
            }
            String[] atual = porPorta.get(porta);
            if (atual == null) {
                porPorta.put(porta, new String[] {escopo, processo});
            } else {
                if (todas) {
                    atual[0] = escopo;
                }
                if (atual[1].isEmpty()) {
                    atual[1] = processo;
                }
            }
        }
        List<PortaAberta> lista = new ArrayList<>();
        porPorta.forEach((p, v) -> lista.add(new PortaAberta(p, v[0], v[1])));
        return List.copyOf(lista);
    }

    // =====================================================================================================
    // Firewall
    // =====================================================================================================

    private static final Map<Integer, String> PORTAS_CONHECIDAS = Map.ofEntries(
        Map.entry(22, "SSH"), Map.entry(25, "e-mail"), Map.entry(53, "DNS"), Map.entry(80, "HTTP"),
        Map.entry(443, "HTTPS"), Map.entry(3306, "MySQL"), Map.entry(5432, "PostgreSQL"), Map.entry(6379, "Redis"));

    /** Uma regra já entendida, antes de virar frase. */
    private record Regra(String protocolo, List<String> portas, String origem, boolean respostas, boolean loopback,
                         boolean icmp, boolean aceita, String bruto) {

        String frase(Integer portaSsh) {
            String acao = aceita ? "permitido" : "bloqueado";
            if (respostas) {
                return "Respostas a conexões que a própria máquina abriu: " + acao;
            }
            if (loopback) {
                return "Tráfego interno da máquina (loopback): " + acao;
            }
            if (icmp) {
                return "Ping e avisos de rede (ICMP): " + acao;
            }
            String origemTexto = origem == null ? "" : aceita ? " só de " + origem : " de " + origem;
            if (!portas.isEmpty()) {
                List<String> nomes = new ArrayList<>();
                for (String p : portas) {
                    nomes.add(p + nomeDaPorta(p, portaSsh));
                }
                String prefixo = ("udp".equals(protocolo) ? "UDP " : "") + (nomes.size() == 1 ? "Porta " : "Portas ");
                return prefixo + String.join(", ", nomes) + ": " + acao + origemTexto;
            }
            if (origem != null) {
                return "Origem " + origem + ": " + acao;
            }
            if (bruto.matches("accept|drop|reject")) {
                return "Todo o resto: " + acao;
            }
            return "Regra: " + TextoSeguro.truncar(TextoSeguro.limparLinha(bruto), 90);
        }
    }

    private static String nomeDaPorta(String porta, Integer portaSsh) {
        try {
            int p = Integer.parseInt(porta);
            if (portaSsh != null && p == portaSsh) {
                return " (SSH)";
            }
            String nome = PORTAS_CONHECIDAS.get(p);
            return nome == null ? "" : " (" + nome + ")";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static final Pattern NFT_POLITICA = Pattern.compile("policy (drop|accept)");
    private static final Pattern NFT_PORTAS = Pattern.compile("(tcp|udp) dport (\\{[^}]*\\}|[0-9-]+)");
    private static final Pattern NFT_ORIGEM = Pattern.compile("ip6? saddr (\\S+)");

    /** Resume {@code nft list chain inet filter input} em frases simples. */
    public static ResumoDoFirewall lerNft(String saida, Integer portaSsh) {
        String politica = null;
        List<Regra> regras = new ArrayList<>();
        for (String bruta : saida == null ? new String[0] : saida.split("\\R")) {
            String linha = bruta.strip();
            Matcher mp = NFT_POLITICA.matcher(linha);
            if (linha.startsWith("type ") && mp.find()) {
                politica = mp.group(1);
                continue;
            }
            if (linha.isEmpty() || linha.startsWith("table ") || linha.startsWith("chain ") || linha.equals("}")) {
                continue;
            }
            String limpa = linha.replaceAll("counter packets \\d+ bytes \\d+", "").replaceAll("\\bcounter\\b", "")
                .replaceAll("comment \"[^\"]*\"", "").replaceAll("\\s+", " ").strip();
            boolean aceita = limpa.endsWith(" accept") || limpa.equals("accept");
            boolean recusa = limpa.endsWith(" drop") || limpa.endsWith(" reject") || limpa.equals("drop") || limpa.equals("reject");
            if (!aceita && !recusa) {
                continue;
            }
            List<String> portas = new ArrayList<>();
            String proto = null;
            Matcher mportas = NFT_PORTAS.matcher(limpa);
            if (mportas.find()) {
                proto = mportas.group(1);
                for (String p : mportas.group(2).replaceAll("[{}]", " ").split("[,\\s]+")) {
                    if (p.matches("[0-9-]+")) {
                        portas.add(p);
                    }
                }
            }
            Matcher morigem = NFT_ORIGEM.matcher(limpa);
            regras.add(new Regra(proto, portas, morigem.find() ? curto(morigem.group(1)) : null,
                limpa.contains("ct state") && limpa.contains("established"),
                limpa.matches(".*\\biif(name)? \"lo\".*"), limpa.startsWith("icmp") || limpa.contains(" icmp"),
                aceita, limpa));
        }
        if (politica == null && regras.isEmpty()) {
            return new ResumoDoFirewall(false, "nftables", null, List.of());
        }
        return resumir("nftables", politica, regras, portaSsh);
    }

    private static final Pattern IPT_POLITICA = Pattern.compile("^-P INPUT (\\w+)");
    private static final Pattern IPT_PORTAS = Pattern.compile("--dports? (\\S+)");
    private static final Pattern IPT_ORIGEM = Pattern.compile("-s (\\S+)");
    private static final Pattern IPT_PROTO = Pattern.compile("-p (\\w+)");
    private static final Pattern IPT_ALVO = Pattern.compile("-j (\\w+)");

    /** Resume {@code iptables -S} (só a cadeia INPUT) em frases simples. */
    public static ResumoDoFirewall lerIptables(String saida, Integer portaSsh) {
        String politica = null;
        List<Regra> regras = new ArrayList<>();
        for (String bruta : saida == null ? new String[0] : saida.split("\\R")) {
            String linha = bruta.strip();
            Matcher mp = IPT_POLITICA.matcher(linha);
            if (mp.find()) {
                politica = mp.group(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (!linha.startsWith("-A INPUT")) {
                continue;
            }
            Matcher alvo = IPT_ALVO.matcher(linha);
            if (!alvo.find()) {
                continue;
            }
            String a = alvo.group(1).toUpperCase(Locale.ROOT);
            if (!a.equals("ACCEPT") && !a.equals("DROP") && !a.equals("REJECT")) {
                continue;
            }
            List<String> portas = new ArrayList<>();
            Matcher mportas = IPT_PORTAS.matcher(linha);
            if (mportas.find()) {
                for (String p : mportas.group(1).split(",")) {
                    portas.add(p.replace(':', '-'));
                }
            }
            Matcher mproto = IPT_PROTO.matcher(linha);
            String proto = mproto.find() ? mproto.group(1) : null;
            Matcher morigem = IPT_ORIGEM.matcher(linha);
            regras.add(new Regra(proto, portas, morigem.find() ? curto(morigem.group(1)) : null,
                linha.contains("ESTABLISHED"), linha.matches(".*-i lo\\b.*"), "icmp".equals(proto), a.equals("ACCEPT"), linha));
        }
        if (politica == null && regras.isEmpty()) {
            return new ResumoDoFirewall(false, "iptables", null, List.of());
        }
        return resumir("iptables", politica, regras, portaSsh);
    }

    private static ResumoDoFirewall resumir(String motor, String politica, List<Regra> regras, Integer portaSsh) {
        List<String> frases = new ArrayList<>();
        for (Regra r : regras) {
            if (frases.size() >= MAX_REGRAS) {
                break;
            }
            frases.add(r.frase(portaSsh));
        }
        String texto = politica == null ? null : politica.equals("accept") ? "aceita" : "bloqueia";
        return new ResumoDoFirewall(true, motor, texto, List.copyOf(frases));
    }

    // =====================================================================================================
    // Configuração do SSH (sshd -T) e fail2ban
    // =====================================================================================================

    /** Extrai porta, senha, root, chave e tentativas do {@code sshd -T}; {@code null} se nenhuma diretiva apareceu. */
    public static ConfiguracaoDoSsh lerSshd(String saida) {
        Integer porta = null;
        Integer max = null;
        Boolean senha = null;
        Boolean chave = null;
        String root = null;
        boolean achou = false;
        for (String linha : saida == null ? new String[0] : saida.split("\\R")) {
            String[] kv = linha.strip().split("\\s+", 2);
            if (kv.length < 2) {
                continue;
            }
            String valor = kv[1].strip().toLowerCase(Locale.ROOT);
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "port" -> {
                    if (porta == null) {
                        porta = inteiro(valor);
                        achou = true;
                    }
                }
                case "passwordauthentication" -> {
                    senha = sim(valor);
                    achou = true;
                }
                case "pubkeyauthentication" -> {
                    chave = sim(valor);
                    achou = true;
                }
                case "permitrootlogin" -> {
                    root = curto(valor);
                    achou = true;
                }
                case "maxauthtries" -> {
                    max = inteiro(valor);
                    achou = true;
                }
                default -> { }
            }
        }
        return achou ? new ConfiguracaoDoSsh(porta, senha, root, chave, max) : null;
    }

    private static Integer inteiro(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean sim(String s) {
        return s.equals("yes") ? Boolean.TRUE : s.equals("no") ? Boolean.FALSE : null;
    }

    private static final Pattern F2B_AGORA = Pattern.compile("Currently banned:\\s*(\\d+)");
    private static final Pattern F2B_TOTAL = Pattern.compile("Total banned:\\s*(\\d+)");
    private static final Pattern F2B_LISTA = Pattern.compile("Banned IP list:[ \\t]*(.*)");

    /** O {@code fail2ban-client status sshd}. {@code ativo} vem do {@code systemctl is-active}. */
    public static EstadoFail2ban lerFail2ban(boolean ativo, String status) {
        if (!ativo) {
            return new EstadoFail2ban(false, null, null, List.of());
        }
        Integer agora = null;
        Integer total = null;
        List<String> ips = new ArrayList<>();
        if (status != null) {
            Matcher a = F2B_AGORA.matcher(status);
            if (a.find()) {
                agora = inteiro(a.group(1));
            }
            Matcher t = F2B_TOTAL.matcher(status);
            if (t.find()) {
                total = inteiro(t.group(1));
            }
            Matcher l = F2B_LISTA.matcher(status);
            if (l.find()) {
                for (String ip : l.group(1).strip().split("\\s+")) {
                    if (ip.matches("[0-9A-Fa-f:.]{2,45}") && ips.size() < 50) {
                        ips.add(ip);
                    }
                }
            }
        }
        return new EstadoFail2ban(true, agora, total, List.copyOf(ips));
    }
}
