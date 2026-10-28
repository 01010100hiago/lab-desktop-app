package br.com.labdesktop.processos;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A lista do que o app NUNCA oferece para encerrar ou reiniciar, e por quê. Vale mesmo que o usuário seja o dono (o {@code root} é dono de tudo) e
 * mesmo que o usuário insista: a tela mostra o motivo e não deixa nem abrir a confirmação. Documentada em ARQUITETURA.
 *
 * <p>O que está nela, e a razão:
 * <ol>
 *   <li><b>PID 1</b> (e 0) e número inválido: o primeiro processo do sistema.</li>
 *   <li><b>O próprio app</b> (Este computador): o processo do app e os que o abriram.</li>
 *   <li><b>O sshd</b> (qualquer processo cujo nome começa com {@code sshd}): o app não sabe qual deles é o da própria sessão, e derrubar o
 *       errado tira o acesso ao servidor.</li>
 *   <li><b>O agente do painel</b>: o container {@code painel-lab} (e o que o app instalou com outro nome): é por ele que o app fala com o servidor.</li>
 *   <li><b>O motor do Docker</b>: {@code dockerd}, {@code containerd} e seus filhos, {@code runc}, {@code docker-proxy}, e o Docker Desktop.</li>
 *   <li><b>Processos do sistema</b>: o systemd e seus serviços, as threads do núcleo, o {@code launchd} e as peças do macOS, o {@code services.exe},
 *       {@code lsass.exe}, {@code svchost.exe} e as peças do Windows, rede, relógio, log e login.</li>
 * </ol>
 * Só vale para processo FORA de container; o de container é tratado pelo container (e este só é protegido se for o do agente).
 */
public final class ListaDeProtecao {

    private ListaDeProtecao() { }

    private static final Set<String> SISTEMA_EXATO = Set.of("init", "systemd", "kthreadd", "dbus-daemon", "dbus-broker", "dbus-broker-launch", "login",
        "agetty", "getty", "mingetty", "networkmanager", "networkd-dispatcher", "wpa_supplicant", "polkitd", "rsyslogd", "syslogd", "cron", "crond",
        "atd", "udevd", "chronyd", "ntpd", "irqbalance", "udisksd", "firewalld", "ufw", "tailscaled", "cloudflared", "iptables", "nftables", "auditd",
        "launchd", "kernel_task", "windowserver", "loginwindow", "dock", "systemuiserver", "finder", "mds", "mds_stores", "mdworker", "coreaudiod", "cfprefsd",
        "distnoted", "securityd", "opendirectoryd", "configd", "userventagent", "launchservicesd", "fseventsd", "diskarbitrationd", "notifyd",
        "powerd", "logd", "bluetoothd", "airportd", "controlcenter", "tccd", "trustd", "amfid", "sandboxd", "syspolicyd", "kextd", "hidd", "locationd",
        "system", "system idle process", "registry", "memory compression", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe",
        "lsass.exe", "lsm.exe", "svchost.exe", "explorer.exe", "dwm.exe", "fontdrvhost.exe", "sihost.exe", "taskhostw.exe", "ctfmon.exe", "spoolsv.exe",
        "wmiprvse.exe", "audiodg.exe", "runtimebroker.exe", "searchhost.exe", "startmenuexperiencehost.exe", "shellexperiencehost.exe", "msmpeng.exe",
        "securityhealthservice.exe", "wslservice.exe", "wslhost.exe", "vmmem", "vmmemwsl", "vmcompute.exe", "vmwp.exe");

    private static final List<String> SISTEMA_PREFIXO = List.of("systemd", "kworker", "ksoftirqd", "rcu_", "migration", "watchdog", "cpuhp", "kswapd",
        "jbd2", "ext4-", "kcompactd", "khugepaged", "kblockd", "kdevtmpfs", "netns", "oom_reaper", "writeback", "idle_inject", "irq/", "scsi_", "xfs-",
        "networkmanager", "com.apple.", "launchd");

    private static final List<String> DOCKER_PREFIXO = List.of("dockerd", "containerd", "docker-proxy", "docker-init", "runc", "com.docker.", "docker desktop",
        "vpnkit", "colima", "limactl", "docker-desktop");

