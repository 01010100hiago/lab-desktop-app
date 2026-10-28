package br.com.labdesktop.ssh;

/**
 * Por onde o app fala HTTP com o agente: {@code http://127.0.0.1:<portaLocal>}. No SSH é o túnel ({@link TunelSsh}, porta efêmera que muda a cada
 * reconexão); em "Este computador" é a porta que o Docker publica em 127.0.0.1, sem túnel nenhum. O {@code ClienteApi} é por geração.
 */
public interface PontaDoAgente {

    /** A porta em 127.0.0.1 do computador do app. */
    int portaLocal();

    /** Cresce quando a ponta é outra (túnel novo, ou porta nova): o {@code ClienteApi} nasce de novo. */
    long geracao();

    /** Uma ponta fixa (sem túnel): o laboratório local e os testes. */
    record Fixa(int portaLocal, long geracao) implements PontaDoAgente { }
}
