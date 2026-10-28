package br.com.labdesktop.local;

import java.util.ArrayList;
import java.util.List;

/**
 * Os planos de instalação do que pode faltar (Docker, git, WSL 2), por sistema e pelo que o computador já tem (Homebrew, winget). Só monta
 * textos e listas de argumentos: NADA roda aqui. O usuário vê cada plano com os comandos exatos e só então o app o executa.
 *
 * <p><b>Mac: Docker Desktop (recomendado).</b> É o jeito que o próprio Docker oferece, com janela, atualização e o caminho de arquivos que o
 * app já conferiu (um container enxerga as pastas do usuário no mesmo caminho). O Colima é mais leve (sem janela, sem licença corporativa), mas
 * exige Homebrew e terminal para ligar e desligar, e o compartilhamento de pastas dele não foi conferido: fica como alternativa, nunca como
 * padrão. A cask do Homebrew se chama {@code docker-desktop} (o nome {@code docker} é só um apelido antigo).
 */
public final class PlanosDeInstalacao {

    private PlanosDeInstalacao() { }

    /** Os planos para instalar o Docker, o recomendado primeiro. */
    public static List<PlanoDeInstalacao> docker(SistemaOperacional sistema, boolean temHomebrew, boolean temWinget) {
        List<PlanoDeInstalacao> l = new ArrayList<>();
        switch (sistema) {
            case MAC -> {
                if (temHomebrew) {
                    l.add(new PlanoDeInstalacao("docker-brew", "Instalar o Docker Desktop",
                        "O app roda o Homebrew para instalar o Docker Desktop (cerca de 2 GB; pode levar alguns minutos).",
                        List.of(List.of("brew", "install", "--cask", "docker-desktop")), null,
                        List.of("Quando terminar, abra o Docker Desktop (o app ajuda a abri-lo) e aceite os termos de uso dele na primeira vez."),
                        null, true, false, true));
                    l.add(new PlanoDeInstalacao("docker-colima", "Alternativa leve: instalar o Colima",
                        "O app roda o Homebrew para instalar o Colima e o cliente do Docker, sem janela e sem o Docker Desktop. Exige ligar o Colima "
                            + "(colima start) e o compartilhamento de pastas dele não foi conferido pelo app.",
                        List.of(List.of("brew", "install", "colima", "docker")), null,
                        List.of("Depois de instalar, o app liga o Colima para você (colima start)."), null, false, false, false));
                } else {
                    l.add(paginaDoDockerDesktop("Baixar o Docker Desktop para Mac",
                        List.of("Escolha a versão certa para o seu Mac (Apple Silicon ou Intel).", "Abra o arquivo .dmg baixado e arraste o Docker para a pasta Aplicativos.",
                            "Abra o Docker e aceite os termos de uso. O sistema pode pedir a sua senha de administrador: isso é do sistema.",
                            "Volte aqui e clique em Verificar de novo.")));
                }
            }
            case WINDOWS -> {
                if (temWinget) {
                    l.add(new PlanoDeInstalacao("docker-winget", "Instalar o Docker Desktop",
                        "O app roda o winget (instalador de programas do Windows) para instalar o Docker Desktop. Ao clicar em Instalar você aceita "
                            + "os termos do Docker Desktop (docker.com/legal) e da fonte do winget. O Windows pode pedir permissão de administrador e "
                            + "reiniciar o computador.",
                        List.of(List.of("winget", "install", "-e", "--id", "Docker.DockerDesktop", "--accept-source-agreements",
                            "--accept-package-agreements")), null,
                        List.of("Se o Windows pedir para reiniciar, reinicie e abra o Docker Desktop. Volte aqui e clique em Verificar de novo."),
                        null, true, true, true));
                }
                l.add(paginaDoDockerDesktop(temWinget ? "Ou baixar o instalador no site do Docker" : "Baixar o Docker Desktop para Windows",
                    List.of("Baixe e rode o instalador (Docker Desktop Installer.exe). O Windows pode pedir permissão de administrador.",
                        "Deixe marcada a opção de usar o WSL 2. Se o Windows pedir para reiniciar, reinicie.",
                        "Abra o Docker Desktop, aceite os termos e volte aqui para clicar em Verificar de novo.")));
            }
            case LINUX -> l.add(new PlanoDeInstalacao("docker-linux", "Ver o guia oficial do Docker",
                "No Linux o app só mostra a orientação: instalar o Docker Engine pede administrador.", List.of(), PlataformaLocal.URL_DO_DOCKER_NO_LINUX,
                List.of("Siga o guia da sua distribuição (Debian e Ubuntu têm um passo a passo com apt).",
                    "Depois ponha seu usuário no grupo docker (sudo usermod -aG docker $USER), saia e entre de novo no sistema.",
                    "Volte aqui e clique em Verificar de novo."), null, false, false, true));
        }
        return l;
    }

