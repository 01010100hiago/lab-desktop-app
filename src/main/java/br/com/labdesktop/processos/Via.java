package br.com.labdesktop.processos;

/** Por onde uma ação chega ao processo. */
public enum Via {
    /** Um sinal ao processo ({@code kill}, {@code taskkill}). */
    PROCESSO,
    /** O processo é de um container: o app age no container ({@code docker stop}, {@code docker restart}). */
    CONTAINER,
    /** O processo pertence a um serviço do systemd do próprio usuário. */
    SERVICO_DO_USUARIO
}
