package br.com.labdesktop.cofre;

import static br.com.labdesktop.cofre.Fixtures.abrir;
import static br.com.labdesktop.cofre.Fixtures.caminhos;
import static br.com.labdesktop.cofre.Fixtures.criar;
import static br.com.labdesktop.cofre.Fixtures.senha;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.CofreDeChaves.ArquivoUsado;
import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.util.CaminhosApp;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CofreDeChavesTest {

    // ------------------------------------------------------------------------------------------
    // Ida e volta
    // ------------------------------------------------------------------------------------------

    @Test
    void criaGravaFechaEAbreDeNovoComTudoIgual(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        assertFalse(CofreDeChaves.existe(c));
        ConfigServidor esperado = Fixtures.popular(c);
        assertTrue(CofreDeChaves.existe(c));

        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(ArquivoUsado.PRINCIPAL, cofre.arquivoUsado());
            assertTrue(cofre.avisos().isEmpty(), cofre.avisos().toString());
            assertEquals(List.of(esperado), cofre.servidores());
            ConfigServidor lido = cofre.servidor(esperado.id()).orElseThrow();
            assertEquals("lab", lido.nome());
            assertEquals("192.0.2.5", lido.host());
            assertEquals(2222, lido.porta());
            assertEquals("labuser", lido.usuario());
            assertEquals(7000, lido.portaAgente());
            assertEquals("mac-de-teste", lido.nomeDoDispositivo());
            assertEquals(Estado2fa.ATIVO, lido.estado2fa());
            assertEquals(1, lido.pins().size());

            assertEquals(1, cofre.chaves().size());
            InfoChave info = cofre.chave(lido.chaveId()).orElseThrow();
            assertEquals("lab", info.nome());
            assertTrue(info.publica().startsWith("ssh-ed25519 "));
            assertTrue(info.publica().endsWith(" lab-desktop@mac-de-teste"));

            // A chave que volta do disco ainda é a MESMA (mesma impressão) e serve para o MINA.
            KeyPair par = cofre.keyPairDoServidor(lido.id());
            assertEquals(info.impressao(), KeyUtils.getFingerPrint(par.getPublic()));

            TokenAgente token = cofre.tokenDoServidor(lido.id()).orElseThrow();
            assertEquals(47, token.cabecalhoAuthorization().length() - "Bearer ".length());
            assertEquals(64, token.hashSha256Hex().length());
        }
    }

    @Test
    void tokenEChaveSobrevivemIdaEVoltaIdenticos(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        String hash;
        String impressao;
        try (CofreDeChaves cofre = criar(c)) {
            ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
            cofre.adicionarChave(chave);
            ConfigServidor cfg = ConfigServidor.novo("s", "h.local", 22, "u", chave.id());
            TokenAgente token = TokenAgente.gerar();
            hash = token.hashSha256Hex();
            impressao = chave.impressao();
            cofre.adicionarServidor(cfg, token);
        }
        try (CofreDeChaves cofre = abrir(c)) {
            String id = cofre.servidores().get(0).id();
            assertEquals(hash, cofre.tokenDoServidor(id).orElseThrow().hashSha256Hex());
            assertEquals(impressao, KeyUtils.getFingerPrint(cofre.keyPairDoServidor(id).getPublic()));
        }
    }

    @Test
    void servidorSemTokenAindaTemTokenVazio(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = criar(c)) {
            ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
            cofre.adicionarChave(chave);
            ConfigServidor cfg = ConfigServidor.novo("s", "h.local", 22, "u", chave.id());
            cofre.adicionarServidor(cfg, null);
            assertTrue(cofre.tokenDoServidor(cfg.id()).isEmpty());
        }
        try (CofreDeChaves cofre = abrir(c)) {
            assertTrue(cofre.tokenDoServidor(cofre.servidores().get(0).id()).isEmpty());
            assertNull(cofre.servidores().get(0).nomeDoDispositivo());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Senha e arquivo
    // ------------------------------------------------------------------------------------------

    @Test
    void senhaErradaLancaSenhaIncorreta(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        assertThrows(SenhaIncorretaException.class,
            () -> CofreDeChaves.abrir(c, Fixtures.OUTRA_SENHA.toCharArray(), ParametrosKdf.MINIMO));
        // A senha certa continua funcionando depois do erro (nada foi apagado nem alterado).
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(1, cofre.servidores().size());
        }
    }

    @Test
    void senhaComUmCaractereAMaisOuAMenosFalha(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        assertThrows(SenhaIncorretaException.class,
            () -> CofreDeChaves.abrir(c, (Fixtures.SENHA + "x").toCharArray(), ParametrosKdf.MINIMO));
        assertThrows(SenhaIncorretaException.class,
            () -> CofreDeChaves.abrir(c, Fixtures.SENHA.substring(1).toCharArray(), ParametrosKdf.MINIMO));
    }

    @Test
    void semArquivoNenhumLancaInexistente(@TempDir Path tmp) {
        assertThrows(CofreInexistenteException.class, () -> abrir(caminhos(tmp)));
    }

    @Test
    void criarExigeSenhaDeDozeCaracteres(@TempDir Path tmp) {
        CaminhosApp c = caminhos(tmp);
        assertThrows(IllegalArgumentException.class,
            () -> CofreDeChaves.criar(c, "curta".toCharArray(), ParametrosKdf.MINIMO));
        assertThrows(IllegalArgumentException.class,
            () -> CofreDeChaves.criar(c, "onze-chars!".toCharArray(), ParametrosKdf.MINIMO));
        assertFalse(CofreDeChaves.existe(c));
    }

    @Test
    void criarDuasVezesNaMesmaPastaFalhaSemApagarOCofre(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        byte[] antes = Files.readAllBytes(c.arquivoCofre());
        assertThrows(CofreException.class, () -> criar(c));
        assertArrayEquals(antes, Files.readAllBytes(c.arquivoCofre()));
    }

    private static byte[] ler(Path p) throws Exception {
        return Files.readAllBytes(p);
    }

    private static void alterarByte(Path arquivo, int posicao, int novoValor) throws Exception {
        byte[] b = ler(arquivo);
        b[posicao < 0 ? b.length + posicao : posicao] = (byte) novoValor;
        Files.write(arquivo, b);
    }

    @Test
    void alterarOMagicEArquivoCorrompido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        alterarByte(c.arquivoCofre(), 0, 'X');
        // Há .bak (a gravação anterior), então primeiro vem a recuperação: apague-o para ver o erro puro.
        Files.deleteIfExists(c.arquivoCofreBak());
        assertThrows(CofreCorrompidoException.class, () -> abrir(c));
    }

    @Test
    void alterarUmByteDoSalDoNonceOuDoCifradoFalhaAAutenticacao(@TempDir Path tmp) throws Exception {
        for (int posicao : new int[] {16, 31, 32, 43, 44, 60, -17, -1}) {
            Path pasta = Files.createDirectories(tmp.resolve("p" + posicao));
            CaminhosApp c = caminhos(pasta);
            Fixtures.popular(c);
            Files.deleteIfExists(c.arquivoCofreBak());
            byte[] original = ler(c.arquivoCofre());
            int idx = posicao < 0 ? original.length + posicao : posicao;
            alterarByte(c.arquivoCofre(), idx, original[idx] ^ 0x01);
            assertThrows(SenhaIncorretaException.class, () -> abrir(c), "byte " + idx);
        }
    }

    @Test
    void baixarOCustoDoKdfNoCabecalhoFalhaAAutenticacao(@TempDir Path tmp) throws Exception {
        // O cabeçalho é AAD do GCM: um atacante não pode baixar iterações ou memória "só para facilitar".
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = CofreDeChaves.criar(c, senha(), new ParametrosKdf(19_456, 4, 1))) {
            cofre.adicionarChave(GeradorDeChaves.gerar("k", "mac"));
        }
        Files.deleteIfExists(c.arquivoCofreBak());
        alterarByte(c.arquivoCofre(), 14, 3); // iterações 4 -> 3, ainda dentro da faixa
        assertThrows(SenhaIncorretaException.class,
            () -> CofreDeChaves.abrir(c, senha(), new ParametrosKdf(19_456, 4, 1)));
    }

    @Test
    void arquivoTruncadoECorrompido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        Files.deleteIfExists(c.arquivoCofreBak());
        byte[] b = ler(c.arquivoCofre());
        Files.write(c.arquivoCofre(), Arrays.copyOf(b, 30));
        assertThrows(CofreCorrompidoException.class, () -> abrir(c));
        Files.write(c.arquivoCofre(), new byte[0]);
        assertThrows(CofreCorrompidoException.class, () -> abrir(c));
        // Cortado no meio do texto cifrado: o cabeçalho é válido, a tag não.
        Files.write(c.arquivoCofre(), Arrays.copyOf(b, b.length - 5));
        assertThrows(SenhaIncorretaException.class, () -> abrir(c));
    }

    private static byte[] cabecalhoFalso(long memoria, int kdf, int iteracoes, int paralelismo) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(200);
        buf.put("LABCOFRE".getBytes(StandardCharsets.US_ASCII));
        buf.put((byte) 1);
        buf.put((byte) kdf);
        buf.putInt((int) memoria);
        buf.put((byte) iteracoes);
        buf.put((byte) paralelismo);
        return buf.array();
    }

    @Test
    void parametrosDoKdfForaDaFaixaSaoRecusadosSemDerivar(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        c.prepararPastas();
        long[][] ruins = {
            {19_455, 1, 2, 1}, {1_048_577, 1, 2, 1}, {0xFFFFFFFFL, 1, 2, 1}, {0, 1, 2, 1},
            {262_144, 1, 1, 1}, {262_144, 1, 11, 1}, {262_144, 1, 255, 1},
            {262_144, 1, 6, 0}, {262_144, 1, 6, 9}, {262_144, 1, 6, 255},
            {262_144, 2, 6, 1}, // id de KDF desconhecido
        };
        for (long[] r : ruins) {
            Files.write(c.arquivoCofre(), cabecalhoFalso(r[0], (int) r[1], (int) r[2], (int) r[3]));
            long ini = System.nanoTime();
            CofreCorrompidoException e = assertThrows(CofreCorrompidoException.class, () -> abrir(c),
                Arrays.toString(r));
            // Se tivesse tentado derivar com 4 GiB, isto levaria segundos (ou OutOfMemoryError).
            assertTrue(System.nanoTime() - ini < TimeUnit.SECONDS.toNanos(2), Arrays.toString(r));
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void versaoDoFormatoDesconhecidaECorrompido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        Files.deleteIfExists(c.arquivoCofreBak());
        alterarByte(c.arquivoCofre(), 8, 2);
        assertThrows(CofreCorrompidoException.class, () -> abrir(c));
    }

    @Test
    void arquivoMaiorQueOitoMiBEhRecusadoSemSerLido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        c.prepararPastas();
        try (RandomAccessFile f = new RandomAccessFile(c.arquivoCofre().toFile(), "rw")) {
            f.setLength(8L * 1024 * 1024 + 1); // esparso: não gasta disco nem tempo
        }
        CofreCorrompidoException e = assertThrows(CofreCorrompidoException.class, () -> abrir(c));
        assertTrue(e.getMessage().contains("grande"), e.getMessage());
    }

    // ------------------------------------------------------------------------------------------
    // Arquivos no disco
    // ------------------------------------------------------------------------------------------

    @Test
    void permissoesSaoZeroSeteZeroZeroEZeroSeisZeroZero(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.pastaDados())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.arquivoCofre())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.arquivoCofreBak())));
        assertFalse(Files.exists(c.arquivoCofreTmp()), "o .tmp só existe durante a gravação");
    }

    @Test
    void permissoesAbertasDemaisSaoCorrigidasNaAberturaComAviso(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        Files.setPosixFilePermissions(c.pastaDados(), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(c.arquivoCofre(), PosixFilePermissions.fromString("rw-r--r--"));
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(2, cofre.avisos().stream().filter(a -> a.contains("permiss")).count(), cofre.avisos().toString());
        }
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.pastaDados())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.arquivoCofre())));
    }

    @Test
    void oBakEhAGeracaoAnterior(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = criar(c)) {
            assertFalse(Files.exists(c.arquivoCofreBak()), "recém criado: não há geração anterior");
            byte[] geracao1 = ler(c.arquivoCofre());
            cofre.adicionarChave(GeradorDeChaves.gerar("k1", "mac"));
            assertArrayEquals(geracao1, ler(c.arquivoCofreBak()));
            byte[] geracao2 = ler(c.arquivoCofre());
            assertFalse(Arrays.equals(geracao1, geracao2));
            cofre.adicionarChave(GeradorDeChaves.gerar("k2", "mac"));
            assertArrayEquals(geracao2, ler(c.arquivoCofreBak()));
        }
    }

    @Test
    void cadaGravacaoUsaNonceNovo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = criar(c)) {
            byte[] nonce1 = Arrays.copyOfRange(ler(c.arquivoCofre()), 32, 44);
            cofre.salvar();
            byte[] nonce2 = Arrays.copyOfRange(ler(c.arquivoCofre()), 32, 44);
            cofre.salvar();
            byte[] nonce3 = Arrays.copyOfRange(ler(c.arquivoCofre()), 32, 44);
            assertFalse(Arrays.equals(nonce1, nonce2));
            assertFalse(Arrays.equals(nonce2, nonce3));
        }
    }

    @Test
    void oArquivoNaoContemChaveNemTokenEmClaro(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        String token;
        String pem;
        String publica;
        try (CofreDeChaves cofre = criar(c)) {
            ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac");
            pem = new String(chave.privadaInterna(), StandardCharsets.US_ASCII);
            publica = chave.publica();
            cofre.adicionarChave(chave);
            ConfigServidor cfg = ConfigServidor.novo("lab-secreto", "192.0.2.5", 2222, "labuser", chave.id());
            TokenAgente t = TokenAgente.gerar();
            token = t.cabecalhoAuthorization().substring("Bearer ".length());
            cofre.adicionarServidor(cfg, t);
            cofre.salvar(); // deixa o .bak também
        }
        String corpoDoPem = pem.replace("-----BEGIN OPENSSH PRIVATE KEY-----", "").replace("-----END OPENSSH PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        List<String> proibidos = new ArrayList<>(List.of(
            "BEGIN OPENSSH", "OPENSSH PRIVATE KEY", "ssh-ed25519", "lab_", token, "192.0.2.5", "labuser",
            "lab-secreto", "privadaOpenssh", "tokenAgente", "servidores", "SHA256:",
            corpoDoPem.substring(0, 40), "b3BlbnNzaC1rZXktdjE", // base64 de "openssh-key-v1"
            publica.split(" ")[1]));
        // O token em base64 nos três alinhamentos possíveis (o JSON o guarda em base64).
        for (int k = 0; k < 3; k++) {
            String b64 = Base64.getEncoder().encodeToString(("x".repeat(k) + token).getBytes(StandardCharsets.US_ASCII));
            proibidos.add(b64.substring(k == 0 ? 0 : 4, b64.length() - 4));
        }
        for (Path arquivo : List.of(c.arquivoCofre(), c.arquivoCofreBak())) {
            String conteudo = new String(ler(arquivo), StandardCharsets.ISO_8859_1);
            for (String p : proibidos) {
                assertFalse(conteudo.contains(p), arquivo.getFileName() + " contém \"" + p + "\"");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Recuperação
    // ------------------------------------------------------------------------------------------

    @Test
    void principalIlegivelCaiNoBakERegravaOPrincipal(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor esperado = Fixtures.popular(c); // várias gerações: o .bak é só a anterior
        byte[] bakAntes = ler(c.arquivoCofreBak());
        // Queda no meio da gravação do principal: ele ficou truncado.
        Files.write(c.arquivoCofre(), Arrays.copyOf(ler(c.arquivoCofre()), 70));

        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(ArquivoUsado.BACKUP, cofre.arquivoUsado());
            assertTrue(cofre.avisos().stream().anyMatch(a -> a.contains("cofre.labcofre.bak")), cofre.avisos().toString());
            // O .bak é a geração anterior: o servidor existe, só a última mudança (2FA ativo) se perdeu.
            assertEquals(esperado.id(), cofre.servidores().get(0).id());
            assertEquals(Estado2fa.DESLIGADO, cofre.servidores().get(0).estado2fa());
        }
        // O principal foi regravado a partir do .bak, e o .bak continua como estava (não virou cópia do ruim).
        assertArrayEquals(bakAntes, ler(c.arquivoCofreBak()));
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(ArquivoUsado.PRINCIPAL, cofre.arquivoUsado());
        }
    }

    @Test
    void principalAusenteUsaOBak(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        Files.delete(c.arquivoCofre());
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(ArquivoUsado.BACKUP, cofre.arquivoUsado());
            assertEquals(1, cofre.servidores().size());
        }
        assertTrue(Files.exists(c.arquivoCofre()));
    }

    @Test
    void usaOTmpQuandoPrincipalEBakEstaoRuins(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        // Estado de uma queda depois de gravar o .tmp inteiro e antes do rename: o .tmp é o mais novo.
        Files.copy(c.arquivoCofre(), c.arquivoCofreTmp());
        Files.write(c.arquivoCofre(), new byte[10]);
        Files.write(c.arquivoCofreBak(), new byte[10]);
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(ArquivoUsado.TEMPORARIO, cofre.arquivoUsado());
            assertTrue(cofre.avisos().stream().anyMatch(a -> a.contains("cofre.labcofre.tmp")));
            assertEquals(Estado2fa.ATIVO, cofre.servidores().get(0).estado2fa());
        }
    }

    @Test
    void tmpIncompletoNaoAtrapalhaEESomeNaProximaGravacao(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        Files.write(c.arquivoCofreTmp(), new byte[]{1, 2, 3}); // sobra de uma queda no meio da escrita
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(ArquivoUsado.PRINCIPAL, cofre.arquivoUsado());
            cofre.adicionarChave(GeradorDeChaves.gerar("outra", "mac"));
        }
        assertFalse(Files.exists(c.arquivoCofreTmp()));
    }

    @Test
    void todosOsArquivosIlegiveisDaCorrompido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        Files.write(c.arquivoCofre(), new byte[10]);
        Files.write(c.arquivoCofreBak(), new byte[10]);
        assertThrows(CofreCorrompidoException.class, () -> abrir(c));
    }

    // ------------------------------------------------------------------------------------------
    // Bloqueio e zeragem
    // ------------------------------------------------------------------------------------------

    @Test
    void bloquearZeraChavesTokensEChaveDerivada(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        CofreDeChaves cofre = abrir(c);
        ConfigServidor cfg = cofre.servidores().get(0);
        ChaveSsh chave = cofre.chaveSsh(cfg.chaveId()).orElseThrow();
        TokenAgente token = cofre.tokenDoServidor(cfg.id()).orElseThrow();
        byte[] chaveDerivada = cofre.chaveiroParaTeste().chave;

        // Antes: tudo vivo (o teste de "zerado" não pode passar por vacuidade).
        assertFalse(chave.estaZerado());
        assertFalse(token.estaZerado());
        assertFalse(Zeragem.todoZero(chaveDerivada));
        assertNotNull(chave.paraKeyPair());
        byte[] copiaDoPemAntes = chave.privadaInterna().clone();
        assertFalse(Zeragem.todoZero(copiaDoPemAntes));

        cofre.bloquear();

        assertFalse(cofre.aberto());
        assertTrue(chave.estaZerado());
        assertTrue(token.estaZerado());
        assertTrue(Zeragem.todoZero(chaveDerivada), "a chave derivada do Argon2id também");
        assertTrue(Zeragem.todoZero(cofre.chaveiroParaTeste().chave));
        assertThrows(SegredoZeradoException.class, chave::paraKeyPair);
        assertThrows(SegredoZeradoException.class, token::cabecalhoAuthorization);
        assertThrows(SegredoZeradoException.class, token::hashSha256Hex);
        assertThrows(CofreBloqueadoException.class, cofre::servidores);
        assertThrows(CofreBloqueadoException.class, () -> cofre.servidor(cfg.id()));
        assertThrows(CofreBloqueadoException.class, () -> cofre.tokenDoServidor(cfg.id()));
        assertThrows(CofreBloqueadoException.class, () -> cofre.keyPairDoServidor(cfg.id()));
        assertThrows(CofreBloqueadoException.class, () -> cofre.gravarEstado2fa(cfg.id(), Estado2fa.DESLIGADO));
        assertThrows(CofreBloqueadoException.class, cofre::salvar);
        cofre.bloquear(); // idempotente
        cofre.close();
    }

    @Test
    void ouvintesDeBloqueioRodamUmaVezDepoisDeZerar(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        CofreDeChaves cofre = abrir(c);
        ConfigServidor cfg = cofre.servidores().get(0);
        ChaveSsh chave = cofre.chaveSsh(cfg.chaveId()).orElseThrow();
        List<String> chamadas = new ArrayList<>();
        cofre.aoBloquear(() -> chamadas.add("primeiro: chave zerada=" + chave.estaZerado()));
        cofre.aoBloquear(() -> {
            throw new IllegalStateException("ouvinte com defeito");
        });
        cofre.aoBloquear(() -> chamadas.add("terceiro"));
        assertTrue(chamadas.isEmpty(), "nada roda antes de bloquear");

        cofre.bloquear();
        assertEquals(List.of("primeiro: chave zerada=true", "terceiro"), chamadas,
            "depois de zerar, em ordem, e o defeituoso não impede os outros");
        cofre.bloquear();
        assertEquals(2, chamadas.size(), "bloquear de novo não repete");

        // Ouvinte registrado com o cofre já bloqueado roda na hora.
        cofre.aoBloquear(() -> chamadas.add("tardio"));
        assertEquals("tardio", chamadas.get(2));
    }

    @Test
    void depoisDeBloquearSePodeAbrirOutraVezComASenha(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        CofreDeChaves a = abrir(c);
        a.bloquear();
        try (CofreDeChaves b = abrir(c)) {
            assertEquals(1, b.servidores().size());
            assertNotSame(a, b);
        }
    }

    @Test
    void removerChaveEServidorZeraOsSegredosQueSairam(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            ConfigServidor cfg = cofre.servidores().get(0);
            ChaveSsh chave = cofre.chaveSsh(cfg.chaveId()).orElseThrow();
            TokenAgente token = cofre.tokenDoServidor(cfg.id()).orElseThrow();

            // Chave em uso por um servidor não sai.
            CofreException e = assertThrows(CofreException.class, () -> cofre.removerChave(chave.id()));
            assertTrue(e.getMessage().contains("lab"), e.getMessage());
            assertFalse(chave.estaZerado());

            cofre.removerServidor(cfg.id());
            assertTrue(token.estaZerado());
            assertTrue(cofre.servidores().isEmpty());
            assertFalse(chave.estaZerado(), "a chave fica no cofre até ser removida");

            cofre.removerChave(chave.id());
            assertTrue(chave.estaZerado());
            assertTrue(cofre.chaves().isEmpty());
        }
        try (CofreDeChaves cofre = abrir(c)) {
            assertTrue(cofre.servidores().isEmpty());
            assertTrue(cofre.chaves().isEmpty());
        }
    }

    @Test
    void trocarOTokenZeraOAntigo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            ConfigServidor cfg = cofre.servidores().get(0);
            TokenAgente antigo = cofre.tokenDoServidor(cfg.id()).orElseThrow();
            String hashAntigo = antigo.hashSha256Hex();
            TokenAgente novo = TokenAgente.gerar();
            cofre.gravarToken(cfg.id(), novo, "outro-mac");
            assertTrue(antigo.estaZerado());
            assertFalse(novo.estaZerado());
            assertSame(novo, cofre.tokenDoServidor(cfg.id()).orElseThrow());
            assertEquals("outro-mac", cofre.servidor(cfg.id()).orElseThrow().nomeDoDispositivo());
            assertNotEquals(hashAntigo, novo.hashSha256Hex());
            // Regravar o MESMO objeto não o zera.
            cofre.gravarToken(cfg.id(), novo, "outro-mac");
            assertFalse(novo.estaZerado());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Escritas
    // ------------------------------------------------------------------------------------------

    @Test
    void falhaDeGravacaoNaoMudaANemoriaEDepoisFunciona(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root ignora permissões");
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = criar(c)) {
            Files.setPosixFilePermissions(c.pastaDados(), PosixFilePermissions.fromString("r-x------"));
            try {
                ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
                assertThrows(CofreException.class, () -> cofre.adicionarChave(chave));
                assertTrue(cofre.chaves().isEmpty(), "a gravação falhou: a memória não pode ter mudado");
                assertFalse(chave.estaZerado(), "a chave rejeitada continua sendo do chamador");
            } finally {
                Files.setPosixFilePermissions(c.pastaDados(), PosixFilePermissions.fromString("rwx------"));
            }
            cofre.adicionarChave(GeradorDeChaves.gerar("k2", "mac"));
            assertEquals(1, cofre.chaves().size());
        }
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(1, cofre.chaves().size());
        }
    }

    @Test
    void mudancaQueNaoMudaNadaNaoRegravaOArquivo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            ConfigServidor cfg = cofre.servidores().get(0);
            byte[] antes = ler(c.arquivoCofre());
            byte[] bakAntes = ler(c.arquivoCofreBak());
            cofre.gravarEstado2fa(cfg.id(), cfg.estado2fa());
            cofre.gravarPin(cfg.id(), cfg.pins().get(0));
            cofre.atualizarServidor(cfg);
            assertArrayEquals(antes, ler(c.arquivoCofre()));
            assertArrayEquals(bakAntes, ler(c.arquivoCofreBak()), "e não gira o .bak");
        }
    }

    @Test
    void pinEhPorAlgoritmoELimparPinsEsvazia(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            String id = cofre.servidores().get(0).id();
            Pin novoEd = new Pin("ssh-ed25519", "SHA256:novanovanovanovanovanovanovanovanovanovanova");
            Pin ecdsa = new Pin("ecdsa-sha2-nistp256", "SHA256:ecdsaecdsaecdsaecdsaecdsaecdsaecdsaecdsaec");
            cofre.gravarPin(id, novoEd);
            cofre.gravarPin(id, ecdsa);
            ConfigServidor cfg = cofre.servidor(id).orElseThrow();
            assertEquals(2, cfg.pins().size());
            assertEquals(novoEd, cfg.pinDe("ssh-ed25519").orElseThrow());
            assertEquals(ecdsa, cfg.pinDe("ecdsa-sha2-nistp256").orElseThrow());
            assertTrue(cfg.pinDe("rsa-sha2-512").isEmpty());
        }
        try (CofreDeChaves cofre = abrir(c)) {
            String id = cofre.servidores().get(0).id();
            assertEquals(2, cofre.servidor(id).orElseThrow().pins().size());
            cofre.limparPins(id);
        }
        try (CofreDeChaves cofre = abrir(c)) {
            assertTrue(cofre.servidores().get(0).pins().isEmpty());
        }
    }

    @Test
    void validacoesDeAdicionarEAtualizar(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = criar(c)) {
            ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
            ConfigServidor cfg = ConfigServidor.novo("lab", "h.local", 22, "u", chave.id());
            // Servidor sem a chave no cofre.
            assertThrows(IllegalArgumentException.class, () -> cofre.adicionarServidor(cfg, null));
            cofre.adicionarChave(chave);
            assertThrows(IllegalArgumentException.class, () -> cofre.adicionarChave(chave), "id repetido");
            cofre.adicionarServidor(cfg, null);
            assertThrows(IllegalArgumentException.class, () -> cofre.adicionarServidor(cfg, null), "id repetido");
            assertThrows(IllegalArgumentException.class,
                () -> cofre.atualizarServidor(ConfigServidor.novo("x", "h", 22, "u", chave.id())), "servidor desconhecido");
            assertThrows(IllegalArgumentException.class, () -> cofre.atualizarServidor(cfg.comChaveId("naoexiste")));
            assertThrows(IllegalArgumentException.class, () -> cofre.gravarEstado2fa("naoexiste", Estado2fa.ATIVO));
            assertThrows(IllegalArgumentException.class, () -> cofre.keyPairDoServidor("naoexiste"));
            assertThrows(IllegalArgumentException.class, () -> cofre.gravarToken(cfg.id(), TokenAgente.gerar(), "Nome Ruim"));

            cofre.atualizarServidor(cfg.comPin(new Pin("ssh-ed25519", "SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")));
            assertEquals(1, cofre.servidor(cfg.id()).orElseThrow().pins().size());
        }
    }

    @Test
    void gravacoesConcorrentesNaoPerdemNada(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        int threads = 4;
        int porThread = 5;
        try (CofreDeChaves cofre = criar(c)) {
            CountDownLatch largada = new CountDownLatch(1);
            AtomicReference<Throwable> erro = new AtomicReference<>();
            List<Thread> lista = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int n = t;
                Thread thread = new Thread(() -> {
                    try {
                        largada.await();
                        for (int i = 0; i < porThread; i++) {
                            cofre.adicionarChave(GeradorDeChaves.gerar("k" + n + "-" + i, "mac"));
                        }
                    } catch (Throwable e) {
                        erro.set(e);
                    }
                });
                lista.add(thread);
                thread.start();
            }
            largada.countDown();
            for (Thread t : lista) {
                t.join(60_000);
            }
            assertEquals(null, erro.get());
            assertEquals(threads * porThread, cofre.chaves().size());
        }
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(threads * porThread, cofre.chaves().size());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reforço do KDF e thread própria
    // ------------------------------------------------------------------------------------------

    @Test
    void kdfMaisFracoQueOAlvoEReforcadoNoDesbloqueio(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c); // criado com MINIMO: t=2
        byte[] salAntes = Arrays.copyOfRange(ler(c.arquivoCofre()), 16, 32);
        ParametrosKdf alvo = new ParametrosKdf(19_456, 3, 1);

        try (CofreDeChaves cofre = CofreDeChaves.abrir(c, senha(), alvo)) {
            assertTrue(cofre.avisos().stream().anyMatch(a -> a.contains("reforçados")), cofre.avisos().toString());
            assertEquals(1, cofre.servidores().size());
        }
        byte[] depois = ler(c.arquivoCofre());
        assertEquals(3, depois[14], "iterações no cabeçalho");
        assertFalse(Arrays.equals(salAntes, Arrays.copyOfRange(depois, 16, 32)), "sal novo");
        // O .bak também foi trocado: não pode ficar para trás uma cópia com o KDF fraco.
        assertArrayEquals(depois, ler(c.arquivoCofreBak()));

        // Reabrir com o mesmo alvo: nada a reforçar. A senha de sempre continua valendo.
        try (CofreDeChaves cofre = CofreDeChaves.abrir(c, senha(), alvo)) {
            assertTrue(cofre.avisos().isEmpty(), cofre.avisos().toString());
            assertEquals(Estado2fa.ATIVO, cofre.servidores().get(0).estado2fa());
        }
        assertArrayEquals(depois, ler(c.arquivoCofre()), "sem reforço, sem regravação");
    }

    @Test
    void kdfMaisForteQueOAlvoNaoEEnfraquecido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = CofreDeChaves.criar(c, senha(), new ParametrosKdf(19_456, 4, 1))) {
            cofre.salvar();
        }
        byte[] antes = ler(c.arquivoCofre());
        try (CofreDeChaves cofre = CofreDeChaves.abrir(c, senha(), ParametrosKdf.MINIMO)) {
            assertTrue(cofre.avisos().isEmpty());
        }
        assertArrayEquals(antes, ler(c.arquivoCofre()));
    }

    @Test
    void abrirEmThreadPropriaUsaCofreKdfECopiaASenha(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        AtomicReference<String> nome = new AtomicReference<>();
        CofreDeChaves.emThreadDoKdf(() -> nome.updateAndGet(x -> Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        assertEquals("cofre-kdf", nome.get());

        char[] senha = Fixtures.senha();
        var futuro = CofreDeChaves.abrirEmThreadPropria(c, senha);
        Zeragem.zerar(senha); // o chamador zera na hora; a abertura usa a cópia dela
        try (CofreDeChaves cofre = futuro.get(30, TimeUnit.SECONDS)) {
            // PADRAO é o alvo: o cofre MINIMO é reforçado a 256 MiB, e abre mesmo assim.
            assertEquals(1, cofre.servidores().size());
        }
    }

    @Test
    void criarEmThreadPropriaFalhaComSenhaCurtaViaFuturo(@TempDir Path tmp) {
        var futuro = CofreDeChaves.criarEmThreadPropria(caminhos(tmp), "curta".toCharArray());
        var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> futuro.get(30, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void cofreComParametrosDeProducaoAbreEGrava(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        try (CofreDeChaves cofre = CofreDeChaves.criar(c, senha())) {
            cofre.adicionarChave(GeradorDeChaves.gerar("k", "mac"));
        }
        byte[] arquivo = ler(c.arquivoCofre());
        assertEquals(262_144, java.nio.ByteBuffer.wrap(arquivo, 10, 4).getInt());
        assertEquals(6, arquivo[14]);
        assertEquals(1, arquivo[15]);
        try (CofreDeChaves cofre = CofreDeChaves.abrir(c, senha())) {
            assertTrue(cofre.avisos().isEmpty(), cofre.avisos().toString());
            assertEquals(1, cofre.chaves().size());
        }
    }

    @Test
    void usoPelaInterfaceEstreitaAcessoAoCofre(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            AcessoAoCofre acesso = cofre;
            ConfigServidor cfg = acesso.servidores().get(0);
            assertNotNull(acesso.keyPairDoServidor(cfg.id()));
            assertTrue(acesso.tokenDoServidor(cfg.id()).isPresent());
            acesso.gravarEstado2fa(cfg.id(), Estado2fa.PENDENTE);
            assertEquals(Estado2fa.PENDENTE, acesso.servidor(cfg.id()).orElseThrow().estado2fa());
        }
    }
}
