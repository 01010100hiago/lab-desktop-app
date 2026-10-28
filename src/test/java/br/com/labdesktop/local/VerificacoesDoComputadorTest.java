package br.com.labdesktop.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.instalador.ResultadoDaVerificacao.Estado;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * "Preparar este computador": cada verificação com o executor falso (Docker ausente, parado, ok, pouco disco, sem WSL, porta ocupada,
 * arquitetura...). Prova também que a conferência só LÊ: nenhum comando de instalação roda antes de um clique.
 */
class VerificacoesDoComputadorTest {

    private final ComputadorSimulado pc = new ComputadorSimulado();
    private final SondaFalsa sonda = new SondaFalsa();
    private PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of());

    private RelatorioDoComputador verificar() throws Exception {
        return new VerificacoesDoComputador(pc, sonda, plataforma).executar(plataforma.pastaDoLaboratorio(), 7070);
    }

    @Test
    void tudoOkNaOrdemDaTelaEProntoParaCriar() throws Exception {
        RelatorioDoComputador r = verificar();
        assertFalse(r.bloqueado(), r.problemas().toString());
        assertTrue(r.pronto());
        assertEquals(List.of(ItemDoComputador.SISTEMA, ItemDoComputador.ARQUITETURA, ItemDoComputador.DOCKER, ItemDoComputador.COMPOSE, ItemDoComputador.DISCO,
            ItemDoComputador.MEMORIA, ItemDoComputador.PORTA, ItemDoComputador.GIT), r.resultados().stream().map(ResultadoDoComputador::item).toList(),
            "no Mac não há item de virtualização, e o node NÃO é requisito");
        for (ResultadoDoComputador v : r.resultados()) {
            assertEquals(Estado.OK, v.estado(), v.item() + ": " + v.frase());
            assertFalse(v.frase().isBlank());
        }
        assertTrue(r.de(ItemDoComputador.DOCKER).frase().contains("29.6.2"));
        assertEquals(7070, r.portaLivreSugerida());
    }

    @Test
    void aConferenciaSoLeENuncaRodaInstalacaoNemAbreNada() throws Exception {
        pc.dockerInstalado = false;
        pc.gitInstalado = false;
        verificar();
        assertFalse(pc.comandos.isEmpty());
        for (List<String> c : pc.comandos) {
            assertTrue(ComandosLocais.ehLeitura(c), "a conferência só pode rodar leituras fixas: " + c);
        }
        assertTrue(pc.escritas().isEmpty());
    }

    @Test
    void dockerAusenteNoMacComHomebrewOfereceDockerDesktopERecomendaEColimaComoAlternativa() throws Exception {
        pc.dockerInstalado = false;
        sonda.programas.add("brew");
        RelatorioDoComputador r = verificar();
        ResultadoDoComputador d = r.de(ItemDoComputador.DOCKER);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertTrue(r.bloqueado());
        assertFalse(r.pronto());
        assertEquals(2, d.planos().size());
        PlanoDeInstalacao docker = d.planos().get(0);
        assertTrue(docker.recomendado());
        assertEquals(List.of(List.of("brew", "install", "--cask", "docker-desktop")), docker.comandos());
        assertEquals(List.of("brew install --cask docker-desktop"), docker.comandosParaTela());
        assertTrue(docker.pedeSenhaDoSistema(), "o sistema pode pedir a senha: isso é do sistema");
        PlanoDeInstalacao colima = d.planos().get(1);
        assertFalse(colima.recomendado());
        assertEquals(List.of(List.of("brew", "install", "colima", "docker")), colima.comandos());
    }

    @Test
    void dockerAusenteNoMacSemHomebrewAbreAPaginaOficialENaoRodaNada() throws Exception {
        pc.dockerInstalado = false;
        sonda.programas.clear();
        ResultadoDoComputador d = verificar().de(ItemDoComputador.DOCKER);
        assertEquals(1, d.planos().size());
        PlanoDeInstalacao p = d.planos().get(0);
        assertFalse(p.executavelPeloApp());
        assertEquals("https://www.docker.com/products/docker-desktop/", p.paginaParaAbrir());
        assertFalse(p.passosManuais().isEmpty());
        assertTrue(p.passosManuais().stream().anyMatch(s -> s.contains("senha de administrador") && s.contains("do sistema")));
    }

    @Test
    void dockerAusenteNoWindowsComWingetOfereceWingetEAPagina() throws Exception {
        plataforma = new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of());
        sonda.programas.clear();
        sonda.programas.add("winget");
        sonda.arquitetura = "amd64";
        pc.dockerInstalado = false;
        ResultadoDoComputador d = verificar().de(ItemDoComputador.DOCKER);
        assertEquals(2, d.planos().size());
        PlanoDeInstalacao winget = d.planos().get(0);
        assertEquals("winget", winget.comandos().get(0).get(0));
        assertTrue(winget.comandos().get(0).containsAll(List.of("install", "-e", "--id", "Docker.DockerDesktop")));
        assertTrue(winget.resumo().contains("aceita os termos"), "o aceite dos termos está dito na confirmação, antes do clique");
        assertTrue(winget.precisaReiniciar());
        assertEquals("https://www.docker.com/products/docker-desktop/", d.planos().get(1).paginaParaAbrir());
    }

    @Test
    void dockerAusenteNoLinuxSoMostraOGuiaEmTexto() throws Exception {
        plataforma = new PlataformaLocal(SistemaOperacional.LINUX, "/home/ana", Map.of());
        pc.dockerInstalado = false;
        RelatorioDoComputador r = verificar();
        assertEquals(Estado.AVISO, r.de(ItemDoComputador.SISTEMA).estado());
        PlanoDeInstalacao p = r.de(ItemDoComputador.DOCKER).planos().get(0);
        assertFalse(p.executavelPeloApp());
        assertEquals("https://docs.docker.com/engine/install/", p.paginaParaAbrir());
    }

    @Test
    void dockerInstaladoEParadoOfereceAbrirOODockerEIssoNaoEInstalar() throws Exception {
        pc.dockerParado = true;
        ResultadoDoComputador d = verificar().de(ItemDoComputador.DOCKER);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertTrue(d.frase().contains("não está rodando"), d.frase());
        assertTrue(d.planos().isEmpty(), "não há o que instalar");
        sonda.caminhos.add("/Applications/Docker.app");
        ResultadoDoComputador comApp = verificar().de(ItemDoComputador.DOCKER);
        assertTrue(comApp.oferecerAbrirDocker(), "com o Docker Desktop instalado o app sabe abri-lo");
        assertTrue(comApp.frase().contains("Abrir o Docker"));
    }

    @Test
    void dockerDesktopInstaladoMasForaDoPathContaComoParado() throws Exception {
        pc.semPrograma("docker");
        sonda.caminhos.add("/Applications/Docker.app");
        ResultadoDoComputador d = verificar().de(ItemDoComputador.DOCKER);
        assertTrue(d.oferecerAbrirDocker());
        assertTrue(d.planos().isEmpty());
    }

    @Test
    void semPermissaoNoLinuxDizOComandoDoGrupoSemElevarPrivilegio() throws Exception {
        plataforma = new PlataformaLocal(SistemaOperacional.LINUX, "/home/ana", Map.of());
        pc.falharEm(c -> c.equals(ComandosLocais.versaoDoDocker()), 1, "permission denied while trying to connect to the Docker daemon socket");
        ResultadoDoComputador d = verificar().de(ItemDoComputador.DOCKER);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertEquals("sudo usermod -aG docker ana", d.comandoSugerido());
    }

    @Test
    void poucoDiscoBloqueiaNoLimiteDeCincoGigas() throws Exception {
        sonda.bytesLivres = 5L * 1024 * 1024 * 1024 - 1;
        ResultadoDoComputador d = verificar().de(ItemDoComputador.DISCO);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertTrue(d.frase().contains("5 GB"), d.frase());
        sonda.bytesLivres = 5L * 1024 * 1024 * 1024;
        assertEquals(Estado.OK, verificar().de(ItemDoComputador.DISCO).estado());
        sonda.bytesLivres = -1;
        assertEquals(Estado.AVISO, verificar().de(ItemDoComputador.DISCO).estado(), "não saber o espaço não impede");
    }

    @Test
    void poucaMemoriaBloqueiaNoLimiteDeQuatroGigas() throws Exception {
        sonda.memoriaTotal = 3L * 1024 * 1024 * 1024;
        assertEquals(Estado.PROBLEMA, verificar().de(ItemDoComputador.MEMORIA).estado());
        sonda.memoriaTotal = 4L * 1024 * 1024 * 1024;
        assertEquals(Estado.OK, verificar().de(ItemDoComputador.MEMORIA).estado());
    }

    @Test
    void arquiteturaQueOAgenteNaoSuportaBloqueia() throws Exception {
        sonda.arquitetura = "amd64";
        assertEquals(Estado.OK, verificar().de(ItemDoComputador.ARQUITETURA).estado());
        sonda.arquitetura = "riscv64";
        ResultadoDoComputador d = verificar().de(ItemDoComputador.ARQUITETURA);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertTrue(d.frase().contains("riscv64"));
    }

    @Test
    void portaOcupadaBloqueiaESugereUmaLivreEExplicaOAirPlayNoMac() throws Exception {
        sonda.portasOcupadas.add(7000);
        plataforma = new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of());
        RelatorioDoComputador r = new VerificacoesDoComputador(pc, sonda, plataforma).executar(plataforma.pastaDoLaboratorio(), 7000);
        ResultadoDoComputador d = r.de(ItemDoComputador.PORTA);
        assertEquals(Estado.PROBLEMA, d.estado());
        assertTrue(d.frase().contains("AirPlay"), d.frase());
        assertEquals(7001, r.portaLivreSugerida());
        assertTrue(d.frase().contains("7001"), d.frase());
        assertTrue(r.bloqueado());
    }

    @Test
    void semNenhumaPortaLivreAchadaPedeOutraPorta() throws Exception {
        for (int p = 7070; p < 7070 + VerificacoesDoComputador.TENTATIVAS_DE_PORTA; p++) {
            sonda.portasOcupadas.add(p);
        }
        RelatorioDoComputador r = verificar();
        assertEquals(0, r.portaLivreSugerida());
        assertTrue(r.de(ItemDoComputador.PORTA).frase().contains("Escolha outra porta"));
    }

    @Test
    void windowsSemWslBloqueiaEMostraOComandoQueExigeAdministradorENuncaORoda() throws Exception {
        plataforma = new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of());
        sonda.arquitetura = "amd64";
        pc.wslInstalado = false;
        RelatorioDoComputador r = verificar();
        ResultadoDoComputador v = r.de(ItemDoComputador.VIRTUALIZACAO);
        assertEquals(Estado.PROBLEMA, v.estado());
        assertEquals("wsl --install", v.comandoSugerido());
        PlanoDeInstalacao p = v.planos().get(0);
        assertFalse(p.executavelPeloApp(), "o app NÃO eleva privilégio nem reinicia: só mostra o comando e espera");
        assertTrue(p.precisaReiniciar());
        assertTrue(p.passosManuais().stream().anyMatch(s -> s.contains("administrador")));
        assertTrue(pc.escritas().isEmpty());
    }

    @Test
    void windowsComWslFuncionandoNaoAcusaVirtualizacaoDesligadaMesmoSeOWindowsDizFalse() throws Exception {
        plataforma = new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of());
        pc.virtualizacaoLigada = false; // com o Hyper-V ou o WSL 2 de pé o Windows diz False: um falso "desligada"
        ResultadoDoComputador v = verificar().de(ItemDoComputador.VIRTUALIZACAO);
        assertEquals(Estado.OK, v.estado(), v.frase());
        assertTrue(pc.comandos.stream().noneMatch(c -> c.get(0).equals("powershell")), "com o WSL respondendo nem olha a BIOS");
        pc.virtualizacaoLigada = true;
        assertEquals(Estado.OK, verificar().de(ItemDoComputador.VIRTUALIZACAO).estado());
    }

    @Test
    void windowsSemWslEComABiosDesligadaAContaVemComoDicaSemMudarOQueFazer() throws Exception {
        plataforma = new PlataformaLocal(SistemaOperacional.WINDOWS, "C:\\Users\\ana", Map.of());
        pc.wslInstalado = false;
        pc.virtualizacaoLigada = false;
        ResultadoDoComputador v = verificar().de(ItemDoComputador.VIRTUALIZACAO);
        assertEquals(Estado.PROBLEMA, v.estado());
        assertEquals("wsl --install", v.comandoSugerido());
        assertTrue(v.frase().contains("BIOS"), v.frase());
        pc.virtualizacaoLigada = true;
        assertFalse(verificar().de(ItemDoComputador.VIRTUALIZACAO).frase().contains("BIOS"));
    }

    @Test
    void gitAusenteEAvisoComOPlanoDoXcodeSemHomebrewENaoImpede() throws Exception {
        pc.gitInstalado = false;
        sonda.programas.clear();
        RelatorioDoComputador r = verificar();
        ResultadoDoComputador g = r.de(ItemDoComputador.GIT);
        assertEquals(Estado.AVISO, g.estado());
        assertFalse(r.bloqueado(), "o git só é preciso para importar projetos");
        assertEquals(List.of(List.of("xcode-select", "--install")), g.planos().get(0).comandos());
        sonda.programas.add("brew");
        List<PlanoDeInstalacao> comBrew = verificar().de(ItemDoComputador.GIT).planos();
        assertEquals(List.of(List.of("brew", "install", "git")), comBrew.get(0).comandos());
    }

    @Test
    void noMacSemAsFerramentasDaAppleAConferenciaNuncaRodaOAtalhoDoGitQueAbreOAvisoDoSistema() throws Exception {
        pc.gitSoOAtalhoDoMac = true; // só existe /usr/bin/git, o atalho
        sonda.programas.remove("git"); // o localizador nunca devolve o atalho
        RelatorioDoComputador r = verificar();
        assertFalse(pc.avisoDoMacDisparado, "rodar git --version aqui abriria o aviso de instalação do macOS");
        assertTrue(pc.comandos.stream().noneMatch(c -> c.get(0).equals("git")), "nem tentou: " + pc.comandos);
        ResultadoDoComputador g = r.de(ItemDoComputador.GIT);
        assertEquals(Estado.AVISO, g.estado());
        assertFalse(r.bloqueado());
        assertEquals(List.of(List.of("xcode-select", "--install")), g.planos().stream().filter(pl -> pl.id().equals("git-xcode")).findFirst().orElseThrow().comandos());
    }

    @Test
    void noMacComGitDeVerdadeOHomebrewOuAsFerramentasDaAppleAConferenciaRodaOGit() throws Exception {
        sonda.programas.add("git"); // achado em /opt/homebrew/bin ou nas ferramentas da Apple
        assertEquals(Estado.OK, verificar().de(ItemDoComputador.GIT).estado());
        assertTrue(pc.comandos.stream().anyMatch(c -> c.equals(List.of("git", "--version"))));
        assertFalse(pc.avisoDoMacDisparado);
    }

    @Test
    void oLocalizadorDoMacNuncaDevolveOAtalhoDeUsrBinEAchaOGitDeVerdade() {
        java.util.Set<String> existentes = java.util.Set.of("/usr/bin/git", "/usr/bin/python3", "/usr/bin/open");
        LocalizadorDeProgramas soAtalho = new LocalizadorDeProgramas(SistemaOperacional.MAC, List.of("/usr/bin", "/opt/homebrew/bin"),
            p -> existentes.contains(p.toString()));
        assertTrue(soAtalho.localizar("git").isEmpty(), "o atalho da Apple não conta como git instalado");
        assertTrue(soAtalho.localizar("python3").isEmpty());
        assertEquals("/usr/bin/open", soAtalho.localizar("open").orElseThrow().toString(), "o resto de /usr/bin continua valendo");
        java.util.Set<String> comBrew = java.util.Set.of("/usr/bin/git", "/opt/homebrew/bin/git");
        LocalizadorDeProgramas l = new LocalizadorDeProgramas(SistemaOperacional.MAC, List.of("/usr/bin", "/opt/homebrew/bin"), p -> comBrew.contains(p.toString()));
        assertEquals("/opt/homebrew/bin/git", l.localizar("git").orElseThrow().toString());
        java.util.Set<String> clt = java.util.Set.of("/usr/bin/git", "/Library/Developer/CommandLineTools/usr/bin/git");
        assertEquals("/Library/Developer/CommandLineTools/usr/bin/git", new LocalizadorDeProgramas(SistemaOperacional.MAC,
            new PlataformaLocal(SistemaOperacional.MAC, "/Users/ana", Map.of()).diretoriosDeProgramas(), p -> clt.contains(p.toString())).localizar("git").orElseThrow().toString());
        // no Linux e no Windows /usr/bin/git é o git de verdade
        assertEquals("/usr/bin/git", new LocalizadorDeProgramas(SistemaOperacional.LINUX, List.of("/usr/bin"), p -> p.toString().equals("/usr/bin/git"))
            .localizar("git").orElseThrow().toString());
    }

    private ComputadorSimulado.Cont laboratorioDoAppRodandoNa(int porta) {
        ComputadorSimulado.Cont c = new ComputadorSimulado.Cont();
        c.rotulo = "0.1.0-abc1234";
        c.rotuloLocal = "1";
        c.portaPublicada = porta;
        pc.containers.put("painel-lab", c);
        sonda.portasOcupadas.add(porta); // quem atende é o container do próprio app
        return c;
    }

    @Test
    void comOLaboratorioDoAppJaRodandoAPortaEDeleENaoDeOutroProgramaEPodeCriarDeNovo() throws Exception {
        laboratorioDoAppRodandoNa(7070);
        RelatorioDoComputador r = verificar();
        assertEquals(Estado.OK, r.de(ItemDoComputador.PORTA).estado(), r.de(ItemDoComputador.PORTA).frase());
        assertTrue(r.de(ItemDoComputador.PORTA).frase().contains("seu laboratório local, que já está rodando"));
        assertTrue(r.laboratorio().existe() && r.laboratorio().rodando());
        assertEquals(7070, r.laboratorio().portaPublicada());
        assertEquals("0.1.0-abc1234", r.laboratorio().versao());
        assertEquals(7070, r.portaLivreSugerida());
        assertTrue(r.pronto(), "dá para rodar o Criar de novo: " + r.problemas());
        assertTrue(pc.escritas().isEmpty());
    }

    @Test
    void aPortaOcupadaPorOutroProgramaOuPorUmPainelQueNaoEDoAppContinuaSendoProblema() throws Exception {
        sonda.portasOcupadas.add(7070);
        assertEquals(Estado.PROBLEMA, verificar().de(ItemDoComputador.PORTA).estado(), "outro programa");
        ComputadorSimulado.Cont c = laboratorioDoAppRodandoNa(7070);
        c.rotuloLocal = ""; // instalado por SSH: não é deste laboratório
        RelatorioDoComputador r = verificar();
        assertEquals(Estado.PROBLEMA, r.de(ItemDoComputador.PORTA).estado());
        assertFalse(r.laboratorio().existe());
        ComputadorSimulado.Cont d = laboratorioDoAppRodandoNa(7071);
        pc.containers.put("painel-lab", d); // do app, mas na 7071: a 7070 é de outro programa
        d.rotuloLocal = "1";
        assertEquals(Estado.PROBLEMA, verificar().de(ItemDoComputador.PORTA).estado());
    }

    @Test
    void composeAusenteEAvisoEDependeDoDocker() throws Exception {
        pc.composeInstalado = false;
        assertEquals(Estado.AVISO, verificar().de(ItemDoComputador.COMPOSE).estado());
        pc.dockerParado = true;
        ResultadoDoComputador c = verificar().de(ItemDoComputador.COMPOSE);
        assertEquals(Estado.AVISO, c.estado());
        assertTrue(c.frase().contains("depende do Docker"));
        assertNull(c.comandoSugerido());
    }

    @Test
    void umaLeituraDaJvmQueFaltaNoAppEmpacotadoNaoDerrubaAConferencia() {
        assertEquals(-1, SondaReal.lerSemCair(() -> {
            throw new NoClassDefFoundError("com/sun/management/OperatingSystemMXBean");
        }), "NoClassDefFoundError é LinkageError, não Exception");
        assertEquals(-1, SondaReal.lerSemCair(() -> {
            throw new UnsatisfiedLinkError("sem o módulo");
        }));
        assertEquals(-1, SondaReal.lerSemCair(() -> {
            throw new IllegalStateException("x");
        }));
        assertEquals(42, SondaReal.lerSemCair(() -> 42));
        assertTrue(new SondaReal(new LocalizadorDeProgramas(SistemaOperacional.MAC, List.of(), p -> false)).memoriaTotalBytes() > 0,
            "na JVM de verdade a memória é lida");
    }

    @Test
    void noWindowsOAtalhoDoWingetEOutrosAliasesContamPelaExistenciaENaoPorSerArquivoComum(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path alias = tmp.resolve("winget.exe");
        java.nio.file.Files.writeString(alias, "x");
        assertTrue(LocalizadorDeProgramas.ehProgramaDeVerdade(SistemaOperacional.WINDOWS, alias));
        assertFalse(LocalizadorDeProgramas.ehProgramaDeVerdade(SistemaOperacional.WINDOWS, tmp), "uma pasta nunca é programa");
        assertFalse(LocalizadorDeProgramas.ehProgramaDeVerdade(SistemaOperacional.WINDOWS, tmp.resolve("nao-existe.exe")));
        assertFalse(LocalizadorDeProgramas.ehProgramaDeVerdade(SistemaOperacional.MAC, alias), "no Mac e no Linux precisa ser executável");
        assertTrue(alias.toFile().setExecutable(true));
        assertTrue(LocalizadorDeProgramas.ehProgramaDeVerdade(SistemaOperacional.MAC, alias));
        LocalizadorDeProgramas l = new LocalizadorDeProgramas(SistemaOperacional.WINDOWS, List.of(tmp.toString()), p -> LocalizadorDeProgramas.ehProgramaDeVerdade(SistemaOperacional.WINDOWS, p));
        assertEquals(alias, l.localizar("winget").orElseThrow());
    }
}
