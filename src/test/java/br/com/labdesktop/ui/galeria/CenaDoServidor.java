package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteFalso.Pedido;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.FerramentasFalsasDeTunel;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.MocksDoFront;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.LancadorDeProcesso;
import br.com.labdesktop.transporte.ProcessoExterno;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.EstiloDeLaf;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.FluxosDeProjeto;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.util.Json;
import br.com.labdesktop.util.Preferencias.Tema;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.sshd.sftp.server.DefaultGroupPrincipal;
import org.apache.sshd.sftp.server.DefaultUserPrincipal;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

/**
 * O mundo das imagens de galeria das abas do SERVIDOR: um servidor vivo (SSH embutido e agente de mentira, o mesmo caminho da janela de verdade) chamado
 * "servidor-demo", com projetos, containers, processos e registro de segurança INVENTADOS (nomes genéricos, usuários ana e beto, endereços de documentação
 * 192.0.2.x, 198.51.100.x e 203.0.113.x). Os JSONs vêm dos mocks do repositório; só os nomes são trocados na hora e as sessões, o registro e o firewall são
 * escritos aqui. Nada vem de um servidor real.
 */
final class CenaDoServidor implements AutoCloseable {

    static final Duration PRAZO = Duration.ofSeconds(40);
    static final String NOME = "servidor-demo";
    /** O número do processo comum (fora de container) da lista: inventado. */
    static final int PID_DO_PROCESSO_COMUM = 28_417;

    final CenarioDeUi cenario;
    final GerenciadorServidores gerenciador;
    final ServidorLab lab;
    final AvisosGravados avisos = new AvisosGravados();
    final DialogosGravados dialogos = new DialogosGravados();
    final PainelPrincipal painel;
    /** O disco do servidor de exemplo (o SFTP enxerga esta pasta como a raiz "/"). */
    final Path raizDoSftp;
    private final String acoesAntes;
    /** O que o {@code sshd -T} do servidor responde (a Segurança mostra a senha ligada ou desligada). */
    volatile String sshd = SegurancaDeExemplo.SSHD_COM_SENHA;
    /** O registro do SSH tem logins aceitos (de ana e beto, de fora da rede local: o Resumo e a Segurança os destacam). */
    volatile boolean loginsNoRegistro = true;
    /** O registro do SSH tem um login aceito por senha (de fora da rede local), o que a Segurança destaca como atenção. */
    volatile boolean loginPorSenhaNoRegistro = true;

    /** As variações da cena: o agente (perfil), a pressa do SSH (para o ritmo lento chegar logo) e se o servidor é alcançado por AWS SSM (com a ferramenta falsa). */
    static final class Opcoes {
        String perfil = "lab";
        String nome = NOME;
        ConfigSsh ssh = ConfigSsh.rapida();
        boolean viaSsm;

        Opcoes perfil(String novo) {
            this.perfil = novo;
            return this;
        }

        Opcoes nome(String novo) {
            this.nome = novo;
            return this;
        }

        Opcoes ritmoLentoLogo() {
            this.ssh = ConfigSsh.rapida().comRitmoLento(3, Duration.ofMinutes(5));
            return this;
        }

        Opcoes viaSsm() {
            this.viaSsm = true;
            return this;
        }
    }

    /** O lançador da ferramenta do túnel: normal, ou como se o login da AWS tivesse expirado (a mesma frase que a aws de verdade escreve). */
    private static final class LancadorAlternavel implements LancadorDeProcesso {
        private final LancadorDeFerramentaFalsa normal;
        private final LancadorDeFerramentaFalsa expirado;
        volatile boolean loginExpirou;

        LancadorAlternavel(int portaDoSsh) {
            normal = new LancadorDeFerramentaFalsa(portaDoSsh, Map.of());
            expirado = new LancadorDeFerramentaFalsa(portaDoSsh, Map.of("LABTEST_SAIR_LOGO_TEXTO", "The SSO session associated with this profile has expired or is otherwise invalid."));
        }

        @Override
        public ProcessoExterno lancar(List<String> argumentos, Map<String, String> ambienteExtra) throws java.io.IOException {
            return (loginExpirou ? expirado : normal).lancar(argumentos, ambienteExtra);
        }
    }

