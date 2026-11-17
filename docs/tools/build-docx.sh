#!/bin/sh
# Regenerates docs/DESIGN_RATIONALE.docx from docs/DESIGN_RATIONALE.md (requires pandoc >= 3.1).
# Usage (from the repository root): sh docs/tools/build-docx.sh
set -e
cd "$(dirname "$0")/.."
pandoc DESIGN_RATIONALE.md -f gfm -t docx --shift-heading-level-by=-1 --toc --toc-depth=2 \
  -M toc-title="Mục lục" -M lang=vi-VN \
  --reference-doc=tools/reference.docx --lua-filter=tools/docx-layout.lua \
  --resource-path=. -o DESIGN_RATIONALE.docx
echo "Wrote docs/DESIGN_RATIONALE.docx"
