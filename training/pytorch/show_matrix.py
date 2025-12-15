"""Print per-square contributions for a given FEN using the trained PyTorch model."""

import argparse

import chess
import torch

from train_eval_net import ChessEvalNet
from generate_dataset import board_to_features


def format_matrix(contrib):
    # contrib is length 64 in Field order (A8 index 0)
    rows = []
    for r in range(7, -1, -1):  # flip so white at bottom
        row_vals = []
        for f in range(8):
            idx = r * 8 + f
            row_vals.append(f"{contrib[idx]:6.3f}")
        rows.append(" ".join(row_vals))
    return "\n".join(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="Show per-square contributions for a FEN")
    parser.add_argument("--weights", type=str, default="training/pytorch/models/eval_net.pt")
    parser.add_argument("--fen", type=str, default="rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    parser.add_argument("--device", type=str, default="auto", help="cuda, cpu, or auto")
    parser.add_argument("--with-delta", action="store_true", help="Also compute counterfactual piece deltas")
    parser.add_argument("--h1", type=int, default=512)
    parser.add_argument("--h2", type=int, default=256)
    parser.add_argument("--h3", type=int, default=128)
    parser.add_argument("--h-global", type=int, default=128)
    parser.add_argument("--dropout", type=float, default=0.1)
    parser.add_argument("--normalize", action="store_true", help="Normalize by nominal piece value (P=1,N=B=3,R=5,Q=9)")
    parser.add_argument("--scale-piece", action="store_true", help="Scale displayed values by nominal piece value (P:1,N=4.1,B=4.1,R=5,Q=7.8,K=4.6 by default)")
    parser.add_argument("--piece-nominals", type=str, default="P:1,N:4.1,B:4.1,R:5,Q:7.8,K:4.6", help="Override piece values for scaling/normalizing, format like P:1,N:4.1,B:4.1,R:5,Q:7.8,K:4.6")
    parser.add_argument("--fancy", action="store_true", help="Print a Stockfish-style grid with pieces and values")
    parser.add_argument("--grid-mode", type=str, default="contrib", choices=["contrib", "delta"],
                        help="Grid values: 'contrib' (per-square head) or 'delta' (remove-piece delta)")
    args = parser.parse_args()

    dev = args.device
    if dev == "auto":
        dev = "cuda" if torch.cuda.is_available() else "cpu"
    device = torch.device(dev)

    state = torch.load(args.weights, map_location=device)
    model = ChessEvalNet(h1=args.h1, h2=args.h2, h3=args.h3, h_global=args.h_global, dropout=args.dropout).to(device)
    model.load_state_dict(state)
    model.eval()

    board = chess.Board(args.fen)
    feats = board_to_features(board).unsqueeze(0).to(device)
    with torch.no_grad():
        g, sq = model(feats)
        sq = sq.view(1, 64, 12)[0].cpu()
        piece_idx = feats[0, :-1].view(64, 12).argmax(dim=1).cpu()
        contrib = sq.gather(1, piece_idx.unsqueeze(1)).squeeze(1)

    contrib_list = []
    # Parse nominals
    default_nom = {"P": 1.0, "N": 3.0, "B": 3.0, "R": 5.0, "Q": 7.0, "K": None}
    for part in args.piece_nominals.split(","):
        if ":" in part:
            k, v = part.split(":")
            default_nom[k.strip().upper()] = float(v)

    nominal_map = {
        chess.PAWN: default_nom.get("P"),
        chess.KNIGHT: default_nom.get("N"),
        chess.BISHOP: default_nom.get("B"),
        chess.ROOK: default_nom.get("R"),
        chess.QUEEN: default_nom.get("Q"),
        chess.KING: default_nom.get("K"),
    }
    for sq_idx in chess.SQUARES:
        piece = board.piece_at(sq_idx)
        if piece:
            val = float(contrib[sq_idx])
            nominal = nominal_map.get(piece.piece_type)
            if args.normalize and nominal:
                val /= nominal
            if args.scale_piece and nominal:
                val *= nominal
            contrib_list.append(val)
        else:
            contrib_list.append(0.0)
    global_eval = g.item()

    print(f"FEN: {args.fen}")
    print(f"Global eval: {global_eval:.3f} pawns")
    total_contrib = sum(contrib_list)
    print(f"Sum of per-square contributions: {total_contrib:.3f} pawns (diff {global_eval - total_contrib:+.3f})")
    print("Per-square contributions (A8 on the first row):")
    print(format_matrix(contrib_list))

    # Top/bottom pieces
    scored = []
    for sq in chess.SQUARES:
        piece = board.piece_at(sq)
        if piece:
            scored.append((contrib_list[sq], piece.symbol(), chess.square_name(sq)))
    scored.sort(key=lambda x: x[0], reverse=True)
    print("\nTop 5 contributors:")
    for val, sym, sqname in scored[:5]:
        print(f"{sqname:>2} {sym:>2}: {val:+.3f}")
    print("\nBottom 5 contributors:")
    for val, sym, sqname in scored[-5:]:
        print(f"{sqname:>2} {sym:>2}: {val:+.3f}")

    deltas = []
    if args.with_delta or args.grid_mode == "delta":
        for sq_idx in chess.SQUARES:
            piece = board.piece_at(sq_idx)
            if piece is None or piece.piece_type == chess.KING:
                continue
            b2 = board.copy(stack=False)
            b2.remove_piece_at(sq_idx)
            f2 = board_to_features(b2).unsqueeze(0).to(device)
            with torch.no_grad():
                g2, _ = model(f2)
            delta = global_eval - g2.item()
            deltas.append((delta, piece.symbol(), chess.square_name(sq_idx), sq_idx))
        deltas.sort(key=lambda x: x[0], reverse=True)
        if args.with_delta:
            print("\nCounterfactual piece deltas (removing piece):")
            for val, sym, sqname, _ in deltas:
                print(f"{sqname:>2} {sym:>2}: {val:+.3f}")

    if args.fancy:
        print("\nFancy grid (piece on first line, value on second):")
        cell_w = 11
        sep = "+" + "+".join(["-" * cell_w] * 8) + "+"
        print(sep)
        delta_map = {sq_idx: val for val, _, _, sq_idx in deltas}
        for r in range(7, -1, -1):  # flip so white at bottom
            sym_cells = []
            val_cells = []
            for f in range(8):
                idx = r * 8 + f
                piece = board.piece_at(idx)
                if piece:
                    sym_cells.append(f"{piece.symbol():^{cell_w}}")
                    if args.grid_mode == "delta":
                        val = delta_map.get(idx, 0.0)
                    else:
                        val = contrib_list[idx]
                    val_cells.append(f"{val:^{cell_w}.2f}")
                else:
                    sym_cells.append(" " * cell_w)
                    val_cells.append(" " * cell_w)
            print("|" + "|".join(sym_cells) + "|")
            print("|" + "|".join(val_cells) + "|")
            print(sep)


if __name__ == "__main__":
    main()
