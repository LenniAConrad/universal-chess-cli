#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mkdir -p out

javac --release 17 -d out $(find src -name "*.java")

java -cp out chess.images.render.GithubBanner "${@}"
