package br.com.labdesktop.local;

import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Roda UM programa neste computador, com os argumentos em LISTA (nunca montados em texto, nunca por shell): é a "tomada" do laboratório local,
 * como o {@code ExecutorDeComandos} é a do instalador por SSH. Em produção, {@link ProcessosLocais}; nos testes, um roteiro falso.
 * Bloqueia: thread de fundo, nunca a EDT. Interromper a thread (o Cancelar da tela) mata o processo.
 */
public interface ExecutorDeProcesso {

    /**
     * @param argumentos o programa e os argumentos ({@code ["docker", "version"]}); o primeiro é um nome de programa ou caminho, nunca uma linha
     * @param stdin      bytes enviados à entrada do programa (e então fechada); pode ser vazio, nunca {@code null}
     * @throws ProgramaNaoEncontradoException o programa não existe neste computador
     * @throws IOException                    não deu para rodar, ou o prazo estourou ({@link br.com.labdesktop.ssh.ComandoDemorouException})
     */
    ResultadoExec executar(List<String> argumentos, byte[] stdin, Duration prazo) throws IOException, InterruptedException;

    default ResultadoExec executar(List<String> argumentos, Duration prazo) throws IOException, InterruptedException {
        return executar(argumentos, new byte[0], prazo);
    }

    /**
     * Como {@link #executar(List, byte[], Duration)}, com variáveis de ambiente a mais para ESTE processo (por exemplo
     * {@code GIT_TERMINAL_PROMPT=0}, que impede o {@code git} de ficar esperando uma senha que ninguém vai digitar). O padrão (executores falsos
     * dos testes, que só anotam o comando) ignora o ambiente; {@link ProcessosLocais} o aplica de verdade.
     */
    default ResultadoExec executar(List<String> argumentos, java.util.Map<String, String> ambiente, byte[] stdin, Duration prazo)
        throws IOException, InterruptedException {
        return executar(argumentos, stdin, prazo);
    }
}
