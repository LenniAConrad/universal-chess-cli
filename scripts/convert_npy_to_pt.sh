#!/usr/bin/env bash
set -euo pipefail

# Convert all *.features.npy/*.labels.npy pairs under training/pytorch/data into
# ~3GB torch shards. No single huge combined file is produced.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT}/training/pytorch/data"
TARGET_GB="${TARGET_GB:-3}"

# Convert the human-friendly TARGET_GB into bytes so Python can use it.
TARGET_BYTES="$(python - <<'PY'
import os
print(int(float(os.environ["TARGET_GB"]) * (1024 ** 3)))
PY
)"

OUT_DIR="${OUT_DIR}" TARGET_BYTES="${TARGET_BYTES}" python - <<'PY'
import glob
import os
import pathlib
import re
from typing import List

import numpy as np
import torch

out_dir = pathlib.Path(os.environ["OUT_DIR"])
target_bytes = int(os.environ["TARGET_BYTES"])


def numeric_stack_key(path: pathlib.Path):
    """Sort by the numeric part of the stack name (e.g., Stack-1234)."""
    match = re.search(r"(\d+)", path.name)
    return int(match.group(1)) if match else path.name


pairs = []
for feat_path_str in sorted(glob.glob(str(out_dir / "*.features.npy")), key=lambda p: numeric_stack_key(pathlib.Path(p))):
    feat_path = pathlib.Path(feat_path_str)
    if not feat_path.name.endswith(".features.npy"):
        continue
    shard_name = feat_path.name[: -len(".features.npy")]
    labels_path = feat_path.with_name(f"{shard_name}.labels.npy")
    if not labels_path.exists():
        print(f"Skipping {feat_path.name} (missing {labels_path.name})")
        continue
    pairs.append((feat_path, labels_path))

print(f"Found {len(pairs)} feature/label pairs.")
if not pairs:
    raise SystemExit(0)

current_feats: List[torch.Tensor] = []
current_labs: List[torch.Tensor] = []
current_bytes = 0
shard_idx = 0


def flush(curr_feats: List[torch.Tensor], curr_labs: List[torch.Tensor], idx: int):
    if not curr_feats:
        return [], [], idx
    features = torch.cat(curr_feats, dim=0)
    labels = torch.cat(curr_labs, dim=0)
    out_path = out_dir / f"dataset_shard_{idx:04d}.pt"
    torch.save({"features": features, "labels": labels}, out_path)
    size_gb = (
        features.element_size() * features.numel()
        + labels.element_size() * labels.numel()
    ) / (1024 ** 3)
    print(
        f"Wrote {out_path.name}: {features.shape[0]} samples, ~{size_gb:.2f} GB (target ~{target_bytes / 1024 ** 3:.2f} GB)"
    )
    return [], [], idx + 1


for feat_path, labels_path in pairs:
    feat_np = np.load(feat_path, mmap_mode="r")
    lab_np = np.load(labels_path, mmap_mode="r")
    pair_bytes = feat_np.nbytes + lab_np.nbytes

    # If adding this pair would exceed the target size, flush what we have first.
    if pair_bytes > target_bytes and current_feats:
        current_feats, current_labs, shard_idx = flush(current_feats, current_labs, shard_idx)
        current_bytes = 0

    if pair_bytes > target_bytes:
        # Single pair is larger than the target size; write it alone.
        current_feats = [torch.from_numpy(feat_np)]
        current_labs = [torch.from_numpy(lab_np)]
        current_bytes = pair_bytes
        current_feats, current_labs, shard_idx = flush(current_feats, current_labs, shard_idx)
        current_bytes = 0
        continue

    if current_bytes + pair_bytes > target_bytes:
        current_feats, current_labs, shard_idx = flush(current_feats, current_labs, shard_idx)
        current_bytes = 0

    current_feats.append(torch.from_numpy(feat_np))
    current_labs.append(torch.from_numpy(lab_np))
    current_bytes += pair_bytes
    del feat_np, lab_np

current_feats, current_labs, shard_idx = flush(current_feats, current_labs, shard_idx)
current_bytes = 0
print(f"Created {shard_idx} shards.")
PY
