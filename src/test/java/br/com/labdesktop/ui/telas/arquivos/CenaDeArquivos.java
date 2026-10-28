package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import java.awt.datatransfer.Clipboard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * O mundo de um teste do {@link GerenciadorDeArquivos}: servidor SSH embutido com SFTP sobre uma pasta temporária (a mesma forma do
 * lab), o gerenciador montado de verdade (com a conexão SSH real) e as peças de mentira que gravam o que foi dito ao usuário:
 * avisos, diálogos, a área de transferência e o seletor de arquivos do Mac.
 */
final class CenaDeArquivos implements AutoCloseable {

    static final Duration PRAZO = Duration.ofSeconds(30);
    static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** O seletor de arquivos do Mac: responde o que o teste mandar. */
    static final class EscolhedorFalso implements EscolhedorLocal {
        List<Path> paraEnviar = List.of();
        Path pastaDeDownload;
        int vezesQuePerguntouOQueEnviar;
        Path padraoVisto;
        /** Cada vez que o app pediu para mostrar uma pasta no gerenciador de arquivos do sistema. */
        final List<Path> mostradas = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void mostrarNaPasta(Path pastaOuArquivo) {
            mostradas.add(pastaOuArquivo);
        }

        @Override
        public List<Path> escolherParaEnviar() {
            vezesQuePerguntouOQueEnviar++;
            return paraEnviar;
        }

        @Override
        public Optional<Path> escolherPastaParaBaixar(Path padrao) {
            padraoVisto = padrao;
            return Optional.ofNullable(pastaDeDownload);
        }
    }

    final CenarioDeSftp sftp;
    final AvisosGravados avisos = new AvisosGravados();
    final DialogosGravados dialogos = new DialogosGravados();
    final EscolhedorFalso escolhedor = new EscolhedorFalso();
    final Clipboard area = new Clipboard("teste");
    final MemoriaDeCaminhos memoria = MemoriaDeCaminhos.emMemoria();
    final Path backups;
    final ContextoDeArquivos ctx;
    final GerenciadorDeArquivos g;
    private final Path pasta;

    CenaDeArquivos(Path pasta, boolean escrita, String projeto) throws IOException, GeneralSecurityException {
        this(pasta, escrita, projeto, null, true);
    }

    /**
     * @param preparo roda com o servidor de pé, antes de o gerenciador abrir (cria os arquivos do teste)
     * @param iniciar {@code false}: monta e não começa a ler (os testes de estado alimentam à mão)
     */
    CenaDeArquivos(Path pasta, boolean escrita, String projeto, Preparo preparo, boolean iniciar) throws IOException, GeneralSecurityException {
        this(new CenarioDeSftp(pasta, true), pasta, escrita, projeto, preparo, iniciar);
    }

    CenaDeArquivos(CenarioDeSftp sftp, Path pasta, boolean escrita, String projeto, Preparo preparo, boolean iniciar)
        throws IOException, GeneralSecurityException {
        Visual.preparar();
        this.pasta = pasta;
        this.sftp = sftp;
        this.backups = pasta.resolve("backups");
        if (preparo != null) {
            preparo.preparar(sftp);
        }
        this.ctx = new ContextoDeArquivos(CofreFalso.ID, "lab", projeto, sftp.conexao::abrirSftp, sftp.conexao::executar,
            new ServicosDeArquivos(memoria, backups), escrita, avisos, dialogos, escolhedor, new CopiadorDeTexto(() -> area), null,
            () -> AGORA, SP);
        this.g = Visual.naEdt(() -> new GerenciadorDeArquivos(ctx));
        if (iniciar) {
            Visual.naEdt(g::iniciar);
        }
    }

    @FunctionalInterface
    interface Preparo {
        void preparar(CenarioDeSftp s) throws IOException;
    }

    /** Espera a situação e que nenhuma tarefa de rede esteja em voo. */
    void esperar(GerenciadorDeArquivos.Situacao situacao) {
        Espera.ate(PRAZO, () -> "situação=" + Visual.naEdt(g::situacao) + " em voo=" + g.emVoo() + " caminho=" + Visual.naEdt(g::caminhoAtual),
            () -> Visual.naEdt(g::situacao) == situacao && g.emVoo() == 0);
    }

