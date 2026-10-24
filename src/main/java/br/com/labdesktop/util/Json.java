package br.com.labdesktop.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * O único {@link ObjectMapper} do app (ARQUITETURA 4.2 e 6.5).
 *
 * <p>Regras: campo desconhecido é ignorado (o agente pode acrescentar campos sem quebrar o app), sem
 * "default typing" (é a porta de entrada clássica de execução de código por JSON hostil) e com tetos
 * explícitos de leitura, para um servidor malicioso ou um arquivo corrompido não provocar
 * {@code OutOfMemoryError}. O teto de tamanho do DOCUMENTO inteiro (2 MiB para JSON da API) é de quem lê o
 * fluxo ({@code LeituraLimitada}); aqui limitamos o que o parser aceita dentro dele.
 */
public final class Json {

    /** Maior string individual aceita. O JSON da API tem teto de 2 MiB no total. */
    public static final int TETO_STRING = 2 * 1024 * 1024;

    private static final ObjectMapper MAPPER = criar();

    private Json() { }

    private static ObjectMapper criar() {
        StreamReadConstraints limites = StreamReadConstraints.builder()
            .maxStringLength(TETO_STRING)
            .maxNestingDepth(64)
            .maxNameLength(256)
            .maxNumberLength(64)
            .build();
        JsonFactory fabrica = JsonFactory.builder().streamReadConstraints(limites).build();
        return JsonMapper.builder(fabrica)
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();
    }

    /** O mapper compartilhado. É seguro entre threads depois de configurado; não reconfigure. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
