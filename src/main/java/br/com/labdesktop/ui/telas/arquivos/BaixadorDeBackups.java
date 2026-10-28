package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.Escopo;
import br.com.labdesktop.arquivos.FilaDeTransferencias;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Decisao;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Escolha;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import br.com.labdesktop.arquivos.PlanoDeBackups;
import br.com.labdesktop.arquivos.ServicoDeArquivos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeTransferencia;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import br.com.labdesktop.util.Fundo;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.swing.JComponent;

/**
 * Baixar os arquivos da área de backups de um projeto para este computador, pelo SFTP e pela MESMA fila de transferências do gerenciador de arquivos (progresso, velocidade, tempo que
 * falta e Cancelar). O que o usuário vê e o que o app garante:
 * <ul>
 *   <li><b>Para onde</b>: o seletor de pasta do sistema, que começa em Downloads; nada é gravado antes de ele escolher.</li>
 *   <li><b>Nome seguro</b>: o nome vem do servidor e vira o do arquivo aqui; {@link PlanoDeBackups} recusa o que é caminho ({@code ../x}) e troca o que o Windows não aceita.</li>
 *   <li><b>Tamanho conferido</b>: cada arquivo vem para um parcial oculto, o tamanho é comparado com o do servidor e só então ele entra no lugar; cancelar ou falhar apaga o parcial.
 *       O servidor não informa hash dos backups, então só o tamanho é conferido.</li>
 *   <li><b>Não sobrescreve sozinho</b>: arquivo que já existe pergunta (Substituir, Pular, Manter os dois, Cancelar).</li>
 *   <li><b>Grande demais ou sem espaço</b>: acima de 500 MB o usuário confirma; sem espaço livre no disco, o app avisa quanto falta e não começa.</li>
 *   <li><b>Se a conexão cair</b> o arquivo recomeça do zero (a retomada não existe nesta versão; a tela diz isso).</li>
 *   <li><b>Mostrar na pasta</b> ao terminar.</li>
 * </ul>
 * Só LÊ do servidor: por isso não depende da trava das ações de escrita (baixar não altera nada lá). Nada de rede nem disco na EDT.
 */
public final class BaixadorDeBackups extends Painel {

    private static final long serialVersionUID = 1L;

    private final ContextoDeArquivos ctx;
    private final Supplier<String> pastaDoProjeto;
    private final PainelDeTransferencias painel;
    private final Rotulo situacao = new Rotulo("").corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final TextoCorrido resultado = new TextoCorrido("", 800).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    private final Botao mostrarNaPasta = Botoes.mini("Mostrar na pasta", "pasta");
    private final JComponent linhaDoResultado;
    private final Map<Long, Path> destinos = new LinkedHashMap<>();
    private final Map<Long, Boolean> jaNotadas = new LinkedHashMap<>();
    private final List<CompletableFuture<Decisao>> decisoesPendentes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private transient FilaDeTransferencias fila;
    private transient ServicoDeArquivos servico;
    private transient ClienteSftp cliente;
    private Runnable desinscrever = () -> { };
    private Path ultimaPasta;
    private int emVoo;
    private boolean encerrado;

    /**
     * @param ctx            o mesmo contexto do gerenciador de arquivos do projeto (servidor, projeto, seletor de pasta, diálogos, avisos)
     * @param pastaDoProjeto a pasta do projeto no servidor ({@code null} se não deu para montar)
     */
    public BaixadorDeBackups(ContextoDeArquivos ctx, Supplier<String> pastaDoProjeto) {
        super(new BorderLayout(0, 6));
        this.ctx = ctx;
        this.pastaDoProjeto = pastaDoProjeto;
        this.painel = new PainelDeTransferencias(new PainelDeTransferencias.Acoes() {
            @Override
            public void cancelar(long id) {
                if (fila != null) {
                    fila.cancelar(id);
                }
            }

            @Override
            public void limparConcluidas() {
                if (fila != null) {
                    fila.limparConcluidas();
                }
            }
        });
        setName("baixador-de-backups");
        linhaDoResultado = Caixa.linha(10, resultado, Caixa.mola(), mostrarNaPasta);
        linhaDoResultado.setVisible(false);
        mostrarNaPasta.addActionListener(e -> {
            if (ultimaPasta != null) {
                ctx.escolhedor().mostrarNaPasta(ultimaPasta);
            }
        });
        situacao.setVisible(false);
        add(Caixa.coluna(6, situacao, linhaDoResultado), BorderLayout.NORTH);
        add(painel, BorderLayout.CENTER);
    }

