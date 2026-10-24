package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreBloqueadoException;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O caminho que a interface vai percorrer, com o COFRE DE VERDADE (e não o fake de 40 linhas dos outros testes):
 * o assistente "Adicionar servidor" (chave, sondagem do host, pin, conexão, registro do token), o app fechado e
 * aberto de novo (tudo precisa ter ido para o arquivo), a conexão com o token que veio do disco e o bloqueio do
 * cofre. Prova que {@code AcessoAoCofre} real e os módulos {@code ssh}, {@code api} e {@code servidor} se
 * encaixam: o fake nunca devolveria um {@code KeyPair} novo a cada chamada nem lançaria
 * {@link CofreBloqueadoException} no meio de uma conexão.
 */
class ServidorLabComCofreRealTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final String NOME_DO_MAC = "mac-de-teste";
    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();

    @TempDir
    Path tmp;

    private static void aguardar(ServidorLab lab, EstadoConexao esperado) {
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == esperado);
    }

    @Test
    void assistenteDeServidorSobreviveAFecharEAbrirOAppEOBloqueioDoCofreParaTudo() throws Exception {
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        try (ServidorSshDeTeste ssh = ServidorSshDeTeste.ed25519(); AgenteDoLab agente = new AgenteDoLab()) {
            ssh.comandoRemoto(agente.comandosPorSsh());
            String servidorId;
            String textoDoToken;

            // ---------------- 1ª execução: cria o cofre e cadastra o servidor, como o assistente ----------------
            try (CofreDeChaves cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
                ChaveSsh chave = GeradorDeChaves.gerar("lab", NOME_DO_MAC);
                cofre.adicionarChave(chave);
                ConfigServidor novo = new ConfigServidor(ConfigServidor.novoId(), "lab", "127.0.0.1", ssh.porta(),
                    "labuser", chave.id(), agente.porta(), List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
                cofre.adicionarServidor(novo, null);
                servidorId = novo.id();
                ssh.aceitar(cofre.keyPairDoServidor(servidorId).getPublic()); // "colou" a pública no authorized_keys

                // Antes de o usuário confiar no host, a conexão nem abre o TCP.
                try (GerenciadorServidores g = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida())) {
                    ServidorLab lab = g.servidor(servidorId).orElseThrow();
                    lab.iniciar();
                    aguardar(lab, EstadoConexao.ERRO_CHAVE_HOST);
                    assertEquals(0, ssh.pedidosDeAutenticacao.get());

                    // A sondagem mostra a impressão; o usuário confere e confirma; o pin vai para o cofre; "tentar agora".
                    SondagemDeHost.Resultado sonda = SondagemDeHost.sondar("127.0.0.1", ssh.porta(), ConfigSsh.rapida());
                    assertEquals(KeyUtils.getFingerPrint(ssh.chaveDoHost.getPublic()), sonda.impressao());
                    cofre.gravarPin(servidorId, sonda.pin());
                    lab.tentarAgora();
                    aguardar(lab, EstadoConexao.CONECTADO);
                    assertTrue(lab.estado().semToken());

                    // Registra o token por SSH e só então grava no cofre.
                    lab.registrarToken(NOME_DO_MAC);
                    assertEquals(NOME_DO_MAC, cofre.servidor(servidorId).orElseThrow().nomeDoDispositivo());
                    assertEquals(1, agente.tokens.size());
                    textoDoToken = cofre.tokenDoServidor(servidorId).orElseThrow().cabecalhoAuthorization()
                        .substring("Bearer ".length());
                    g.fechar().join();
                }
            }
            assertTrue(textoDoToken.startsWith("lab_"));

            // ---------------- 2ª execução: "Cmd+Q e abrir de novo", agora com o agente exigindo o token ----------------
            agente.exigeToken = true;
            ssh.chavesOferecidas.clear();
            int pedidosDaPrimeiraExecucao = agente.http.pedidos.size();
            try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
                ConfigServidor lido = cofre.servidor(servidorId).orElseThrow();
                assertEquals(1, lido.pins().size(), "o pin do host sobreviveu ao arquivo");
                assertEquals(NOME_DO_MAC, lido.nomeDoDispositivo());
                assertTrue(cofre.tokenDoServidor(servidorId).isPresent());

                GerenciadorServidores g = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
                ServidorLab lab = g.servidor(servidorId).orElseThrow();
                g.iniciarTodos();
                aguardar(lab, EstadoConexao.CONECTADO);
                assertEquals(NOME_DO_MAC, lab.estado().versao().cliente(), "o token que veio do DISCO foi aceito");
                assertFalse(lab.estado().semToken());
                assertEquals(1, ssh.chavesOferecidas.stream().distinct().count(), "o servidor só viu a chave do cofre");
                Espera.ate(PRAZO, () -> "limites=" + lab.estado().limites(), () -> lab.estado().limites() != null);
                List<?> semBearer = agente.http.pedidos.subList(pedidosDaPrimeiraExecucao, agente.http.pedidos.size())
                    .stream().filter(p -> p.cabecalho("Authorization") == null).toList();
                assertTrue(semBearer.isEmpty(), "com a exigência de credencial, nenhuma chamada do app vai sem ela: " + semBearer);

                // Bloquear o cofre fecha sessão e túnel de todos e para de vez (nada de reconectar).
                int conexoesAntes = ssh.sessoesAbertas.get();
                cofre.bloquear();
                aguardar(lab, EstadoConexao.BLOQUEADO_COFRE);
                assertTrue(lab.tunel().isEmpty());
                assertThrows(CofreBloqueadoException.class, () -> cofre.tokenDoServidor(servidorId));
                int pedidosDepois = agente.http.pedidos.size();
                Espera.naoAcontece(Duration.ofMillis(1200), "nova conexão ou consulta com o cofre bloqueado",
                    () -> agente.http.pedidos.size() > pedidosDepois || lab.estado().conexao() != EstadoConexao.BLOQUEADO_COFRE);
                assertEquals(conexoesAntes, ssh.sessoesAbertas.get());
                g.fechar().join();
            }

            // ---------------- Nada em claro no disco ----------------
            try (Stream<Path> arquivos = Files.walk(tmp)) {
                for (Path f : arquivos.filter(Files::isRegularFile).toList()) {
                    String conteudo = lerComoTexto(f);
                    assertFalse(conteudo.contains(textoDoToken), "token em claro em " + f);
                    assertFalse(conteudo.contains("BEGIN OPENSSH"), "chave privada em claro em " + f);
                }
            }
        }
    }

    private static String lerComoTexto(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
    }
}
