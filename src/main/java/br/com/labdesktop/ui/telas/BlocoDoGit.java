package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.AcoesDaInterface;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Git neste servidor" (aba Máquina): se o git responde e, se falta, o botão "Instalar o Git…", que abre a confirmação com os comandos exatos (nada roda sem o clique). Só LÊ ao aparecer
 * ({@code git --version}). Em "Este computador" é a mesma conferência de "Preparar este computador", e o botão leva a ele. Nada de rede na EDT.
 */
final class BlocoDoGit extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;

    /** O que o bloco mostra. */
    enum Modo { LENDO, INSTALADO, AUSENTE, SEM_CONEXAO, NAO_CONFERI }

    private final transient ServidorLab servidor;
    private final transient AcoesDaInterface acoes;
    private final Selo selo = new Selo("", Gravidade.NEUTRO);
    private final TextoCorrido texto = new TextoCorrido("", 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao instalar = Botoes.miniPrimario("Instalar o Git…", "baixar");
    private final Botao conferirDeNovo = Botoes.mini("Conferir de novo", "atualizar");
    private final AtomicInteger geracao = new AtomicInteger();
    private Modo modo = Modo.LENDO;
    private String versao = "";

    BlocoDoGit(ServidorLab servidor, AcoesDaInterface acoes) {
        super("Git", "terminal");
        this.servidor = servidor;
        this.acoes = acoes;
        setName("bloco-do-git");
        instalar.setName("instalar-git");
        conferirDeNovo.setName("conferir-git");
        instalar.addActionListener(e -> {
            if (acoes != null && servidor != null) {
                acoes.instalarGit(servidor, this::conferir);
            }
        });
        conferirDeNovo.addActionListener(e -> conferir());
        redesenhar();
    }

    /** Lê o git agora (só leitura, em segundo plano). Chamado ao ficar visível, no "Conferir de novo" e depois da instalação. */
    void conferir() {
        if (servidor == null) {
            return;
        }
        if (!servidor.ehLocal() && !servidor.estado().conexao().permiteExec()) {
            modo = Modo.SEM_CONEXAO;
            redesenhar();
            return;
        }
        int minha = geracao.incrementAndGet();
        modo = Modo.LENDO;
        redesenhar();
        Tarefas.rodar(() -> servidor.ehLocal()
            ? br.com.labdesktop.local.GitLocal.versao(servidor.servicosLocais().sonda(), servidor.servicosLocais().executor(), servidor.servicosLocais().plataforma().sistema())
            : Optional.of(servidor.instaladorDeGit().conferirOGit()).filter(e -> e.instalado()).map(e -> e.versao()), (Optional<String> v) -> {
                if (minha != geracao.get()) {
                    return;
                }
                if (v.isPresent()) {
                    modo = Modo.INSTALADO;
                    versao = v.get();
                } else {
                    modo = Modo.AUSENTE;
                }
                redesenhar();
            }, falha -> {
                if (minha != geracao.get()) {
                    return;
                }
                modo = Modo.NAO_CONFERI;
                texto.setText("Não consegui conferir agora: " + MensagensDeErro.de(falha));
                redesenhar();
            });
    }

    /** Põe o bloco num estado (o que a leitura faria); público para os testes de tela alimentarem o bloco sem servidor vivo. */
    void definir(Modo novo, String versaoLida) {
        this.modo = novo;
        this.versao = versaoLida == null ? "" : versaoLida;
        redesenhar();
    }

    /** O bloco foi escondido: o que estava em voo é descartado. */
    void soltar() {
        geracao.incrementAndGet();
    }

    private void redesenhar() {
        limpar();
        boolean local = servidor != null && servidor.ehLocal();
        String onde = local ? "este computador" : "este servidor";
        switch (modo) {
            case LENDO -> {
                selo.atualizar("LENDO", Gravidade.NEUTRO, null);
                texto.setText("Conferindo o git em " + onde + " (só leitura)…");
            }
            case INSTALADO -> {
                selo.atualizar("INSTALADO", Gravidade.OK, null);
                texto.setText(versao + ". É ele que o app usa para clonar repositórios (Novo projeto, Repositório Git). O app não mexe na configuração do git.");
            }
            case AUSENTE -> {
                selo.atualizar("NÃO INSTALADO", Gravidade.ATENCAO, null);
                texto.setText("O git não está instalado em " + onde + ", e sem ele o app não clona repositórios (Novo projeto, Repositório Git). "
                    + (local ? "\"Instalar o Git…\" abre \"Preparar este computador\", que mostra o jeito de instalar neste sistema." : "\"Instalar o Git…\" mostra os comandos exatos antes de rodar qualquer coisa."));
            }
            case SEM_CONEXAO -> {
                selo.atualizar("SEM CONEXÃO", Gravidade.NEUTRO, null);
                texto.setText("Sem conexão com o servidor agora: não dá para conferir o git.");
            }
            case NAO_CONFERI -> selo.atualizar("NÃO CONFERI", Gravidade.ATENCAO, null);
        }
        linha(Caixa.linha(8, selo, Caixa.mola()), 0);
        linha(texto, 6);
        instalar.setVisible(modo == Modo.AUSENTE && acoes != null);
        linha(Caixa.linha(8, instalar, conferirDeNovo, Caixa.mola()), 8);
        terminar();
    }

    // ---- para os testes ----

    Modo modo() {
        return modo;
    }

    Botao botaoInstalar() {
        return instalar;
    }

    Botao botaoConferirDeNovo() {
        return conferirDeNovo;
    }

    String textoDoSelo() {
        return selo.texto();
    }

    String textoMostrado() {
        return texto.getText();
    }
}
