package br.com.labdesktop.projetos;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cria o projeto NO PRÓPRIO AMBIENTE (o computador local): a pasta na raiz dos projetos com os MESMOS arquivos que o agente escreve
 * ({@link GabaritoDoProjeto}, sem guia para IA: o Claude não existe no laboratório local) e a rede {@code <nome>_default} com os rótulos do compose.
 * Mesmas regras do agente: nome pela regra dele, nome reservado e nome que já existe recusados com a mesma frase.
 *
 * <p>Se um arquivo não puder ser gravado no meio, a pasta que ESTA chamada criou é apagada (nada de projeto pela metade); uma pasta que já existia
 * nunca é tocada. A rede é um extra: se o Docker não responde, vira aviso (o compose a cria na primeira vez que o projeto sobe).
 */
public final class CriadorNoAmbiente implements CriadorDeProjeto {

    private static final Duration PRAZO_DA_REDE = Duration.ofSeconds(30);
    private static final int PERMISSAO_DO_ARQUIVO = 0644;

    private final AmbienteDeProjetos ambiente;
    private final Clock relogio;

    public CriadorNoAmbiente(AmbienteDeProjetos ambiente) {
        this(ambiente, Clock.systemDefaultZone());
    }

    public CriadorNoAmbiente(AmbienteDeProjetos ambiente, Clock relogio) {
        this.ambiente = ambiente;
        this.relogio = relogio;
    }

    @Override
    public Resultado criar(String nome, Boolean guiaIa) throws FalhaDoProjeto {
        if (!NomeDoProjeto.valido(nome)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, NomeDoProjeto.fraseDeRecusa(nome));
        }
        if (NomeDoProjeto.reservado(nome)) {
            throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Esse nome é reservado pela infraestrutura do lab.");
        }
        List<String> avisos = new ArrayList<>();
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            String raiz = ambiente.raizDosProjetos();
            String pasta = CaminhoRemoto.juntar(raiz, nome);
            Pastas.garantir(c, raiz);
            if (c.existe(pasta)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Já existe um projeto com esse nome.");
            }
            c.criarPasta(pasta);
            try {
                for (Map.Entry<String, String> arquivo : GabaritoDoProjeto.arquivos(nome, LocalDate.now(relogio)).entrySet()) {
                    c.gravarAtomico(CaminhoRemoto.juntar(pasta, arquivo.getKey()), arquivo.getValue().getBytes(StandardCharsets.UTF_8), PERMISSAO_DO_ARQUIVO);
                }
            } catch (ErroSftp e) {
                apagarQuieto(c, pasta); // só a pasta que esta chamada acabou de criar
                throw Falhas.de(e, "criar os arquivos do projeto");
            }
        } catch (ErroSftp e) {
            throw Falhas.de(e, "criar o projeto");
        }
        avisos.addAll(criarRede(nome));
        return new Resultado(nome, avisos);
    }

    private List<String> criarRede(String nome) {
        try {
            ResultadoExec r = ambiente.executor().executar(ComandosDeProjeto.criarRede(nome), Map.of(), new byte[0], PRAZO_DA_REDE);
            if (r.status() != 0) {
                String que = r.stderr().toLowerCase(java.util.Locale.ROOT);
                if (que.contains("already exists")) {
                    return List.of(); // a rede já estava lá: está tudo certo
                }
                return List.of("A rede " + GabaritoDoProjeto.redeDoProjeto(nome) + " não foi criada (o Docker recusou). Sem problema: o Docker a cria na primeira vez que o projeto subir.");
            }
            return List.of();
        } catch (IOException e) {
            return List.of("A rede " + GabaritoDoProjeto.redeDoProjeto(nome) + " não foi criada (o Docker não respondeu). Sem problema: o Docker a cria na primeira vez que o projeto subir.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("A criação da rede foi interrompida; o Docker a cria na primeira vez que o projeto subir.");
        }
    }

    private static void apagarQuieto(ClienteSftp c, String pasta) {
        try {
            c.apagar(pasta, true);
        } catch (ErroSftp e) {
            // a pasta ficou lá: o usuário a vê na lista e a apaga
        }
    }

    @Override
    public Optional<Boolean> existe(String nome) {
        if (!NomeDoProjeto.valido(nome)) {
            return Optional.of(false);
        }
        try (ClienteSftp c = ambiente.abrirArquivos()) {
            return Optional.of(c.existe(CaminhoRemoto.juntar(ambiente.raizDosProjetos(), nome)));
        } catch (ErroSftp e) {
            return Optional.empty();
        }
    }
}
