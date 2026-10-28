package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.FabricaDeClienteSftp;
import br.com.labdesktop.arquivos.FabricaDeSftp;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.dialogos.Apresentador;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tudo o que um {@link GerenciadorDeArquivos} recebe de fora, num lugar só: quem é o servidor, como abrir o canal SFTP e rodar o
 * sudo, o que lembrar, se as ações de escrita estão ligadas e quem mostra avisos e diálogos. O mesmo componente serve ao servidor
 * inteiro ({@code projeto == null}) e a um projeto (raiz travada na pasta dele).
 *
 * @param servidorId        id do servidor no cofre (chave da memória de caminhos)
 * @param servidorNome      o nome mostrado (e o nome da pasta de backups)
 * @param projeto           o projeto, ou {@code null} no modo "servidor inteiro"
 * @param fabrica           abre o canal SFTP na sessão SSH; {@code null} = nunca houve conexão (a tela mostra "sem conexão")
 * @param executorDeSudo    roda o comando do "Salvar com sudo" na sessão SSH; {@code null} desliga o sudo
 * @param escritaLiberada   {@code false}: só navegar, abrir e baixar (sem {@code -Plabdesktop.acoes=true})
 * @param observarEstado    passa a receber o estado da conexão (na EDT); {@code null} = só os testes alimentam
 * @param blindados         em "Este computador": as pastas do próprio app (dados, cofre, logs), como caminho do app, que o gerenciador nunca apaga
 *                          nem move, com tudo o que há dentro (vazio no servidor remoto)
 */
public record ContextoDeArquivos(
    String servidorId,
    String servidorNome,
    String projeto,
    FabricaDeSftp fabrica,
    ProvisionadorRemoto.Executor executorDeSudo,
    ServicosDeArquivos servicos,
    boolean escritaLiberada,
    Notificacoes avisos,
    Apresentador apresentador,
    EscolhedorLocal escolhedor,
    CopiadorDeTexto copiador,
    Function<Consumer<EstadoServidor>, Inscricao> observarEstado,
    Supplier<Instant> relogio,
    ZoneId fuso,
    FabricaDeClienteSftp fabricaDeCliente,
    Set<String> blindados) {

    public ContextoDeArquivos {
        blindados = blindados == null ? Set.of() : Set.copyOf(blindados);
    }

    /**
     * O construtor de antes: o cliente de arquivos é o do SFTP da sessão SSH ({@code fabrica}). Em "Este computador" a fábrica de cliente
     * vem pronta ({@link ServidorLab#fabricaDeArquivos()}) e não há canal SFTP.
     */
    public ContextoDeArquivos(String servidorId, String servidorNome, String projeto, FabricaDeSftp fabrica,
                              ProvisionadorRemoto.Executor executorDeSudo, ServicosDeArquivos servicos, boolean escritaLiberada,
                              Notificacoes avisos, Apresentador apresentador, EscolhedorLocal escolhedor, CopiadorDeTexto copiador,
                              Function<Consumer<EstadoServidor>, Inscricao> observarEstado, Supplier<Instant> relogio, ZoneId fuso) {
        this(servidorId, servidorNome, projeto, fabrica, executorDeSudo, servicos, escritaLiberada, avisos, apresentador, escolhedor, copiador,
            observarEstado, relogio, fuso, fabrica == null ? null : FabricaDeClienteSftp.doSftp(fabrica), Set.of());
    }

    /** O contexto de produção para um servidor vivo (e um projeto dele, ou {@code null}). */
    public static ContextoDeArquivos de(ServidorLab lab, String projeto, ServicosDeArquivos servicos, boolean escritaLiberada,
                                        Notificacoes avisos, Apresentador apresentador, EscolhedorLocal escolhedor) {
        return new ContextoDeArquivos(lab == null ? "sem-servidor" : lab.id(), lab == null ? "servidor" : lab.estado().nome(), projeto,
            null, lab == null ? null : lab.executorDeSudo(), servicos, escritaLiberada,
            avisos, apresentador, escolhedor, CopiadorDeTexto.doSistema(), lab == null ? null : lab::observarEstado, Instant::now,
            ZoneId.systemDefault(), lab == null ? null : lab.fabricaDeArquivos(), lab != null && lab.ehLocal() ? pastasDoAppComoCaminhosDoApp(servicos) : Set.of());
    }

    /** As pastas do app neste computador no formato dos caminhos do gerenciador (no Windows, {@code /C:/Users/...}). */
    static Set<String> pastasDoAppComoCaminhosDoApp(ServicosDeArquivos servicos) {
        CaminhosLocais caminhos = CaminhosLocais.doSistema();
        Set<String> s = new java.util.LinkedHashSet<>();
        for (java.nio.file.Path p : servicos.pastasDoApp()) {
            s.add(caminhos.doTextoDoSistema(p.toAbsolutePath().normalize().toString()));
        }
        return s;
    }

    public ContextoDeArquivos comRelogio(Supplier<Instant> novo, ZoneId novoFuso) {
        return new ContextoDeArquivos(servidorId, servidorNome, projeto, fabrica, executorDeSudo, servicos, escritaLiberada, avisos,
            apresentador, escolhedor, copiador, observarEstado, novo, novoFuso, fabricaDeCliente, blindados);
    }

    public ContextoDeArquivos comCopiador(CopiadorDeTexto novo) {
        return new ContextoDeArquivos(servidorId, servidorNome, projeto, fabrica, executorDeSudo, servicos, escritaLiberada, avisos,
            apresentador, escolhedor, novo, observarEstado, relogio, fuso, fabricaDeCliente, blindados);
    }

    public boolean modoProjeto() {
        return projeto != null;
    }
}
