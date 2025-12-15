#!/usr/bin/env bash
set -euo pipefail

# Download Syzygy endgame tablebases from the Lichess mirror.
# Usage: ./scripts/download_tablebases.sh [destination_dir]

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_DEST="${ROOT_DIR}/tablebases/syzygy"
DEST_DIR_INPUT="${1:-$DEFAULT_DEST}"
BASE_URL="https://tablebase.lichess.ovh/tables/standard"
DOWNLOAD_LIST_URL="${BASE_URL}/download.txt"

declare -A MENU=(
  [1]="3-4-5 WDL|3-4-5-wdl|~170 MB"
  [2]="3-4-5 DTZ|3-4-5-dtz|~1.3 GB"
  [3]="6-men WDL|6-wdl|~2.5 GB"
  [4]="6-men DTZ|6-dtz|~15 GB"
  [5]="7-men WDL+DTZ|7|~18 TB (download with caution)"
)

declare -A MAX_PIECES=(
  [1]=5
  [2]=5
  [3]=6
  [4]=6
  [5]=7
)

PROBE_LIMIT=6

print_menu() {
  echo "Choose which Syzygy tablebases to download (space-separated, e.g. \"1 2\")."
  for i in 1 2 3 4 5; do
    IFS="|" read -r label _ size <<<"${MENU[$i]}"
    printf "  %s) %-12s (%s)\n" "$i" "$label" "$size"
  done
  echo "  a) all of the above"
}

normalize_dest_dir() {
  mkdir -p "$DEST_DIR_INPUT"
  DEST_DIR="$(cd "$DEST_DIR_INPUT" && pwd)"
}

fetch_download_list() {
  DOWNLOAD_LIST="$(curl -fsSL "$DOWNLOAD_LIST_URL")"
  if [ -z "$DOWNLOAD_LIST" ]; then
    echo "Failed to fetch download list from ${DOWNLOAD_LIST_URL}" >&2
    exit 1
  fi
}

gather_urls() {
  URL_FILE="$(mktemp)"
  trap 'rm -f "$URL_FILE"' EXIT

  local choice dir
  for choice in "${SELECTION[@]}"; do
    IFS="|" read -r _ dir _ <<<"${MENU[$choice]}"
    echo "$DOWNLOAD_LIST" | grep "/${dir}/" >>"$URL_FILE"
  done

  if [ ! -s "$URL_FILE" ]; then
    echo "No URLs collected; check your selections." >&2
    exit 1
  fi
}

compute_probe_limit() {
  local max=0
  for choice in "${SELECTION[@]}"; do
    local pieces="${MAX_PIECES[$choice]:-0}"
    if (( pieces > max )); then
      max=$pieces
    fi
  done
  PROBE_LIMIT=$(( max < 6 ? 6 : max ))
}

download_urls() {
  echo "Downloading to: $DEST_DIR"
  if command -v aria2c >/dev/null 2>&1; then
    aria2c \
      --continue=true \
      --max-connection-per-server=8 \
      --min-split-size=5M \
      --max-concurrent-downloads=4 \
      --auto-file-renaming=false \
      --dir="$DEST_DIR" \
      --input-file="$URL_FILE"
  elif command -v wget >/dev/null 2>&1; then
    wget -c -P "$DEST_DIR" -i "$URL_FILE"
  elif command -v curl >/dev/null 2>&1; then
    while IFS= read -r url; do
      [ -z "$url" ] && continue
      file="${url##*/}"
      echo "Downloading $file ..."
      curl -fL -C - --output "${DEST_DIR}/${file}" "$url"
    done <"$URL_FILE"
  else
    echo "Need one of: aria2c, wget, or curl." >&2
    exit 1
  fi
}

print_instructions() {
  cat <<EOF

Tablebases stored in: $DEST_DIR

To load them in your engine protocol, add these startup commands to your engine TOML
(\`config/default.engine.toml\`, \`config/stockfish.engine.toml\`, etc.) inside the
\`setup = [ ... ]\` block:
  "setoption name SyzygyPath value $DEST_DIR",
  "setoption name SyzygyProbeDepth value 1",
  "setoption name SyzygyProbeLimit value $PROBE_LIMIT"

Example snippet:
setup = [
   "setoption name UCI_ShowWDL value true",
   "setoption name Hash value 4000",
   "setoption name threads value 4",
   "setoption name multipv value 2",
   "setoption name SyzygyPath value $DEST_DIR",
   "setoption name SyzygyProbeDepth value 1",
   "setoption name SyzygyProbeLimit value $PROBE_LIMIT"
]

Restart any running engines after updating the protocol so the new path is picked up.
EOF
}

main() {
  print_menu
  read -rp "Selection: " answer
  if [ -z "$answer" ]; then
    echo "No selection provided; exiting."
    exit 1
  fi

  if [[ "$answer" =~ ^[aA]$ ]]; then
    SELECTION=(1 2 3 4 5)
  else
    read -r -a raw <<<"$answer"
    SELECTION=("${raw[@]}")
  fi

  # Validate input
  for choice in "${SELECTION[@]}"; do
    if [[ -z "${MENU[$choice]:-}" ]]; then
      echo "Invalid selection: $choice" >&2
      exit 1
    fi
  done

  normalize_dest_dir
  fetch_download_list
  gather_urls
  compute_probe_limit
  download_urls
  print_instructions
}

main "$@"
