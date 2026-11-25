"""Renders the submission Markdown files to A4 PDFs (Mermaid diagrams included).

usage: python docs/submission/build_pdfs.py      (needs Python Playwright + Chromium, and internet
for the pinned marked and mermaid scripts)
writes docs/submission/pdf/*.pdf
"""
import json
import re
import tempfile
from pathlib import Path

from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
OUT = HERE / "pdf"
OUT.mkdir(exist_ok=True)
DOCS = ["A-proposal.md", "C-technical-report.md", "D-demo-evidence.md"]

CSS = """
@page { size: A4; margin: 16mm 16mm 18mm 16mm; }
:root { --ink:#1d1a24; --ink2:#55506a; --accent:#5b21b6; --rule:#e2ddef; --code:#f6f4fb; }
html { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
body { font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif; font-size: 9.6pt; line-height: 1.45;
       color: var(--ink); background: #fff; margin: 0; }
h1 { font-size: 19pt; line-height: 1.15; color: var(--accent); margin: 0 0 4pt; letter-spacing: -0.01em; }
h1 + p { color: var(--ink2); margin-top: 0; }
h2 { font-size: 12.5pt; color: var(--accent); margin: 14pt 0 5pt; padding-bottom: 2pt;
     border-bottom: 1.2pt solid var(--rule); break-after: avoid; }
h3 { font-size: 10.5pt; margin: 10pt 0 3pt; break-after: avoid; }
p { margin: 4pt 0 6pt; }
ul, ol { margin: 3pt 0 6pt; padding-left: 16pt; } li { margin: 1.5pt 0; }
table { border-collapse: collapse; width: 100%; margin: 5pt 0 8pt; font-size: 8.6pt; break-inside: auto; }
tr { break-inside: avoid; }
th { text-align: left; background: #f0ebfa; color: var(--ink); font-weight: 600; }
th, td { border: 0.6pt solid var(--rule); padding: 3pt 5pt; vertical-align: top; }
code { font-family: "Cascadia Mono", Consolas, monospace; font-size: 8.4pt; background: var(--code);
       padding: 0 2pt; border-radius: 2pt; }
pre { background: var(--code); border: 0.6pt solid var(--rule); border-radius: 4pt; padding: 6pt 8pt;
      font-size: 7.9pt; line-height: 1.35; white-space: pre-wrap; break-inside: avoid; margin: 5pt 0 8pt; }
pre code { background: none; padding: 0; font-size: inherit; }
img { display: block; max-width: 100%; margin: 6pt auto 2pt; border-radius: 3pt; }
img.shot { max-width: 80%; max-height: 92mm; object-fit: contain; border: 0.6pt solid var(--rule); }
img.fig { max-width: 74%; }
body.compact { font-size: 9pt; line-height: 1.38; } body.compact h2 { margin-top: 10pt; } body.compact table { font-size: 8.2pt; }
img + em, p > em:only-child { color: var(--ink2); }
.mermaid { text-align: center; margin: 6pt 0 8pt; break-inside: avoid; }
.mermaid svg { max-width: 100%; height: auto; max-height: 300px; }
strong { font-weight: 650; }
a { color: var(--accent); text-decoration: none; }
"""

TEMPLATE = """<!doctype html><html><head><meta charset="utf-8"><base href="{base}">
<style>{css}</style>
<script src="https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
</head><body><main id="doc"></main>
<script>
const src = {md};
const r = new marked.Renderer();
const baseCode = r.code.bind(r);
r.code = (code, lang) => lang === "mermaid" ? `<div class="mermaid">${{code}}</div>` : baseCode(code, lang);
document.getElementById("doc").innerHTML = marked.parse(src, {{ renderer: r, gfm: true }});
for (const img of document.querySelectorAll("img")) img.classList.add(img.src.includes("fig-") ? "fig" : "shot");
if ({compact}) document.body.classList.add("compact");
mermaid.initialize({{ startOnLoad: false, theme: "neutral", fontFamily: "Segoe UI, Arial",
  flowchart: {{ htmlLabels: true, curve: "basis" }}, sequence: {{ useMaxWidth: true }} }});
mermaid.run({{ querySelector: ".mermaid" }}).then(() => {{ window.__done = true; }})
  .catch(e => {{ window.__err = String(e); window.__done = true; }});
</script></body></html>"""

FOOTER = ('<div style="font-size:7.5pt;color:#77718a;width:100%;padding:0 16mm;display:flex;'
          'justify-content:space-between;font-family:Segoe UI,Arial"><span>{title}</span>'
          '<span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>')

with sync_playwright() as p, tempfile.TemporaryDirectory() as tmp:
    browser = p.chromium.launch()
    for name in DOCS:
        md = (HERE / name).read_text(encoding="utf-8")
        title = re.search(r"^# (.+)$", md, re.M).group(1)
        html = TEMPLATE.format(base=HERE.as_uri() + "/", css=CSS, md=json.dumps(md), compact="true" if name.startswith("A-") else "false")
        page_file = Path(tmp) / (name + ".html")
        page_file.write_text(html, encoding="utf-8")
        page = browser.new_page()
        page.goto(page_file.as_uri(), wait_until="networkidle")
        page.wait_for_function("window.__done === true", timeout=60000)
        err = page.evaluate("window.__err || null")
        if err:
            raise SystemExit(f"{name}: mermaid failed: {err}")
        page.wait_for_function("[...document.images].every(i => i.complete && i.naturalWidth > 0)", timeout=30000)
        pdf = OUT / name.replace(".md", ".pdf")
        page.pdf(path=str(pdf), format="A4", print_background=True, display_header_footer=True,
                 header_template="<div></div>", footer_template=FOOTER.format(title=title.replace("&", "&amp;")),
                 margin={"top": "16mm", "bottom": "18mm", "left": "16mm", "right": "16mm"})
        pages = len(re.findall(rb"/Type\s*/Page[^s]", pdf.read_bytes()))
        print(f"{pdf.name}: {pages} pages, {pdf.stat().st_size / 1e6:.2f} MB")
        page.close()
    browser.close()