    private final LancadorAlternavel lancador;
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);

    CenaDoServidor(Path tmp) throws Exception {
        this(tmp, new Opcoes());
    }

    CenaDoServidor(Path tmp, String perfil) throws Exception {
        this(tmp, new Opcoes().perfil(perfil));
    }

    CenaDoServidor(Path tmp, Opcoes o) throws Exception {
        acoesAntes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true"); // os botões que escrevem no servidor aparecem ligados, como na janela de verdade
        Visual.tema(Tema.CLARO);
        cenario = new CenarioDeUi(PedidoDeCodigo2fa.NUNCA, false);
        raizDoSftp = tmp.resolve("servidor");
        Files.createDirectories(raizDoSftp);
        cenario.ssh.comSftp(raizDoSftp.toRealPath()); // o gerenciador de arquivos lê esta pasta, que tem a forma do servidor (/srv/lab/projects/...)
        // o dono dos arquivos que o SFTP informa seria o usuário do Mac que roda o teste: o servidor de exemplo diz que é "labuser"
        cenario.ssh.sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().withFileSystemAccessor(new DonoDeExemplo()).build()));
        cenario.agente.perfil = o.perfil;
        cenario.agente.historico = true;
        cenario.agente.excecoes = this::responder;
        var base = cenario.agente.comandosPorSsh();
        cenario.ssh.comandoRemoto((linha, entrada, saida) -> {
            String r = SegurancaDeExemplo.responder(linha, sshd, loginsNoRegistro, loginPorSenhaNoRegistro);
            if (r != null) {
                saida.append(r);
                return 0;
            }
            if (linha.startsWith("git --version")) { // o servidor tem o git: o bloco do Git na aba Máquina mostra a versão
                saida.append("git version 2.43.0\n");
                return 0;
            }
            return base.executar(linha, entrada, saida);
        });
        // o nome do servidor na tela ("servidor-demo" em vez de "lab") e a marca "protegido" automática recusada: o agente do lab de fora não vira selo na imagem
        var cfg = cenario.cofre.servidor(CofreFalso.ID).orElseThrow();
        Transporte transporte = o.viaSsm ? Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22) : Transporte.DIRETO;
        cenario.cofre.alterarServidor(CofreFalso.ID, c -> c.editar(o.nome, transporte, cfg.host(), cfg.porta(), cfg.usuario(), cfg.portaAgente()).comPin(cenario.ssh.pin())
            .comProtegido(true).comProtegido(false));
        // o servidor do cenário foi criado com o nome antigo: este gerenciador nasce depois da edição e já lê o nome novo
        lancador = new LancadorAlternavel(cenario.ssh.porta());
        gerenciador = o.viaSsm
            ? new GerenciadorServidores(cenario.cofre, PedidoDeCodigo2fa.NUNCA, o.ssh, ServicosLocais.padrao(), FerramentasFalsasDeTunel.abridor(lancador, registro))
            : new GerenciadorServidores(cenario.cofre, PedidoDeCodigo2fa.NUNCA, o.ssh);
        lab = gerenciador.servidor(CofreFalso.ID).orElseThrow();
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO && (!"lab".equals(o.perfil) || lab.estado().limites() != null));
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, (AssistenteDeServidor) null, avisos, dialogos, new NavegadorGravado(avisos),
            new ServicosDeArquivos(MemoriaDeCaminhos.emMemoria(), tmp.resolve("backups"))));
    }

    /** O servidor passa a não voltar: o SSH de exemplo sai do ar e, no modo AWS, o login da AWS expira. Espera o ritmo lento (uma tentativa a cada 5 minutos). */
    void perderAConexao() throws java.io.IOException {
        lancador.loginExpirou = true;
        registro.encerrarTodos(); // o túnel da AWS cai (sem túnel, não faz nada); o SSH direto cai com o servidor
        cenario.ssh.close();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.RECONECTANDO && lab.estado().ritmoLento() && lab.estado().falha() != null);
    }

    // ---------------------------------------------------------------------------------------------
    // O que o agente de mentira responde
    // ---------------------------------------------------------------------------------------------

    private Resposta responder(Pedido p) {
        return switch (p.caminho()) {
            case "/api/projetos" -> Resposta.json(200, texto(projetosNo()));
            case "/api/limites" -> Resposta.json(200, texto(limitesNo()));
            case "/api/servidor" -> Resposta.json(200, texto(servidorNo()));
            case "/api/servidor/detalhe" -> Resposta.json(200, texto(detalheNo()));
            default -> null;
        };
    }

    /** Troca os nomes dos mocks (que ainda lembram os de um projeto real) por nomes genéricos. */
    static String renomear(String json) {
        return json.replace("fit-app", "loja-web").replace("ponto-api", "api-pedidos").replace("bazar", "blog")
            .replace("\"vendas\"", "\"painel-vendas\"").replace("vendas-", "painel-vendas-")
            .replace("lab-vendas.example.com", "painel.example.com").replace("lab-app-loja.example.com", "loja.example.com")
            .replace("fit.example.com", "loja-web.example.com").replace("servidor-exemplo", NOME)
            .replace("AMD Ryzen 5 5600G", "Processador Exemplo de 8 núcleos");
    }

    /** {@code /api/projetos}: os cinco projetos genéricos, todos dentro dos limites do plano (nada pausado: a imagem mostra a lista, não os avisos de limite). */
    private static ObjectNode projetosNo() {
        ObjectNode raiz = ler(renomear(MocksDoFront.ler("projetos.json")));
        java.util.Map<String, String> descricoes = java.util.Map.of("loja-web", "Loja virtual: catálogo, carrinho e pedidos.", "painel-vendas", "Painel de vendas do mês.",
            "app-loja", "App da loja: pedidos e cardápio.", "api-pedidos", "API de pedidos (parada).", "blog", "Blog da empresa.");
        for (var projeto : raiz.withArray("projetos")) {
            ObjectNode p = (ObjectNode) projeto;
            p.put("descricao", descricoes.getOrDefault(p.path("nome").asText(), p.path("descricao").asText()));
            if (p.path("limite").isObject()) {
                ObjectNode l = (ObjectNode) p.get("limite");
                l.put("estado", "ok");
                l.put("liberado", true);
                l.putNull("pausa");
                l.put("usado_pp", Math.min(l.path("usado_pp").asDouble(), 9.5));
                if ("loja-web".equals(p.path("nome").asText())) { // o mesmo número da Visão geral do projeto loja-web (2,0 % de 20 %)
                    l.put("usado_pp", 1.96);
                    l.put("teto_pct", 20);
                }
            }
        }
        return raiz;
    }

    /** {@code /api/limites}: o plano do Claude com folga (34% das 5 horas, 41% da semana, nenhuma sessão pausada). */
    private static ObjectNode limitesNo() {
        ObjectNode raiz = ler(renomear(MocksDoFront.ler("limites.json")));
        ((ObjectNode) raiz.get("cinco_horas")).put("usado_pct", 34);
        ((ObjectNode) raiz.get("semana")).put("usado_pct", 41);
        ((ObjectNode) raiz.get("freio")).put("ativo", false);
        raiz.putArray("pausados");
        raiz.get("por_projeto").properties().forEach(e -> {
            if (e.getValue() instanceof ObjectNode p && p.has("estado")) {
                p.put("estado", "ok");
                p.put("usado_pp", "loja-web".equals(e.getKey()) ? 1.96 : Math.min(p.path("usado_pp").asDouble(), 9.5));
                if ("loja-web".equals(e.getKey())) {
                    p.put("teto_pct", 20);
                }
            }
        });
        return raiz;
    }

    private static String texto(ObjectNode no) {
        try {
            return Json.mapper().writeValueAsString(no);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode ler(String json) {
        try {
            return (ObjectNode) Json.mapper().readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code /api/servidor}: o mock com os nomes trocados e três sessões do Claude abertas (projeto, container, desde quando, CPU e memória). */
    private static ObjectNode servidorNo() {
        ObjectNode raiz = ler(renomear(MocksDoFront.ler("servidor.json")));
        ArrayNode sessoes = Json.mapper().createArrayNode();
        Instant agora = Instant.now();
        String[][] dados = {{"loja-web", "197132288", "4.8", "190"}, {"painel-vendas", "164536320", "2.1", "75"}, {"app-loja", "231735296", "6.4", "25"}};
        for (String[] d : dados) {
            ObjectNode s = sessoes.addObject();
            s.put("projeto", d[0]);
            s.put("container", "lab-claude-" + d[0]);
            s.put("desde", agora.minusSeconds(Long.parseLong(d[3]) * 60).toString());
            s.put("cpu", Double.parseDouble(d[2]));
            s.put("mem", Long.parseLong(d[1]));
            s.putArray("redes").add("bridge").add(d[0] + "_default");
        }
        raiz.set("sessoes", sessoes);
        raiz.put("quando", agora.toString());
        // um programa comum do usuário (fora de container) entre os que mais gastam: é o que a imagem de "Encerrar" escolhe
        ObjectNode comum = raiz.withArray("processos").insertObject(3);
        comum.put("pid", PID_DO_PROCESSO_COMUM);
        comum.put("nome", "node");
        comum.put("usuario", "labuser");
        comum.put("cpu", 4.4);
        comum.put("mem", 131_072_000L);
        comum.putNull("container");
        return raiz;
    }

    /**
     * {@code /api/servidor/detalhe}: a MESMA máquina do {@code /api/servidor} (para a aba Máquina e a Processos não se contradizerem), mais sensores, energia,
     * hardware e o projeto de cada processo.
     */
    private static ObjectNode detalheNo() {
        ObjectNode raiz = servidorNo();
        ObjectNode sintetico = ler(br.com.labdesktop.api.FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json"));
        for (String campo : new String[] {"sensores", "energia", "maquina"}) {
            raiz.set(campo, sintetico.get(campo));
        }
        raiz.set("node", sintetico.get("node"));
        raiz.put("sistema", "Debian GNU/Linux 12 (bookworm)");
        raiz.put("kernel", "6.1.0-exemplo-amd64");
        ObjectNode maquina = (ObjectNode) raiz.get("maquina");
        maquina.put("cpuModelo", "Processador Exemplo de 8 núcleos");
        maquina.put("nucleos", 8);
        maquina.put("memoriaTotal", 33_654_927_360L);
        for (var processo : raiz.withArray("processos")) {
            String container = processo.path("container").asText(null);
            String projeto = "(servidor)";
            if (container != null) {
                projeto = container.startsWith("lab-claude-") ? container.substring("lab-claude-".length()) : container.replaceAll("-(web|api|db|worker|redis|proxy)-\\d+$", "");
            }
            ((ObjectNode) processo).put("projeto", projeto);
        }
        return raiz;
    }

    // ---------------------------------------------------------------------------------------------
    // A janela
    // ---------------------------------------------------------------------------------------------

    TelaServidor tela() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    /** Mostra a aba do servidor (como o clique no rótulo) e, nas que são páginas, assina os tópicos que a janela de verdade assinaria ao aparecer. */
    void abrir(Aba aba) {
        Visual.naEdt(() -> painel.navegacao().mostrarServidor(lab, aba));
        TelaServidor t = tela();
        Visual.naEdt(() -> {
            if (t.pagina(aba) != null) {
                t.pagina(aba).assinar();
            }
        });
    }

    /** Os textos que a janela mostra agora (só o que está visível: as outras abas guardam os seus, escondidos). */
    String textos() {
        return Visual.naEdt(() -> {
            StringBuilder sb = new StringBuilder();
            coletar(painel, sb);
            return sb.toString();
        });
    }

    static String textosDe(java.awt.Component raiz) {
        return Visual.naEdt(() -> {
            StringBuilder sb = new StringBuilder();
            coletar(raiz, sb);
            return sb.toString();
        });
    }

    private static void coletar(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
        }
        if (c instanceof javax.swing.JPasswordField) {
            return;
        }
        if (c instanceof javax.swing.JLabel l && l.getText() != null && !l.getText().isEmpty()) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.text.JTextComponent t && t.getText() != null && !t.getText().isEmpty()) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo s && !s.texto().isEmpty()) {
            sb.append(s.texto()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null && !b.getText().isEmpty()) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                coletar(f, sb);
            }
        }
    }

    /** Espera a tela ter todos os textos e nenhum "Carregando". */
    void esperarTextos(String descricao, String... esperados) {
        Espera.ate(PRAZO, () -> descricao + ": " + textos().replace('\n', '|'), () -> {
            String t = textos();
            if (t.contains("Carregando") || t.contains("lendo…") || t.contains("sem dado ainda")) {
                return false;
            }
            for (String e : esperados) {
                if (!t.contains(e)) {
                    return false;
                }
            }
            return true;
        });
    }

    /** Confere que o que está na imagem é fictício e cabe na janela, e grava a imagem nos dois temas. */
    void fotografar(String nome) {
        fotografar(nome, 860);
    }

    /** Igual, com a altura da janela escolhida (uma tela mais comprida que o normal, como o Sobre, precisa de mais). */
    void fotografar(String nome, int altura) {
        String t = textos();
        assertTrue(t.contains(lab.estado().nome()), "o servidor se chama " + lab.estado().nome() + ": " + t);
        confereDadosInventados(t);
        confereSemTransbordo();
        if (deveGravar(nome)) {
            Visual.galeriaNosDoisTemas(painel, 1440, altura, nome, "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
        }
    }

    /** As imagens "-generico" só saem quando a suíte roda com {@code -Plabdesktop.laf=generico}; as outras, só no estilo de sempre (senão uma sobrescreveria a outra). */
    static boolean ehGenerico() {
        return "generico".equalsIgnoreCase(System.getProperty(EstiloDeLaf.PROPRIEDADE));
    }

    static boolean deveGravar(String nome) {
        return nome.endsWith("-generico") == ehGenerico();
    }

    /** Um diálogo na galeria (moldura de diálogo, título dele), nos dois temas e na largura que a janela de verdade teria. */
    static void fotografarDialogo(br.com.labdesktop.ui.dialogos.Dialogo d, String nome) {
        String texto = textosDe(d.raiz());
        confereDadosInventados(texto);
        br.com.labdesktop.ui.dialogos.Dialogo.definirAlturaDaTelaParaTeste(1700);
        try {
            for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
                Visual.tema(t);
                Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
                int largura = Math.max(560, Visual.naEdt(() -> d.raiz().getPreferredSize().width));
                int altura = Visual.alturaPreferida(d.raiz(), largura);
                List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(d.raiz()));
                assertTrue(achados.isEmpty(), "o diálogo transborda: " + achados);
                if (deveGravar(nome)) {
                    Visual.salvarNaGaleria(Visual.renderizar(d.raiz(), largura, altura), nome + (t == Tema.CLARO ? "-claro" : "-escuro"), d.titulo(), t == Tema.ESCURO,
                        MolduraDeJanela.Tipo.DIALOGO);
                }
            }
        } finally {
            br.com.labdesktop.ui.dialogos.Dialogo.definirAlturaDaTelaParaTeste(0);
            Visual.tema(Tema.CLARO);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
        }
    }

    void confereSemTransbordo() {
        Visual.naEdt(() -> painel.setSize(1440, 860));
        List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(painel));
        assertTrue(achados.isEmpty(), "a tela transborda: " + achados);
    }

    /** Nada do computador de teste na imagem: caminhos, usuário do Mac, endereços que não sejam de documentação. */
    static void confereDadosInventados(String texto) {
        String limpo = texto.replace("/Users/ana/", ""); // a pasta de usuário inventada (ana) do "Este computador" é a única permitida
        for (String proibido : new String[] {"/Users/", "/var/folders", "/private/", ComputadorDeTeste.usuario(), ComputadorDeTeste.casa(), "/tmp/"}) {
            assertTrue(!limpo.contains(proibido), "dado do computador de teste na tela (" + proibido + "): " + texto);
        }
        // versões (do Java, do sistema, do app) também têm quatro números separados por ponto: não são endereços
        String semVersoes = texto;
        for (String versao : new String[] {System.getProperty("java.version", "?"), System.getProperty("os.version", "?"), br.com.labdesktop.Versao.atual()}) {
            semVersoes = semVersoes.replace(versao, "");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b").matcher(semVersoes);
        while (m.find()) {
            String ip = m.group();
            // o endereço 127.0.0.1:porta do servidor de teste é trocado por Visual.galeriaNosDoisTemas; qualquer outro tem de ser de documentação
            boolean doc = ip.startsWith("192.0.2.") || ip.startsWith("198.51.100.") || ip.startsWith("203.0.113.") || ip.equals("127.0.0.1") || ip.equals("0.0.0.0");
            assertTrue(doc, "endereço que não é de documentação: " + ip);
        }
    }

    /** SHA256: sintética (o SHA-256 de um texto de fixture, em base64: parece de verdade, mas não é de chave nenhuma). */
    static String impressao(int n) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("fixture-sintetica-galeria-a1-" + n).getBytes(StandardCharsets.UTF_8));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O SFTP do servidor de exemplo informa sempre o mesmo dono e grupo inventados (o do disco de teste é o usuário do computador). */
    private static final class DonoDeExemplo implements SftpFileSystemAccessor {
        @Override
        public java.util.NavigableMap<String, Object> resolveReportedFileAttributes(SftpSubsystemProxy sftp, java.nio.file.Path file, int flags,
            java.util.NavigableMap<String, Object> attrs, java.nio.file.LinkOption... options) throws java.io.IOException {
            java.util.NavigableMap<String, Object> r = SftpFileSystemAccessor.super.resolveReportedFileAttributes(sftp, file, flags, attrs, options);
            if (r.containsKey("owner")) {
                r.put("owner", new DefaultUserPrincipal("labuser"));
            }
            if (r.containsKey("group")) {
                r.put("group", new DefaultGroupPrincipal("labuser"));
            }
            return r;
        }
    }

    static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    @Override
    public void close() throws java.io.IOException {
        Visual.naEdt(painel::encerrar);
        gerenciador.fechar().join();
        registro.encerrarTodos();
        cenario.close();
        if (acoesAntes == null) {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        } else {
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, acoesAntes);
        }
        Visual.tema(Tema.CLARO);
    }
}