    void esperarNormal() {
        esperar(GerenciadorDeArquivos.Situacao.NORMAL);
    }

    void esperarQue(String descricao, BooleanSupplier condicao) {
        Espera.ate(PRAZO, () -> descricao + " | situação=" + Visual.naEdt(g::situacao) + " caminho=" + Visual.naEdt(g::caminhoAtual),
            () -> Visual.naEdt(() -> condicao.getAsBoolean()));
    }

    /** Os nomes (como a tela os mostra) das linhas da tabela, na ordem. */
    List<String> nomes() {
        return Visual.naEdt(() -> {
            List<String> l = new ArrayList<>();
            for (var e : g.tabela().itens()) {
                l.add(e.nome());
            }
            return l;
        });
    }

    /** Estado de conexão de teste. */
    static EstadoServidor estado(EstadoConexao c) {
        return new EstadoServidor(CofreFalso.ID, "lab", c, null, 0, null, c.permiteExec() ? null : AGORA.minusSeconds(600), 1,
            br.com.labdesktop.cofre.Estado2fa.DESLIGADO, "mac-de-teste", null, null);
    }

    /** O arquivo que o teste escreve no servidor. */
    static void escrever(CenarioDeSftp s, String caminho, String conteudo) throws IOException {
        s.escrever(caminho, conteudo);
    }

