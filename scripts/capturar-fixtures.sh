#!/usr/bin/env bash
# Grava respostas REAIS do agente do painel em src/test/resources/lab-real/, as fixtures do teste de contrato
# (ARQUITETURA 12). O mock do front NÃO vale como contrato: ele diverge do agente
# (mem em texto, datas com macro @+5d3h). Estas respostas, sim: cada DTO do app é lido a partir delas em
# ContratoComFixturesReaisTest.
#
# SOMENTE LEITURA: cada rota é um GET feito de dentro do lab, por `ssh <alvo> curl 127.0.0.1:7000/...`. Nada é
# escrito no servidor. Use um servidor de teste.
# Rode com o agente já atualizado: GET /api/versao só existe no agente novo.
#
# Uso:   scripts/capturar-fixtures.sh [alvo-ssh]        (padrão: lab, um alias do seu ~/.ssh/config; passe o alvo SSH que você usa)
#        scripts/capturar-fixtures.sh --ajuda
#
# O que grava (um arquivo por rota):
#   versao.json            GET /api/versao
#   limites.json           GET /api/limites
#   projetos.json          GET /api/projetos
#   servidor.json          GET /api/servidor
#   projetos/lab.json      GET /api/projetos/lab                   (a sessão raiz, forma própria)
#   projetos/<comum>.json  GET /api/projetos/<primeiro projeto com sessão do Claude aberta>
#   projetos/<sem>.json    GET /api/projetos/<primeiro projeto sem sessão>
#
# As fixtures contêm nomes e descrições REAIS de projetos: a pasta fica no .gitignore e não deve ser publicada. Antes de
# compartilhar, leia o conteúdo. O script já tira o que seria sensível sem mudar a FORMA do JSON: os textos `readme` e `status`
# do detalhe viram um marcador, e a URL de login do Claude perde a query (o `state` do OAuth).
set -euo pipefail

if [[ "${1:-}" == "--ajuda" || "${1:-}" == "-h" ]]; then
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 0
fi

ALVO="${1:-lab}"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINO="$RAIZ/src/test/resources/lab-real"
BASE="http://127.0.0.1:7000"
OPCOES_SSH=(-o BatchMode=yes -o ConnectTimeout=10)
NOME_VALIDO='^[a-z0-9][a-z0-9-]{0,48}$'

command -v node >/dev/null 2>&1 || { echo "erro: o node é preciso para validar e redigir o JSON (brew install node)." >&2; exit 1; }

# Valida o JSON e redige o que seria sensível, sem mudar a forma. Lê stdin, escreve stdout.
redigir() {
  node -e '
    let texto = "";
    process.stdin.on("data", (c) => { texto += c; }).on("end", () => {
      const j = JSON.parse(texto); // não é JSON? o script para aqui, antes de gravar qualquer coisa
      if (j && typeof j === "object" && !Array.isArray(j)) {
        for (const k of ["readme", "status"]) {
          if (typeof j[k] === "string") { j[k] = "(redigido pelo capturar-fixtures.sh: " + j[k].length + " caracteres)"; }
        }
      }
      const semQuery = (o) => {
        if (Array.isArray(o)) { o.forEach(semQuery); return; }
        if (!o || typeof o !== "object") { return; }
        for (const [k, v] of Object.entries(o)) {
          if (k === "url" && typeof v === "string" && /^https:\/\/claude\.(ai|com)\//.test(v)) { o[k] = v.split(/[?#]/)[0]; }
          else { semQuery(v); }
        }
      };
      semQuery(j);
      process.stdout.write(JSON.stringify(j, null, 2) + "\n");
    });
  '
}

# capturar <rota> <arquivo relativo a lab-real/>
capturar() {
  local rota="$1" arquivo="$2" bruto
  mkdir -p "$(dirname "$DESTINO/$arquivo")"
  bruto="$(mktemp "$DESTINO/.captura.XXXXXX")"
  if ssh "${OPCOES_SSH[@]}" "$ALVO" "curl -fsS --max-time 30 '$BASE$rota'" > "$bruto" && redigir < "$bruto" > "$bruto.json"; then
    chmod 600 "$bruto.json"
    mv "$bruto.json" "$DESTINO/$arquivo"
    rm -f "$bruto"
    echo "gravado: ${DESTINO#"$RAIZ"/}/$arquivo  ($rota)"
  else
    rm -f "$bruto" "$bruto.json"
    echo "erro: não consegui capturar $rota de '$ALVO' (o painel novo já foi publicado, com /api/versao? o ssh funciona" \
      "sem senha?)." >&2
    return 1
  fi
}

# O primeiro projeto (fora o lab) que atende ao critério, lido do projetos.json que acabou de ser gravado.
escolher() { # escolher true|false
  node -e '
    const l = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
    const quer = process.argv[2] === "true";
    const p = (l.projetos || []).find((x) => x && x.nome && x.nome !== "lab" && (x.sessaoClaude === true) === quer);
    if (p) { process.stdout.write(p.nome); }
  ' "$DESTINO/projetos.json" "$1"
}

echo "Capturando de '$ALVO' (somente GET, dentro do servidor)..."
capturar /api/versao versao.json
capturar /api/limites limites.json
capturar /api/projetos projetos.json
capturar /api/servidor servidor.json
capturar /api/projetos/lab projetos/lab.json

for par in "true:com sessão aberta" "false:sem sessão"; do
  nome="$(escolher "${par%%:*}")"
  if [[ -z "$nome" ]]; then
    echo "aviso: nenhum projeto ${par#*:} em /api/projetos; fixture pulada." >&2
  elif [[ ! "$nome" =~ $NOME_VALIDO ]]; then
    echo "aviso: nome de projeto fora do padrão ('$nome'); fixture pulada." >&2
  else
    capturar "/api/projetos/$nome" "projetos/$nome.json"
  fi
done

echo
echo "Pronto. Confira o que entrou ('git diff --stat src/test/resources/lab-real') e rode ./gradlew test:"
echo "ContratoComFixturesReaisTest agora lê estas respostas em vez de pular."
