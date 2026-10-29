package br.com.labdesktop.seguranca;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * As regras ESTRITAS dos dados de "Como conectar": o ID da instância, a região e o perfil da AWS, a porta SSH na instância e o endereço
 * (hostname) da Cloudflare. Esses textos viram ARGUMENTOS de um programa externo ({@code aws}, {@code cloudflared}), então a regra é uma lista branca fechada: só
 * letras ASCII, números e poucos símbolos; nada de espaço, aspas, {@code ;}, {@code $}, crase, quebra de linha, unicode nem hífen na frente.
 *
 * <p>Modelo puro (sem rede, disco nem processo). Cada campo tem {@code valido...} (para o cofre) e {@code erroDe...} (a frase simples para a tela, ou vazio se vale).
 * Nenhum desses valores passa por um shell: o montador de argumentos os entrega como itens separados de uma lista.
 */
public final class ValidadorDeTransporte {

    /** {@code i-} (instância) ou {@code mi-} (servidor gerenciado) e de 8 a 17 hexadecimais em minúsculas. */
    public static final Pattern INSTANCIA = Pattern.compile("^(?:i|mi)-[0-9a-f]{8,17}$");
    /** Região da AWS: {@code us-east-1}, {@code eu-west-3}, {@code ap-southeast-2}, {@code us-gov-west-1}. Fechada: letras minúsculas, hífens e o número. */
    public static final Pattern REGIAO = Pattern.compile("^[a-z]{2,5}(?:-[a-z]{2,12}){1,2}-[0-9]{1,2}$");
    /** Perfil da AWS: letras, números, ponto, hífen e sublinhado (1 a 64), SEM hífen nem ponto na frente (nunca vira opção do comando). */
    public static final Pattern PERFIL = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");
    /** Um rótulo de DNS, em minúsculas (o endereço é levado a minúsculas antes de conferir). */
    private static final Pattern ROTULO = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    /** Só o que um nome DNS pode ter ANTES de levar a minúsculas: ASCII, sem unicode que viraria letra comum ao trocar a caixa. */
    private static final Pattern CARACTERES_DO_DNS = Pattern.compile("^[A-Za-z0-9.-]+$");

    private static final Pattern SO_DIGITOS = Pattern.compile("^[0-9]{1,5}$");

    public static final int PORTA_SSH_PADRAO = 22;
    private static final int TAMANHO_MAXIMO_DA_REGIAO = 30;

    private ValidadorDeTransporte() { }

    // ----- instância -----

    public static boolean instanciaValida(String id) {
        return id != null && INSTANCIA.matcher(id).matches();
    }

    /** A frase simples para a tela ({@code ""} se vale). */
    public static String erroDaInstancia(String id) {
        if (id == null || id.isBlank()) {
            return "Digite o ID da instância, por exemplo i-0123456789abcdef0.";
        }
        return instanciaValida(id) ? "" : "O ID da instância começa com i- (ou mi-) e tem de 8 a 17 letras de a a f e números, sem espaço.";
    }

    // ----- região -----

    public static boolean regiaoValida(String regiao) {
        return regiao != null && regiao.length() <= TAMANHO_MAXIMO_DA_REGIAO && REGIAO.matcher(regiao).matches();
    }

    /** A região é opcional (vazia: o {@code aws} usa a do perfil ou do ambiente). */
    public static boolean regiaoOpcionalValida(String regiao) {
        return regiao == null || regiao.isEmpty() || regiaoValida(regiao);
    }

    public static String erroDaRegiao(String regiao) {
        if (regiao == null || regiao.isBlank()) {
            return "";
        }
        return regiaoValida(regiao) ? "" : "A região parece com us-east-1: letras minúsculas, hífens e o número no fim, sem espaço.";
    }

    // ----- perfil (opcional) -----

    /** Vazio (ou {@code null}) vale: o perfil é opcional. */
    public static boolean perfilValido(String perfil) {
        return perfil == null || perfil.isEmpty() || PERFIL.matcher(perfil).matches();
    }

    public static String erroDoPerfil(String perfil) {
        if (perfil == null || perfil.isBlank()) {
            return "";
        }
        return perfilValido(perfil) ? "" : "O perfil da AWS usa letras, números, ponto, hífen e sublinhado (até 64) e não começa com hífen nem ponto.";
    }

    // ----- porta SSH na instância -----

    public static boolean portaValida(int porta) {
        return porta >= 1 && porta <= 65_535;
    }

    /** A porta escrita só com dígitos ASCII (nada de dígito unicode, sinal ou espaço no meio); vazio se não é número de 1 a 65535. */
    public static OptionalInt portaDe(String texto) {
        if (texto == null) {
            return OptionalInt.empty();
        }
        String t = texto.strip();
        if (!SO_DIGITOS.matcher(t).matches()) {
            return OptionalInt.empty();
        }
        int p = Integer.parseInt(t);
        return portaValida(p) ? OptionalInt.of(p) : OptionalInt.empty();
    }

    public static String erroDaPorta(String texto) {
        if (texto == null || texto.isBlank()) {
            return "Digite a porta SSH da instância (o normal é 22).";
        }
        if (!SO_DIGITOS.matcher(texto.strip()).matches()) {
            return "A porta SSH da instância precisa ser um número.";
        }
        return portaDe(texto).isPresent() ? "" : "A porta SSH da instância vai de 1 a 65535.";
    }

    // ----- hostname da Cloudflare -----

    /**
     * O hostname em minúsculas se vale, senão {@code empty}. Vale: nome DNS com pelo menos dois rótulos (letras ASCII, números e hífen no meio de cada um, até 63 por rótulo),
     * até 253 no total, SEM esquema ({@code https://}), caminho, porta, espaço, usuário, ponto no fim nem unicode.
     */
    public static Optional<String> hostnameNormalizado(String texto) {
        if (texto == null || texto.length() > 253 || !CARACTERES_DO_DNS.matcher(texto).matches()) {
            return Optional.empty();
        }
        String minusculo = texto.toLowerCase(Locale.ROOT);
        String[] rotulos = minusculo.split("\\.", -1);
        if (rotulos.length < 2) {
            return Optional.empty();
        }
        for (String r : rotulos) {
            if (!ROTULO.matcher(r).matches()) {
                return Optional.empty();
            }
        }
        return Optional.of(minusculo);
    }

    public static boolean hostnameValido(String texto) {
        return hostnameNormalizado(texto).isPresent();
    }

    /** A frase para a tela; quem a usa passa o texto já sem os espaços das pontas (a regra é estrita: um espaço ou uma quebra de linha no meio ou nas pontas NÃO vale). */
    public static String erroDoHostname(String texto) {
        if (texto == null || texto.isBlank()) {
            return "Digite o endereço da Cloudflare, por exemplo ssh.example.com.";
        }
        if (texto.contains("://") || texto.contains("/") || texto.contains(":") || texto.contains("@")) {
            return "Digite só o nome, como ssh.example.com, sem https://, sem barra e sem porta.";
        }
        return hostnameValido(texto) ? "" : "O endereço da Cloudflare é um nome como ssh.example.com: letras, números, hífen e pontos, sem espaço.";
    }
}
