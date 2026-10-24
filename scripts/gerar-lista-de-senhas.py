#!/usr/bin/env python3
"""Gera src/main/resources/br/com/labdesktop/seguranca/senhas-comuns.txt, a lista de senhas e palavras
comuns que a régua da senha-mestra (ForcaDeSenha) usa para recusar senha de dicionário.

Fontes (todas em texto, só dados; nada é executado):
  - as listas do zxcvbn (Dropbox, licença MIT) que vêm no jar com.nulab-inc:zxcvbn:1.9.0 do Maven Central:
    passwords.txt (30 mil senhas mais comuns, em ordem de frequência), english_wikipedia.txt, us_tv_and_film.txt,
    male_names.txt, female_names.txt e surnames.txt;
  - scripts/senhas-ptbr.txt, o complemento em português do Brasil escrito à mão.

Cada palavra fica com o MENOR rank que tiver em qualquer lista (rank 1 = a mais comum). Palavras com menos de 3
caracteres saem (a régua só procura trechos de 3 ou mais). Os acentos são removidos.

Uso (o diretório das listas é o conteúdo de com/nulabinc/zxcvbn/matchers/dictionaries/ do jar, extraído em
uma pasta nova e vazia):

    python3 -I scripts/gerar-lista-de-senhas.py <pasta-das-listas-do-zxcvbn>

A origem exata do jar (SHA-256) fica registrada no cabeçalho do arquivo gerado.
"""
import sys
import unicodedata
from pathlib import Path

LISTAS_DO_ZXCVBN = [
    "passwords.txt",
    "english_wikipedia.txt",
    "us_tv_and_film.txt",
    "male_names.txt",
    "female_names.txt",
    "surnames.txt",
]
JAR = "com.nulab-inc:zxcvbn:1.9.0 (sha256 38efaebab09144eb1f4d4c9ff650e79df875a8d6c4539c105b079a606bb7db34)"
MINIMO = 3
SAIDA = Path(__file__).resolve().parent.parent / "src/main/resources/br/com/labdesktop/seguranca/senhas-comuns.txt"
PTBR = Path(__file__).resolve().parent / "senhas-ptbr.txt"


def dobrar(palavra: str) -> str:
    sem_acento = "".join(c for c in unicodedata.normalize("NFKD", palavra) if not unicodedata.combining(c))
    return sem_acento.lower().strip()


def ler(caminho: Path):
    """As palavras do arquivo, na ordem (a posição é o rank)."""
    rank = 0
    for linha in caminho.read_text(encoding="utf-8").splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#"):
            continue
        rank += 1
        yield rank, dobrar(linha)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    pasta = Path(sys.argv[1])
    melhor = {}
    for nome in LISTAS_DO_ZXCVBN:
        for rank, palavra in ler(pasta / nome):
            if len(palavra) >= MINIMO and " " not in palavra and "\t" not in palavra:
                if palavra not in melhor or rank < melhor[palavra]:
                    melhor[palavra] = rank
    for rank, palavra in ler(PTBR):
        if len(palavra) >= MINIMO and " " not in palavra and "\t" not in palavra:
            if palavra not in melhor or rank < melhor[palavra]:
                melhor[palavra] = rank
    ordenadas = sorted(melhor.items(), key=lambda par: (par[1], par[0]))
    SAIDA.parent.mkdir(parents=True, exist_ok=True)
    with SAIDA.open("w", encoding="utf-8", newline="\n") as f:
        f.write("# Senhas e palavras comuns para a régua da senha-mestra (ForcaDeSenha). GERADO por\n")
        f.write("# scripts/gerar-lista-de-senhas.py: não edite à mão.\n")
        f.write("# Formato: palavra<TAB>rank (1 = a mais comum). Sem acentos, minúsculas, 3 ou mais caracteres.\n")
        f.write("# Origem: listas do zxcvbn (Dropbox, MIT) do jar " + JAR + ",\n")
        f.write("# mais o complemento scripts/senhas-ptbr.txt. Cada palavra fica com o menor rank entre as listas.\n")
        for palavra, rank in ordenadas:
            f.write(f"{palavra}\t{rank}\n")
    print(f"{len(ordenadas)} palavras em {SAIDA}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
