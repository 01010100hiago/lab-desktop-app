package br.com.labdesktop.local;

/**
 * O que o servidor "Este computador" usa para falar com o computador: o executor de processos, a plataforma, a sonda de fatos e os tempos. Um
 * pacote só para o {@code GerenciadorServidores} entregar a cada {@code ServidorLab} sem aumentar a lista de parâmetros; os testes entregam
 * versões falsas.
 */
public record ServicosLocais(ExecutorDeProcesso executor, PlataformaLocal plataforma, SondaDoComputador sonda, ConfigLocal config) {

    /** Os de produção: processos de verdade, o sistema detectado, fatos lidos do disco e da JVM. */
    public static ServicosLocais padrao() {
        PlataformaLocal plataforma = PlataformaLocal.detectar();
        LocalizadorDeProgramas localizador = LocalizadorDeProgramas.doSistema(plataforma);
        return new ServicosLocais(new ProcessosLocais(localizador, plataforma), plataforma, new SondaReal(localizador), ConfigLocal.padrao());
    }
}
