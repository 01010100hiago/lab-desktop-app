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
 * <p>Um servidor {@link Transporte#LOCAL} ("Este computador") não tem SSH: {@code host} vale sempre {@code "localhost"}, {@code porta}
 * 0, {@code usuario} vazio e {@code chaveId} {@code null} (o construtor impõe isso, e as regras do SSH não se aplicam). Só {@code id},
 * {@code nome} e {@code portaAgente} (a porta publicada em 127.0.0.1 do computador) valem para ele.
 *
 * @param id                identificador interno e estável (nunca sai do Mac): {@code s1}, {@code s3f9a1c2b}
 * @param nome              nome mostrado ao usuário ("lab")
 * @param host              endereço do SSH (IP ou nome DNS)
 * @param porta             porta do SSH (2222 no lab)
 * @param usuario           usuário do SSH
 * @param chaveId           id da {@link ChaveSsh} deste servidor (uma chave por servidor); {@code null} no servidor local
 * @param portaAgente       porta do agente no loopback DO SERVIDOR (destino do túnel; 7000); no local, a porta que o Docker publica em 127.0.0.1
 * @param pins              impressões da chave do host já confirmadas pelousuário, por algoritmo; vazio = host ainda não confiado
 * @param transporte        direto (LAN), sem porta aberta (SSM ou Cloudflare) ou "Este computador" (local); nos modos sem porta aberta {@code host} e {@code porta} não existem (ficam fixos)
 * @param nomeDoDispositivo {@code nome_do_dispositivo} do token deste Mac no agente; {@code null} até o token ser registrado
 * @param estado2fa         última leitura do campo {@code totp} de {@code /api/versao} (dica; a fonte é o agente)
 * @param protegido         "Servidor protegido": o app nunca instala, atualiza nem remove nada neste servidor (agente, Docker, Git);
 *                          vale para servidores que já estão em produção. Sempre {@code false} em "Este computador"
 * @param protecaoAutomaticaRecusada o usuário desligou a proteção: a marca AUTOMÁTICA (agente do lab instalado fora do app) não volta a ligar sozinha
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
    Estado2fa estado2fa,
    boolean protegido,
    boolean protecaoAutomaticaRecusada) {

    public static final int PORTA_AGENTE_PADRAO = 7000;
    /**
     * A porta padrão do agente de "Este computador". NÃO é a 7000: no macOS o AirPlay Receiver (ControlCenter) escuta em 7000 e 5000 em
     * todas as interfaces, e o Docker não consegue publicar ali.
     */
    public static final int PORTA_AGENTE_LOCAL_PADRAO = 7070;
    /** Valores fixos dos campos do SSH num servidor local (não são usados; só mantêm o registro válido e legível). */
    public static final String HOST_LOCAL = "localhost";
    /** O endereço "de enfeite" de um servidor sem porta aberta (SSM ou Cloudflare): um nome que nunca resolve. */
    public static final String HOST_DO_TRANSPORTE = "transporte.invalid";

    private static final SecureRandom ALEATORIO = new SecureRandom();

    public ConfigServidor {
        if (!ValidadorDeNomes.idLocalValido(id)) {
            throw new IllegalArgumentException("Id de servidor inválido.");
        }
        nome = TextoSeguro.limparLinha(nome);
        if (nome.isEmpty() || nome.length() > 64) {
            throw new IllegalArgumentException("O nome do servidor deve ter de 1 a 64 caracteres.");
        }
        transporte = transporte == null ? Transporte.DIRETO : transporte;
        if (transporte.tipo() == Transporte.Tipo.LOCAL) {
            host = HOST_LOCAL;
            porta = 0;
            usuario = "";
            chaveId = null;
            pins = List.of();
        } else if (transporte.ehTunel()) {
            // Sem porta aberta: o endereço e a porta do servidor não existem. O destino mora no transporte, e o SSH conecta numa porta local que o app
            // abre a cada tentativa. O host guardado é um nome que NUNCA resolve (TLD .invalid, RFC 2606): se um código esquecer de usar o túnel, falha em vez de
            // conectar noutro lugar.
            host = HOST_DO_TRANSPORTE;
            porta = 0;
            if (!ValidadorDeNomes.usuarioSshValido(usuario)) {
                throw new IllegalArgumentException("Usuário do SSH inválido.");
            }
            if (!ValidadorDeNomes.idLocalValido(chaveId)) {
                throw new IllegalArgumentException("Id de chave inválido.");
            }
        } else {
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
        }
        if (portaAgente < 1 || portaAgente > 65_535) {
            throw new IllegalArgumentException("Porta do agente fora de 1 a 65535.");
        }
        pins = pins == null ? List.of() : List.copyOf(pins);
        if (nomeDoDispositivo != null && !ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido (a-z, 0-9 e hífen, até 40).");
        }
        estado2fa = estado2fa == null ? Estado2fa.DESLIGADO : estado2fa;
        if (transporte.tipo() == Transporte.Tipo.LOCAL) {
            protegido = false; // "Este computador" é a máquina do próprio usuário: preparar o laboratório é a razão de ele existir
            protecaoAutomaticaRecusada = false;
        }
    }

    /** Sem as marcas de proteção (servidor comum, e o formato antigo). */
    public ConfigServidor(String id, String nome, String host, int porta, String usuario, String chaveId, int portaAgente, List<Pin> pins,
                          Transporte transporte, String nomeDoDispositivo, Estado2fa estado2fa) {
        this(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte, nomeDoDispositivo, estado2fa, false, false);
    }

    /** Servidor novo, sem pins, sem token e com o agente na porta padrão. */
    public static ConfigServidor novo(String nome, String host, int porta, String usuario, String chaveId) {
        return new ConfigServidor(novoId(), nome, host, porta, usuario, chaveId, PORTA_AGENTE_PADRAO,
            List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
    }

    /** O servidor "Este computador": sem SSH, sem chave e sem pins; o agente em 127.0.0.1 na porta dada. */
    public static ConfigServidor novoLocal(String nome, int portaAgente) {
        return new ConfigServidor(novoId(), nome, HOST_LOCAL, 0, "", null, portaAgente, List.of(), Transporte.LOCAL, null,
            Estado2fa.DESLIGADO);
    }

    /** É o servidor "Este computador" (sem SSH)? */
    public boolean ehLocal() {
        return transporte.tipo() == Transporte.Tipo.LOCAL;
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
            nomeDoDispositivo, estado2fa, protegido, protecaoAutomaticaRecusada);
    }

    public ConfigServidor semPins() {
        return comPins(List.of());
    }

    public ConfigServidor comNomeDoDispositivo(String novoNome) {
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte,
            novoNome, estado2fa, protegido, protecaoAutomaticaRecusada);
    }

    public ConfigServidor comEstado2fa(Estado2fa novo) {
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte,
            nomeDoDispositivo, novo, protegido, protecaoAutomaticaRecusada);
    }

    public ConfigServidor comChaveId(String novaChaveId) {
        return new ConfigServidor(id, nome, host, porta, usuario, novaChaveId, portaAgente, pins, transporte,
            nomeDoDispositivo, estado2fa, protegido, protecaoAutomaticaRecusada);
    }

    /**
     * Liga ou desliga "Servidor protegido" por escolha do USUÁRIO (a caixa de Editar servidor ou do Adicionar servidor). Desligar uma proteção que estava
     * ligada (a que o usuário pôs, ou a que o app pôs ao ver o agente do lab) também anota que a marca automática foi recusada: ela não volta
     * a ligar sozinha. Em "Este computador" não faz nada (a marca não existe).
     */
    public ConfigServidor comProtegido(boolean novo) {
        if (ehLocal()) {
            return this;
        }
        boolean recusada = protecaoAutomaticaRecusada || (protegido && !novo);
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte, nomeDoDispositivo, estado2fa, novo, recusada);
    }

    /** O app pode ligar a marca sozinho (viu o agente do lab instalado fora do app)? Só se não está ligada e o usuário nunca a desligou. */
    public boolean podeProtegerSozinho() {
        return !ehLocal() && !protegido && !protecaoAutomaticaRecusada;
    }

    /** A marca ligada pelo APP ao ver o agente do lab de fora. Devolve esta mesma configuração se não pode ({@link #podeProtegerSozinho}). */
    public ConfigServidor comProtecaoAutomatica() {
        if (!podeProtegerSozinho()) {
            return this;
        }
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, transporte, nomeDoDispositivo, estado2fa, true, false);
    }

    /** Mesma conexão que {@code outra}: tudo igual, menos no máximo as marcas de proteção (é o que decide se salvar reconecta). */
    public boolean mesmaConexaoQue(ConfigServidor outra) {
        return equals(new ConfigServidor(outra.id, outra.nome, outra.host, outra.porta, outra.usuario, outra.chaveId, outra.portaAgente, outra.pins,
            outra.transporte, outra.nomeDoDispositivo, outra.estado2fa, protegido, protecaoAutomaticaRecusada));
    }

    /**
     * O que o usuário muda na tela "Editar servidor" (o transporte continua o que é). Veja {@link #editar(String, Transporte, String, int, String, int)}.
     *
     * @throws IllegalArgumentException se algum campo não vale (as mesmas regras do construtor)
     */
    public ConfigServidor editar(String novoNome, String novoHost, int novaPorta, String novoUsuario, int novaPortaAgente) {
        return editar(novoNome, null, novoHost, novaPorta, novoUsuario, novaPortaAgente);
    }

    /**
     * O que o usuário muda na tela "Editar servidor". A regra do pin mora aqui, junto do dado: as impressões digitais guardadas eram as da máquina que o app alcançava
     * ANTES; mudar o DESTINO dentro do mesmo modo (o endereço ou a porta de um servidor direto; a instância, a região ou a porta SSH do SSM; o hostname da Cloudflare) apaga
     * os pins, porque outra máquina precisa ser conferida de novo, senão o app passaria a confiar às cegas num servidor que o usuário nunca conferiu. TROCAR O MODO
     * (direto, SSM, Cloudflare) NÃO apaga: o servidor é o mesmo, só o caminho até ele mudou, e os pins ficam presos à identidade do servidor (o id), não ao endereço e à porta.
     * Se na verdade o usuário apontou para outra máquina, a chave dela não bate com o pin e a conexão é RECUSADA (nunca confia sozinha). Mudar só o nome, o usuário, o perfil da
     * AWS ou a porta do agente mantém os pins. O endereço é comparado sem diferença de maiúsculas, como o DNS faz.
     *
     * @param novoTransporte o modo e os dados dele; {@code null} mantém o de agora. Num servidor sem porta aberta, {@code novoHost} e {@code novaPorta} não valem
     * @throws IllegalArgumentException se algum campo não vale (as mesmas regras do construtor)
     */
    public ConfigServidor editar(String novoNome, Transporte novoTransporte, String novoHost, int novaPorta, String novoUsuario, int novaPortaAgente) {
        Transporte alvo = novoTransporte == null ? transporte : novoTransporte;
        String hostLimpo = novoHost == null ? null : novoHost.strip();
        String usuarioLimpo = novoUsuario == null ? null : novoUsuario.strip();
        boolean mudouODestino;
        if (alvo.tipo() != transporte.tipo()) {
            mudouODestino = false; // trocar o modo mantém os pins: a identidade é o servidor, não o caminho
        } else if (alvo.ehTunel()) {
            mudouODestino = !alvo.mesmoDestinoQue(transporte);
        } else {
            mudouODestino = hostLimpo == null || !host.equalsIgnoreCase(hostLimpo) || porta != novaPorta;
        }
        return new ConfigServidor(id, novoNome, hostLimpo, novaPorta, usuarioLimpo, chaveId, novaPortaAgente,
            mudouODestino ? List.of() : pins, alvo, nomeDoDispositivo, estado2fa, protegido, protecaoAutomaticaRecusada);
    }

    /** O mesmo servidor com outro modo de conectar (tudo o mais fica, inclusive os pins). */
    public ConfigServidor comTransporte(Transporte novo) {
        return new ConfigServidor(id, nome, host, porta, usuario, chaveId, portaAgente, pins, novo, nomeDoDispositivo, estado2fa, protegido, protecaoAutomaticaRecusada);
    }

    /** Onde o servidor está, para mostrar ao usuário: {@code host:porta} no direto; a instância e a região no SSM; o hostname na Cloudflare. */
    public String enderecoParaMostrar() {
        return switch (transporte.tipo()) {
            case DIRETO -> host + ":" + porta;
            case LOCAL -> "Este computador";
            case SSM, CLOUDFLARED -> transporte.destinoEmTexto();
        };
    }

    /** O que o usuário muda de "Este computador": o nome e a porta do agente (o resto não existe nele). */
    public ConfigServidor editarLocal(String novoNome, int novaPortaAgente) {
        if (!ehLocal()) {
            throw new IllegalStateException("Só o servidor local tem esta edição.");
        }
        return new ConfigServidor(id, novoNome, host, porta, usuario, chaveId, novaPortaAgente, pins, transporte, nomeDoDispositivo, estado2fa,
            protegido, protecaoAutomaticaRecusada);
    }

    /**
     * {@code true} se {@code outra} aponta para o mesmo destino (o que decide se os pins ainda valem): o mesmo endereço e a mesma porta do SSH num servidor direto, ou a
     * mesma instância, região e porta (SSM) ou o mesmo hostname (Cloudflare). Modos diferentes são destinos diferentes aqui; a troca de modo, que MANTÉM os pins, é decidida em {@link #editar}.
     */
    public boolean mesmoEnderecoQue(ConfigServidor outra) {
        if (ehLocal() || outra.ehLocal()) {
            return ehLocal() && outra.ehLocal();
        }
        if (transporte.tipo() != outra.transporte.tipo()) {
            return false;
        }
        if (transporte.ehTunel()) {
            return transporte.mesmoDestinoQue(outra.transporte);
        }
        return host.equalsIgnoreCase(outra.host) && porta == outra.porta;
    }
}
