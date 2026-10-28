package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A regra da Cloudflare, como fronteira do código (o comportamento é provado nos testes do pacote {@code cloudflare} e dos diálogos): nada acontece na Cloudflare
 * nem no servidor antes do CLIQUE e da confirmação. Abrir a aba, o diálogo, a prévia, o Sondador, um timer ou o app subindo só LEEM (GET na API, SFTP de leitura,
 * {@code docker inspect}); as escritas (DNS, regras do túnel, config.yml, compose, reinício do cloudflared) só existem dentro de {@code executar}/{@code aplicar} e do desfazer
 * deles, alcançados só pelos botões "Publicar na internet" e "Remover". Nada de {@code cloudflared login}.
 */
class CloudflareSemEfeitoSozinhoTest {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");
    private static final String ACOES = "ui/AcoesDaInterface.java";
    private static final Pattern DECLARACAO_DE_METODO = Pattern.compile("(?m)^ {4,12}(?:@\\w+ )*(?:(?:public|private|protected|static|final|synchronized|default) )*[\\w<>\\[\\],.? ]+? (\\w+)\\([^;{}]*\\)(?: throws [\\w., ]+)? \\{$");

    private static List<Path> todos() throws IOException {
        try (Stream<Path> s = Files.walk(RAIZ)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String semComentarios(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean bloco = false;
        for (String linha : Files.readAllLines(p)) {
            String t = linha.strip();
            if (bloco) {
                bloco = !t.contains("*/");
                continue;
            }
            if (t.startsWith("/*")) {
                bloco = !t.contains("*/");
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            sb.append(linha).append('\n');
        }
        return sb.toString();
    }

    private static String codigo(String relativo) throws IOException {
        return semComentarios(RAIZ.resolve(relativo));
    }

    private static String nome(Path p) {
        return RAIZ.relativize(p).toString().replace('\\', '/');
    }

    /** Os arquivos (fora os permitidos) em que o padrão aparece. */
    private static List<String> quemUsa(Pattern padrao, Set<String> permitidos) throws IOException {
        List<String> fora = new ArrayList<>();
        for (Path p : todos()) {
            if (!permitidos.contains(nome(p)) && padrao.matcher(semComentarios(p)).find()) {
                fora.add(nome(p));
            }
        }
        return fora;
    }

    /** O nome do método que contém a posição (o último declarado antes dela). */
    private static String metodoDe(String codigo, int posicao) {
        Matcher m = DECLARACAO_DE_METODO.matcher(codigo.substring(0, posicao));
        String ultimo = "(fora de método)";
        while (m.find()) {
            ultimo = m.group(1);
        }
        return ultimo;
    }

    /** Os métodos em que o padrão aparece, num arquivo. */
    private static Set<String> metodosQueUsam(String relativo, Pattern padrao) throws IOException {
        String c = codigo(relativo);
        java.util.TreeSet<String> r = new java.util.TreeSet<>();
        Matcher m = padrao.matcher(c);
        while (m.find()) {
            int ini = c.lastIndexOf('\n', m.start()) + 1;
            int fim = c.indexOf('\n', m.start());
            if (DECLARACAO_DE_METODO.matcher(c.substring(ini, fim < 0 ? c.length() : fim)).matches()) {
                continue; // a própria declaração do método não é uma chamada
            }
            r.add(metodoDe(c, m.start()));
        }
        return r;
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void asEscritasDaApiSoExistemEmTresMetodosEFalamUmMetodoHttpCadaUm() throws IOException {
        String c = codigo("cloudflare/ClienteCloudflare.java");
        Matcher m = Pattern.compile("chamar\\(\"(\\w+)\"[^;]*?, (true|false)\\)").matcher(c);
        int escritas = 0;
        while (m.find()) {
            boolean escrita = !m.group(1).equals("GET");
            assertEquals(escrita, m.group(2).equals("true"), "o último argumento de chamar() diz se é escrita: " + m.group(0));
            if (escrita) {
                escritas++;
                assertTrue(Set.of("gravarConfiguracaoDoTunel", "criarCname", "removerDns").contains(metodoDe(c, m.start())), "escrita fora dos três métodos: " + metodoDe(c, m.start()));
            }
        }
        assertEquals(3, escritas, "PUT da configuração do túnel, POST do CNAME e DELETE do DNS: nada mais escreve na Cloudflare");
        assertFalse(Pattern.compile("\"PATCH\"|\"PUT\"").matcher(c.replace("\"PUT\", \"/accounts", "")).find(), "um PUT só, o da configuração do túnel");
    }

    @Test
    void quemChamaAsEscritasDaApiSoEstaNoPublicadorNoRemovedorENoTunelPelaApiDentroDeExecutarEDesfazer() throws IOException {
        Pattern escrita = Pattern.compile("\\.(criarCname|removerDns|gravarConfiguracaoDoTunel)\\(");
        assertEquals(List.of(), quemUsa(escrita, Set.of("cloudflare/ClienteCloudflare.java", "cloudflare/ApiCloudflare.java", "cloudflare/PublicadorNaInternet.java",
            "cloudflare/RemovedorDeEndereco.java", "cloudflare/TunelPelaApi.java")));
        assertEquals(Set.of("criarDns"), metodosQueUsam("cloudflare/PublicadorNaInternet.java", escrita), "criar e desfazer o DNS: só em criarDns, que só o executar chama");
        assertEquals(Set.of("apagarDns"), metodosQueUsam("cloudflare/RemovedorDeEndereco.java", escrita));
        assertEquals(Set.of("executar"), metodosQueUsam("cloudflare/RemovedorDeEndereco.java", Pattern.compile("(?<![\\w.])apagarDns\\(")), "apagarDns só nasce do executar");
        assertEquals(Set.of("aplicar", "desfazer"), metodosQueUsam("cloudflare/TunelPelaApi.java", escrita));
        // criarDns só é chamado dentro de executar
        assertEquals(Set.of("executar"), metodosQueUsam("cloudflare/PublicadorNaInternet.java", Pattern.compile("(?<![\\w.])criarDns\\(previa")));
        assertEquals(Set.of("executar"), metodosQueUsam("cloudflare/PublicadorNaInternet.java", Pattern.compile("(?<![\\w.])comporNoServidor\\(")));
    }

    @Test
    void osComandosQueMudamOServidorSoNascemNoTunelPorArquivoDentroDeAplicar() throws IOException {
        Pattern comandos = Pattern.compile("\\bComandosCloudflared\\.");
        assertEquals(List.of(), quemUsa(comandos, Set.of("cloudflare/TunelPorArquivo.java", "cloudflare/ComandosCloudflared.java")),
            "inspecionar, conferir as regras e reiniciar só nascem no túnel por arquivo");
        String t = codigo("cloudflare/TunelPorArquivo.java");
        Matcher m = Pattern.compile("ComandosCloudflared\\.(\\w+)\\(").matcher(t);
        while (m.find()) {
            String metodo = metodoDe(t, m.start());
            if (m.group(1).equals("inspecionar")) {
                continue; // docker inspect é só leitura
            }
            assertTrue(Set.of("conferirComOCloudflared", "reiniciar").contains(metodo), m.group(1) + " em " + metodo);
        }
        // conferir com o cloudflared e reiniciar só são chamados por aplicar e pelo desfazer dele (devolver o arquivo e reiniciar de novo)
        assertEquals(Set.of("aplicar", "desfazer"), metodosQueUsam("cloudflare/TunelPorArquivo.java", Pattern.compile("(?<![\\w.])(conferirComOCloudflared|reiniciar)\\(\\)")));
        // gravar o config.yml: só aplicar e o desfazer (restaurar)
        assertEquals(Set.of("aplicar", "restaurar"), metodosQueUsam("cloudflare/TunelPorArquivo.java", Pattern.compile("\\.gravarNoLugar\\(")));
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.gravarNoLugar\\("), Set.of("cloudflare/TunelPorArquivo.java", "arquivos/ClienteSftp.java", "arquivos/ClienteSftpMina.java",
            "arquivos/ClienteSftpLocal.java")));
        // nenhum comando do cloudflared que cria túnel, mexe em login ou em DNS pelo programa
        for (String proibido : List.of("tunnel login", "\"tunnel\", \"login\"", "cloudflared login", "tunnel create", "route dns", "tunnel delete", "--overwrite-dns", "service install")) {
            // (só o texto do diálogo Conectar diz, ao usuário, que o app NÃO usa o cloudflared login)
            assertEquals(List.of(), quemUsa(Pattern.compile(Pattern.quote(proibido)), proibido.equals("cloudflared login") ? Set.of("ui/dialogos/DialogoConectarCloudflare.java") : Set.of()),
                proibido + " não existe no app");
        }
        // e o cloudflared só é reiniciado por docker restart, do container que o usuário escolheu (nunca o servidor, nunca o Docker)
        String comandosDoCloudflared = codigo("cloudflare/ComandosCloudflared.java");
        assertFalse(comandosDoCloudflared.contains("\"reboot\"") || comandosDoCloudflared.contains("systemctl") || comandosDoCloudflared.contains("\"sh\"") || comandosDoCloudflared.contains("\"-c\""),
            "nunca um shell, nunca systemctl, nunca reiniciar a máquina");
    }

    @Test
    void oComposeEOComandoDeConferenciaSoSaoMexidosPeloExecutarDoPublicador() throws IOException {
        Pattern escrita = Pattern.compile("\\.(gravarAtomico|gravarNoLugar|renomear|escrever|criarArquivo|apagar|enviar)\\(|ComandosDeProjeto\\.validarCompose\\(|aplicador\\.aplicar\\(");
        Set<String> metodos = metodosQueUsam("cloudflare/PublicadorNaInternet.java", escrita);
        assertTrue(Set.of("comporNoServidor", "conferirComODocker", "executar", "reverter", "criarDns").containsAll(metodos), "escrita fora dos passos do executar: " + metodos);
        assertEquals(Set.of("executar"), metodosQueUsam("cloudflare/PublicadorNaInternet.java", Pattern.compile("(?<![\\w.])reverter\\(")));
        assertEquals(Set.of("comporNoServidor"), metodosQueUsam("cloudflare/PublicadorNaInternet.java", Pattern.compile("(?<![\\w.])conferirComODocker\\(")));
        // a prévia (preparar) só lê
        String c = codigo("cloudflare/PublicadorNaInternet.java");
        String preparar = c.substring(c.indexOf("public Previa preparar("), c.indexOf("private void validar("));
        assertFalse(escrita.matcher(preparar).find(), "preparar não escreve nada");
        assertFalse(Pattern.compile("\\.(criarCname|removerDns|gravarConfiguracaoDoTunel)\\(|\\.aplicar\\(|\\.executar\\(").matcher(preparar).find(), "preparar não aplica nada");
        String r = codigo("cloudflare/RemovedorDeEndereco.java");
        String prepararRemocao = r.substring(r.indexOf("public Previa preparar("), r.indexOf("public Resultado executar("));
        assertFalse(Pattern.compile("\\.(criarCname|removerDns|gravarConfiguracaoDoTunel)\\(|\\.aplicar\\(|\\.executar\\(").matcher(prepararRemocao).find(), "a prévia da remoção só lê");
    }

    @Test
    void osPontosDeEntradaSaoBotoesESoAAcaoDoCliqueMontaPublicadorERemovedor() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew (PublicadorNaInternet|RemovedorDeEndereco|ListadorDeEnderecos|TunelPorArquivo|TunelPelaApi)\\("), Set.of("cloudflare/ServicoCloudflare.java")),
            "só a fachada monta quem publica, remove e lista");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.(publicador|removedor)\\("), Set.of("cloudflare/ServicoCloudflare.java", ACOES)),
            "só AcoesDaInterface (pedida pelo botão) pede o publicador e o removedor");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.publicarNaInternet\\("), Set.of(ACOES, "ui/telas/TelaProjetos.java")),
            "só a lista de projetos (a serviço dos botões da página) chama as ações");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.removerEnderecoDaInternet\\("), Set.of(ACOES, "ui/telas/TelaProjetos.java", "ui/telas/PaginaCloudflare.java")),
            "e o botão Remover da aba Cloudflare do servidor");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.(publicarDe|removerEnderecoDe)\\("), Set.of("ui/telas/TelaServidor.java")));
        // as ações de escrita só valem com a trava das ações
        String t = codigo("ui/telas/TelaProjetos.java");
        for (String metodo : List.of("void publicarDe(", "void removerEnderecoDe(")) {
            int i = t.indexOf(metodo);
            assertTrue(i > 0 && t.substring(i, t.indexOf("\n    }\n", i)).contains("!fluxos.liberadas()"), metodo + " recusa sem as ações liberadas");
        }
        // as páginas: botão de publicar e de remover só existem com as ações liberadas
        assertTrue(codigo("ui/telas/PaginaInternet.java").contains("if (ctx.acoesLiberadas()) {\n            caixa.linha(Caixa.linha(8, publicar, atualizar"));
        assertTrue(codigo("ui/telas/PaginaCloudflare.java").contains("new ListaDeEnderecos(true, acoesLiberadas,"));
        assertTrue(codigo("ui/telas/PaginaInternet.java").contains("new ListaDeEnderecos(false, ctx.acoesLiberadas(),"));
        assertTrue(codigo("ui/telas/ListaDeEnderecos.java").contains("if (comRemover) {"));
    }

    @Test
    void oQueRodaAoAbrirAbasESondadoresSoLe() throws IOException {
        // as páginas só chamam estas leituras da fachada
        for (String pagina : List.of("ui/telas/PaginaCloudflare.java", "ui/telas/PaginaInternet.java")) {
            String c = codigo(pagina);
            Matcher m = Pattern.compile("(?:\\bs|cloudflare\\(\\))\\.(\\w+)\\(").matcher(c);
            while (m.find()) {
                assertTrue(Set.of("ehLocal", "conectado", "escolha", "enderecos", "conferirToken").contains(m.group(1)), pagina + " chama " + m.group(1));
            }
            assertFalse(Pattern.compile("Fundo\\.agenda|Agendador|ScheduledExecutor|javax\\.swing\\.Timer|new Timer\\(").matcher(c).find(), pagina + " não agenda nada");
        }
        // o ServidorLab (que conecta sozinho) só MONTA a fachada: ela é preguiçosa e nada é lido ao montar
        String lab = codigo("servidor/ServidorLab.java");
        assertEquals(1, Pattern.compile("new br\\.com\\.labdesktop\\.cloudflare\\.ServicoCloudflare\\(").matcher(lab).results().count());
        assertFalse(Pattern.compile("cloudflare\\(\\)\\.").matcher(lab).find(), "o ServidorLab não usa a Cloudflare por conta própria");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.cloudflare\\(\\)"), Set.of(ACOES, "ui/telas/PaginaCloudflare.java", "ui/telas/PaginaInternet.java", "servidor/ServidorLab.java")),
            "só a interface (ação do clique e as duas abas) usa a fachada");
        // a fachada: o construtor não lê nada; quem lê o cofre ou a rede é cada método, chamado a pedido
        String s = codigo("cloudflare/ServicoCloudflare.java");
        String construtor = s.substring(s.indexOf("public ServicoCloudflare("), s.indexOf("public static final String RAIZ_DOS_PROJETOS"));
        assertFalse(Pattern.compile("cofre\\.\\w+\\(|clienteDoCofre\\(|ambientes\\.apply\\(").matcher(construtor).find(), "montar a fachada não lê o cofre nem abre conexão");
        // e o Conectar só LÊ da Cloudflare: o diálogo usa descobrir (GETs) e salvar (cofre), nunca as escritas
        String d = codigo("ui/dialogos/DialogoConectarCloudflare.java");
        assertFalse(Pattern.compile("publicador|removedor|criarCname|removerDns|gravarConfiguracaoDoTunel|gravarNoLugar").matcher(d).find());
    }

    @Test
    void oTokenSoNasceDoCofreENuncaEGravadoEmOutroLugar() throws IOException {
        // quem lê o token do cofre
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.tokenCloudflare\\("), Set.of("cloudflare/ServicoCloudflare.java", "cofre/AcessoAoCofre.java", "cofre/CofreDeChaves.java")),
            "só a fachada lê o token do cofre");
        // quem grava
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.gravarCloudflare\\("), Set.of("cloudflare/ServicoCloudflare.java", "cofre/AcessoAoCofre.java", "cofre/CofreDeChaves.java")));
        // o token nunca vai a log, ao disco fora do cofre nem à URL
        for (Path p : todos()) {
            String c = semComentarios(p);
            if (nome(p).startsWith("cloudflare/") || nome(p).equals("cofre/TokenCloudflare.java")) {
                assertFalse(Pattern.compile("(?i)(log|LOG|logger)\\.\\w+\\([^;]*(token|segredo)").matcher(c).find(), nome(p) + " não manda o token ao log");
                assertFalse(Pattern.compile("Files\\.(write|writeString|newBufferedWriter)\\(").matcher(c).find(), nome(p) + " não grava arquivo local");
            }
        }
        // o token de Cloudflare vai só no cabeçalho
        String c = codigo("cloudflare/ClienteCloudflare.java");
        assertEquals(1, Pattern.compile("cabecalhoAuthorization\\(\\)").matcher(c).results().count(), "o token entra num lugar só: o cabeçalho Authorization");
        assertFalse(c.contains("valorParaFiltro") && c.contains("uri") && Pattern.compile("query[^;]*valorParaFiltro").matcher(c).find(), "nem em query string");
        // nunca ao servidor: nenhum comando do cloudflared recebe token
        String comandos = codigo("cloudflare/ComandosCloudflared.java");
        assertFalse(Pattern.compile("(?i)token").matcher(comandos).find(), "os comandos do servidor não levam token");
    }
}
