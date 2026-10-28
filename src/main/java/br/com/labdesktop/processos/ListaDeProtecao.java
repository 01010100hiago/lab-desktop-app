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
            String c = alvo.container().toLowerCase(Locale.ROOT);
            if (ambiente.containersProtegidos().contains(c)) {
                return Optional.of("Este é o agente do painel (container " + alvo.container() + "): é por ele que o app fala com o servidor. "
                    + "Para mexer nele, use Adicionar servidor ou o Agente, que sabem atualizar sem perder a conexão.");
            }
            return Optional.empty();
        }
        String nome = alvo.nomeCurto().toLowerCase(Locale.ROOT);
        if (nome.isBlank()) {
            return Optional.of("A lista não trouxe o nome do programa, então o app não consegue conferir que é o processo certo.");
        }
        if (nome.startsWith("sshd")) {
            return Optional.of("Este é o servidor SSH (sshd). O app não sabe qual dos sshd é o da sua própria conexão, e encerrar o errado tira o seu acesso ao servidor.");
        }
        for (String p : DOCKER_PREFIXO) {
            if (nome.startsWith(p)) {
                return Optional.of("Este é o motor do Docker (" + alvo.nomeCurto() + "): parar ele derruba todos os containers e projetos. Pare os containers um a um, pela lista de containers.");
            }
        }
        if (SISTEMA_EXATO.contains(nome)) {
            return Optional.of("Este é um processo do sistema (" + alvo.nomeCurto() + "): o computador depende dele e o app não o encerra.");
        }
        for (String p : SISTEMA_PREFIXO) {
            if (nome.startsWith(p)) {
                return Optional.of("Este é um processo do sistema (" + alvo.nomeCurto() + "): o computador depende dele e o app não o encerra.");
            }
        }
        return Optional.empty();
    }
}