    // =====================================================================================================
    // Baixar
    // =====================================================================================================

    /** Pergunta a pasta (Downloads de partida) e baixa os backups escolhidos. Na EDT. */
    public void baixar(List<PlanoDeBackups.Backup> escolhidos) {
        if (encerrado || escolhidos.isEmpty()) {
            return;
        }
        String pasta = pastaDoProjeto.get();
        if (ctx.fabricaDeCliente() == null || pasta == null) {
            ctx.avisos().atencao("Sem conexão com o servidor para baixar agora. Espere a conexão voltar e tente de novo.");
            return;
        }
        PlanoDeBackups plano = PlanoDeBackups.de(pasta, escolhidos);
        if (!plano.recusados().isEmpty()) {
            ctx.avisos().atencao(plano.recusados().get(0).motivo() + (plano.recusados().size() > 1 ? " (e mais " + (plano.recusados().size() - 1) + ")" : ""));
        }
        if (plano.vazio()) {
            return;
        }
        Optional<Path> destino = ctx.escolhedor().escolherPastaParaBaixar(ServicosDeArquivos.pastaDeDownloadsPadrao());
        if (destino.isEmpty()) {
            return;
        }
        conferirEComecar(plano, destino.get());
    }

    private void conferirEComecar(PlanoDeBackups plano, Path destino) {
        Optional<String> falta = plano.faltaDeEspaco(destino);
        if (falta.isPresent()) {
            ctx.apresentador().mostrar(new DialogoConfirmar("Sem espaço no disco", "alerta", falta.get(), "Escolher outra pasta", false, true, ok -> {
                if (ok) {
                    Optional<Path> outra = ctx.escolhedor().escolherPastaParaBaixar(destino);
                    outra.ifPresent(p -> conferirEComecar(plano, p));
                }
            }));
            return;
        }
        if (plano.grande()) {
            ctx.apresentador().mostrar(new DialogoConfirmar("Backup grande", "alerta", "Isto soma " + Formato.fmtBytes(plano.bytes())
                + ". Pode demorar bastante e, se a conexão cair no meio, o app não retoma de onde parou: começa do zero. Continuar?", "Baixar", false, true, ok -> {
                    if (ok) {
                        comecar(plano, destino);
                    }
                }));
            return;
        }
        comecar(plano, destino);
    }

    private void comecar(PlanoDeBackups plano, Path destino) {
        emVoo++;
        situacao.setText("Preparando o download…");
        situacao.setVisible(true);
        linhaDoResultado.setVisible(false);
        Tarefas.rodar(this::garantirServico, s -> {
            emVoo--;
            if (encerrado) {
                return;
            }
            situacao.setVisible(false);
            long id = garantirFila().baixar(plano.entradas(), destino);
            destinos.put(id, destino);
            painel.definirAberto(true);
            revalidate();
        }, falha -> {
            emVoo--;
            situacao.setVisible(false);
            ctx.avisos().erro("Não consegui começar o download: " + MensagensDeErro.de(falha));
        });
    }

    private synchronized ServicoDeArquivos garantirServico() throws Exception {
        if (servico == null) {
            ClienteSftp c = ctx.fabricaDeCliente().criar();
            try {
                String casa = CaminhoRemoto.normalizar(c.caminhoCanonico("."));
                String raiz = ctx.servicos().memoria().raizDosProjetos(ctx.servidorId());
                Escopo escopo = Escopo.projeto(raiz, ctx.projeto(), casa).comBlindados(ctx.blindados());
                this.servico = new ServicoDeArquivos(c, escopo);
                this.cliente = c;
            } catch (Exception e) {
                c.close();
                throw e;
            }
        }
        return servico;
    }

