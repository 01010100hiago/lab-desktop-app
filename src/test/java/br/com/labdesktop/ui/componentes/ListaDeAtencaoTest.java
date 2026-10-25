package br.com.labdesktop.ui.componentes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.ListaDeAtencao.Item;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A lista de atenção: ordem por gravidade, teto de 5, estado vazio e clique. */
@ExtendWith(VerificaEdt.class)
class ListaDeAtencaoTest {

    private static Item item(Gravidade g, String t) {
        return new Item(g, t, "Destino", null);
    }

    @Test
    void ordenaDoMaisGraveAoMenosGraveSemEmbaralharOsIguais() {
        List<Item> r = ListaDeAtencao.ordenar(List.of(item(Gravidade.ATENCAO, "a1"), item(Gravidade.PERIGO, "p1"),
            item(Gravidade.ATENCAO, "a2"), item(Gravidade.PERIGO, "p2")));
        assertEquals(List.of("p1", "p2", "a1", "a2"), r.stream().map(Item::texto).toList());
    }

    @Test
    void limitaACincoMantendoOsMaisGraves() {
        List<Item> muitos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            muitos.add(item(Gravidade.ATENCAO, "a" + i));
        }
        for (int i = 0; i < 4; i++) {
            muitos.add(item(Gravidade.PERIGO, "p" + i));
        }
        List<Item> r = ListaDeAtencao.ordenar(muitos);
        assertEquals(5, r.size());
        assertEquals(5, ListaDeAtencao.MAXIMO);
        assertEquals(List.of("p0", "p1", "p2", "p3", "a0"), r.stream().map(Item::texto).toList());
    }

    @Test
    void semItensDizQueNadaPrecisaDeAtencao() {
        Visual.tema(Tema.CLARO);
        ListaDeAtencao l = Visual.naEdt(ListaDeAtencao::new);
        assertTrue(l.itens().isEmpty());
        assertTrue(Visual.naEdt(() -> TextosDeTela.de(l)).contains("Nada precisa de atenção agora"));
    }

    @Test
    void cadaLinhaTemEstadoEmTextoOFraseEODestinoEOCliqueAbreODestino() {
        Visual.tema(Tema.CLARO);
        AtomicReference<String> aberto = new AtomicReference<>();
        ListaDeAtencao l = Visual.naEdt(ListaDeAtencao::new);
        Visual.naEdt(() -> l.definir(List.of(
            new Item(Gravidade.ATENCAO, "Disco /: 78% cheio", "Máquina", () -> aberto.set("Máquina")),
            new Item(Gravidade.PERIGO, "Segurança: o SSH aceita senha", "Segurança", () -> aberto.set("Segurança")))));
        assertEquals(List.of("CRÍTICO: Segurança: o SSH aceita senha > Segurança", "ATENÇÃO: Disco /: 78% cheio > Máquina"), l.textos());
        Visual.naEdt(() -> l.abrir(0));
        assertEquals("Segurança", aberto.get());
        assertTrue(Visual.naEdt(() -> l.linha(0).isFocusable()), "alcançável pelo teclado");
        assertEquals("CRÍTICO: Segurança: o SSH aceita senha > Segurança", Visual.naEdt(() -> l.linha(0).getAccessibleContext().getAccessibleName()));
        Visual.salvar(Visual.renderizar(l, 520, Visual.alturaPreferida(l, 520) + 8), "lista-de-atencao");
    }
}
