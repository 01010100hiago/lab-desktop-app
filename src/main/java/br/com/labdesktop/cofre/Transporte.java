package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.ValidadorDeTransporte;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Como o app chega ao servidor (ARQUITETURA 5.3 e 7.14).
 * <ul>
 *   <li>{@link Tipo#DIRETO}: o SSH na rede, no endereço e na porta do servidor (o padrão, e o que todo cofre de antes tem);</li>
 *   <li>{@link Tipo#SSM}: SEM PORTA ABERTA, pelo AWS Session Manager (instância, região, perfil opcional e a porta do SSH nela);</li>
 *   <li>{@link Tipo#CLOUDFLARED}: SEM PORTA ABERTA, pelo túnel da Cloudflare ({@code cloudflared access tcp}, no hostname do túnel);</li>
 *   <li>{@link Tipo#LOCAL}: "Este computador", sem SSH, sem chave e sem túnel.</li>
 * </ul>
 * Os dois modos sem porta aberta valem só quando o usuário os escolhe; nada é migrado. Cada campo passa por {@link ValidadorDeTransporte} (lista branca fechada) já no
 * construtor: o que chega aqui não tem espaço, aspas, {@code ;}, {@code $}, crase, quebra de linha, unicode nem hífen na frente, e vai para o programa externo como argumento
 * separado de uma lista, nunca por um shell.
 *
 * @param tipo                direto, ssm, cloudflared ou local
 * @param hostname            só para cloudflared: o nome do túnel (em minúsculas)
 * @param instanciaId         só para ssm: {@code i-...} ou {@code mi-...}
 * @param regiao              só para ssm: {@code us-east-1}, ou {@code null} para usar a do perfil da AWS
 * @param perfilAws           só para ssm: o perfil da AWS, ou {@code null} para o padrão
 * @param portaSshNaInstancia só para ssm: a porta do SSH DENTRO da instância (22); 0 nos outros
 */
public record Transporte(Tipo tipo, String hostname, String instanciaId, String regiao, String perfilAws, int portaSshNaInstancia) {

    /** Os transportes conhecidos. */
    public enum Tipo { DIRETO, CLOUDFLARED, LOCAL, SSM }

    public static final Transporte DIRETO = new Transporte(Tipo.DIRETO, null);
    /** "Este computador": sem SSH. */
    public static final Transporte LOCAL = new Transporte(Tipo.LOCAL, null);

    public Transporte {
        if (tipo == null) {
            throw new IllegalArgumentException("Tipo de transporte ausente.");
        }
        switch (tipo) {
            case DIRETO, LOCAL -> {
                hostname = null;
                instanciaId = null;
                regiao = null;
                perfilAws = null;
                portaSshNaInstancia = 0;
            }
            case CLOUDFLARED -> {
                hostname = ValidadorDeTransporte.hostnameNormalizado(hostname)
                    .orElseThrow(() -> new IllegalArgumentException("O transporte cloudflared pede um hostname válido (como ssh.example.com)."));
                instanciaId = null;
                regiao = null;
                perfilAws = null;
                portaSshNaInstancia = 0;
            }
            case SSM -> {
                if (!ValidadorDeTransporte.instanciaValida(instanciaId)) {
                    throw new IllegalArgumentException("ID de instância inválido para o AWS Session Manager.");
                }
                regiao = regiao == null || regiao.isEmpty() ? null : regiao; // a região é opcional: sem ela o aws usa a do perfil ou do ambiente
                if (!ValidadorDeTransporte.regiaoOpcionalValida(regiao)) {
                    throw new IllegalArgumentException("Região da AWS inválida.");
                }
                perfilAws = perfilAws == null || perfilAws.isEmpty() ? null : perfilAws;
                if (!ValidadorDeTransporte.perfilValido(perfilAws)) {
                    throw new IllegalArgumentException("Perfil da AWS inválido.");
                }
                if (!ValidadorDeTransporte.portaValida(portaSshNaInstancia)) {
                    throw new IllegalArgumentException("Porta do SSH na instância fora de 1 a 65535.");
                }
                hostname = null;
            }
        }
    }

    /** Só tipo e hostname (o formato antigo): vale para direto, local e cloudflared. */
    public Transporte(Tipo tipo, String hostname) {
        this(tipo, hostname, null, null, null, 0);
    }

    public static Transporte cloudflared(String hostname) {
        return new Transporte(Tipo.CLOUDFLARED, hostname);
    }

    /** SSM: a instância, a região, o perfil da AWS (pode ser {@code null}) e a porta do SSH nela. */
    public static Transporte ssm(String instanciaId, String regiao, String perfilAws, int portaSshNaInstancia) {
        return new Transporte(Tipo.SSM, null, instanciaId, regiao, perfilAws, portaSshNaInstancia);
    }

    /** Sem porta aberta: o SSH chega por um programa externo (um túnel local), e não pelo endereço e pela porta do servidor. */
    public boolean ehTunel() {
        return tipo == Tipo.SSM || tipo == Tipo.CLOUDFLARED;
    }

    /**
     * A MESMA máquina de destino (a identidade que os pins dizem confirmar)? Mesmo tipo e mesmos dados de endereçamento. O perfil da AWS não conta: é só quem assina o
     * pedido, e a instância continua a mesma. Direto não entra aqui (o endereço e a porta de um servidor direto moram em {@link ConfigServidor}).
     */
    public boolean mesmoDestinoQue(Transporte outro) {
        if (outro == null || tipo != outro.tipo) {
            return false;
        }
        return switch (tipo) {
            case SSM -> instanciaId.equals(outro.instanciaId) && Objects.equals(regiao, outro.regiao) && portaSshNaInstancia == outro.portaSshNaInstancia;
            case CLOUDFLARED -> hostname.equals(outro.hostname);
            case DIRETO, LOCAL -> true;
        };
    }

    /** O nome do modo, como o usuário lê. */
    public String rotulo() {
        return switch (tipo) {
            case DIRETO -> "Direto (SSH)";
            case SSM -> "AWS Session Manager (SSM)";
            case CLOUDFLARED -> "Cloudflare";
            case LOCAL -> "Este computador";
        };
    }

    /** O selo do cartão e da página; vazio nos modos que não têm selo (direto e local). */
    public Optional<String> selo() {
        return switch (tipo) {
            case SSM -> Optional.of("Sem porta aberta: SSM");
            case CLOUDFLARED -> Optional.of("Sem porta aberta: Cloudflare");
            case DIRETO, LOCAL -> Optional.empty();
        };
    }

    /** Onde o servidor está, numa linha (sem usuário): a instância e a região, ou o hostname. Vazio no direto e no local. */
    public String destinoEmTexto() {
        return switch (tipo) {
            case SSM -> instanciaId + (regiao == null && perfilAws == null ? ""
                : " (" + (regiao == null ? "" : regiao) + (regiao != null && perfilAws != null ? ", " : "") + (perfilAws == null ? "" : "perfil " + perfilAws) + ")");
            case CLOUDFLARED -> hostname;
            case DIRETO, LOCAL -> "";
        };
    }

    @Override
    public String toString() {
        return "Transporte[" + tipo.name().toLowerCase(Locale.ROOT) + (ehTunel() ? " " + destinoEmTexto() : "") + "]";
    }
}