    /** Programas que servem a própria conexão ou abrem a sessão: o app não sabe qual deles é o da sessão dele. */
    private static final List<String> SESSAO_PREFIXO = List.of("sshd", "sftp-server", "internal-sftp", "scp", "ssh-agent", "gpg-agent");
    private static final Set<String> SHELLS = Set.of("bash", "sh", "dash", "zsh", "fish", "ksh", "csh", "tcsh", "ash", "busybox", "login-shell");

    /** O {@code comm} do Linux tem no máximo 15 letras: um nome da lista, de 15 letras, pode ser só o começo de um nome maior. */
    private static final int COMM_DO_LINUX = 15;

    /**
     * Pedaços de nome de container que o app protege SEM o usuário marcar: o que carrega o tráfego, o túnel e o próprio Docker. Parar um destes derruba o acesso a
     * todos os outros projetos (e, às vezes, ao próprio servidor).
     */
    private static final List<String> CONTAINER_PADROES = List.of("cloudflared", "traefik", "caddy", "nginx-proxy", "proxy", "dockerd", "docker-proxy", "docker-in-docker",
        "painel-lab", "painel-agent", "lab-agent");

    /**
     * Por que este processo está protegido, ou vazio se pode ser oferecido. O motivo é uma frase que a tela mostra como está.
     */
    public static Optional<String> motivo(ProcessoAlvo alvo, AmbienteDeProcessos ambiente) {
        Integer pid = alvo.pid();
        if (pid != null && (pid == 0 || pid == 1)) {
            return Optional.of("O processo " + pid + " é o primeiro do sistema: se ele para, o computador inteiro para.");
        }
        if (!ValidadorDePid.valido(pid)) {
            return Optional.of("A lista não trouxe um número de processo que o app possa usar com segurança.");
        }
        if (ambiente.pidsDoApp().contains(pid)) {
            return Optional.of("Este é o próprio app (ou o programa que o abriu). Para fechá-lo, use o menu do app.");
        }
        if (alvo.ehDeContainer()) {
            return motivoDoContainer(alvo.container(), ambiente);
        }
        String nome = alvo.nomeCurto().toLowerCase(Locale.ROOT);
        if (nome.isBlank()) {
            return Optional.of("A lista não trouxe o nome do programa, então o app não consegue conferir que é o processo certo.");
        }
        for (String candidato : candidatos(alvo.nome())) {
            Optional<String> m = motivoDoNome(candidato, alvo.nomeCurto());
            if (m.isPresent()) {
                return m;
            }
        }
        return Optional.empty();
    }

    /** O nome como a lista o trouxe, o nome sem a pasta e o primeiro pedaço ({@code sshd: ana@pts/0} e {@code /usr/sbin/sshd -D} são do sshd), todos em minúsculas. */
    private static List<String> candidatos(String nomeBruto) {
        String bruto = nomeBruto == null ? "" : nomeBruto.strip();
        java.util.LinkedHashSet<String> c = new java.util.LinkedHashSet<>();
        c.add(ProcessoAlvo.nomeCurto(bruto).toLowerCase(Locale.ROOT));
        c.add(bruto.toLowerCase(Locale.ROOT));
        String semArgs = bruto;
        int a = semArgs.indexOf(' ');
        if (a > 0) {
            String primeiro = semArgs.substring(0, a);
            if (primeiro.endsWith(":")) {
                primeiro = primeiro.substring(0, primeiro.length() - 1);
            }
            c.add(ProcessoAlvo.nomeCurto(primeiro).toLowerCase(Locale.ROOT));
        }
        c.removeIf(String::isBlank);
        return List.copyOf(c);
    }

    private static Optional<String> motivoDoNome(String nome, String nomeParaTela) {
        for (String p : SESSAO_PREFIXO) {
            if (nome.startsWith(p)) {
                return Optional.of("Este é " + (p.equals("sshd") ? "o servidor SSH (sshd)" : "um programa da conexão SSH (" + nomeParaTela + ")")
                    + ". O app não sabe qual deles é o da sua própria conexão, e encerrar o errado tira o seu acesso ao servidor.");
            }
        }
        if (SHELLS.contains(nome) || SHELLS.contains(nome.startsWith("-") ? nome.substring(1) : nome)) {
            return Optional.of("Este é um shell (" + nomeParaTela + "): o app não sabe se é o da sua própria conexão ou do seu Terminal, e encerrá-lo derruba a sessão. "
                + "Feche-o pelo Terminal (exit).");
        }
        for (String p : DOCKER_PREFIXO) {
            if (nome.startsWith(p)) {
                return Optional.of("Este é o motor do Docker (" + nomeParaTela + "): parar ele derruba todos os containers e projetos. Pare os containers um a um, pela lista de containers.");
            }
        }
        if (naListaExata(nome)) {
            return Optional.of("Este é um processo do sistema (" + nomeParaTela + "): o computador depende dele e o app não o encerra.");
        }
        for (String p : SISTEMA_PREFIXO) {
            if (nome.startsWith(p)) {
                return Optional.of("Este é um processo do sistema (" + nomeParaTela + "): o computador depende dele e o app não o encerra.");
            }
        }
        return Optional.empty();
    }

