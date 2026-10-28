package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.arquivos.ClienteSftpLocal;
import br.com.labdesktop.local.LocalizadorDeProgramas;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ProcessosLocais;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.projetos.ModelosDeContainer.Parametros;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Criar projeto e adicionar container com o Docker de VERDADE deste computador: a rede {@code <nome>_default} nasce com os rótulos do compose e o
 * {@code docker compose config} aceita cada um dos quatro modelos (sem subir nenhum container, sem baixar nenhuma imagem). Só roda com
 * {@code -Plabdesktop.dockerReal=true}; o projeto se chama {@code labteste-novo} (nada do que o usuário já tem é tocado) e a rede é removida no fim.
 */
@EnabledIfSystemProperty(named = "labdesktop.dockerReal", matches = "true")
class NovoProjetoComDockerDeVerdadeTest {

    private static final String NOME = "labteste-novo";

    @TempDir
    Path tmp;

    private final PlataformaLocal plataforma = PlataformaLocal.detectar();
    private final ProcessosLocais processos = new ProcessosLocais(LocalizadorDeProgramas.doSistema(plataforma), plataforma);

    @AfterEach
    void limpar() throws Exception {
        try {
            processos.executar(List.of("docker", "network", "rm", NOME + "_default"), Duration.ofSeconds(60));
        } catch (java.io.IOException e) {
            // não existe: ótimo
        }
    }

    @Test
    void criaARedeComOsRotulosEOComposeAceitaOsQuatroModelos() throws Exception {
        CaminhosLocais caminhos = CaminhosLocais.doSistema();
        Path casa = tmp.resolve("casa");
        Path raiz = casa.resolve("LabLocal").resolve("projects");
        Files.createDirectories(casa);
        AmbienteDeProjetos amb = AmbienteDeProjetos.local(caminhos.doTextoDoSistema(raiz.toString()), () -> new ClienteSftpLocal(caminhos, caminhos.doTextoDoSistema(casa.toString())),
            processos, caminhos);

        CriadorDeProjeto.Resultado r = new CriadorNoAmbiente(amb).criar(NOME, null);
        assertEquals(List.of(), r.avisos(), "o Docker de verdade criou a rede");

        var rede = processos.executar(List.of("docker", "network", "inspect", "-f", "{{index .Labels \"com.docker.compose.project\"}}|{{index .Labels \"com.docker.compose.network\"}}",
            NOME + "_default"), Duration.ofSeconds(30));
        assertEquals(0, rede.status(), rede.stderr());
        assertEquals(NOME + "|default", rede.stdout().strip());

        // criar de novo não estraga (a pasta existe: recusa) e a rede repetida só vira aviso
        EscritorDeCompose escritor = new EscritorDeCompose(amb);
        for (Modelo m : Modelo.values()) {
            Parametros p = m == Modelo.IMAGEM ? new Parametros("web", "nginx:alpine", 8080, 80) : Parametros.padraoDe(m);
            Path pasta = raiz.resolve(NOME);
            Files.writeString(pasta.resolve("docker-compose.yml"), GabaritoDoProjeto.composeVazio(NOME), StandardCharsets.UTF_8); // volta ao esqueleto
            EscritorDeCompose.Previa previa = escritor.preparar(NOME, ModelosDeContainer.gerar(m, p));
            assertTrue(previa.gravavel(), m.name());
            EscritorDeCompose.Gravado g = escritor.gravar(previa);
            assertTrue(Files.readString(pasta.resolve("docker-compose.yml")).contains("services:\n  " + p.servico() + ":"), m.name());
            assertTrue(g.copia() != null, "guardou a cópia datada");
            // o Docker de verdade confere o arquivo gravado como o usuário o usaria (com o .env da pasta)
            var config = processos.executar(List.of("docker", "compose", "--project-directory", pasta.toString(), "-f", pasta.resolve("docker-compose.yml").toString(), "config", "-q"),
                Duration.ofSeconds(60));
            assertEquals(0, config.status(), m.name() + ": " + config.stderr());
            Files.deleteIfExists(pasta.resolve(".env"));
        }
    }
}
