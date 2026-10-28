package br.com.labdesktop.acesso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.ResultadoExec;
import br.com.labdesktop.teste.ShellDeTeste;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A instalação da chave com o {@code sh} de VERDADE (o comando fixo roda numa pasta pessoal de teste): cria a pasta e o arquivo com 700 e 600,
 * preserva o que já existia (com ou sem a quebra de linha final), NÃO duplica ao repetir, e valida a chave antes de ela sair do app.
 */
class InstaladorDeChaveTest {

    @TempDir
    Path tmp;

    private ShellDeTeste shell;
    private ChaveSsh chave;

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh");
        shell = new ShellDeTeste(tmp);
        chave = GeradorDeChaves.gerar("lab", "Mac de Teste");
    }

    private InstaladorDeChave.Resultado instalar() throws Exception {
        return InstaladorDeChave.instalar(shell, chave.publica(), 7000);
    }

    private static String permissoes(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    private List<String> linhasDoArquivo() throws Exception {
        return Files.readAllLines(shell.authorizedKeys());
    }

    @Test
    void criaAPastaEOArquivoComPermissoesSeguras() throws Exception {
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, instalar());

        assertEquals("rwx------", permissoes(tmp.resolve(".ssh")), "a pasta .ssh fica 700");
        assertEquals("rw-------", permissoes(shell.authorizedKeys()), "o authorized_keys fica 600");
        List<String> linhas = linhasDoArquivo();
        assertEquals(1, linhas.size());
        assertTrue(linhas.get(0).startsWith("no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:7000\" ssh-ed25519 "), linhas.get(0));
        assertTrue(linhas.get(0).endsWith(" lab-desktop@Mac-de-Teste"), "o comentário vira texto limpo: " + linhas.get(0));
    }

    @Test
    void instalarDuasVezesNaoDuplicaEDizQueJaExistia() throws Exception {
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, instalar());
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, instalar());
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, instalar());
        assertEquals(1, linhasDoArquivo().size(), "uma linha só, por mais vezes que se instale");
    }

    @Test
    void preservaAsChavesQueJaEstavamEAsPermissoesFrouxasViramSeguras() throws Exception {
        Files.createDirectories(tmp.resolve(".ssh"));
        Files.setPosixFilePermissions(tmp.resolve(".ssh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        String antiga = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDexemplo usuario@notebook\n# comentario do usuário\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIoutra outra@maquina\n";
        Files.writeString(shell.authorizedKeys(), antiga);
        Files.setPosixFilePermissions(shell.authorizedKeys(), PosixFilePermissions.fromString("rw-r--r--"));

        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, instalar());

        List<String> linhas = linhasDoArquivo();
        assertEquals(4, linhas.size());
        assertEquals(List.of(antiga.split("\n")), linhas.subList(0, 3), "o que o usuário já tinha continua igual, na mesma ordem");
        assertEquals("rwx------", permissoes(tmp.resolve(".ssh")));
        assertEquals("rw-------", permissoes(shell.authorizedKeys()));
    }

    @Test
    void arquivoSemQuebraDeLinhaNoFimNaoGrudaALinhaNova() throws Exception {
        Files.createDirectories(tmp.resolve(".ssh"));
        Files.writeString(shell.authorizedKeys(), "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIsemquebra x@y"); // sem \n no fim
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, instalar());
        List<String> linhas = linhasDoArquivo();
        assertEquals(2, linhas.size(), "a linha antiga e a nova são linhas diferentes");
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIsemquebra x@y", linhas.get(0));
    }

    @Test
    void naoUsaSudoNemTocaEmOutroLugarQueAPastaPessoal() throws Exception {
        instalar();
        for (String comando : shell.recebidos) {
            assertFalse(comando.contains("sudo"), "instalar a chave não pede privilégio: " + comando);
        }
        assertEquals(1, shell.recebidos.size(), "UM comando só (a leitura de permissões é de outro tipo e não conta aqui)");
        try (var arquivos = Files.walk(tmp)) {
            assertEquals(Set.of(".ssh", ".ssh/authorized_keys"),
                arquivos.filter(p -> !p.equals(tmp)).map(p -> tmp.relativize(p).toString().replace('\\', '/')).collect(java.util.stream.Collectors.toSet()),
                "só a pasta .ssh e o arquivo");
        }
    }

    @Test
    void aChaveVaiPorStdinENuncaNaLinhaDeComando() throws Exception {
        instalar();
        String blob = chave.publica().split(" ")[1];
        for (String comando : shell.recebidos) {
            assertFalse(comando.contains(blob), "a chave não está na linha de comando: " + comando);
        }
    }

    private String blob() {
        return chave.publica().split(" ")[1];
    }

    @Test
    void chaveSoComentadaOuRestritaGanhaUmaLinhaNossaENuncaDizJaExistia() throws Exception {
        Files.createDirectories(tmp.resolve(".ssh"));
        String comentada = "# ssh-ed25519 " + blob() + " antiga\n";
        String restrita = "command=\"/bin/true\",from=\"10.0.0.1\" ssh-ed25519 " + blob() + " presa\n";
        Files.writeString(shell.authorizedKeys(), comentada + restrita);

        assertEquals(InstaladorDeChave.Resultado.ADICIONADA_APESAR_DE_LINHA_INUTIL, instalar(), "não existe uma linha que sirva: acrescenta e avisa");
        List<String> linhas = linhasDoArquivo();
        assertEquals(3, linhas.size());
        assertEquals("# ssh-ed25519 " + blob() + " antiga", linhas.get(0), "o que era do usuário fica intacto");
        assertTrue(linhas.get(1).startsWith("command="));
        assertTrue(linhas.get(2).startsWith("no-agent-forwarding,"));
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, instalar(), "agora há uma linha que serve");
        assertEquals(3, linhasDoArquivo().size());
    }

    @Test
    void linhaPlenaJaExistenteNaoDuplicaMesmoComOpcoesNossasOuSemElas() throws Exception {
        Files.createDirectories(tmp.resolve(".ssh"));
        Files.writeString(shell.authorizedKeys(), "ssh-ed25519 " + blob() + " de-teste\n");
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, instalar());
        assertEquals(1, linhasDoArquivo().size());
    }

    @Test
    void ruidoDoShellDoUsuarioAntesEDepoisNaoQuebraALeitura() throws Exception {
        var comRuido = (br.com.labdesktop.ssh.ProvisionadorRemoto.Executor) (c, in, prazo) -> {
            ResultadoExec r = shell.executar(c, in, prazo);
            return new ResultadoExec(r.status(), ("Bem-vindo ao servidor!\nultimo login: ontem\n" + r.stdout() + "ate logo\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), r.stderrBytes(), false);
        };
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, InstaladorDeChave.instalar(comRuido, chave.publica(), 7000));
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, InstaladorDeChave.instalar(comRuido, chave.publica(), 7000));
    }

    @Test
    void semOsMarcadoresOuComCodigoDeErroNaoAfirmaNada() {
        assertEquals(null, InstaladorDeChave.respostaEntreOsMarcadores("adicionada\n"));
        assertEquals(null, InstaladorDeChave.respostaEntreOsMarcadores(ComandosDoAcesso.MARCADOR_DE_INICIO + "\nadicionada\n"), "sem o fim, o comando não terminou");
        assertEquals("adicionada", InstaladorDeChave.respostaEntreOsMarcadores("lixo\n" + ComandosDoAcesso.MARCADOR_DE_INICIO + "\nadicionada\n" + ComandosDoAcesso.MARCADOR_DE_FIM + "\n"));
        for (int codigo : new int[] {6, 7, 9}) {
            var executor = (br.com.labdesktop.ssh.ProvisionadorRemoto.Executor) (c, in, prazo) -> new ResultadoExec(codigo, new byte[0], new byte[0], false);
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> InstaladorDeChave.instalar(executor, chave.publica(), 7000));
            assertTrue(e.getMessage().contains("pode ter sido instalada") || e.getMessage().contains("está instalada"), codigo + ": " + e.getMessage());
            assertFalse(e.getMessage().contains("Nada mais foi alterado"), e.getMessage());
        }
    }

    @Test
    void chaveInvalidaNuncaSaiDoApp() {
        for (String ruim : new String[] {"", "ssh-rsa AAAAB3NzaC1yc2E x", "ssh-ed25519 curta x", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI'; rm -rf / #",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nssh-ed25519 outra"}) {
            assertThrows(IllegalArgumentException.class, () -> InstaladorDeChave.linhaParaInstalar(ruim, 7000), ruim);
        }
        assertThrows(IllegalArgumentException.class, () -> InstaladorDeChave.linhaParaInstalar(chave.publica(), 0));
        assertThrows(IllegalArgumentException.class, () -> InstaladorDeChave.linhaParaInstalar(chave.publica(), 70_000));
    }

    @Test
    void comentarioComAcentoEAspaEVirandoTextoLimpo() {
        String base = chave.publica().split(" ")[0] + " " + chave.publica().split(" ")[1];
        String linha = InstaladorDeChave.linhaParaInstalar(base + " lab-desktop@Mac de Teste's \"novo\"; rm -rf", 7000);
        String comentario = linha.substring(linha.lastIndexOf(' ') + 1);
        assertTrue(comentario.matches("[A-Za-z0-9._@-]+"), comentario);
        assertFalse(linha.contains("'") || linha.contains(";") || linha.chars().filter(c -> c == '"').count() != 2, linha);
    }

    @Test
    void falhaDoComandoViraMensagemEmPortuguesSemMexerEmMais() {
        var executor = (br.com.labdesktop.ssh.ProvisionadorRemoto.Executor) (c, in, prazo) -> new ResultadoExec(5, new byte[0], new byte[0], false);
        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> InstaladorDeChave.instalar(executor, chave.publica(), 7000));
        assertEquals(FalhaDeEntradaPorSenha.Motivo.FALHOU_AO_INSTALAR, e.motivo());
        assertTrue(e.getMessage().contains("authorized_keys"), e.getMessage());
        assertTrue(e.getMessage().contains("Nada foi acrescentado"), e.getMessage());
    }
}
