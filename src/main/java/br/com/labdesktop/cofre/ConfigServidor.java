package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Configuração de um servidor no cofre, SEM segredo (por isso é {@code record}: a chave privada fica em
 * {@link ChaveSsh}, referida por {@link #chaveId}, e o token em {@link TokenAgente}). É o que as telas e o
 * pacote {@code servidor} podem segurar e imprimir à vontade. Imutável: alterar é criar outra com os
 * métodos {@code com...} e gravar pelo cofre.
 *
 * @param id                identificador interno e estável (nunca sai do Mac): {@code s1}, {@code s3f9a1c2b}
 * @param nome              nome mostrado ao usuário ("lab")
 * @param host              endereço do SSH (IP ou nome DNS)
 * @param porta             porta do SSH (2222 no lab)
 * @param usuario           usuário do SSH
 * @param chaveId           id da {@link ChaveSsh} deste servidor (uma chave por servidor)
 * @param portaAgente       porta do agente no loopback DO SERVIDOR (destino do túnel; 7000)
 * @param pins              impressões da chave do host já confirmadas pelousuário, por algoritmo; vazio = host ainda não confiado
 * @param transporte direto (LAN) ou cloudflared
 * @param nomeDoDispositivo {@code nome_do_dispositivo} do token deste Mac no agente; {@code null} até o token ser registrado
 * @param estado2fa         última leitura do campo {@code totp} de {@code /api/versao} (dica; a fonte é o agente)
 */
public record ConfigServidor(
    String id,
    String nome,
    String host,
    int porta,
    String usuario,
    String chaveId,
    int portaAgente,
    List<Pin> pins,
    Transporte transporte,
    String nomeDoDispositivo,
    Estado2fa estado2fa) {

    public static final int PORTA_AGENTE_PADRAO = 7000;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    public ConfigServidor {
        if (!ValidadorDeNomes.idLocalValido(id)) {
            throw new IllegalArgumentException("Id de servidor inválido.");
        }
        nome = TextoSeguro.limparLinha(nome);
        if (nome.isEmpty() || nome.length() > 64) {
            throw new IllegalArgumentException("O nome do servidor deve ter de 1 a 64 caracteres.");
        }
        if (!ValidadorDeNomes.hostValido(host)) {
            throw new IllegalArgumentException("Endereço do servidor inválido.");
        }
        if (porta < 1 || porta > 65_535) {
            throw new IllegalArgumentException("Porta do SSH fora de 1 a 65535.");
        }
        if (!ValidadorDeNomes.usuarioSshValido(usuario)) {
            throw new IllegalArgumentException("Usuário do SSH inválido.");
        }
        if (!ValidadorDeNomes.idLocalValido(chaveId)) {
            throw new IllegalArgumentException("Id de chave inválido.");
        }
        if (portaAgente < 1 || portaAgente > 65_535) {
            throw new IllegalArgumentException("Porta do agente fora de 1 a 65535.");
        }
        pins = pins == null ? List.of() : List.copyOf(pins);
        transporte = transporte == null ? Transporte.DIRETO : transporte;
        if (nomeDoDispositivo != null && !ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido (a-z, 0-9 e hífen, até 40).");
        }
        estado2fa = estado2fa == null ? Estado2fa.DESLIGADO : estado2fa;
    }

    /** Servidor novo, sem pins, sem token e com o agente na porta padrão. */
    public static ConfigServidor novo(String nome, String host, int porta, String usuario, String chaveId) {
        return new ConfigServidor(novoId(), nome, host, porta, usuario, chaveId, PORTA_AGENTE_PADRAO,
            List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
    }

    /** Id novo ({@code s} mais 8 hexadecimais): não precisa ser secreto, só não colidir. */
    public static String novoId() {
        byte[] b = new byte[4];
        ALEATORIO.nextBytes(b);
        return "s" + HexFormat.of().formatHex(b);
    }

    /** O pin do algoritmo informado, se houver. */
    public Optional<Pin> pinDe(String algoritmo) {
        return pins.stream().filter(p -> p.algoritmo().equals(algoritmo)).findFirst();
    }

    /** Troca (ou acrescenta) o pin do algoritmo do pin dado. */
    public ConfigServidor comPin(Pin pin) {
        List<Pin> novos = new ArrayList<>(pins.size() + 1);
        for (Pin p : pins) {
            if (!p.algoritmo().equals(pin.algoritmo())) {
                novos.add(p);
            }
        }
        novos.add(pin);
        return comPins(novos);
    }

    public ConfigServidor comPins(List<Pin> novos) {
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, novos, transporte,
            nomeDoDispositivo, estado2fa);
    }

    public ConfigServidor semPins() {
        return comPins(List.of());
    }

    public ConfigServidor comNomeDoDispositivo(String novoNome) {
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte,
            novoNome, estado2fa);
    }

    public ConfigServidor comEstado2fa(Estado2fa novo) {
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte,
            nomeDoDispositivo, novo);
    }

    public ConfigServidor comChaveId(String novaChaveId) {
        return new ConfigServidor(id, nome, host, porta, usuario, novaChaveId, portaAgente, pins, transporte,
            nomeDoDispositivo, estado2fa);
    }

    /**
     * O que o usuário muda na tela "Editar servidor". A regra do pin mora aqui, junto do dado: mudar o ENDEREÇO ou a PORTA do
     * SSH apaga as impressões digitais guardadas, porque elas eram as daquela máquina naquela porta; outra máquina (ou
     * outro serviço na outra porta) precisa ser conferida de novo, senão o app passaria a confiar às cegas num servidor que
     * o usuário nunca conferiu. Mudar só o nome, o usuário ou a porta do agente mantém os pins (a máquina é a mesma). O
     * endereço é comparado sem diferença de maiúsculas, como o DNS faz.
     *
     * @throws IllegalArgumentException se algum campo não vale (as mesmas regras do construtor)
     */
    public ConfigServidor editar(String novoNome, String novoHost, int novaPorta, String novoUsuario, int novaPortaAgente) {
        String hostLimpo = novoHost == null ? null : novoHost.strip();
        String usuarioLimpo = novoUsuario == null ? null : novoUsuario.strip();
        boolean mudouOEndereco = hostLimpo == null || !host.equalsIgnoreCase(hostLimpo) || porta != novaPorta;
        return new ConfigServidor(id, novoNome, hostLimpo, novaPorta, usuarioLimpo, chaveId, novaPortaAgente,
            mudouOEndereco ? List.of() : pins, transporte, nomeDoDispositivo, estado2fa);
    }

    /** {@code true} se {@code outra} aponta para o mesmo endereço e a mesma porta do SSH (o que decide se os pins ainda valem). */
    public boolean mesmoEnderecoQue(ConfigServidor outra) {
        return host.equalsIgnoreCase(outra.host) && porta == outra.porta;
    }
}
