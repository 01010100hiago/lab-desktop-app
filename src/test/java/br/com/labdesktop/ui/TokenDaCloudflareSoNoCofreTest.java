package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cloudflare.PublicadorPorArquivoTestApoio;
import br.com.labdesktop.cloudflare.RemovedorDeEndereco;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.teste.CloudflareFalsa;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O token da Cloudflare, de ponta a ponta: conectar, publicar, remover e desconectar, e depois procurar o token (em texto) em TODOS os arquivos do disco (o cofre, os logs, a pasta
 * do servidor simulado). Ele só pode existir cifrado dentro do cofre, e some do cofre ao desconectar.
 */
class TokenDaCloudflareSoNoCofreTest {

    @TempDir
    Path tmp;

    private static List<String> arquivosCom(Path raiz, String token) throws IOException {
        byte[] procurado = token.getBytes(StandardCharsets.UTF_8);
        List<String> achados = new ArrayList<>();
        try (Stream<Path> s = Files.walk(raiz)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(p);
                if (indexOf(bytes, procurado) >= 0) {
                    achados.add(raiz.relativize(p).toString());
                }
            }
        }
        return achados;
    }

    private static int indexOf(byte[] palheiro, byte[] agulha) {
        for (int i = 0; i + agulha.length <= palheiro.length; i++) {
            int j = 0;
            while (j < agulha.length && palheiro[i + j] == agulha[j]) {
                j++;
            }
            if (j == agulha.length) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void oTokenNaoApareceEmArquivoNenhumDoDiscoEODesconectarOApagaDoCofre() throws Exception {
        try (CenarioDeCloudflare c = new CenarioDeCloudflare(tmp)) {
            c.prepararOServidorDoDono(PublicadorPorArquivoTestApoio.COMPOSE, PublicadorPorArquivoTestApoio.CONFIG);
            c.conectarPorArquivo();
            TokenCloudflare doCofre = c.cofre.tokenCloudflare(c.servidorId).orElseThrow();
            assertEquals("Bearer " + CloudflareFalsa.TOKEN_CERTO, doCofre.cabecalhoAuthorization(), "o cofre devolve o token que foi colado");
            assertFalse(doCofre.toString().contains(CloudflareFalsa.TOKEN_CERTO), "toString redigido");

            try (PublicadorNaInternet p = c.servico.publicador(x -> { })) {
                PublicadorNaInternet.Resultado r = p.executar(p.preparar(new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 3000, "lab-demo", false)), ObservadorDaEtapa.NENHUM,
                    Cancelamento.NUNCA);
                assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
                assertFalse(r.toString().contains(CloudflareFalsa.TOKEN_CERTO));
            }
            assertEquals(List.of("lab-demo.example.com"), c.servico.enderecos(null).stream().map(e -> e.hostname()).toList());
            try (RemovedorDeEndereco rem = c.servico.removedor()) {
                RemovedorDeEndereco.Resultado r = rem.executar(rem.preparar("lab-demo.example.com"), ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
                assertTrue(r.removido(), r.falha());
            }
            assertEquals(List.of(), arquivosCom(tmp, CloudflareFalsa.TOKEN_CERTO), "o token não está em texto em nenhum arquivo (cofre cifrado, logs, pasta do servidor)");
            for (String comando : c.lab.comandos) {
                assertFalse(comando.contains(CloudflareFalsa.TOKEN_CERTO), "nenhum comando no servidor: " + comando);
            }
            for (String entrada : c.lab.ssh.entradas) {
                assertFalse(entrada.contains(CloudflareFalsa.TOKEN_CERTO));
            }
            assertTrue(c.cf.pedidos.stream().allMatch(x -> x.autorizacao().equals("Bearer " + CloudflareFalsa.TOKEN_CERTO)), "só viaja no cabeçalho, para a Cloudflare");
            assertTrue(c.cf.pedidos.stream().noneMatch(x -> x.caminho().contains(CloudflareFalsa.TOKEN_CERTO) || x.query().contains(CloudflareFalsa.TOKEN_CERTO)
                || x.corpo().contains(CloudflareFalsa.TOKEN_CERTO)), "nunca na URL nem no corpo");

            c.servico.desconectar();
            assertTrue(c.cofre.tokenCloudflare(c.servidorId).isEmpty(), "desconectar apaga o token do cofre");
            assertTrue(c.cofre.configuracaoCloudflare(c.servidorId).isEmpty());
            assertEquals(List.of(), arquivosCom(tmp, CloudflareFalsa.TOKEN_CERTO));
        }
    }
}