    /** Está na lista exata? Um nome de 15 letras vale também como começo de um nome maior da lista (o {@code ps} corta o nome em 15). */
    private static boolean naListaExata(String nome) {
        if (SISTEMA_EXATO.contains(nome)) {
            return true;
        }
        if (nome.length() >= COMM_DO_LINUX) {
            for (String e : SISTEMA_EXATO) {
                if (e.length() > nome.length() && e.startsWith(nome)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Por que um CONTAINER está protegido (o do agente, o marcado pelo usuário ou um dos padrões do que carrega o tráfego), ou vazio. */
    public static Optional<String> motivoDoContainer(String container, AmbienteDeProcessos ambiente) {
        String c = container == null ? "" : container.toLowerCase(Locale.ROOT);
        if (ambiente.containersProtegidos().contains(c)) {
            if (c.equals(AmbienteDeProcessos.CONTAINER_DO_AGENTE)) {
                return Optional.of("Este é o agente do painel (container " + container + "): é por ele que o app fala com o servidor. "
                    + "Para mexer nele, use Adicionar servidor ou o Agente, que sabem atualizar sem perder a conexão.");
            }
            return Optional.of("Você marcou o container " + container + " como protegido neste servidor: o app não o para nem o reinicia. "
                + "Para liberar, tire a marca em Ajustes, Segurança.");
        }
        for (String p : CONTAINER_PADROES) {
            if (c.contains(p)) {
                return Optional.of("O container " + container + " parece ser parte do que leva o tráfego e o acesso ao servidor (túnel, proxy ou o próprio Docker): "
                    + "pará-lo derruba os outros projetos. O app não o para nem o reinicia por esta tela.");
            }
        }
        return Optional.empty();
    }

    /** Este container está protegido só pelo nome (padrão), sem o usuário ter marcado? A tela usa para dizer de quem é a regra. */
    public static boolean protegidoPorPadrao(String container) {
        String c = container == null ? "" : container.toLowerCase(Locale.ROOT);
        return CONTAINER_PADROES.stream().anyMatch(c::contains);
    }

    /**
     * Por que uma UNIDADE do systemd ({@code docker.service}) está protegida, ou vazio. A mesma lista dos programas vale para a unidade: o processo pode ser
     * filho de um serviço protegido (o {@code containerd-shim} é do {@code docker.service}) e, nesse caso, o app NÃO oferece "Reiniciar o serviço".
     */
    public static Optional<String> motivoDaUnidade(String unidade) {
        String u = unidade == null ? "" : unidade.strip().toLowerCase(Locale.ROOT);
        if (u.endsWith(".service")) {
            u = u.substring(0, u.length() - ".service".length());
        } else if (u.endsWith(".socket")) {
            u = u.substring(0, u.length() - ".socket".length());
        }
        if (u.isBlank()) {
            return Optional.of("O serviço não tem um nome que o app possa conferir.");
        }
        String semInstancia = u.contains("@") ? u.substring(0, u.indexOf('@')) : u;
        for (String n : List.of(u, semInstancia)) {
            if (motivoDoNome(n, unidade).isPresent()) {
                return Optional.of("O serviço " + unidade + " é protegido (do sistema, do Docker ou do acesso ao servidor). O app não o reinicia.");
            }
            if (n.equals("ssh") || n.equals("sshd") || n.equals("cloudflared") || n.equals("docker") || n.equals("containerd") || n.equals("dbus")
                || n.startsWith("systemd") || n.startsWith("network") || n.equals("tailscaled") || n.equals("user")) {
                return Optional.of("O serviço " + unidade + " é do sistema, do Docker ou do acesso ao servidor. O app não o reinicia.");
            }
        }
        return Optional.empty();
    }
}
