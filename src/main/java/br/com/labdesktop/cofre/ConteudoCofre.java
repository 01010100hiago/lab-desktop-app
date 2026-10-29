package br.com.labdesktop.cofre;

import br.com.labdesktop.cofre.ConteudoEmMemoria.ServidorGuardado;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.seguranca.Zeragem;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * O JSON UTF-8 que fica DENTRO do texto cifrado (ARQUITETURA 5.3). É só o formato de arquivo; o app trabalha
 * com {@link ConteudoEmMemoria}.
 *
 * <p>{@code privadaOpenssh} e {@code tokenAgente} são {@code byte[]}: o Jackson os grava como base64 e os
 * devolve como {@code byte[]} direto, sem eu criar uma {@code String} com a chave. Campos desconhecidos
 * (versões futuras) são ignorados. Os campos {@code protegido} e {@code protecaoAutomaticaRecusada} são opcionais, como os outros. Os campos {@code nomeDoDispositivo} e {@code estado2fa} do servidor foram
 * acrescentados à especificação de 5.3 (opcionais; ausentes valem {@code null} e {@code desligado}), por isso
 * a {@code versao} continua 1.
 *
 * <p>Classe comum com campos de pacote, {@code toString} redigido: segura segredo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
final class ConteudoCofre {

    static final int VERSAO = 1;
    /**
     * O conteúdo que tem o servidor "Este computador" (transporte {@code local}, sem chave SSH). Um app mais antigo lê isto como
     * "servidor que aponta para chave inexistente" (cofre corrompido); com a versão 2 ele diz com clareza que não suporta esta versão, e o
     * cofre sem servidor local continua versão 1, legível por qualquer app.
     */
    static final int VERSAO_COM_SERVIDOR_LOCAL = 2;
    /**
     * O conteúdo em que algum servidor tem a marca "Servidor protegido" ou a recusa da marca automática. Um app sem a marca abriria o
     * cofre, ignoraria os campos que não conhece e, ao regravar, APAGARIA a proteção em silêncio; com a versão 3 ele recusa abrir ("versão não suportada"),
     * como foi feito com o servidor local. A versão 2 não serve: um app sem a marca já a aceita. Cofre sem nenhuma marca continua nas
     * versões 1 e 2 de sempre, o mesmo arquivo de hoje.
     */
    static final int VERSAO_COM_PROTECAO = 3;

