package br.com.labdesktop.arquivos;

import br.com.labdesktop.util.ArquivoPrivado;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O que o gerenciador de arquivos lembra entre uma abertura e outra, por servidor: a raiz que o usuário escolheu, o último
 * caminho em que estava e, por projeto, o último caminho dentro dele. Guarda também, por servidor, o período escolhido nas Tendências do Resumo (10 min a 1 mês): é a mesma "memória
 * por servidor" e o mesmo arquivo, para não abrir um segundo. Também guarda a <b>raiz dos projetos</b> do servidor
 * ({@code /srv/lab/projects} por padrão): a pasta de um projeto é {@code <raiz dos projetos>/<nome>}, e quem não usa essa
 * convenção a muda uma vez e o app a lembra.
 *
 * <p>Fica em {@code memoria-arquivos.json} na pasta de dados (preferência, sem segredo; fora do cofre, para não regravá-lo a cada
 * pasta aberta e para não mexer no formato dele: o cofre antigo continua abrindo igual). A gravação roda em segundo plano e
 * junta pedidos seguidos; ler é tolerante (arquivo ausente ou estragado vira memória vazia).
 */
public final class MemoriaDeCaminhos {

    public static final String RAIZ_DOS_PROJETOS_PADRAO = "/srv/lab/projects";
    private static final long TAMANHO_MAXIMO = 512 * 1024;
    private static final int MAXIMO_DE_PROJETOS_LEMBRADOS = 500;
    private static final Logger LOG = LoggerFactory.getLogger(MemoriaDeCaminhos.class);

    /** Dados de um servidor. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class DadosDoServidor {
        public String raizDosProjetos;
        public String raiz;
        public String ultimoCaminho;
        /** O período das Tendências do Resumo (o código do agente: 10m, 1h, 1d, 7d, 30d). */
        public String janelaDasTendencias;
        public Map<String, String> projetos = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Arquivo {
        public Integer versao = 1;
        public Map<String, DadosDoServidor> servidores = new LinkedHashMap<>();
    }

    private final Path arquivo;
    private final Map<String, DadosDoServidor> servidores = new HashMap<>();
    private final AtomicBoolean gravacaoPendente = new AtomicBoolean();
    /** Uma gravação por vez: o arquivo temporário da gravação atômica tem nome fixo, e duas gravações juntas o pisariam. */
    private final Object travaDeDisco = new Object();

    private MemoriaDeCaminhos(Path arquivo) {
        this.arquivo = arquivo;
    }

    /** Sem disco: lembra só enquanto o app estiver aberto (testes e telas sem pasta de dados). */
    public static MemoriaDeCaminhos emMemoria() {
        return new MemoriaDeCaminhos(null);
    }

