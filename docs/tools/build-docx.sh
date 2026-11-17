#!/bin/sh
# Regenerates docs/DESIGN_RATIONALE.docx (the 5–8 page D4 deliverable) from docs/DESIGN_RATIONALE.md (requires pandoc >= 3.1).
# docs/DESIGN_RATIONALE_full.md is the extended version kept for reference and is not built.
# Usage (from the repository root): sh docs/tools/build-docx.sh
set -e
cd "$(dirname "$0")/.."
pandoc DESIGN_RATIONALE.md -f gfm -t docx --shift-heading-level-by=-1 -M lang=vi-VN \
  --reference-doc=tools/reference.docx --resource-path=. -o DESIGN_RATIONALE.docx
echo "Wrote docs/DESIGN_RATIONALE.docx"