    /** Uma pasta de projeto parecida com a de verdade, para as telas de exemplo. */
    static void projetoDeExemplo(CenarioDeSftp s) throws IOException {
        String o = CenarioDeSftp.ORBITA;
        Files.createDirectories(s.local(o + "/src/componentes"));
        Files.createDirectories(s.local(o + "/logs"));
        Files.createDirectories(s.local(o + "/docs"));
        Files.createDirectories(s.local(o + "/node_modules"));
        s.escrever(o + "/README.md", "# Órbita\n\nPainel de acompanhamento das entregas.\n\n## Como rodar\n\n```\ndocker compose up -d\n```\n");
        s.escrever(o + "/docker-compose.yml", "services:\n  web:\n    image: orbita-web:latest\n    ports:\n      - \"3100:3000\"\n    env_file: .env\n    restart: unless-stopped\n");
        s.escrever(o + "/package.json", "{\n  \"name\": \"orbita\",\n  \"version\": \"2.4.1\",\n  \"scripts\": { \"start\": \"node src/servidor.js\" }\n}\n");
        s.escrever(o + "/.env", "PORTA=3000\nBANCO_URL=postgres://orbita@db/orbita\nSEGREDO=trocar-isto\n");
        s.escrever(o + "/.gitignore", "node_modules\n.env\nlogs\n");
        s.escrever(o + "/src/servidor.js", "const http = require('http');\n\n// sobe o servidor na porta do ambiente\nconst porta = Number(process.env.PORTA) || 3000;\nhttp.createServer((req, res) => {\n  res.end('ok');\n}).listen(porta, () => console.log(`no ar em ${porta}`));\n");
        s.escrever(o + "/src/componentes/cartao.js", "export const cartao = (titulo) => `<div class=\"cartao\">${titulo}</div>`;\n");
        s.escrever(o + "/logs/app.log", "2026-10-24 17:55:01 INFO  no ar em 3000\n".repeat(40));
        s.escrever(o + "/docs/arquitetura.md", "# Arquitetura\n\nUm servidor, um banco.\n");
        s.escrever(o + "/Dockerfile", "FROM node:20-alpine\nWORKDIR /app\nCOPY . .\nCMD [\"node\", \"src/servidor.js\"]\n");
        Files.write(s.local(o + "/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1});
        s.escrever(CenarioDeSftp.PROJETOS + "/outro/LEIA.md", "outro projeto\n");
        Files.createDirectories(s.local(CenarioDeSftp.PROJETOS + "/bazar"));
        Files.createDirectories(s.local(CenarioDeSftp.PROJETOS + "/vendas"));
    }

    // ---------------------------------------------------------------------------------------------
    // Gestos do usuário
    // ---------------------------------------------------------------------------------------------

    /** Dá tamanho e faz o layout (como a janela faria): a tabela só sabe onde ficam as linhas depois disto. */
    void renderizar() {
        Visual.renderizar(g, 884, 634);
    }

    /** Vai para a pasta e espera a listagem chegar. */
    void irParaEEsperar(String caminho) {
        Visual.naEdt(() -> g.irPara(caminho));
        esperarQue("chegou em " + caminho, () -> caminho.equals(g.caminhoAtual()) && g.emVoo() == 0
            && (g.situacao() == GerenciadorDeArquivos.Situacao.NORMAL || g.situacao() == GerenciadorDeArquivos.Situacao.VAZIA));
    }

    private int linhaDe(String nome) {
        for (int i = 0; i < g.tabela().itens().size(); i++) {
            if (g.tabela().itens().get(i).nome().equals(nome)) {
                return i;
            }
        }
        throw new AssertionError("não há '" + nome + "' na tabela: " + nomes());
    }

    /** O duplo clique do mouse sobre a linha deste item. */
    void duploClique(String nome) {
        renderizar();
        Visual.naEdt(() -> {
            var t = g.tabela();
            var r = t.getCellRect(linhaDe(nome), 0, true);
            long agora = System.currentTimeMillis();
            int x = r.x + 20;
            int y = r.y + r.height / 2;
            // sem tela o Swing não aceita o "pressionar" (ele pergunta ao sistema qual é a tecla de atalho): o clique que importa é o duplo
            t.setRowSelectionInterval(linhaDe(nome), linhaDe(nome));
            t.dispatchEvent(new java.awt.event.MouseEvent(t, java.awt.event.MouseEvent.MOUSE_CLICKED, agora, 0, x, y, 2, false, java.awt.event.MouseEvent.BUTTON1));
        });
    }

    /** Seleciona a linha e aperta a tecla na tabela, pelo mapa de teclas dela (prova que a tecla está ligada). */
    void teclaNaTabela(int vk, int modificadores, String... selecionar) {
        Visual.naEdt(() -> {
            if (selecionar.length > 0) {
                g.selecionarNaTabela(selecionar);
            }
            var t = g.tabela();
            var chave = javax.swing.KeyStroke.getKeyStroke(vk, modificadores);
            Object nome = t.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(chave);
            if (nome == null) {
                throw new AssertionError("a tecla " + chave + " não está ligada na tabela");
            }
            t.getActionMap().get(nome).actionPerformed(new java.awt.event.ActionEvent(t, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
        });
    }

    /** O clique do mouse no cabeçalho da coluna. */
    void cliqueNoCabecalho(int coluna) {
        renderizar();
        Visual.naEdt(() -> {
            var h = g.tabela().getTableHeader();
            var r = h.getHeaderRect(coluna);
            long agora = System.currentTimeMillis();
            h.dispatchEvent(new java.awt.event.MouseEvent(h, java.awt.event.MouseEvent.MOUSE_CLICKED, agora, 0, r.x + 8, r.y + r.height / 2, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
        });
    }

    void clicar(String botao) {
        Visual.naEdt(() -> g.botao(botao).doClick(0));
    }

    <T extends br.com.labdesktop.ui.dialogos.Dialogo> T dialogo(Class<T> tipo) {
        return Visual.naEdt(() -> dialogos.ultimoComo(tipo));
    }

    /** Espera o diálogo do tipo aparecer (os que vêm de tarefas de fundo chegam depois). */
    <T extends br.com.labdesktop.ui.dialogos.Dialogo> T esperarDialogo(Class<T> tipo) {
        Espera.ate(PRAZO, () -> "diálogo " + tipo.getSimpleName() + "; vi " + dialogos.mostrados.stream().map(d -> d.getClass().getSimpleName()).toList(),
            () -> Visual.naEdt(() -> tipo.isInstance(dialogos.ultimo())));
        return dialogo(tipo);
    }

    /** Todos os textos visíveis (rótulos, botões, campos) de um componente. */
    static String textosDe(java.awt.Component raiz) {
        return Visual.naEdt(() -> br.com.labdesktop.ui.TextosDeTela.de(raiz));
    }

    @Override
    public void close() throws IOException {
        Visual.naEdt(g::encerrar);
        sftp.close();
    }
}