    private static PlanoDeInstalacao paginaDoDockerDesktop(String titulo, List<String> passos) {
        return new PlanoDeInstalacao("docker-pagina", titulo, "O app abre a página oficial de download no navegador e explica os passos. Nada é "
            + "baixado nem instalado por ele.", List.of(), PlataformaLocal.URL_DO_DOCKER_DESKTOP, passos, null, false, false, true);
    }

    /** O WSL 2 do Windows: instalar pede administrador e reiniciar, então o app NUNCA o faz: mostra o comando e espera. */
    public static PlanoDeInstalacao wsl() {
        return new PlanoDeInstalacao("wsl", "Instalar o WSL 2 (comando para você rodar)",
            "O WSL 2 é o que o Docker Desktop usa no Windows. Instalar exige administrador e reiniciar o computador, então o app não o faz por "
                + "você.", List.of(), null,
            List.of("Abra o PowerShell como administrador (botão direito no menu Iniciar, Terminal ou PowerShell (Administrador)).",
                "Rode o comando abaixo e reinicie o computador quando ele pedir.", "Depois do reinício, volte aqui e clique em Verificar de novo."),
            "wsl --install", false, true, true);
    }

    /** O git. */
    public static List<PlanoDeInstalacao> git(SistemaOperacional sistema, boolean temHomebrew, boolean temWinget) {
        List<PlanoDeInstalacao> l = new ArrayList<>();
        switch (sistema) {
            case MAC -> {
                if (temHomebrew) {
                    l.add(new PlanoDeInstalacao("git-brew", "Instalar o git", "O app roda o Homebrew para instalar o git.",
                        List.of(List.of("brew", "install", "git")), null, List.of(), null, false, false, true));
                }
                l.add(new PlanoDeInstalacao("git-xcode", "Instalar as ferramentas de linha de comando da Apple (git)",
                    "O app pede ao macOS as ferramentas de linha de comando da Apple, que trazem o git. Abre uma janela do sistema que pede a sua "
                        + "confirmação.", List.of(List.of("xcode-select", "--install")), null,
                    List.of("Confirme na janela do sistema e espere terminar. Depois clique em Verificar de novo."), null, true, false, !temHomebrew));
            }
            case WINDOWS -> {
                if (temWinget) {
                    l.add(new PlanoDeInstalacao("git-winget", "Instalar o git",
                        "O app roda o winget para instalar o Git para Windows. Ao clicar em Instalar você aceita os termos dele e da fonte do winget.",
                        List.of(List.of("winget", "install", "-e", "--id", "Git.Git", "--accept-source-agreements", "--accept-package-agreements")),
                        null, List.of("Depois de instalar, feche e abra o app de novo para o git entrar no PATH."), null, true, false, true));
                }
                l.add(new PlanoDeInstalacao("git-pagina", "Baixar o Git para Windows", "O app abre a página oficial do git no navegador.", List.of(),
                    PlataformaLocal.URL_DO_GIT, List.of("Baixe e rode o instalador. Depois clique em Verificar de novo."), null, false, false, !temWinget));
            }
            case LINUX -> l.add(new PlanoDeInstalacao("git-linux", "Instalar o git", "No Linux o app só mostra o comando.", List.of(), null,
                List.of("Instale o git pelo gerenciador de pacotes da sua distribuição."), "sudo apt install git", false, false, true));
        }
        return l;
    }
}
