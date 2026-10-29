package br.com.labdesktop.cloudflare;

import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que "Publicar na internet" muda no {@code docker-compose.yml}: o serviço ganha um {@code container_name} único e entra na rede {@code edge} (a rede
 * que o cloudflared enxerga), e o arquivo declara {@code edge} como rede externa. Edição por ACRÉSCIMO de linhas (a lista de redes em uma linha ganha {@code edge} dentro dos colchetes), como TEXTO (sem biblioteca de YAML:
 * uma que reescreve o arquivo perderia os comentários e a ordem do usuário).
 *
 * <p>Só edita um compose no formato comum (blocos com recuo de espaços, serviços com dois espaços de recuo, {@code networks:} como lista, como mapa ou como lista em uma linha ({@code [default, edge]}),
 * sem CRLF, sem tabulação, sem {@code network_mode}). Qualquer outra coisa vira {@link Situacao#MANUAL}: o app NÃO escreve e mostra o bloco para o usuário
 * copiar, com a frase de onde cada parte vai (o compose do usuário nunca é reescrito, igual ao "Adicionar container").
 */
public final class ComposeComEdge {

    /** A rede que o cloudflared enxerga. */
    public static final String REDE_EDGE = "edge";

    private static final Pattern SERVICO = Pattern.compile("^  ([A-Za-z0-9][A-Za-z0-9_.-]*):[ ]*(#.*)?$");
    private static final Pattern CHAVE_DO_SERVICO = Pattern.compile("^    ([A-Za-z_][A-Za-z0-9_-]*):(.*)$");
    private static final Pattern PORTA = Pattern.compile("(?:^|:)(\\d{1,5})(?:/[a-z]+)?$");

    /** O que o app consegue fazer com o arquivo. */
    public enum Situacao {
        /** O serviço já tem {@code container_name} e já está na {@code edge}, e o arquivo declara a rede: nada a escrever. */
        PRONTO,
        /** O app sabe escrever as linhas que faltam. */
        EDITAVEL,
        /** O app não escreve neste arquivo: só mostra o bloco para copiar. */
        MANUAL
    }

    /**
     * Um serviço do compose, como o app o enxerga.
     *
     * @param containerName o {@code container_name} que o serviço já tem, ou {@code null}
     * @param naRedeEdge    o serviço já lista a rede {@code edge}
     * @param portaSugerida a porta de dentro do container que o arquivo indica ({@code ports} ou {@code expose}), ou {@code null}
     */
    public record Servico(String nome, String containerName, boolean naRedeEdge, Integer portaSugerida) { }

    /**
     * A proposta.
     *
     * @param novoTexto       o arquivo com as linhas acrescentadas ({@code null} se {@link Situacao#MANUAL}; igual ao de antes se {@link Situacao#PRONTO})
     * @param containerName   o nome que o container terá (o que o serviço já tinha, ou o pedido)
     * @param motivos         em {@link Situacao#MANUAL}, por que o app não escreve (frases simples); senão vazio
     * @param blocoParaCopiar o texto para o usuário colar à mão (plano B); vazio quando não há bloco que resolva (o serviço usa network_mode: só o motivo vale)
     */
    public record Proposta(Situacao situacao, String novoTexto, String containerName, List<String> motivos, String blocoParaCopiar) {
        public Proposta {
            motivos = List.copyOf(motivos);
        }
    }

    private ComposeComEdge() { }

    // ---------------------------------------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------------------------------------

    private static int recuo(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean conteudo(String l) {
        return !l.isBlank() && !l.stripLeading().startsWith("#");
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

    /** O fim de um bloco: a linha DEPOIS da última linha de conteúdo com recuo maior que {@code recuoDoPai}, a partir de {@code ini} (a linha de baixo do pai). */
    private static int fimDoBloco(List<String> l, int ini, int recuoDoPai) {
        int fim = ini;
        for (int i = ini; i < l.size(); i++) {
            String linha = l.get(i);
            if (!conteudo(linha)) {
                continue;
            }
            if (recuo(linha) <= recuoDoPai) {
                break;
            }
            fim = i + 1;
        }
        return fim;
    }

    private static List<String> linhas(String texto) {
        String t = texto.endsWith("\n") ? texto.substring(0, texto.length() - 1) : texto;
        return new ArrayList<>(List.of(t.split("\n", -1)));
    }

    /** Os pedaços do arquivo que importam. */
    private static final class Mapa {
        final List<String> l;
        int servicos = -1;      // a linha de "services:"
        int fimDeServicos;
        final List<String> motivos = new ArrayList<>();
        int redesDeCima = -1;   // a linha do "networks:" de cima
        int fimDeRedesDeCima;

        Mapa(List<String> l) {
            this.l = l;
        }
    }

    private static Mapa mapear(String texto) {
        Mapa m = new Mapa(linhas(texto));
        if (texto.indexOf('\r') >= 0) {
            m.motivos.add("O arquivo usa fim de linha do Windows (CRLF).");
        }
        for (String l : m.l) {
            int i = 0;
            while (i < l.length() && l.charAt(i) == ' ') {
                i++;
            }
            if (i < l.length() && l.charAt(i) == '\t' || l.startsWith("\t")) {
                m.motivos.add("O arquivo tem tabulação no recuo.");
                break;
            }
        }
        for (int i = 0; i < m.l.size(); i++) {
            String l = m.l.get(i);
            if (recuo(l) != 0 || !conteudo(l)) {
                continue;
            }
            if (l.matches("^services:[ ]*(#.*)?$")) {
                m.servicos = i;
            } else if (l.matches("^services:.*")) {
                m.motivos.add("A seção services está escrita em uma linha só.");
            } else if (l.matches("^networks:[ ]*(#.*)?$")) {
                m.redesDeCima = i;
            } else if (l.matches("^networks:.*")) {
                m.motivos.add("A seção networks do arquivo está escrita em uma linha só.");
            } else if (l.startsWith("---") || l.startsWith("...")) {
                m.motivos.add("O arquivo tem mais de um documento YAML.");
            }
        }
        if (m.servicos < 0) {
            m.motivos.add("O arquivo não tem a seção services no formato de blocos.");
        } else {
            m.fimDeServicos = fimDoBloco(m.l, m.servicos + 1, 0);
        }
        if (m.redesDeCima >= 0) {
            m.fimDeRedesDeCima = fimDoBloco(m.l, m.redesDeCima + 1, 0);
        }
        return m;
    }

    private record Bloco(String nome, int inicio, int fim) { }

    private static List<Bloco> blocosDeServico(Mapa m) {
        List<Bloco> r = new ArrayList<>();
        if (m.servicos < 0) {
            return r;
        }
        List<Integer> inicios = new ArrayList<>();
        for (int i = m.servicos + 1; i < m.fimDeServicos; i++) {
            if (SERVICO.matcher(m.l.get(i)).matches()) {
                inicios.add(i);
            }
        }
        for (int k = 0; k < inicios.size(); k++) {
            int ini = inicios.get(k);
            int limite = k + 1 < inicios.size() ? inicios.get(k + 1) : m.fimDeServicos;
            int fim = ini + 1;
            for (int j = ini + 1; j < limite; j++) {
                if (conteudo(m.l.get(j)) && recuo(m.l.get(j)) > 2) {
                    fim = j + 1;
                }
            }
            Matcher mm = SERVICO.matcher(m.l.get(ini));
            mm.matches();
            r.add(new Bloco(mm.group(1), ini, fim));
        }
        return r;
    }

    /** Os serviços do compose (para a tela escolher). Vazio se o arquivo não está no formato que o app lê. */
    public static List<Servico> servicos(String texto) {
        if (texto == null) {
            return List.of();
        }
        Mapa m = mapear(texto);
        List<Servico> r = new ArrayList<>();
        for (Bloco b : blocosDeServico(m)) {
            r.add(descrever(m, b));
        }
        return r;
    }

    private static Servico descrever(Mapa m, Bloco b) {
        String container = null;
        boolean edge = false;
        Integer porta = null;
        for (int i = b.inicio() + 1; i < b.fim(); i++) {
            Matcher c = CHAVE_DO_SERVICO.matcher(m.l.get(i));
            if (!c.matches()) {
                continue;
            }
            switch (c.group(1)) {
                case "container_name" -> container = semAspas(c.group(2));
                case "networks" -> edge = listaDeRedes(m, i, b.fim()).contains(REDE_EDGE);
                case "ports", "expose" -> {
                    if (porta == null) {
                        porta = primeiraPorta(m, i, b.fim());
                    }
                }
                default -> { }
            }
        }
        return new Servico(b.nome(), container == null || container.isEmpty() ? null : container, edge, porta);
    }

    /**
     * Os nomes de uma lista de redes escrita em uma linha ({@code [default, edge]}, com ou sem aspas e com comentário no fim), ou {@code null} se o valor é
     * outra coisa (mapa entre chaves, lista que continua na linha de baixo, vazia, com âncora, variável ou nome estranho): aí o app não a lê nem a edita.
     */
    private static List<String> redesDeLinhaSo(String valor) {
        String v = valor.strip();
        if (!v.startsWith("[")) {
            return null;
        }
        int fecha = v.indexOf(']');
        if (fecha < 0) {
            return null;
        }
        String depois = v.substring(fecha + 1);
        // em YAML o "#" só abre comentário depois de um espaço: "[a]#x" é um arquivo que o compose rejeita, e o app não escreve em cima dele
        if (!depois.isBlank() && !(depois.stripLeading().startsWith("#") && Character.isWhitespace(depois.charAt(0)))) {
            return null;
        }
        String dentro = v.substring(1, fecha);
        if (dentro.isBlank() || dentro.indexOf('[') >= 0 || dentro.indexOf('{') >= 0 || dentro.indexOf('#') >= 0) {
            return null;
        }
        String semVirgulaFinal = dentro.stripTrailing();
        if (semVirgulaFinal.endsWith(",")) { // a vírgula final é YAML válido
            dentro = semVirgulaFinal.substring(0, semVirgulaFinal.length() - 1);
        }
        List<String> nomes = new ArrayList<>();
        for (String parte : dentro.split(",", -1)) {
            String nome = semAspas(parte);
            if (!nome.matches("^[A-Za-z0-9][A-Za-z0-9_.-]*$")) {
                return null;
            }
            nomes.add(nome);
        }
        return nomes;
    }

    /** O que vem depois de {@code networks:} na linha do serviço, sem o comentário vazio: {@code ""} quando as redes vêm nas linhas de baixo. */
    private static String valorDoNetworks(Mapa m, int linhaDoNetworks) {
        Matcher c = CHAVE_DO_SERVICO.matcher(m.l.get(linhaDoNetworks));
        if (!c.matches() || c.group(2).isBlank() || c.group(2).strip().startsWith("#")) {
            return "";
        }
        return c.group(2);
    }

    /** Os nomes de rede de um {@code networks:} do serviço, em lista ({@code - nome}), mapa ({@code nome:}) ou lista em uma linha. Vazio se está em outro formato de uma linha só. */
    private static List<String> listaDeRedes(Mapa m, int linhaDoNetworks, int fimDoServico) {
        String emLinha = valorDoNetworks(m, linhaDoNetworks);
        if (!emLinha.isEmpty()) {
            List<String> lida = redesDeLinhaSo(emLinha);
            return lida == null ? List.of() : lida;
        }
        List<String> nomes = new ArrayList<>();
        for (int i = linhaDoNetworks + 1; i < fimDoServico; i++) {
            String l = m.l.get(i);
            if (!conteudo(l)) {
                continue;
            }
            if (recuo(l) <= 4) {
                break;
            }
            String s = l.strip();
            if (recuo(l) == 6) {
                if (s.startsWith("- ")) {
                    nomes.add(semAspas(s.substring(2)));
                } else if (s.endsWith(":") || s.contains(": ") || s.contains(":#")) {
                    nomes.add(s.substring(0, s.indexOf(':')).strip());
                }
            }
        }
        return nomes;
    }

    private static Integer primeiraPorta(Mapa m, int linhaDaChave, int fimDoServico) {
        for (int i = linhaDaChave + 1; i < fimDoServico; i++) {
            String l = m.l.get(i);
            if (!conteudo(l)) {
                continue;
            }
            if (recuo(l) <= 4) {
                break;
            }
            String s = l.strip();
            if (s.startsWith("- ")) {
                String v = semAspas(s.substring(2));
                Matcher p = PORTA.matcher(v);
                if (p.find()) {
                    int n = Integer.parseInt(p.group(1));
                    if (n >= 1 && n <= 65535) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // A proposta
    // ---------------------------------------------------------------------------------------------

    /**
     * Calcula o que mudaria para publicar {@code servico} com o container de nome {@code containerPedido} (usado só se o serviço ainda não tem
     * {@code container_name}). Não escreve nada.
     */
    public static Proposta propor(String texto, String servico, String containerPedido) {
        if (!ValidadorDeNomes.containerValido(containerPedido) || !containerPedido.matches("^[a-z0-9][a-z0-9_.-]{0,40}$")) {
            throw new IllegalArgumentException("Nome de container inválido.");
        }
        Mapa m = mapear(texto == null ? "" : texto);
        List<String> motivos = new ArrayList<>(m.motivos);
        Bloco alvo = blocosDeServico(m).stream().filter(b -> b.nome().equals(servico)).findFirst().orElse(null);
        if (alvo == null && m.motivos.isEmpty()) {
            motivos.add("Não achei o serviço \"" + servico + "\" no docker-compose.yml (ele precisa estar escrito com dois espaços de recuo).");
        }
        String nome = containerPedido;
        boolean temContainerName = false;
        boolean temNetworks = false;
        boolean redesEmLinhaSo = false;
        boolean redesEmLinhaLegiveis = false;
        boolean usaNetworkMode = false;
        int linhaDoNetworks = -1;
        if (alvo != null) {
            for (int i = alvo.inicio() + 1; i < alvo.fim(); i++) {
                Matcher c = CHAVE_DO_SERVICO.matcher(m.l.get(i));
                if (!c.matches()) {
                    continue;
                }
                switch (c.group(1)) {
                    case "container_name" -> {
                        String v = semAspas(c.group(2));
                        if (v.isEmpty() || !ValidadorDeNomes.containerValido(v) || !v.matches("^[a-z0-9][a-z0-9_.-]{0,40}$")) {
                            motivos.add("O container_name que o serviço já tem (\"" + v + "\") tem um formato que o app não usa numa regra do túnel.");
                        } else {
                            nome = v;
                            temContainerName = true;
                        }
                    }
                    case "networks" -> {
                        temNetworks = true;
                        linhaDoNetworks = i;
                        if (!c.group(2).isBlank() && !c.group(2).strip().startsWith("#")) {
                            redesEmLinhaSo = true;
                            redesEmLinhaLegiveis = redesDeLinhaSo(c.group(2)) != null;
                        }
                    }
                    case "network_mode" -> {
                        motivos.add("O serviço usa network_mode, que não combina com a rede edge (com network_mode o container não entra em rede nenhuma do compose): "
                            + "troque o network_mode por redes, ou não publique este serviço pelo túnel.");
                        usaNetworkMode = true;
                    }
                    default -> { }
                }
            }
            if (redesEmLinhaSo && !redesEmLinhaLegiveis) {
                motivos.add("As redes do serviço estão escritas em uma linha só (entre colchetes ou chaves).");
            }
            for (Bloco outro : blocosDeServico(m)) {
                if (!outro.nome().equals(servico) && outroTemContainerName(m, outro, nome)) {
                    motivos.add("Outro serviço já usa o container_name \"" + nome + "\": o nome tem de ser único.");
                }
            }
        }
        // a rede edge no nível de cima
        boolean redeDeclarada = false;
        boolean redeAMenos = true;
        int linhaDaRedeEdge = -1;
        if (m.redesDeCima >= 0) {
            for (int i = m.redesDeCima + 1; i < m.fimDeRedesDeCima; i++) {
                String l = m.l.get(i);
                if (conteudo(l) && recuo(l) == 2 && l.strip().startsWith(REDE_EDGE + ":")) {
                    linhaDaRedeEdge = i;
                    redeDeclarada = true;
                }
            }
            if (redeDeclarada) {
                int fim = fimDoBloco(m.l, linhaDaRedeEdge + 1, 2);
                boolean externa = false;
                String resto = m.l.get(linhaDaRedeEdge).strip().substring((REDE_EDGE + ":").length()).strip();
                if (!resto.isEmpty() && !resto.startsWith("#")) {
                    motivos.add("A rede edge do arquivo está escrita em uma linha só.");
                }
                for (int i = linhaDaRedeEdge + 1; i < fim; i++) {
                    if (m.l.get(i).strip().replaceAll("\\s+#.*$", "").equals("external: true")) {
                        externa = true;
                    }
                }
                if (!externa) {
                    motivos.add("O arquivo já declara uma rede edge que não é externa (external: true): o app não a troca.");
                }
                redeAMenos = false;
            }
        }
        // com network_mode o bloco (networks: default e edge) CONTRADIZ o bloqueio: não há o que colar, só o motivo
        String bloco = usaNetworkMode ? "" : blocoParaCopiar(servico, nome, temContainerName, temNetworks, redesEmLinhaSo);
        if (!motivos.isEmpty()) {
            return new Proposta(Situacao.MANUAL, null, nome, motivos, bloco);
        }
        boolean servicoNaEdge = false;
        if (temNetworks) {
            servicoNaEdge = listaDeRedes(m, linhaDoNetworks, alvo.fim()).contains(REDE_EDGE);
        }
        if (temContainerName && servicoNaEdge && !redeAMenos) {
            return new Proposta(Situacao.PRONTO, texto, nome, List.of(), bloco);
        }
        // Editar: cada acréscimo tem a linha onde entra; aplicados do FIM para o começo, para as linhas de cima não mudarem de lugar (a rede de cima
        // do arquivo pode vir antes ou depois dos serviços).
        record Acrescimo(int onde, List<String> linhas) { }
        List<Acrescimo> acrescimos = new ArrayList<>();
        String linhaDasRedes = null; // a lista de redes em uma linha, com edge dentro dos colchetes (troca a linha no lugar; as demais só ganham linhas)
        if (redeAMenos) {
            if (m.redesDeCima < 0) {
                acrescimos.add(new Acrescimo(m.l.size(), List.of("", "networks:", "  " + REDE_EDGE + ":", "    external: true")));
            } else {
                acrescimos.add(new Acrescimo(m.fimDeRedesDeCima, List.of("  " + REDE_EDGE + ":", "    external: true")));
            }
        }
        if (!servicoNaEdge) {
            if (!temNetworks) {
                acrescimos.add(new Acrescimo(alvo.inicio() + 1, List.of("    networks:", "      - default", "      - " + REDE_EDGE)));
            } else if (redesEmLinhaSo) {
                String linha = m.l.get(linhaDoNetworks);
                int fecha = linha.indexOf(']');
                String antes = linha.substring(0, fecha);
                String separador = antes.stripTrailing().endsWith(",") ? " " : ", "; // com vírgula final, não repete a vírgula
                linhaDasRedes = antes + separador + REDE_EDGE + linha.substring(fecha);
            } else {
                boolean emLista = false;
                for (int i = linhaDoNetworks + 1; i < alvo.fim(); i++) {
                    String l = m.l.get(i);
                    if (!conteudo(l)) {
                        continue;
                    }
                    if (recuo(l) <= 4) {
                        break;
                    }
                    if (recuo(l) == 6 && l.strip().startsWith("- ")) {
                        emLista = true;
                    }
                }
                int onde = fimDoBloco(m.l, linhaDoNetworks + 1, 4);
                if (emLista) {
                    acrescimos.add(new Acrescimo(onde, List.of("      - " + REDE_EDGE)));
                } else if (onde == linhaDoNetworks + 1) {
                    acrescimos.add(new Acrescimo(onde, List.of("      - default", "      - " + REDE_EDGE))); // networks: sem nada embaixo
                } else {
                    acrescimos.add(new Acrescimo(onde, List.of("      " + REDE_EDGE + ":")));
                }
            }
        }
        if (!temContainerName) {
            acrescimos.add(new Acrescimo(alvo.inicio() + 1, List.of("    container_name: " + nome)));
        }
        List<String> novas = new ArrayList<>(m.l);
        if (linhaDasRedes != null) {
            novas.set(linhaDoNetworks, linhaDasRedes);
        }
        List<Acrescimo> ordenados = new ArrayList<>(acrescimos);
        ordenados.sort((x, y) -> Integer.compare(y.onde(), x.onde())); // estável: no mesmo lugar, o que veio depois entra primeiro na linha (container_name antes de networks)
        for (Acrescimo a : ordenados) {
            novas.addAll(a.onde(), a.linhas());
        }
        boolean terminaEmEol = texto.endsWith("\n");
        return new Proposta(Situacao.EDITAVEL, String.join("\n", novas) + (terminaEmEol ? "\n" : ""), nome, List.of(), bloco);
    }

    private static boolean outroTemContainerName(Mapa m, Bloco b, String nome) {
        for (int i = b.inicio() + 1; i < b.fim(); i++) {
            Matcher c = CHAVE_DO_SERVICO.matcher(m.l.get(i));
            if (c.matches() && c.group(1).equals("container_name") && semAspas(c.group(2)).equals(nome)) {
                return true;
            }
        }
        return false;
    }

    private static String blocoParaCopiar(String servico, String nome, boolean temContainerName, boolean temNetworks, boolean redesEmLinhaSo) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 1) No serviço \"").append(servico).append("\" do docker-compose.yml, dentro dele (com quatro espaços de recuo), acrescente");
        if (temContainerName) {
            sb.append(" (o container_name já existe: deixe como está)");
        }
        sb.append(":\n");
        if (!temContainerName) {
            sb.append("    container_name: ").append(nome).append('\n');
        }
        if (temNetworks && redesEmLinhaSo) {
            sb.append("    # em networks:, que está escrito em uma linha, acrescente ").append(REDE_EDGE)
                .append(" ao que já está lá, sem tirar nada (ou escreva as redes em linhas separadas, uma por linha, e acrescente - ").append(REDE_EDGE).append(")\n");
        } else if (temNetworks) {
            sb.append("    # em networks:, acrescente a linha:\n      - ").append(REDE_EDGE).append('\n');
        } else {
            sb.append("    networks:\n      - default\n      - ").append(REDE_EDGE).append('\n');
        }
        sb.append("\n# 2) No nível de cima do arquivo (sem recuo; se já existe um networks:, acrescente só o edge dentro dele), acrescente:\n");
        sb.append("networks:\n  ").append(REDE_EDGE).append(":\n    external: true\n");
        return sb.toString();
    }
}
