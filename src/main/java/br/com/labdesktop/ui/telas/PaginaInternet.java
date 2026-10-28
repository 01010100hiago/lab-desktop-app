package br.com.labdesktop.ui.telas;

import br.com.labdesktop.cloudflare.EnderecoDoApp;
import br.com.labdesktop.cloudflare.ServicoCloudflare;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A aba "Internet" de um projeto: o que o usuário precisa para dar ao projeto um endereço público (lab-meu-projeto.example.com) e os endereços que o app já criou para ele, com
 * Abrir e Remover. Não depende do detalhe do projeto (HTTP do agente): fala só com o cofre e com a Cloudflare. Ao ficar visível, lê os endereços (só leitura); escrever é sempre
 * dos diálogos Publicar e Remover, depois do clique de confirmação, e esses botões só aparecem com as ações de escrita liberadas.
 *
 * <p>Estados: servidor local (sem túnel), Cloudflare não conectada (com o botão Conectar, que só lê e grava no cofre local), conectada (lista + Publicar), e erro de leitura.
 */
final class PaginaInternet extends PaginaDoProjeto {

    private static final long serialVersionUID = 1L;

    /** O que a aba mostra. */
    enum Modo { CARREGANDO, LOCAL, NAO_CONECTADO, CONECTADO }

