package br.com.labdesktop.instalador;

import java.util.List;

/**
 * O resultado das verificações prévias (só leitura) mais o que o instalador decidiu a partir delas: como falar com o Docker neste
 * servidor, se é preciso instalar o Docker primeiro e se o app sabe fazê-lo ali.
 *
 * @param resultados        uma entrada por verificação, na ordem da tela
 * @param modo              instalar ou atualizar (muda o que é problema: a porta em uso, por exemplo)
 * @param docker            o comando do Docker que vale neste servidor ({@code docker} ou {@code sudo -n docker}); {@code null} se não há Docker
 *                          utilizável agora
 * @param administrador     {@code root}, {@code sudo} (sudo sem senha) ou {@code nenhum}
 * @param distribuicao      o {@code ID} de {@code /etc/os-release} ({@code debian}, {@code ubuntu}...); vazio se não deu para ler
 * @param dockerPresente    o Docker está instalado e responde (sozinho ou com sudo)
 * @param dockerAusente     o servidor não tem o Docker instalado (diferente de "instalado e sem permissão" ou "parado")
 */
public record RelatorioDeVerificacao(List<ResultadoDaVerificacao> resultados, ModoDaInstalacao modo, String docker,
                                     Administrador administrador, String distribuicao, boolean dockerPresente, boolean dockerAusente) {

    /** Quem executa o que exige administração. */
    public enum Administrador {
        ROOT, SUDO_SEM_SENHA, NENHUM
    }

    public RelatorioDeVerificacao {
        resultados = List.copyOf(resultados);
        distribuicao = distribuicao == null ? "" : distribuicao;
    }

    /** Algum problema impede continuar. */
    public boolean bloqueado() {
        return resultados.stream().anyMatch(ResultadoDaVerificacao::bloqueia);
    }

    /** Os problemas que impedem, na ordem da tela. */
    public List<ResultadoDaVerificacao> problemas() {
        return resultados.stream().filter(ResultadoDaVerificacao::bloqueia).toList();
    }

    public ResultadoDaVerificacao de(ResultadoDaVerificacao.Item item) {
        return resultados.stream().filter(r -> r.item() == item).findFirst().orElse(null);
    }

    /** O app precisa instalar o Docker antes (e sabe: Debian ou Ubuntu, com administrador). */
    public boolean vaiInstalarODocker() {
        return !dockerPresente && ComandosRemotos.distribuicaoSuportada(distribuicao) && administrador != Administrador.NENHUM;
    }

    public boolean root() {
        return administrador == Administrador.ROOT;
    }
}
