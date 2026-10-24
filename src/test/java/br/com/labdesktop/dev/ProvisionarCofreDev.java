package br.com.labdesktop.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Ferramenta de DESENVOLVIMENTO, não é teste: monta um cofre separado (pasta {@code LABDEV_PASTA}, fora do cofre do usuário)
 * já ligado a um servidor de teste, para abrir o app com {@code --pasta} e ver tudo conectado sem digitar nada na tela do
 * assistente. Só roda com {@code LABDEV_FASE=1} (cria cofre, servidor e chave; grava a linha do authorized_keys) ou
 * {@code LABDEV_FASE=2} (depois de a linha estar no servidor: confere a impressão digital esperada em
 * {@code LABDEV_IMPRESSAO}, grava o pin, conecta e registra o token). A senha do cofre de teste nasce aleatória e fica
 * só em {@code SENHA-DE-TESTE.txt} (0600) dentro da pasta, que o git ignora. Nunca aponte para o cofre de verdade.
 */
@EnabledIfEnvironmentVariable(named = "LABDEV_FASE", matches = "[12]")
class ProvisionarCofreDev {

    private static final String HOST = "192.0.2.5";
    private static final int PORTA = 2222;
    private static final String USUARIO = "labuser";
    private static final int PORTA_DO_AGENTE = 7000;
    private static final String NOME_DO_SERVIDOR = "lab";
    private static final String NOME_DO_MAC = "mac-de-teste";
    private static final Duration PRAZO = Duration.ofSeconds(60);

    private static Path pasta() {
        String p = System.getenv("LABDEV_PASTA");
        if (p == null || p.isBlank()) {
            throw new IllegalStateException("Defina LABDEV_PASTA com a pasta do cofre de teste.");
        }
        return Path.of(p).toAbsolutePath().normalize();
    }

    private static void somenteDono(Path arquivo) throws Exception {
        Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void fase1CriaCofreServidorEChave() throws Exception {
        assumeTrue("1".equals(System.getenv("LABDEV_FASE")));
        Path pasta = pasta();
        Files.createDirectories(pasta);
        Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwx------"));
        assertFalse(Files.exists(pasta.resolve(CaminhosApp.NOME_COFRE)),
            "o cofre de teste já existe; apague a pasta para recomeçar");

        byte[] sorteio = new byte[18];
        new SecureRandom().nextBytes(sorteio);
        String senha = Base64.getUrlEncoder().withoutPadding().encodeToString(sorteio);
        Path arquivoDaSenha = pasta.resolve("SENHA-DE-TESTE.txt");
        Files.writeString(arquivoDaSenha, senha + "\n");
        somenteDono(arquivoDaSenha);

        CaminhosApp caminhos = CaminhosApp.comPasta(pasta);
        try (CofreDeChaves cofre = CofreDeChaves.criar(caminhos, senha.toCharArray())) {
            ChaveSsh chave = GeradorDeChaves.gerar(NOME_DO_SERVIDOR, NOME_DO_MAC);
            cofre.adicionarChave(chave);
            ConfigServidor novo = new ConfigServidor(ConfigServidor.novoId(), NOME_DO_SERVIDOR, HOST, PORTA, USUARIO,
                chave.id(), PORTA_DO_AGENTE, List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
            cofre.adicionarServidor(novo, null);
            Path linha = pasta.resolve("linha-authorized-keys.txt");
            Files.writeString(linha, chave.linhaDeAuthorizedKeys(PORTA_DO_AGENTE) + "\n");
            somenteDono(linha);
        }
    }

    @Test
    void fase2ConectaERegistraOToken() throws Exception {
        assumeTrue("2".equals(System.getenv("LABDEV_FASE")));
        Path pasta = pasta();
        String esperada = System.getenv("LABDEV_IMPRESSAO");
        if (esperada == null || !esperada.startsWith("SHA256:")) {
            throw new IllegalStateException("Defina LABDEV_IMPRESSAO com a impressão digital do host conferida.");
        }
        String senha = Files.readString(pasta.resolve("SENHA-DE-TESTE.txt")).trim();
        CaminhosApp caminhos = CaminhosApp.comPasta(pasta);
        StringBuilder relatorio = new StringBuilder();
        try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, senha.toCharArray())) {
            String id = cofre.servidores().get(0).id();
            SondagemDeHost.Resultado sonda = SondagemDeHost.sondar(HOST, PORTA, ConfigSsh.padrao());
            assertEquals(esperada, sonda.impressao(), "a impressão digital do host diverge da conferida");
            cofre.gravarPin(id, sonda.pin());
            relatorio.append("host confere: ").append(sonda.impressao()).append('\n');

            try (GerenciadorServidores g = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.padrao())) {
                ServidorLab lab = g.servidor(id).orElseThrow();
                lab.iniciar();
                Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
                relatorio.append("conexao: ").append(lab.estado().conexao()).append(" (sem token: ")
                    .append(lab.estado().semToken()).append(")\n");
                lab.registrarToken(NOME_DO_MAC);
                Espera.ate(PRAZO, () -> "estado=" + lab.estado(),
                    () -> lab.estado().versao() != null && lab.estado().limites() != null);
                relatorio.append("depois do token: ").append(lab.estado().conexao()).append('\n');
                relatorio.append("versao do agente: ").append(lab.estado().versao()).append('\n');
                relatorio.append("limites: ").append(lab.estado().limites()).append('\n');
                g.fechar().join();
            }
        }
        Path saida = pasta.resolve("resultado.txt");
        Files.writeString(saida, relatorio.toString());
        somenteDono(saida);
    }
}
