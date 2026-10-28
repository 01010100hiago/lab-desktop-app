package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** O log do app: o MINA em WARN e o ruído esperado do túnel de um servidor sem agente silenciado. */
class SimpleLoggerTest {

    private static Properties propriedades() throws Exception {
        Properties p = new Properties();
        try (InputStream in = SimpleLoggerTest.class.getResourceAsStream("/simplelogger.properties")) {
            assertNotNull(in);
            p.load(in);
        }
        return p;
    }

    @Test
    void oDefaultForwarderDoMinaFicaDesligadoPorqueServidorSemAgenteRecusaOTunelDeProposito() throws Exception {
        Properties p = propriedades();
        assertEquals("off", p.getProperty("org.slf4j.simpleLogger.log.org.apache.sshd.common.forward.DefaultForwarder"),
            "sem o agente, o destino do túnel recusa a conexão: o NullPointerException em WARN a cada tentativa é esperado, não é problema");
        assertEquals("warn", p.getProperty("org.slf4j.simpleLogger.log.org.apache.sshd"), "o resto do MINA segue em WARN");
        assertEquals("info", p.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
    }
}
