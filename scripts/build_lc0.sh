#!/usr/bin/env bash
set -euo pipefail

# Build Leela Chess Zero (lc0) from source and place the artifacts under engines/lc0.
# Requires Debian/Ubuntu-style package management. Run from anywhere; paths are resolved from repo root.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="${ROOT_DIR}/engines/lc0"
REPO_URL="https://github.com/LeelaChessZero/lc0.git"
BUILD_DATE="$(date +%Y-%m-%d)"

APT_PACKAGES=(
  git
  build-essential
  cmake
  ninja-build
  pkg-config
  curl
  libprotobuf-dev
  protobuf-compiler
  libopenblas-dev
  libzstd-dev
  libgtest-dev
  libomp-dev
  libopencl-dev
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
  cmake -S "${ENGINE_DIR}" -B "${ENGINE_DIR}/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DUSE_OPENBLAS=ON
  cmake --build "${ENGINE_DIR}/build"
}

download_network() {
  local net_dir="${ENGINE_DIR}/networks"
  NET_PATH="${net_dir}/default.pb.gz"
  NET_NAME="default.pb.gz"
  local net_url="https://lczero.org/get_network?sha=latest"

  mkdir -p "${net_dir}"
  echo "Downloading LC0 network to ${NET_PATH} ..."
  curl -L --fail -o "${NET_PATH}" "${net_url}"
  echo "Network downloaded: ${NET_PATH}"
}

write_config() {
  local config_dir="${ROOT_DIR}/config"
  local config_file="${config_dir}/lc0.engine.toml"
  mkdir -p "${config_dir}"
  cat > "${config_file}" <<EOF
#
# Auto-generated LC0 engine config (path points to scripts/build_lc0.sh output)
#

path                  = "${ENGINE_DIR}/build/lc0"
name                  = "Leela Chess Zero (built ${BUILD_DATE}, net ${NET_NAME})"
settings              = "openblas backend; cache=4096MB; multipv=2; threads=4"
isready               = "isready"
readyok               = "readyok"
searchDepth           = "go depth %d"
searchNodes           = "go nodes %d"
searchTime            = "go movetime %d"
setPosition           = "position fen %s"
stop                  = "stop"
newGame               = "ucinewgame"
setChess960           = "setoption name UCI_Chess960 value %b"
setHashSize           = "setoption name Cache value %d"
setMultiPivotAmount   = "setoption name MultiPV value %d"
setThreadAmount       = "setoption name Threads value %d"
showUci               = "uci"
showWinDrawLoss       = "setoption name UCI_ShowWDL value %b"

setup = [
   "setoption name Backend value openblas",
   "setoption name Threads value 4",
   "setoption name Cache value 4096",
   "setoption name MultiPV value 2",
   "setoption name UCI_ShowWDL value true",
   "setoption name WeightsFile value ${NET_PATH}"
]
EOF
  echo "Wrote LC0 config to: ${config_file}"
}

ensure_packages
clone_or_update
build
download_network
write_config

echo "lc0 built at: ${ENGINE_DIR}/build/lc0"
