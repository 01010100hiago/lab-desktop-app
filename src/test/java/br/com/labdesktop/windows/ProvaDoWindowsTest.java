package br.com.labdesktop.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.ClienteSftpLocal;
import br.com.labdesktop.plataforma.AmbienteDoSistema;
import br.com.labdesktop.plataforma.PastasDoApp;
import br.com.labdesktop.util.AparenciaDoSistema;
import br.com.labdesktop.util.Permissoes;
import br.com.labdesktop.util.ProtecaoAcl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * As PROVAS no Windows de verdade: só rodam em máquina Windows, e só com {@code ./gradlew provaWindows} (o job do Windows do CI roda sem bloquear). Cada uma confere uma
 * suposição que num Mac só se consegue escrever como texto: a ACL do NTFS, a junção, o registro do tema, as pastas do usuário. O que elas imprimem fica no relatório do job, para o
 * usuário ver o que o Windows de verdade respondeu mesmo quando tudo passa. Tag {@code prova-windows}: fora do {@code ./gradlew test} normal.
 */
@Tag("prova-windows")
@EnabledOnOs(OS.WINDOWS)
class ProvaDoWindowsTest {

    @Test
    void asPastasDoAppSaoAsDoPerfilDeVerdade() {
        PastasDoApp p = PastasDoApp.de(AmbienteDoSistema.detectar());
        System.out.println("[prova-windows] dados=" + p.dados() + " logs=" + p.logs());
        assertTrue(p.dados().toLowerCase().contains("appdata"), p.dados());
        assertTrue(p.logs().toLowerCase().contains("appdata") && p.logs().endsWith("Logs"), p.logs());
    }

    @Test
    void aAclDoNtfsFicaSoComODonoOuRegistraQuemMaisTemAcesso(@TempDir Path tmp) throws Exception {
        Path arquivo = tmp.resolve("segredo.txt");
        Files.writeString(arquivo, "x");
        AclFileAttributeView v = Files.getFileAttributeView(arquivo, AclFileAttributeView.class);
        assertNotNull(v, "o NTFS deveria ter a vista de ACL");
        new ProtecaoAcl().restringirAoDono(arquivo, false);
        var acl = v.getAcl();
        var proprietario = v.getOwner();
        System.out.println("[prova-windows] proprietario=" + proprietario.getName() + " ACL depois de restringir=" + acl);
        assertTrue(acl.stream().anyMatch(e -> e.type() == AclEntryType.ALLOW && e.principal().equals(proprietario)), "o proprietario tem acesso");
        // as entradas herdadas do pai que sobraram são só INFORMAÇÃO: a suposição é que não há 'Todos' nem 'Usuários' na pasta do perfil
        List<String> estranhos = acl.stream().map(e -> e.principal().getName().toLowerCase()).filter(n -> n.contains("everyone") || n.contains("todos") || n.endsWith("\\users") || n.endsWith("\\usuários")).toList();
        System.out.println("[prova-windows] entradas de Todos/Usuários na ACL: " + estranhos);
        assertEquals(List.of(), estranhos);
    }

    @Test
    void aPermissoesCriaPastaEArquivoPrivadosNoNtfs(@TempDir Path tmp) throws Exception {
        Path pasta = tmp.resolve("dados");
        Permissoes.criarPastaPrivada(pasta);
        Path arquivo = pasta.resolve("a.bin");
        Permissoes.criarArquivoPrivado(arquivo);
        assertTrue(Files.isDirectory(pasta) && Files.isRegularFile(arquivo));
        System.out.println("[prova-windows] Permissoes.comoTexto(pasta)=" + Permissoes.comoTexto(pasta));
    }

    @Test
    void aTravaDeInstanciaSobreviveAApagarOuRenomearOArquivoAbertoNoWindows(@TempDir Path tmp) throws Exception {
        // no Unix, apagar o cofre.lock com o app aberto deixa uma SEGUNDA instância criar outro arquivo e travar o novo (duas instâncias no mesmo cofre). No Windows a suposição é que
        // o arquivo aberto não some nem muda de nome, ou, se some, que a segunda instância ainda é recusada. A prova imprime o que o Windows fez.
        br.com.labdesktop.util.CaminhosApp c = br.com.labdesktop.util.CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        try (var primeira = br.com.labdesktop.cofre.TravaDeInstancia.adquirir(c)) {
            java.util.Objects.requireNonNull(primeira);
            Path trava = c.arquivoTrava();
            boolean apagou;
            try {
                Files.delete(trava);
                apagou = true;
            } catch (java.io.IOException e) {
                apagou = false;
            }
            boolean renomeou;
            try {
                Files.move(trava, trava.resolveSibling("outro-nome.lock"));
                renomeou = true;
            } catch (java.io.IOException e) {
                renomeou = false;
            }
            System.out.println("[prova-windows] com a trava aberta: apagar=" + apagou + " renomear=" + renomeou);
            boolean segundaRecusada;
            try (var segunda = br.com.labdesktop.cofre.TravaDeInstancia.adquirir(c)) {
                java.util.Objects.requireNonNull(segunda);
                segundaRecusada = false;
            } catch (br.com.labdesktop.cofre.CofreEmUsoException e) {
                segundaRecusada = true;
            }
            System.out.println("[prova-windows] segunda instância recusada=" + segundaRecusada);
            assertTrue(segundaRecusada || (!apagou && !renomeou), "ou a segunda instância é recusada, ou o Windows não deixou apagar nem renomear a trava");
        }
    }

    @Test
    void oRegistroDoTemaResponde() {
        boolean escuro = AparenciaDoSistema.escuro();
        System.out.println("[prova-windows] AppsUseLightTheme diz: " + (escuro ? "escuro" : "claro"));
    }

    @Test
    void apagarUmaPastaComJuncaoNaoApagaOQueEstaNoDestino(@TempDir Path tmp) throws Exception {
        Path destino = tmp.resolve("destino");
        Files.createDirectories(destino);
        Files.writeString(destino.resolve("importante.txt"), "nao apague");
        Path pasta = tmp.resolve("pasta");
        Files.createDirectories(pasta);
        Path juncao = pasta.resolve("juncao");
        Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", juncao.toString(), destino.toString()).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "mklink /J falhou: " + saida);
        assertTrue(Files.exists(juncao.resolve("importante.txt")), "a junção aponta para o destino");
        ClienteSftpLocal c = ClienteSftpLocal.doSistema();
        c.apagar(br.com.labdesktop.arquivos.CaminhosLocais.doSistema().texto(pasta), true);
        assertFalse(Files.exists(pasta), "a pasta saiu");
        assertTrue(Files.exists(destino.resolve("importante.txt")), "o conteúdo do destino da junção ficou");
    }
}