    private FilaDeTransferencias garantirFila() {
        if (fila == null) {
            fila = new FilaDeTransferencias(() -> servico, this::decidirConflito, Fundo::naEdt, 2);
            desinscrever = fila.observar(retratos -> {
                painel.definir(retratos);
                aoMudar(retratos);
            });
        }
        return fila;
    }

    // =====================================================================================================
    // Resultado
    // =====================================================================================================

    private void aoMudar(List<Retrato> retratos) {
        for (Retrato r : retratos) {
            if (r.estado().terminou() && jaNotadas.putIfAbsent(r.id(), Boolean.TRUE) == null) {
                mostrarResultado(r);
            }
        }
    }

    private void mostrarResultado(Retrato r) {
        Path pasta = destinos.get(r.id());
        ultimaPasta = pasta;
        String texto;
        boolean temArquivo = pasta != null && (r.estado() == Estado.CONCLUIDA || r.estado() == Estado.COM_PROBLEMAS);
        switch (r.estado()) {
            case CONCLUIDA -> texto = "Pronto: " + r.mensagem() + " em " + (pasta == null ? "a pasta escolhida" : pasta) + ". O tamanho de cada arquivo foi conferido com o do servidor.";
            case COM_PROBLEMAS -> texto = "Terminou com problema: " + r.mensagem();
            case CANCELADA -> texto = "Download cancelado. O que estava pela metade foi apagado.";
            default -> texto = "Não deu certo: " + r.mensagem();
        }
        resultado.setText(texto);
        mostrarNaPasta.setVisible(temArquivo);
        linhaDoResultado.setVisible(true);
        revalidate();
        repaint();
    }

    private Decisao decidirConflito(FilaDeTransferencias.Conflito c) {
        CompletableFuture<Decisao> f = new CompletableFuture<>();
        decisoesPendentes.add(f);
        try {
            if (encerrado) {
                return new Decisao(Escolha.CANCELAR, false);
            }
            Fundo.naEdt(() -> ctx.apresentador().mostrar(new DialogoConflitoDeTransferencia(c, ctx.fuso(), f::complete)));
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Decisao(Escolha.CANCELAR, false);
        } catch (ExecutionException e) {
            return new Decisao(Escolha.CANCELAR, false);
        } finally {
            decisoesPendentes.remove(f);
        }
    }

    // =====================================================================================================
    // Fim de vida
    // =====================================================================================================

    /** Quantas transferências ainda andam (quem vai sair do app avisa o usuário). */
    public int emAberto() {
        return fila == null ? 0 : fila.emAberto();
    }

    /** A página saiu: cancela o que anda, responde "cancelar" a quem espera uma decisão e fecha o canal. */
    public void encerrar() {
        encerrado = true;
        for (CompletableFuture<Decisao> f : decisoesPendentes) {
            f.complete(new Decisao(Escolha.CANCELAR, false));
        }
        desinscrever.run();
        if (fila != null) {
            fila.close();
        }
        if (cliente != null) {
            try {
                cliente.close();
            } catch (RuntimeException e) {
                // já estava fechado
            }
        }
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    public FilaDeTransferencias fila() {
        return fila;
    }

    PainelDeTransferencias painelDeTransferencias() {
        return painel;
    }

    public String textoDoResultado() {
        return linhaDoResultado.isVisible() ? resultado.getText() : "";
    }

    public Botao botaoMostrarNaPasta() {
        return mostrarNaPasta;
    }

    public boolean mostraResultado() {
        return linhaDoResultado.isVisible();
    }

    public int emVoo() {
        return emVoo;
    }

    public List<String> destinosComoTexto() {
        List<String> l = new ArrayList<>();
        destinos.values().forEach(p -> l.add(p.toString()));
        return l;
    }
}