    private final CaixaDoServidor caixa = new CaixaDoServidor("Internet", "rede");
    private final Selo selo = new Selo("", Gravidade.NEUTRO);
    private final Botao conectar = Botoes.miniPrimario("Conectar a Cloudflare…", "link");
    private final Botao adicionarDominio = Botoes.miniPrimario("Adicionar domínio…", "externo");
    private final Botao publicar = Botoes.mini("Publicar na internet…", "rede");
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    private final TextoCorrido situacao = new TextoCorrido("", 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido erro = new TextoCorrido("", 640).corDoToken(Tokens.PERIGO).tamanho(12.5f);
    private final ListaDeEnderecos lista;
    private final AtomicInteger geracao = new AtomicInteger();
    private Modo modo = Modo.CARREGANDO;
    private List<EnderecoDoApp> enderecos = List.of();
    private boolean carregou;

    PaginaInternet(ContextoDoProjeto ctx) {
        super(ctx);
        this.lista = new ListaDeEnderecos(false, ctx.acoesLiberadas(), host -> ctx.abrirEndereco.accept(host),
            host -> ctx.removerEndereco.accept(host, this::recarregar));
        conectar.addActionListener(e -> ctx.conectarCloudflare.accept(this::recarregar));
        publicar.addActionListener(e -> ctx.publicarNaInternet.accept(this::recarregar));
        adicionarDominio.addActionListener(e -> ctx.adicionarDominio.accept(this::recarregar));
        adicionarDominio.setName("adicionar-dominio");
        adicionarDominio.setToolTipText("Dá a este projeto um endereço novo na internet, em passos curtos: conta, domínio, nome, serviço e confirmação");
        publicar.setToolTipText("Escolhe o endereço e mostra o que vai mudar antes de qualquer alteração");
        atualizar.addActionListener(e -> recarregar());
        atualizar.setToolTipText("Lê de novo os endereços na Cloudflare");
        setName("pagina-internet");
        iniciar();
    }

    @Override
    boolean independeDoDetalhe() {
        return true;
    }

    @Override
    JComponent montarConteudo() {
        caixa.apoio(new AEsquerda(selo));
        JPanel conteudo = new Painel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        conteudo.add(caixa, BorderLayout.NORTH);
        return conteudo;
    }

    @Override
    void aoAparecer() {
        recarregar();
    }

    @Override
    void aoSumir() {
        geracao.incrementAndGet(); // o que ainda estava em voo é descartado
    }

    /** Lê o que está escolhido (o cofre) e, conectado, os endereços do projeto na Cloudflare (só leitura). */
    void recarregar() {
        if (ctx.servidor == null) {
            modo = Modo.NAO_CONECTADO;
            carregou = true;
            enderecos = List.of();
            redesenhar();
            return;
        }
        ServicoCloudflare s = ctx.servidor.cloudflare();
        if (s.ehLocal()) {
            modo = Modo.LOCAL;
            carregou = true;
            enderecos = List.of();
            redesenhar();
            return;
        }
        boolean conectado;
        try {
            conectado = s.conectado();
        } catch (RuntimeException e) {
            conectado = false;
        }
        if (!conectado) {
            modo = Modo.NAO_CONECTADO;
            carregou = true;
            enderecos = List.of();
            redesenhar();
            return;
        }
        modo = Modo.CONECTADO;
        int minha = geracao.incrementAndGet();
        situacao.setText(carregou ? "Atualizando…" : "Lendo da Cloudflare…");
        redesenhar();
        Tarefas.rodar(() -> s.enderecos(ctx.projeto), itens -> {
            if (minha != geracao.get()) {
                return;
            }
            carregou = true;
            enderecos = itens;
            erro.setVisible(false);
            situacao.setText("");
            redesenhar();
        }, falha -> {
            if (minha != geracao.get()) {
                return;
            }
            carregou = true;
            erro.setText(falha instanceof FalhaDoProjeto f ? f.mensagem() : MensagensDeErro.de(falha));
            erro.setVisible(true);
            situacao.setText("");
            redesenhar();
        });
    }

    @Override
    void atualizarConteudo() {
        caixa.limpar();
        switch (modo) {
            case CARREGANDO -> {
                selo.atualizar("LENDO", Gravidade.NEUTRO, null);
                caixa.linha(texto("Lendo o que está guardado…"), 0);
            }
            case LOCAL -> {
                selo.atualizar("SEM TÚNEL", Gravidade.NEUTRO, null);
                caixa.linha(texto("Publicar na internet pede um servidor com o cloudflared (o túnel da Cloudflare). \"Este computador\" não tem um túnel configurado pelo app. "
                    + "Para dar um endereço público a este projeto, ele precisa estar em um servidor conectado por SSH."), 0);
            }
            case NAO_CONECTADO -> {
                selo.atualizar("NÃO CONECTADA", Gravidade.NEUTRO, null);
                caixa.linha(texto("Para dar a este projeto um endereço público, conecte a sua conta da Cloudflare. O app usa um token de API que você mesmo cria, com as "
                    + "permissões mínimas, e o guarda só no cofre cifrado."), 0);
                caixa.linha(Caixa.linha(8, adicionarDominio, conectar, Caixa.mola()), 10);
                desligarSemAsAcoes();
            }
            case CONECTADO -> desenharConectado();
        }
        caixa.terminar();
        revalidate();
    }

    private void desenharConectado() {
        Optional<ConfiguracaoCloudflare> o = ctx.servidor.cloudflare().escolha();
        if (o.isEmpty()) {
            selo.atualizar("NÃO CONECTADA", Gravidade.NEUTRO, null);
            return;
        }
        ConfiguracaoCloudflare c = o.get();
        selo.atualizar("CONECTADA", Gravidade.OK, null);
        caixa.linha(texto("Domínio " + c.zonaNome() + (c.tunelNome().isEmpty() ? "" : ", túnel " + c.tunelNome()) + "."), 0);
        if (!situacao.getText().isEmpty()) {
            situacao.setVisible(true);
            caixa.linha(situacao, 6);
        }
        if (erro.isVisible()) {
            caixa.linha(erro, 6);
        }
        if (enderecos.isEmpty() && !erro.isVisible() && situacao.getText().isEmpty()) {
            caixa.linha(texto("Este projeto ainda não tem endereço público criado pelo app."), 8);
        }
        if (!enderecos.isEmpty()) {
            lista.definir(enderecos);
            caixa.linha(lista, 10);
        }
        caixa.linha(Caixa.linha(8, adicionarDominio, publicar, atualizar, Caixa.mola()), 12);
        if (!ctx.acoesLiberadas()) {
            caixa.linha(new Rotulo(TextosDeAcao.TRAVA + " Adicionar, publicar e remover endereços ficam desligados.").corDoToken(Tokens.TEXTO3).tamanho(12f), 6);
        }
        desligarSemAsAcoes();
    }

    /** Sem as ações de escrita ligadas os botões que escrevem aparecem DESLIGADOS, com a explicação, e não somem. */
    private void desligarSemAsAcoes() {
        boolean liberadas = ctx.acoesLiberadas();
        adicionarDominio.setEnabled(liberadas);
        publicar.setEnabled(liberadas);
        if (!liberadas) {
            adicionarDominio.setToolTipText(TextosDeAcao.TRAVA);
            publicar.setToolTipText(TextosDeAcao.TRAVA);
        }
    }

    private static TextoCorrido texto(String t) {
        return new TextoCorrido(t, 640).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    Modo modo() {
        return modo;
    }

    Botao botaoPublicar() {
        return publicar;
    }

    Botao botaoAdicionarDominio() {
        return adicionarDominio;
    }

    Botao botaoConectar() {
        return conectar;
    }

    Botao botaoAtualizar() {
        return atualizar;
    }

    List<String> enderecosMostrados() {
        return lista.hostnames();
    }

    List<Botao> botoesDeRemover() {
        return lista.botoesDeRemover();
    }

    List<Botao> botoesDeAbrir() {
        return lista.botoesDeAbrir();
    }

    String textoDoSelo() {
        return selo.texto();
    }

    String textoDoErro() {
        return erro.isVisible() ? erro.getText() : "";
    }

    boolean carregou() {
        return carregou;
    }
}
