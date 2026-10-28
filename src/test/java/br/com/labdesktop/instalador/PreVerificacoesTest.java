package br.com.labdesktop.instalador;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.RelatorioDeVerificacao.Administrador;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Estado;
import br.com.labdesktop.instalador.ResultadoDaVerificacao.Item;
import br.com.labdesktop.teste.ServidorSimulado;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * As verificações prévias da instalação do agente: cada uma, contra um servidor simulado, com a frase e o comando exato que o usuário
 * (ou o administrador do servidor) deve rodar quando falta algo. Só LEITURA: nenhum comando de escrita roda aqui.
 */
class PreVerificacoesTest {

    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[] {1, 2, 3, 4});
    private final ParametrosDaInstalacao parametros = ParametrosDaInstalacao.padrao(7000);
    private final ServidorSimulado servidor = new ServidorSimulado(parametros, PACOTE);

    private RelatorioDeVerificacao verificar(ModoDaInstalacao modo) throws Exception {
        RelatorioDeVerificacao r = new PreVerificacoes(servidor, parametros, PACOTE).executar(modo);
        assertTrue(servidor.escritas().isEmpty(), "verificar só lê: " + servidor.escritas());
        assertFalse(servidor.comandos.isEmpty());
        return r;
    }

    private static void assertEstado(RelatorioDeVerificacao r, Item item, Estado esperado) {
        ResultadoDaVerificacao v = r.de(item);
        assertNotNull(v, "faltou a verificação " + item);
        assertEquals(esperado, v.estado(), item + ": " + v.frase());
    }

    @Test
    void debianComDockerEPermissaoTudoOkNaOrdemDaTela() throws Exception {
        servidor.noGrupoDocker = true;
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(r.bloqueado(), r.problemas().toString());
        assertEquals(List.of(Item.SISTEMA, Item.ARQUITETURA, Item.PERMISSAO, Item.SHELL, Item.TMUX, Item.DOCKER, Item.COMPOSE, Item.CONTAINER,
            Item.PASTA, Item.PORTA, Item.DISCO, Item.INTERNET), r.resultados().stream().map(ResultadoDaVerificacao::item).toList());
        for (ResultadoDaVerificacao v : r.resultados()) {
            assertEquals(Estado.OK, v.estado(), v.item() + ": " + v.frase());
            assertFalse(v.frase().isBlank());
        }
        assertEquals("docker", r.docker());
        assertTrue(r.dockerPresente());
        assertFalse(r.vaiInstalarODocker());
        assertTrue(r.de(Item.SISTEMA).frase().contains("Debian GNU/Linux 13"), r.de(Item.SISTEMA).frase());
        assertTrue(r.de(Item.DOCKER).frase().contains("27.3.1"));
        assertTrue(r.de(Item.DISCO).frase().contains("40 GB"), r.de(Item.DISCO).frase());
    }

    @Test
    void ubuntuSemDockerComSudoSemSenhaOferecePorOApp() throws Exception {
        servidor.ubuntu().semDocker();
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(r.bloqueado(), r.problemas().toString());
        assertEstado(r, Item.DOCKER, Estado.AVISO);
        assertTrue(r.de(Item.DOCKER).frase().contains("pode instalá-lo"), r.de(Item.DOCKER).frase());
        assertEquals(Administrador.SUDO_SEM_SENHA, r.administrador());
        assertTrue(r.vaiInstalarODocker());
        assertNull(r.docker(), "ainda não há Docker para usar");
        assertEstado(r, Item.PERMISSAO, Estado.OK);
        assertTrue(r.de(Item.PERMISSAO).frase().contains("sudo sem senha"), r.de(Item.PERMISSAO).frase());
        assertEstado(r, Item.COMPOSE, Estado.AVISO);
    }

    @Test
    void rootSemDockerTambemPodeInstalar() throws Exception {
        servidor.comoRoot().semDocker();
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Administrador.ROOT, r.administrador());
        assertTrue(r.vaiInstalarODocker());
        assertTrue(r.root());
        assertTrue(r.de(Item.PERMISSAO).frase().contains("root"));
    }

    @Test
    void semDockerESemSudoBloqueiaEDizOComandoExatoParaOAdministrador() throws Exception {
        servidor.semDocker().semPermissaoNenhuma();
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertTrue(r.bloqueado());
        ResultadoDaVerificacao p = r.de(Item.PERMISSAO);
        assertEquals(Estado.PROBLEMA, p.estado());
        assertTrue(p.frase().contains("não consegue instalar o Docker"), p.frase());
        assertTrue(p.frase().contains("não tenta elevar") || p.frase().contains("Peça a quem administra"), p.frase());
        assertEquals("echo \"labuser ALL=(ALL) NOPASSWD:ALL\" | sudo tee /etc/sudoers.d/90-lab-desktop >/dev/null && sudo chmod 0440 /etc/sudoers.d/90-lab-desktop && sudo visudo -cf /etc/sudoers.d/90-lab-desktop", p.comandoSugerido());
        assertFalse(r.vaiInstalarODocker());
        assertEstado(r, Item.DOCKER, Estado.PROBLEMA);
    }

    @Test
    void dockerPresenteSoComSudoVaiPorSudoEAvisaComOComandoDoGrupo() throws Exception {
        servidor.noGrupoDocker = false; // tem sudo, não está no grupo docker
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(r.bloqueado(), r.problemas().toString());
        assertEquals("sudo -n docker", r.docker());
        ResultadoDaVerificacao p = r.de(Item.PERMISSAO);
        assertEquals(Estado.AVISO, p.estado());
        assertEquals("sudo usermod -aG docker labuser", p.comandoSugerido());
        assertTrue(p.frase().contains("sudo sem senha"), p.frase());
        assertEstado(r, Item.DOCKER, Estado.OK);
    }

    @Test
    void dockerSemPermissaoNenhumaBloqueiaComOComandoDoGrupo() throws Exception {
        servidor.semPermissaoNenhuma();
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertTrue(r.bloqueado());
        ResultadoDaVerificacao p = r.de(Item.PERMISSAO);
        assertEquals(Estado.PROBLEMA, p.estado());
        assertEquals("sudo usermod -aG docker labuser", p.comandoSugerido());
        assertTrue(p.frase().contains("não tenta elevar privilégio"), p.frase());
        assertNull(r.docker());
        assertFalse(r.dockerPresente());
    }

    @Test
    void dockerParadoBloqueiaComOComandoParaLigar() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.dockerParado = true;
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.PROBLEMA, r.de(Item.DOCKER).estado());
        assertEquals("sudo systemctl start docker", r.de(Item.DOCKER).comandoSugerido());
    }

    @Test
    void outraDistribuicaoSemDockerDizQueOAppAindaNaoInstalaEPermiteJaInstalei() throws Exception {
        servidor.fedora().semDocker();
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.AVISO, r.de(Item.SISTEMA).estado());
        assertTrue(r.de(Item.SISTEMA).frase().contains("Fedora"), r.de(Item.SISTEMA).frase());
        ResultadoDaVerificacao d = r.de(Item.DOCKER);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertTrue(d.frase().contains("ainda não o instala"), d.frase());
        assertTrue(d.frase().contains("Já instalei, continuar"), d.frase());
        assertFalse(r.vaiInstalarODocker());
        // o usuário instalou e clica em "Já instalei, continuar": a mesma verificação, agora com o Docker
        servidor.dockerInstalado = true;
        servidor.noGrupoDocker = true;
        servidor.limparRegistro();
        RelatorioDeVerificacao de_novo = verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(de_novo.bloqueado(), de_novo.problemas().toString());
    }

    @Test
    void fedoraComDockerNaoImpedeNada() throws Exception {
        servidor.fedora();
        servidor.noGrupoDocker = true;
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertFalse(r.bloqueado(), r.problemas().toString());
        assertEquals(Estado.AVISO, r.de(Item.SISTEMA).estado());
    }

    @Test
    void arquiteturaQueNaoTemImagemBloqueia() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.arquitetura = "armv7l";
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.PROBLEMA, r.de(Item.ARQUITETURA).estado());
        assertTrue(r.de(Item.ARQUITETURA).frase().contains("armv7l"));
        servidor.arquitetura = "aarch64";
        assertEquals(Estado.OK, verificar(ModoDaInstalacao.INSTALAR).de(Item.ARQUITETURA).estado());
    }

    @Test
    void portaOcupadaBloqueiaMasNaoSeForODoAgenteDoApp() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.portaOcupada = true;
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.PROBLEMA, r.de(Item.PORTA).estado());
        assertTrue(r.de(Item.PORTA).frase().contains("7000"));
        assertTrue(r.de(Item.PORTA).frase().contains("em uso"));

        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        RelatorioDeVerificacao doApp = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.OK, doApp.de(Item.PORTA).estado(), "a porta é do agente que o app instalou antes");
        assertEquals(Estado.AVISO, doApp.de(Item.CONTAINER).estado());
        assertFalse(doApp.bloqueado());
    }

    @Test
    void semOComandoSsAPortaVirAvisoENaoProblema() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.ssDisponivel = false;
        assertEquals(Estado.AVISO, verificar(ModoDaInstalacao.INSTALAR).de(Item.PORTA).estado());
    }

    @Test
    void discoCheioBloqueiaEOLimiteEDeDoisGigas() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.discoLivreKib = 1024L * 1024; // 1 GB
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.PROBLEMA, r.de(Item.DISCO).estado());
        assertTrue(r.de(Item.DISCO).frase().contains("1,0 GB"), r.de(Item.DISCO).frase());
        servidor.discoLivreKib = PreVerificacoes.MINIMO_DE_DISCO_KIB - 1;
        assertEquals(Estado.PROBLEMA, verificar(ModoDaInstalacao.INSTALAR).de(Item.DISCO).estado());
        servidor.discoLivreKib = PreVerificacoes.MINIMO_DE_DISCO_KIB;
        assertEquals(Estado.OK, verificar(ModoDaInstalacao.INSTALAR).de(Item.DISCO).estado());
    }

    @Test
    void semSaidaParaAInternetBloqueiaNaInstalacaoEAvisaNaAtualizacao() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.internet = "000";
        assertEquals(Estado.PROBLEMA, verificar(ModoDaInstalacao.INSTALAR).de(Item.INTERNET).estado());
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        assertEquals(Estado.AVISO, verificar(ModoDaInstalacao.ATUALIZAR).de(Item.INTERNET).estado());
        servidor.internet = "sem-ferramenta";
        assertEquals(Estado.AVISO, verificar(ModoDaInstalacao.INSTALAR).de(Item.INTERNET).estado());
    }

    @Test
    void containerComOMesmoNomeQueNaoEDoAppBloqueiaEOAppNaoMexeNele() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.containerNome = "painel-lab"; // o agente do lab de produção: sem o rótulo do instalador
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        ResultadoDaVerificacao c = r.de(Item.CONTAINER);
        assertEquals(Estado.PROBLEMA, c.estado());
        assertTrue(c.frase().contains("NÃO foi criado por este app"), c.frase());
        assertTrue(c.frase().contains("outro nome"), c.frase());
        assertEquals(Estado.PROBLEMA, verificar(ModoDaInstalacao.ATUALIZAR).de(Item.CONTAINER).estado());
    }

    @Test
    void atualizarExigeUmAgenteDoAppEAvisaDeVersaoParaVersao() throws Exception {
        servidor.noGrupoDocker = true;
        RelatorioDeVerificacao sem = verificar(ModoDaInstalacao.ATUALIZAR);
        assertEquals(Estado.PROBLEMA, sem.de(Item.CONTAINER).estado());
        assertTrue(sem.de(Item.CONTAINER).frase().contains("para atualizar"));

        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        RelatorioDeVerificacao com = verificar(ModoDaInstalacao.ATUALIZAR);
        assertFalse(com.bloqueado(), com.problemas().toString());
        assertTrue(com.de(Item.CONTAINER).frase().contains("0.0.9-velha"));
        assertTrue(com.de(Item.CONTAINER).frase().contains("0.1.0-abc1234"));
        assertTrue(com.de(Item.PORTA).frase().contains("substituído"), "na atualização a porta ocupada é a do agente atual");
    }

    @Test
    void shellEmuxSaoSoAvisosQueNaoImpedem() throws Exception {
        servidor.noGrupoDocker = true;
        servidor.shell = "/usr/bin/fish";
        servidor.tmux = false;
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertEquals(Estado.AVISO, r.de(Item.SHELL).estado());
        assertTrue(r.de(Item.SHELL).frase().contains("fish"));
        assertEquals(Estado.AVISO, r.de(Item.TMUX).estado());
        assertTrue(r.de(Item.TMUX).frase().contains("Só é preciso para sessões do Claude"));
        assertFalse(r.bloqueado());
    }

    @Test
    void usuarioComNomeEstranhoNaoVirouComandoSugerido() throws Exception {
        servidor.semDocker().semPermissaoNenhuma();
        servidor.usuario = "x; rm -rf /";
        RelatorioDeVerificacao r = verificar(ModoDaInstalacao.INSTALAR);
        assertNull(r.de(Item.PERMISSAO).comandoSugerido(), "nome vindo do servidor que não passa na regra nunca vira comando para colar");
        assertFalse(r.de(Item.PERMISSAO).frase().contains("rm -rf"));
    }

    @Test
    void todasAsVerificacoesSaoLeiturasFixas() throws Exception {
        servidor.noGrupoDocker = true;
        verificar(ModoDaInstalacao.INSTALAR);
        for (String c : servidor.comandos) {
            assertTrue(ComandosRemotos.ehLeitura(c), "comando que não é de leitura nas verificações: " + c);
        }
    }
}