    /** Lê o arquivo (que pode não existir). Faz E/S: chame de thread de fundo ou na partida. */
    public static MemoriaDeCaminhos doArquivo(Path arquivo) {
        MemoriaDeCaminhos m = new MemoriaDeCaminhos(arquivo);
        if (Files.isRegularFile(arquivo)) {
            try {
                Arquivo a = Json.mapper().readValue(ArquivoPrivado.lerLimitado(arquivo, TAMANHO_MAXIMO), Arquivo.class);
                if (a != null && a.servidores != null) {
                    m.servidores.putAll(a.servidores);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Memória dos arquivos ilegível, começando vazia ({}).", e.getClass().getSimpleName());
            }
        }
        return m;
    }

    private DadosDoServidor dados(String servidorId) {
        return servidores.computeIfAbsent(servidorId, k -> new DadosDoServidor());
    }

    // ---------------------------------------------------------------------------------------------
    // Raiz dos projetos
    // ---------------------------------------------------------------------------------------------

    public synchronized String raizDosProjetos(String servidorId) {
        DadosDoServidor d = servidores.get(servidorId);
        if (d == null || d.raizDosProjetos == null || d.raizDosProjetos.isBlank()) {
            return RAIZ_DOS_PROJETOS_PADRAO;
        }
        try {
            return CaminhoRemoto.normalizar(d.raizDosProjetos);
        } catch (ErroSftp e) {
            return RAIZ_DOS_PROJETOS_PADRAO; // arquivo editado à mão com lixo: volta ao padrão
        }
    }

    /** @throws ErroSftp se o caminho não vale (vazio, relativo, com controle) ou é a raiz {@code /} (os projetos não ficam nela) */
    public synchronized void definirRaizDosProjetos(String servidorId, String caminho) throws ErroSftp {
        String n = CaminhoRemoto.normalizar(caminho == null ? "" : caminho.strip());
        if (n.equals(CaminhoRemoto.RAIZ)) {
            throw ErroSftp.nomeInvalido("A pasta dos projetos não pode ser a raiz (/) do servidor.");
        }
        dados(servidorId).raizDosProjetos = n.equals(RAIZ_DOS_PROJETOS_PADRAO) ? null : n;
        salvarEmFundo();
    }

    // ---------------------------------------------------------------------------------------------
    // O período das Tendências
    // ---------------------------------------------------------------------------------------------

    /** O código do período que o usuário escolheu da última vez neste servidor ({@code 10m}...), ou {@code null} se nunca escolheu (ou se o arquivo tem lixo). */
    public synchronized String janelaDasTendencias(String servidorId) {
        DadosDoServidor d = servidores.get(servidorId);
        String c = d == null ? null : d.janelaDasTendencias;
        return c != null && br.com.labdesktop.tendencias.JanelaDeTempo.doCodigo(c).isPresent() ? c : null;
    }

    /** Lembra o período escolhido (um código inválido é ignorado). */
    public synchronized void definirJanelaDasTendencias(String servidorId, String codigo) {
        if (br.com.labdesktop.tendencias.JanelaDeTempo.doCodigo(codigo).isEmpty()) {
            return;
        }
        dados(servidorId).janelaDasTendencias = codigo;
        salvarEmFundo();
    }

    // ---------------------------------------------------------------------------------------------
    // Último caminho
    // ---------------------------------------------------------------------------------------------

    /** A raiz que o usuário escolheu da última vez neste servidor (Projetos, se nunca escolheu). */
    public synchronized RaizDoServidor raizEscolhida(String servidorId) {
        DadosDoServidor d = servidores.get(servidorId);
        if (d != null && d.raiz != null) {
            for (RaizDoServidor r : RaizDoServidor.values()) {
                if (r.name().equals(d.raiz)) {
                    return r;
                }
            }
        }
        return RaizDoServidor.PROJETOS;
    }

    public synchronized Optional<String> ultimoCaminhoDoServidor(String servidorId) {
        DadosDoServidor d = servidores.get(servidorId);
        return d == null ? Optional.empty() : Optional.ofNullable(d.ultimoCaminho);
    }

    public synchronized void lembrarDoServidor(String servidorId, RaizDoServidor raiz, String caminho) {
        DadosDoServidor d = dados(servidorId);
        d.raiz = raiz.name();
        d.ultimoCaminho = caminho;
        salvarEmFundo();
    }

    public synchronized Optional<String> ultimoCaminhoDoProjeto(String servidorId, String projeto) {
        DadosDoServidor d = servidores.get(servidorId);
        return d == null ? Optional.empty() : Optional.ofNullable(d.projetos.get(projeto));
    }

    public synchronized void lembrarDoProjeto(String servidorId, String projeto, String caminho) {
        DadosDoServidor d = dados(servidorId);
        d.projetos.remove(projeto); // reinsere no fim: o mais antigo é o primeiro
        d.projetos.put(projeto, caminho);
        while (d.projetos.size() > MAXIMO_DE_PROJETOS_LEMBRADOS) {
            d.projetos.remove(d.projetos.keySet().iterator().next());
        }
        salvarEmFundo();
    }

    // ---------------------------------------------------------------------------------------------
    // Disco
    // ---------------------------------------------------------------------------------------------

    private void salvarEmFundo() {
        if (arquivo == null || !gravacaoPendente.compareAndSet(false, true)) {
            return;
        }
        Fundo.emFundo(() -> {
            while (gravacaoPendente.getAndSet(false)) {
                gravarAgora();
            }
        });
    }

    /** Grava já (E/S). Só os testes e o encerramento precisam disto. */
    void gravarAgora() {
        if (arquivo == null) {
            return;
        }
        synchronized (travaDeDisco) {
            // o retrato é tirado DENTRO da trava: quem grava depois grava o estado mais novo
            Arquivo a = new Arquivo();
            synchronized (this) {
                a.servidores = new LinkedHashMap<>(servidores);
            }
            try {
                Files.createDirectories(arquivo.getParent());
                ArquivoPrivado.gravarAtomico(arquivo, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(a));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Não consegui gravar a memória dos arquivos ({}).", e.getClass().getSimpleName());
            }
        }
    }

    /** Espera a gravação em segundo plano terminar (testes). */
    void esvaziar() {
        while (gravacaoPendente.get()) {
            Thread.onSpinWait();
        }
        gravarAgora();
    }
}
