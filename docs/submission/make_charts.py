"""Draws the report's performance charts from the numbers in docs/benchmarks.md.

usage: python docs/submission/make_charts.py   (writes docs/submission/assets/fig-*.png)
"""
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

OUT = Path(__file__).parent / "assets"
OUT.mkdir(exist_ok=True)

NOTCH, BASE = "#2a78d6", "#eb6834"  # categorical slots 1 and 2 (validated pair)
SURFACE, INK, INK2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e4e3df"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "font.family": "DejaVu Sans", "font.size": 10, "text.color": INK,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
})

thousands = FuncFormatter(lambda v, _: f"{v:,.0f}")


def finish(fig, ax, name, title, subtitle):
    ax.set_title(title, loc="left", fontsize=12, fontweight="bold", color=INK, pad=24)
    ax.text(0, 1.02, subtitle, transform=ax.transAxes, fontsize=9, color=INK2, va="bottom")
    fig.tight_layout()
    fig.savefig(OUT / name, dpi=200)
    plt.close(fig)


# 1. Seats filled: best-score-first greedy vs max-flow (share of seats).
pools = ["1,000", "10,000", "100,000"]
seats = [350, 3500, 35000]
greedy = [297, 2824, 28301]
flow = [350, 3500, 35000]
fig, ax = plt.subplots(figsize=(6.4, 3.7))
x = range(len(pools))
w = 0.36
gp = [100 * g / s for g, s in zip(greedy, seats)]
fp = [100 * f / s for f, s in zip(flow, seats)]
b1 = ax.bar([i - w / 2 - 0.01 for i in x], gp, w, color=BASE, label="Best score first (greedy)")
b2 = ax.bar([i + w / 2 + 0.01 for i in x], fp, w, color=NOTCH, label="Max-flow (Notch)")
for bars, vals in ((b1, gp), (b2, fp)):
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v + 1.5, f"{v:.0f}%", ha="center", fontsize=9, color=INK)
ax.set_xticks(list(x), [f"{p} candidates" for p in pools])
ax.set_ylim(0, 112)
ax.set_ylabel("Seats filled (%)")
ax.grid(axis="x", visible=False)
ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.13), ncol=2, fontsize=9)
finish(fig, ax, "fig-seats-filled.png", "Seats filled: max-flow vs best-score-first",
       "Synthetic campus drive, 5 roles, seats = 35% of the pool, threshold 70")

# 2. Max-flow running time: cold start vs greedy warm start (log scale).
n = [1000, 10000, 100000]
cold = [4.0, 151.2, 28546.4]
warm = [2.5, 61.9, 13550.0]
fig, ax = plt.subplots(figsize=(6.4, 3.4))
ax.plot(n, cold, color=BASE, lw=2, marker="o", ms=6, label="Cold start")
ax.plot(n, warm, color=NOTCH, lw=2, marker="o", ms=6, label="Warm start from greedy")
ax.set_xscale("log"); ax.set_yscale("log")
ax.xaxis.set_major_formatter(thousands); ax.yaxis.set_major_formatter(thousands)
ax.set_xlabel("Candidates in the drive"); ax.set_ylabel("Time (ms, log scale)")
ax.text(100000 * 1.08, cold[-1], "Cold start", color=INK, va="center", fontsize=9)
ax.text(100000 * 1.08, warm[-1] * 0.8, "Warm start", color=INK, va="center", fontsize=9)
ax.set_xlim(700, 300000)
ax.legend(frameon=False, loc="upper left", fontsize=9)
finish(fig, ax, "fig-maxflow-time.png", "Edmonds–Karp time: cold vs warm start",
       "Same result either way; the warm start cuts augmenting paths by about 80%")

# 3. Percentile bands: randomized Quickselect vs full sort.
n = [1000, 10000, 100000, 1000000]
qs = [0.64, 0.96, 4.05, 33.08]
srt = [0.40, 1.28, 12.87, 87.48]
fig, ax = plt.subplots(figsize=(6.4, 3.4))
ax.plot(n, srt, color=BASE, lw=2, marker="o", ms=6, label="Full sort, O(n log n)")
ax.plot(n, qs, color=NOTCH, lw=2, marker="o", ms=6, label="Randomized Quickselect, expected O(n)")
ax.set_xscale("log"); ax.set_yscale("log")
ax.xaxis.set_major_formatter(thousands)
ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}"))
ax.set_xlabel("Scores per skill"); ax.set_ylabel("Time (ms, log scale)")
ax.annotate("2.6× faster", (1000000, 33.08), xytext=(-70, -22), textcoords="offset points", fontsize=9, color=INK)
ax.legend(frameon=False, loc="upper left", fontsize=9)
finish(fig, ax, "fig-percentile-bands.png", "Percentile cut-offs (99/95/90/75th): Quickselect vs sort",
       "Sort wins below ~10,000 on constant factors; selection pulls ahead after")

# 4. Bloom filter: measured vs theoretical false-positive rate.
items = ["1,000", "10,000", "100,000"]
theory = [1.003, 1.004, 1.004]
measured = [1.017, 1.037, 0.983]
fig, ax = plt.subplots(figsize=(6.4, 3.3))
x = range(len(items))
b1 = ax.bar([i - w / 2 - 0.01 for i in x], theory, w, color=BASE, label="Theoretical")
b2 = ax.bar([i + w / 2 + 0.01 for i in x], measured, w, color=NOTCH, label="Measured (200,000 probes)")
for bars, vals in ((b1, theory), (b2, measured)):
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v + 0.03, f"{v:.3f}%", ha="center", fontsize=8.5, color=INK)
ax.set_xticks(list(x), [f"{i} items" for i in items])
ax.set_ylim(0, 1.35); ax.set_ylabel("False-positive rate (%)")
ax.grid(axis="x", visible=False)
ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.13), ncol=2, fontsize=9)
finish(fig, ax, "fig-bloom-fpr.png", "Bloom filter false positives, sized for 1%",
       "k = 7 hashes by double hashing; 117 KB at 100,000 items vs 6.1 MB of raw hashes")

print("charts written to", OUT)
