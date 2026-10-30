package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AmbienteDeProcessos;
import br.com.labdesktop.processos.ControleDeProcessos;
import br.com.labdesktop.processos.Desfecho;
import br.com.labdesktop.processos.IdentidadeDoProcesso;
import br.com.labdesktop.processos.ObservadorDeProcesso;
import br.com.labdesktop.processos.Previa;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.processos.Resultado;
import br.com.labdesktop.ui.dialogos.DialogoProcesso;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.PaginaCloudflare;
import br.com.labdesktop.ui.telas.PaginaSeguranca;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * As imagens de galeria do README com a JANELA INTEIRA de um servidor de exemplo ("servidor-demo", dados inventados) em cada aba: Containers, Segurança, Máquina,
 * Projetos, Arquivos, Terminal, Cloudflare. Saem em {@code build/galeria/<nome>-claro.png} e {@code -escuro.png}, dentro da moldura de janela do macOS.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaServidorTest {

    @TempDir
    Path tmp;

    @Test
    void containers() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            c.abrir(Aba.CONTAINERS);
            c.esperarTextos("a aba Containers com as sessões", "Containers e sessões do Claude", "SESSÕES DO CLAUDE ABERTAS", "lab-claude-loja-web", "lab-claude-painel-vendas");
            String t = c.textos();
            assertTrue(t.contains("Rodando") && t.contains("imagens e"), t);
            assertFalse(t.contains("Nenhuma sessão"), t);
            c.fotografar("servidor-containers");
        }
    }

    @Test
    void seguranca() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            c.abrir(Aba.SEGURANCA);
            c.esperarTextos("a aba Segurança com a leitura", "TENTATIVAS · 24 H", "Aceita senha");
            Visual.naEdt(() -> ((PaginaSeguranca) c.tela().pagina(Aba.SEGURANCA)).mostrarSubAba(PaginaSeguranca.SUB_SSH));
            c.esperarTextos("o login por senha à vista", "CONFIGURAÇÃO DO SSH", "Login por senha", "Desligar o login por senha…", "BLOQUEIO AUTOMÁTICO DE IPS (FAIL2BAN)");
            String t = c.textos();
            assertTrue(t.contains("ligada: qualquer um pode tentar adivinhar uma senha"), t);
            c.fotografar("servidor-seguranca");
        }
    }

    @Test
    void maquina() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            c.abrir(Aba.MAQUINA);
            c.esperarTextos("a aba Máquina com o Git", "Git", "INSTALADO");
            assertFalse(c.textos().contains("NÃO INSTALADO"), "o servidor de exemplo tem o Git");
            c.fotografar("servidor-maquina");
        }
    }

    @Test
    void projetos() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            c.abrir(Aba.PROJETOS);
            Visual.naEdt(() -> c.tela().projetos().assinar()); // a janela de verdade assina a lista ao ficar à vista
            c.esperarTextos("a lista de projetos", "Novo projeto", "loja-web", "painel-vendas", "app-loja", "api-pedidos", "blog");
            String t = c.textos();
            assertFalse(t.contains("Nenhum projeto ainda"), t);
            c.fotografar("servidor-projetos");
        }
    }

    @Test
    void arquivos() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            ArquivosDeExemplo.popular(c.raizDoSftp);
            c.abrir(Aba.ARQUIVOS);
            GerenciadorDeArquivos g = Visual.naEdt(() -> c.tela().arquivos());
            Visual.naEdt(g::iniciar);
            esperarPasta(g, null);
            Visual.naEdt(() -> g.irPara(ArquivosDeExemplo.PASTA));
            esperarPasta(g, ArquivosDeExemplo.PASTA);
            c.esperarTextos("a pasta do projeto com os arquivos", "loja-web", "5 pastas, 5 arquivos", "Enviar", "Baixar", "Nova pasta");
            for (String nome : new String[] {"src", "public", "Dockerfile", "docker-compose.yml", "README.md", "package.json"}) { // a tabela é desenhada à mão: os nomes vêm do modelo
                assertTrue(Visual.naEdt(() -> g.entrada(nome)).isPresent(), "falta " + nome + " na pasta");
            }
            c.fotografar("servidor-arquivos");
        }
    }

    private static void esperarPasta(GerenciadorDeArquivos g, String caminho) {
        Espera.ate(CenaDoServidor.PRAZO, () -> "situação=" + Visual.naEdt(g::situacao) + " em voo=" + g.emVoo() + " caminho=" + Visual.naEdt(g::caminhoAtual),
            () -> Visual.naEdt(g::situacao) == GerenciadorDeArquivos.Situacao.NORMAL && g.emVoo() == 0 && (caminho == null || caminho.equals(Visual.naEdt(g::caminhoAtual))));
    }

    @Test
    void cloudflare() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp); CenarioDeCloudflare cf = new CenarioDeCloudflare(tmp.resolve("cf"))) {
            CloudflareDeExemplo.preparar(cf);
            c.lab.definirCloudflare(cf.servico);
            c.abrir(Aba.CLOUDFLARE);
            PaginaCloudflare pagina = Visual.naEdt(() -> c.tela().cloudflare());
            Visual.naEdt(pagina::recarregar); // a janela de verdade lê ao ficar à vista
            Espera.ate(CenaDoServidor.PRAZO, () -> "modo=" + Visual.naEdt(pagina::modo), () -> Visual.naEdt(pagina::carregou) && Visual.naEdt(pagina::modo) == PaginaCloudflare.Modo.CONECTADO);
            c.esperarTextos("a conta conectada com os endereços", "CONECTADA", "example.com", "loja.example.com", "painel.example.com");
            String t = c.textos();
            assertFalse(t.contains(CloudflareFalsa.TOKEN_CERTO), "o token nunca aparece");
            c.fotografar("servidor-cloudflare");
        }
    }

    /** Um controle de processos de mentira: só anota (nenhum sinal de verdade); a regra do que se oferece é a do app. */
    private static final class ControleDeExemplo implements ControleDeProcessos {
        final List<String> chamadas = new CopyOnWriteArrayList<>();

        @Override
        public AmbienteDeProcessos ambiente() {
            return AmbienteDeProcessos.servidor("labuser", Set.of());
        }

        @Override
        public Previa preparar(ProcessoAlvo alvo) {
            chamadas.add("preparar:" + alvo.pid());
            return new Previa(alvo, avaliar(alvo), new IdentidadeDoProcesso(alvo.pid(), "1000", "Thu Oct 29 18:05:42 2026", alvo.nome()), null);
        }

        @Override
        public Resultado encerrar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
            chamadas.add("encerrar:" + p.alvo().pid());
            return Resultado.de(Desfecho.ENCERRADO, "O processo " + p.alvo().pid() + " fechou.");
        }

        @Override
        public Resultado forcar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
            chamadas.add("forcar:" + p.alvo().pid());
            return Resultado.de(Desfecho.ENCERRADO, "à força");
        }

        @Override
        public Resultado reiniciar(Previa p, ObservadorDeProcesso o, Cancelamento c) {
            chamadas.add("reiniciar:" + p.alvo().pid());
            return Resultado.de(Desfecho.REINICIADO, "reiniciado");
        }
    }

    /**
     * A confirmação de "Encerrar" de um processo da aba Processos: o diálogo mostra nome, número, dono e hora de início ANTES de qualquer envio. É um diálogo, então a imagem é só
     * dele (moldura de diálogo); a janela de trás não entra.
     */
    @Test
    void processoEncerrar() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp)) {
            ControleDeExemplo controle = new ControleDeExemplo();
            c.lab.definirControleDeProcessos(controle);
            c.abrir(Aba.PROCESSOS);
            c.esperarTextos("a lista de processos", "Processos que mais gastam", "node", "postgres", "Encerrar");
            ProcessoAlvo alvo = new ProcessoAlvo(CenaDoServidor.PID_DO_PROCESSO_COMUM, "node", "labuser", null, null);
            Visual.naEdt(() -> c.painel.acoes().encerrarOuReiniciarProcesso(c.lab, alvo, "o próprio servidor (fora de container)", Acao.ENCERRAR, null));
            DialogoProcesso d = c.dialogos.ultimoComo(DialogoProcesso.class);
            Espera.ate(CenaDoServidor.PRAZO, () -> "fatos=" + Visual.naEdt(d::fatos), () -> !Visual.naEdt(d::fatos).isEmpty());
            List<String> fatos = Visual.naEdt(d::fatos);
            assertTrue(fatos.contains("Programa: node"), fatos.toString());
            assertTrue(fatos.contains("Número do processo (PID): " + CenaDoServidor.PID_DO_PROCESSO_COMUM), fatos.toString());
            assertTrue(fatos.stream().anyMatch(f -> f.startsWith("Começou em: 29/10/2026 às 18:05:42")), fatos.toString());
            assertTrue(Visual.naEdt(d::textoDaExplicacao).contains("SIGTERM"), Visual.naEdt(d::textoDaExplicacao));
            assertTrue(Visual.naEdt(() -> d.botaoEncerrar().isVisible()) && !Visual.naEdt(() -> d.botaoForcar().isVisible()), "só o Encerrar, sem Forçar");
            assertEquals(List.of("preparar:" + CenaDoServidor.PID_DO_PROCESSO_COMUM), controle.chamadas, "abrir a confirmação só lê: nada foi encerrado");
            CenaDoServidor.fotografarDialogo(d, "processo-encerrar");
        }
    }

    /**
     * O servidor que não volta: depois de várias falhas seguidas o app passa ao ritmo lento e a faixa diz desde quando, de quanto em quanto tempo tenta de novo, a causa e os três
     * botões. Aqui é de verdade (o SSH de exemplo sai do ar), com a lista de projetos da última leitura por baixo.
     */
    @Test
    void faixaDoRitmoLentoComConexaoRecusada() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp, new CenaDoServidor.Opcoes().ritmoLentoLogo())) {
            c.abrir(Aba.PROJETOS);
            Visual.naEdt(() -> c.tela().projetos().assinar());
            c.esperarTextos("a lista de projetos antes de cair", "loja-web", "painel-vendas");
            c.perderAConexao();
            faixaDoRitmoLento(c, "conexão recusada", "faixa-ritmo-lento");
        }
    }

    @Test
    void faixaDoRitmoLentoComOLoginDaAwsExpirado() throws Exception {
        try (CenaDoServidor c = new CenaDoServidor(tmp, new CenaDoServidor.Opcoes().nome("nuvem").viaSsm().ritmoLentoLogo())) {
            c.abrir(Aba.PROJETOS);
            Visual.naEdt(() -> c.tela().projetos().assinar());
            c.esperarTextos("a lista de projetos antes de cair", "loja-web", "painel-vendas");
            c.perderAConexao();
            faixaDoRitmoLento(c, "login da AWS expirou", "faixa-ritmo-lento-aws");
        }
    }

    private static void faixaDoRitmoLento(CenaDoServidor c, String causa, String imagem) {
        String nome = c.lab.estado().nome();
        Visual.naEdt(() -> c.tela().projetos().aoMudarEstado(c.lab.estado()));
        c.esperarTextos("a faixa do ritmo lento", "Sem conexão com " + nome + " desde", "Tentando de novo a cada 5 min", "Tentar agora", "Editar servidor", "Remover servidor");
        String t = c.textos();
        assertTrue(t.toLowerCase(java.util.Locale.ROOT).contains(causa.toLowerCase(java.util.Locale.ROOT)), "a causa (" + causa + ") na faixa: " + t);
        assertFalse(t.contains("tentativa"), "o número da tentativa não aparece no ritmo lento: " + t);
        c.fotografar(imagem);
    }
}
