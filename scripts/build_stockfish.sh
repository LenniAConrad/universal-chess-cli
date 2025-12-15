#!/usr/bin/env bash
set -euo pipefail

# Build Stockfish from source and place the artifacts under engines/stockfish.
# Requires Debian/Ubuntu-style package management. Run from anywhere; paths are resolved from repo root.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="${ROOT_DIR}/engines/stockfish"
REPO_URL="https://github.com/official-stockfish/Stockfish.git"
ENGINE_VERSION=""

APT_PACKAGES=(
  git
  build-essential
)

ensure_packages() {
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y "${APT_PACKAGES[@]}"
  else
    echo "apt-get not found; install the following packages manually: ${APT_PACKAGES[*]}" >&2
    exit 1
  fi
}

clone_or_update() {
  if [ -d "${ENGINE_DIR}/.git" ]; then
    git -C "${ENGINE_DIR}" fetch --all --prune
    git -C "${ENGINE_DIR}" checkout master
    git -C "${ENGINE_DIR}" pull --ff-only
  else
    mkdir -p "${ROOT_DIR}/engines"
    git clone --depth 1 "${REPO_URL}" "${ENGINE_DIR}"
  fi
}

build() {
  cd "${ENGINE_DIR}/src"
  make build ARCH=x86-64-modern
}

detect_version() {
  if [ -x "${ENGINE_DIR}/src/build/stockfish" ]; then
    ENGINE_VERSION="$("${ENGINE_DIR}/src/build/stockfish" bench 2>/dev/null | head -n 1 | sed -E 's/^Stockfish\\s*//')"
  fi
  if [ -z "${ENGINE_VERSION}" ]; then
    ENGINE_VERSION="Stockfish"
  else
    ENGINE_VERSION="Stockfish ${ENGINE_VERSION}"
  fi
}

write_config() {
  local config_dir="${ROOT_DIR}/config"
  local config_file="${config_dir}/stockfish.engine.toml"
  mkdir -p "${config_dir}"
  cat > "${config_file}" <<EOF
#
# Auto-generated Stockfish engine config (path points to scripts/build_stockfish.sh output)
#

path                  = "${ENGINE_DIR}/src/build/stockfish"
name                  = "${ENGINE_VERSION}"
settings              = "hash=4096MB; threads=4; multipv=2"
isready               = "isready"
readyok               = "readyok"
searchDepth           = "go depth %d"
searchNodes           = "go nodes %d"
searchTime            = "go movetime %d"
setPosition           = "position fen %s"
stop                  = "stop"
newGame               = "ucinewgame"
setChess960           = "setoption name UCI_Chess960 value %b"
setHashSize           = "setoption name Hash value %d"
setMultiPivotAmount   = "setoption name multipv value %d"
setThreadAmount       = "setoption name threads value %d"
showUci               = "uci"
showWinDrawLoss       = "setoption name UCI_ShowWDL value %b"

setup = [
   "setoption name UCI_ShowWDL value true",
   "setoption name Hash value 4096",
   "setoption name threads value 4",
   "setoption name multipv value 2"
]
EOF
  echo "Wrote Stockfish config to: ${config_file}"
}

ensure_packages
clone_or_update
build
detect_version
write_config

echo "Stockfish built at: ${ENGINE_DIR}/src/build/stockfish"
