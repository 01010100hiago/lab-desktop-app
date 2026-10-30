#!/bin/sh
# Gera as imagens da galeria do README a partir dos testes de tela (dados inventados, com a moldura de janela do macOS).
#
# 1. ./gradlew test        grava as telas em build/galeria/<nome>.png (janelas e diálogos, tema claro e escuro)
# 2. scripts/gerar-galeria.sh   copia para docs/img/ e gera as miniaturas de 460 px de largura em docs/img/mini/ (WebP)
#
# Precisa do cwebp (macOS: brew install webp; Debian e Ubuntu: apt install webp).
set -eu
cd "$(dirname "$0")/.."
if [ ! -d build/galeria ]; then
  echo "Falta build/galeria: rode ./gradlew test antes." >&2
  exit 1
fi
command -v cwebp >/dev/null 2>&1 || { echo "Falta o cwebp (pacote webp)." >&2; exit 1; }
mkdir -p docs/img/mini
for f in build/galeria/*.png; do
  nome=$(basename "$f" .png)
  cp "$f" "docs/img/$nome.png"
  cwebp -quiet -q 82 -alpha_q 90 -resize 460 0 "$f" -o "docs/img/mini/$nome.webp"
done
echo "Pronto: $(ls docs/img/*.png | wc -l | tr -d ' ') imagens em docs/img e as miniaturas em docs/img/mini."
