package br.com.labdesktop.api;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;

/**
 * O {@code ObjectMapper} do contrato com o agente: o único do app ({@link Json#mapper()}) mais leitura
 * TOLERANTE de número e data.
 *
 * <p>Por que tolerante: "todo campo é anulável" (ARQUITETURA 6.5). Um campo cujo tipo desvia (o {@code mem} do
 * mock do front é texto {@code "186MiB"}; o do agente real é número; as datas do mock são macros como
 * {@code @+5d3h}) vira {@code null} naquele campo em vez de derrubar o tópico inteiro com
 * {@code RespostaInvalida}. Estrutura errada (lista onde se esperava objeto) continua sendo erro. Os testes de
 * contrato com fixtures REAIS do lab são quem pega desvio de verdade.
 */
final class ContratoJson {

    private static final ObjectMapper MAPPER = criar();

    private ContratoJson() { }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper criar() {
        SimpleModule m = new SimpleModule("contrato-tolerante");
        m.addDeserializer(Long.class, new LongoTolerante());
        m.addDeserializer(Integer.class, new InteiroTolerante());
        m.addDeserializer(Double.class, new DecimalTolerante());
        m.addDeserializer(Instant.class, new InstanteTolerante());
        return Json.mapper().copy().registerModule(m);
    }

    /** Base: número ou texto numérico viram valor; qualquer outra coisa vira {@code null} e é descartada. */
    private abstract static class Tolerante<T> extends StdDeserializer<T> {
        private static final long serialVersionUID = 1L;

        Tolerante(Class<T> tipo) {
            super(tipo);
        }

        abstract T deNumero(JsonParser p) throws IOException;

        abstract T deTexto(String texto);

        @Override
        public T deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                try {
                    return deNumero(p);
                } catch (ArithmeticException e) {
                    return null; // não cabe no tipo
                }
            }
            if (t == JsonToken.VALUE_STRING) {
                try {
                    return deTexto(p.getText().strip());
                } catch (RuntimeException e) {
                    return null; // "186MiB", "@+5d3h"
                }
            }
            p.skipChildren(); // objeto ou lista onde se esperava escalar
            return null;
        }

        @Override
        public T getNullValue(DeserializationContext ctx) {
            return null;
        }
    }

    private static final class LongoTolerante extends Tolerante<Long> {
        private static final long serialVersionUID = 1L;

        LongoTolerante() {
            super(Long.class);
        }

        @Override
        Long deNumero(JsonParser p) throws IOException {
            return p.getDecimalValue().setScale(0, java.math.RoundingMode.DOWN).longValueExact();
        }

        @Override
        Long deTexto(String texto) {
            return Long.valueOf(texto);
        }
    }

    private static final class InteiroTolerante extends Tolerante<Integer> {
        private static final long serialVersionUID = 1L;

        InteiroTolerante() {
            super(Integer.class);
        }

        @Override
        Integer deNumero(JsonParser p) throws IOException {
            return p.getDecimalValue().setScale(0, java.math.RoundingMode.DOWN).intValueExact();
        }

        @Override
        Integer deTexto(String texto) {
            return Integer.valueOf(texto);
        }
    }

    private static final class DecimalTolerante extends Tolerante<Double> {
        private static final long serialVersionUID = 1L;

        DecimalTolerante() {
            super(Double.class);
        }

        @Override
        Double deNumero(JsonParser p) throws IOException {
            return p.getDoubleValue();
        }

        @Override
        Double deTexto(String texto) {
            double d = Double.parseDouble(texto);
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
    }

    private static final class InstanteTolerante extends Tolerante<Instant> {
        private static final long serialVersionUID = 1L;

        InstanteTolerante() {
            super(Instant.class);
        }

        @Override
        Instant deNumero(JsonParser p) {
            return null; // o agente só manda ISO; número aqui é desvio
        }

        @Override
        Instant deTexto(String texto) {
            return Instant.parse(texto);
        }
    }
}