    @JsonProperty("versao")
    int versao = VERSAO;
    @JsonProperty("chaves")
    List<ChaveJson> chaves = new ArrayList<>();
    @JsonProperty("servidores")
    List<ServidorJson> servidores = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class ChaveJson {
        @JsonProperty("id")
        String id;
        @JsonProperty("nome")
        String nome;
        @JsonProperty("algoritmo")
        String algoritmo;
        @JsonProperty("privadaOpenssh")
        byte[] privadaOpenssh;
        @JsonProperty("publica")
        String publica;
        @JsonProperty("impressao")
        String impressao;
        @JsonProperty("criadaEm")
        Instant criadaEm;
        /** {@code gerada} ou {@code importada}; ausente (cofre antigo) = origem desconhecida. */
        @JsonProperty("origem")
        String origem;

        @Override
        public String toString() {
            return "ChaveJson[redigido]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class ServidorJson {
        @JsonProperty("id")
        String id;
        @JsonProperty("nome")
        String nome;
        @JsonProperty("host")
        String host;
        @JsonProperty("porta")
        int porta;
        @JsonProperty("usuario")
        String usuario;
        @JsonProperty("chaveId")
        String chaveId;
        @JsonProperty("portaAgente")
        int portaAgente = ConfigServidor.PORTA_AGENTE_PADRAO;
        @JsonProperty("tokenAgente")
        byte[] tokenAgente;
        @JsonProperty("pins")
        List<PinJson> pins = new ArrayList<>();
        @JsonProperty("transporte")
        TransporteJson transporte;
        @JsonProperty("nomeDoDispositivo")
        String nomeDoDispositivo;
        @JsonProperty("estado2fa")
        String estado2fa;
        /** Container do agente que o app instalou (ausente: nunca instalou aqui) e se o Docker só responde por sudo. Opcionais. */
        @JsonProperty("agenteContainer")
        String agenteContainer;
        @JsonProperty("agenteComSudo")
        Boolean agenteComSudo;
        /**
         * "Servidor protegido" e a recusa da marca automática. Opcionais e gravados só quando valem {@code true}: cofre de antes não tem os
         * campos (ausente vale {@code false}) e cofre novo sem proteção fica idêntico ao de hoje.
         */
        @JsonProperty("protegido")
        Boolean protegido;
        @JsonProperty("protecaoAutomaticaRecusada")
        Boolean protecaoAutomaticaRecusada;
        /** O token de API da Cloudflare (base64 de bytes, como os outros segredos) e a escolha do usuário; opcionais, ausentes valem "nunca conectou". */
        @JsonProperty("cloudflareToken")
        byte[] cloudflareToken;
        @JsonProperty("cloudflare")
        CloudflareJson cloudflare;

        @Override
        public String toString() {
            return "ServidorJson[redigido]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class CloudflareJson {
        @JsonProperty("contaId")
        String contaId;
        @JsonProperty("contaNome")
        String contaNome;
        @JsonProperty("zonaId")
        String zonaId;
        @JsonProperty("zonaNome")
        String zonaNome;
        @JsonProperty("tunelId")
        String tunelId;
        @JsonProperty("tunelNome")
        String tunelNome;
        @JsonProperty("tipo")
        String tipo;
        @JsonProperty("prefixo")
        String prefixo;
        @JsonProperty("caminhoDoConfig")
        String caminhoDoConfig;
        @JsonProperty("containerDoCloudflared")
        String containerDoCloudflared;
        @JsonProperty("caminhoNoContainer")
        String caminhoNoContainer;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class PinJson {
        @JsonProperty("algoritmo")
        String algoritmo;
        @JsonProperty("impressao")
        String impressao;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class TransporteJson {
        @JsonProperty("tipo")
        String tipo;
        @JsonProperty("hostname")
        String hostname;
    }

    /**
     * Monta o JSON a partir do conteúdo em memória. Os {@code byte[]} dos segredos são os arrays INTERNOS
     * (sem cópia): o resultado é para ser serializado e descartado na hora, não guardado.
     */
    static ConteudoCofre de(ConteudoEmMemoria memoria) {
        ConteudoCofre json = new ConteudoCofre();
        if (memoria.servidores.stream().anyMatch(s -> s.config.ehLocal())) {
            json.versao = VERSAO_COM_SERVIDOR_LOCAL;
        }
        if (memoria.servidores.stream().anyMatch(s -> s.config.protegido() || s.config.protecaoAutomaticaRecusada())) {
            json.versao = VERSAO_COM_PROTECAO;
        }
        for (ChaveSsh c : memoria.chaves) {
            ChaveJson j = new ChaveJson();
            j.id = c.id();
            j.nome = c.nome();
            j.algoritmo = c.algoritmo();
            j.privadaOpenssh = c.privadaInterna();
            j.publica = c.publica();
            j.impressao = c.impressao();
            j.criadaEm = c.criadaEm();
            j.origem = rotuloDaOrigem(c.origem());
            json.chaves.add(j);
        }
        for (ServidorGuardado s : memoria.servidores) {
            ConfigServidor cfg = s.config;
            ServidorJson j = new ServidorJson();
            j.id = cfg.id();
            j.nome = cfg.nome();
            j.host = cfg.ehLocal() ? null : cfg.host(); // o servidor local não tem endereço, porta nem usuário de SSH
            j.porta = cfg.porta();
            j.usuario = cfg.ehLocal() ? null : cfg.usuario();
            j.chaveId = cfg.chaveId();
            j.portaAgente = cfg.portaAgente();
            j.tokenAgente = s.token == null ? null : s.token.bytesInternos();
            for (Pin p : cfg.pins()) {
                PinJson pj = new PinJson();
                pj.algoritmo = p.algoritmo();
                pj.impressao = p.impressao();
                j.pins.add(pj);
            }
            TransporteJson tj = new TransporteJson();
            tj.tipo = switch (cfg.transporte().tipo()) {
                case DIRETO -> "direto";
                case CLOUDFLARED -> "cloudflared";
                case LOCAL -> "local";
            };
            tj.hostname = cfg.transporte().hostname();
            j.transporte = tj;
            j.nomeDoDispositivo = cfg.nomeDoDispositivo();
            j.estado2fa = cfg.estado2fa().rotulo();
            j.protegido = cfg.protegido() ? Boolean.TRUE : null;
            j.protecaoAutomaticaRecusada = cfg.protecaoAutomaticaRecusada() ? Boolean.TRUE : null;
            if (s.agente != null) {
                j.agenteContainer = s.agente.container();
                j.agenteComSudo = s.agente.comSudo() ? Boolean.TRUE : null;
            }
            j.cloudflareToken = s.cloudflareToken == null ? null : s.cloudflareToken.bytesInternos();
            if (s.cloudflare != null) {
                ConfiguracaoCloudflare c = s.cloudflare;
                CloudflareJson cj = new CloudflareJson();
                cj.contaId = c.contaId();
                cj.contaNome = c.contaNome();
                cj.zonaId = c.zonaId();
                cj.zonaNome = c.zonaNome();
                cj.tunelId = c.tunelId();
                cj.tunelNome = c.tunelNome();
                cj.tipo = c.tipo() == ConfiguracaoCloudflare.Tipo.API ? "api" : "arquivo";
                cj.prefixo = c.prefixo();
                cj.caminhoDoConfig = c.caminhoDoConfig();
                cj.containerDoCloudflared = c.containerDoCloudflared();
                cj.caminhoNoContainer = c.caminhoNoContainer();
                j.cloudflare = cj;
            }
            json.servidores.add(j);
        }
        return json;
    }

    /**
     * Valida e converte para memória. ADOTA os {@code byte[]} dos segredos (sem copiar). Em caso de erro zera
     * o que já tinha criado, para não deixar chave solta na memória.
     */
    ConteudoEmMemoria paraMemoria() throws CofreCorrompidoException {
        if (versao != VERSAO && versao != VERSAO_COM_SERVIDOR_LOCAL && versao != VERSAO_COM_PROTECAO) {
            throw new CofreCorrompidoException("Versão do conteúdo do cofre não suportada: " + versao + ".");
        }
        List<ChaveSsh> chavesProntas = new ArrayList<>();
        List<ServidorGuardado> servidoresProntos = new ArrayList<>();
        try {
            Set<String> idsDeChave = new HashSet<>();
            for (ChaveJson j : chaves == null ? List.<ChaveJson>of() : chaves) {
                if (j == null || !ValidadorDeNomes.idLocalValido(j.id) || !idsDeChave.add(j.id)) {
                    throw new CofreCorrompidoException("Cofre com chave de id inválido ou repetido.");
                }
                if (!ChaveSsh.ALGORITMO_ED25519.equals(j.algoritmo)) {
                    throw new CofreCorrompidoException("Cofre com chave de algoritmo não suportado.");
                }
                if (j.privadaOpenssh == null || j.privadaOpenssh.length == 0 || j.nome == null || j.nome.isBlank()
                    || j.publica == null || j.impressao == null || !j.impressao.startsWith("SHA256:")) {
                    throw new CofreCorrompidoException("Cofre com chave incompleta.");
                }
                chavesProntas.add(new ChaveSsh(j.id, j.nome, j.algoritmo, j.publica, j.impressao,
                    j.criadaEm == null ? Instant.EPOCH : j.criadaEm, origemDe(j.origem), j.privadaOpenssh));
            }
            Set<String> idsDeServidor = new HashSet<>();
            for (ServidorJson j : servidores == null ? List.<ServidorJson>of() : servidores) {
                if (j == null || !idsDeServidor.add(j.id)) {
                    throw new CofreCorrompidoException("Cofre com servidor de id repetido.");
                }
                boolean local = j.transporte != null && "local".equals(j.transporte.tipo);
                if (!local && !idsDeChave.contains(j.chaveId)) {
                    throw new CofreCorrompidoException("Cofre com servidor que aponta para chave inexistente.");
                }
                TokenAgente token = null;
                if (j.tokenAgente != null) {
                    try {
                        token = TokenAgente.adotar(j.tokenAgente);
                    } catch (IllegalArgumentException e) {
                        throw new CofreCorrompidoException("Cofre com token do agente inválido.", e);
                    }
                }
                TokenCloudflare cfToken = null;
                try {
                    AgenteInstalado agente = j.agenteContainer == null ? null
                        : new AgenteInstalado(j.agenteContainer, Boolean.TRUE.equals(j.agenteComSudo));
                    ConfiguracaoCloudflare cf = j.cloudflare == null ? null : cloudflareDe(j.cloudflare);
                    if (j.cloudflareToken != null) {
                        cfToken = TokenCloudflare.adotar(j.cloudflareToken);
                    }
                    servidoresProntos.add(new ServidorGuardado(configDe(j), token, agente, cfToken, cf));
                } catch (IllegalArgumentException e) {
                    if (token != null) {
                        token.zerar();
                    }
                    if (cfToken != null) {
                        cfToken.zerar();
                    }
                    throw new CofreCorrompidoException("Cofre com servidor inválido: " + e.getMessage(), e);
                }
            }
            return new ConteudoEmMemoria(chavesProntas, servidoresProntos);
        } catch (CofreCorrompidoException | RuntimeException e) {
            chavesProntas.forEach(ChaveSsh::zerar);
            servidoresProntos.stream().filter(s -> s.token != null).forEach(s -> s.token.zerar());
            servidoresProntos.stream().filter(s -> s.cloudflareToken != null).forEach(s -> s.cloudflareToken.zerar());
            zerarArrays();
            if (e instanceof CofreCorrompidoException corrompido) {
                throw corrompido;
            }
            // JSON de forma inesperada (elemento nulo numa lista, por exemplo): é arquivo corrompido, não bug.
            throw new CofreCorrompidoException("O conteúdo do cofre tem uma forma inesperada.", e);
        }
    }

    private static String rotuloDaOrigem(InfoChave.Origem origem) {
        return switch (origem) {
            case GERADA_PELO_APP -> "gerada";
            case IMPORTADA -> "importada";
            case DESCONHECIDA -> null; // não grava: o arquivo continua igual ao de antes para quem não sabe
        };
    }

    /** Rótulo que não conheço (versão futura) vale como desconhecido, nunca como "gerada": o erro seguro é não revogar. */
    private static InfoChave.Origem origemDe(String rotulo) {
        if (rotulo == null) {
            return InfoChave.Origem.DESCONHECIDA;
        }
        return switch (rotulo) {
            case "gerada" -> InfoChave.Origem.GERADA_PELO_APP;
            case "importada" -> InfoChave.Origem.IMPORTADA;
            default -> InfoChave.Origem.DESCONHECIDA;
        };
    }

    private static ConfiguracaoCloudflare cloudflareDe(CloudflareJson c) {
        ConfiguracaoCloudflare.Tipo tipo = switch (c.tipo == null ? "" : c.tipo) {
            case "api" -> ConfiguracaoCloudflare.Tipo.API;
            case "arquivo" -> ConfiguracaoCloudflare.Tipo.ARQUIVO;
            default -> throw new IllegalArgumentException("tipo de túnel desconhecido");
        };
        return new ConfiguracaoCloudflare(c.contaId, c.contaNome, c.zonaId, c.zonaNome, c.tunelId, c.tunelNome, tipo, c.prefixo, c.caminhoDoConfig,
            c.containerDoCloudflared, c.caminhoNoContainer);
    }

    private static ConfigServidor configDe(ServidorJson j) {
        List<Pin> pins = new ArrayList<>();
        if (j.pins != null) {
            for (PinJson p : j.pins) {
                pins.add(new Pin(p.algoritmo, p.impressao));
            }
        }
        Transporte transporte = Transporte.DIRETO;
        if (j.transporte != null && j.transporte.tipo != null) {
            transporte = switch (j.transporte.tipo) {
                case "direto" -> Transporte.DIRETO;
                case "cloudflared" -> Transporte.cloudflared(j.transporte.hostname);
                case "local" -> Transporte.LOCAL;
                default -> throw new IllegalArgumentException("transporte desconhecido");
            };
        }
        return new ConfigServidor(j.id, j.nome, j.host, j.porta, j.usuario, j.chaveId, j.portaAgente, pins,
            transporte, j.nomeDoDispositivo, Estado2fa.deRotulo(j.estado2fa), Boolean.TRUE.equals(j.protegido),
            Boolean.TRUE.equals(j.protecaoAutomaticaRecusada));
    }

    /** Zera os arrays de segredo que o Jackson alocou (usado quando a conversão falha). */
    private void zerarArrays() {
        if (chaves != null) {
            for (ChaveJson c : chaves) {
                if (c != null) {
                    Zeragem.zerar(c.privadaOpenssh);
                }
            }
        }
        if (servidores != null) {
            for (ServidorJson s : servidores) {
                if (s != null) {
                    Zeragem.zerar(s.tokenAgente);
                    Zeragem.zerar(s.cloudflareToken);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ConteudoCofre[redigido]";
    }
}
