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
 * (versões futuras) são ignorados. Os campos {@code nomeDoDispositivo} e {@code estado2fa} do servidor foram
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

        @Override
        public String toString() {
            return "ServidorJson[redigido]";
        }
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
        for (ChaveSsh c : memoria.chaves) {
            ChaveJson j = new ChaveJson();
            j.id = c.id();
            j.nome = c.nome();
            j.algoritmo = c.algoritmo();
            j.privadaOpenssh = c.privadaInterna();
            j.publica = c.publica();
            j.impressao = c.impressao();
            j.criadaEm = c.criadaEm();
            json.chaves.add(j);
        }
        for (ServidorGuardado s : memoria.servidores) {
            ConfigServidor cfg = s.config;
            ServidorJson j = new ServidorJson();
            j.id = cfg.id();
            j.nome = cfg.nome();
            j.host = cfg.host();
            j.porta = cfg.porta();
            j.usuario = cfg.usuario();
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
            tj.tipo = cfg.transporte().tipo() == Transporte.Tipo.DIRETO ? "direto" : "cloudflared";
            tj.hostname = cfg.transporte().hostname();
            j.transporte = tj;
            j.nomeDoDispositivo = cfg.nomeDoDispositivo();
            j.estado2fa = cfg.estado2fa().rotulo();
            json.servidores.add(j);
        }
        return json;
    }

    /**
     * Valida e converte para memória. ADOTA os {@code byte[]} dos segredos (sem copiar). Em caso de erro zera
     * o que já tinha criado, para não deixar chave solta na memória.
     */
    ConteudoEmMemoria paraMemoria() throws CofreCorrompidoException {
        if (versao != VERSAO) {
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
                    j.criadaEm == null ? Instant.EPOCH : j.criadaEm, j.privadaOpenssh));
            }
            Set<String> idsDeServidor = new HashSet<>();
            for (ServidorJson j : servidores == null ? List.<ServidorJson>of() : servidores) {
                if (j == null || !idsDeServidor.add(j.id)) {
                    throw new CofreCorrompidoException("Cofre com servidor de id repetido.");
                }
                if (!idsDeChave.contains(j.chaveId)) {
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
                try {
                    servidoresProntos.add(new ServidorGuardado(configDe(j), token));
                } catch (IllegalArgumentException e) {
                    if (token != null) {
                        token.zerar();
                    }
                    throw new CofreCorrompidoException("Cofre com servidor inválido: " + e.getMessage(), e);
                }
            }
            return new ConteudoEmMemoria(chavesProntas, servidoresProntos);
        } catch (CofreCorrompidoException | RuntimeException e) {
            chavesProntas.forEach(ChaveSsh::zerar);
            servidoresProntos.stream().filter(s -> s.token != null).forEach(s -> s.token.zerar());
            zerarArrays();
            if (e instanceof CofreCorrompidoException corrompido) {
                throw corrompido;
            }
            // JSON de forma inesperada (elemento nulo numa lista, por exemplo): é arquivo corrompido, não bug.
            throw new CofreCorrompidoException("O conteúdo do cofre tem uma forma inesperada.", e);
        }
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
                default -> throw new IllegalArgumentException("transporte desconhecido");
            };
        }
        return new ConfigServidor(j.id, j.nome, j.host, j.porta, j.usuario, j.chaveId, j.portaAgente, pins,
            transporte, j.nomeDoDispositivo, Estado2fa.deRotulo(j.estado2fa));
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
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ConteudoCofre[redigido]";
    }
}
