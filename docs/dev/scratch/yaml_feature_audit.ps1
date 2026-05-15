# YAML feature audit for ADR-025 reversal (A12 in-house parser scoping).
# Scans all shipped .yml configs and reports usage of YAML features that would
# complicate a hand-rolled parser/serializer for the subset RTP actually uses.
# Run from repo root: pwsh -File docs/dev/scratch/yaml_feature_audit.ps1
param([switch]$Verbose)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$paths = @(
  "$root\rtp-plugin\src\main\resources",
  "$root\rtp-plugin\src\lite\resources",
  "$root\addons\RTP_ExampleAddon\src\main\resources",
  "$root\rtp-core\src\test\resources"
)
$files = @()
foreach ($p in $paths) { if (Test-Path $p) { $files += Get-ChildItem $p -Recurse -Filter *.yml -File } }
Write-Host "=== Files scanned: $($files.Count) ==="

function Section($title, $pattern, $cap = 20) {
    Write-Host ""
    Write-Host "--- $title ---"
    $hits = foreach ($f in $files) { Select-String -Path $f.FullName -Pattern $pattern -AllMatches }
    Write-Host ("Total hits: {0}" -f $hits.Count)
    $hits | Select-Object -First $cap | ForEach-Object {
        $rel = $_.Path.Substring($root.Length + 1)
        "{0}:{1}: {2}" -f $rel, $_.LineNumber, $_.Line.Trim()
    }
}

# Anchors `&name` / aliases `*name` — must appear after whitespace or `:` to count.
Section "ANCHORS / ALIASES" '(^|[\s:])[&*][A-Za-z_][\w-]*'

# Merge keys
Section "MERGE KEYS (<<:)" '<<\s*:'

# Block-scalar indicators (multi-line `|` or `>` ending a value line)
Section "BLOCK SCALAR INDICATORS (|, >)" ':\s*[|>][-+]?\s*(#.*)?$'

# Flow mappings / sequences at value position
Section "FLOW STYLE at value position" ':\s*[\[\{]'

# Explicit tags
Section "EXPLICIT TAGS (!Tag / !!type)" '(^|\s):\s*!'

# Multi-document separators
Section "DOCUMENT SEPARATORS" '^(---|\.\.\.)\s*$'

# Inline (same-line) trailing comments — these are explicitly OUT of A12 scope.
# Match: a line that has a key:value followed by whitespace + `#` (not a comment-only line).
Section "INLINE TRAILING COMMENTS (out-of-scope for A12)" '^\s*[^#\s][^#]*:\s+\S[^#]*\s+#\s' 40

# Block (above-line) comments — IN scope. Just count them per file for sanity.
Write-Host ""
Write-Host "--- BLOCK COMMENT DENSITY (above-line `# …` count per file, top 15 by count) ---"
$counts = foreach ($f in $files) {
    $c = (Select-String -Path $f.FullName -Pattern '^\s*#' -AllMatches).Count
    [pscustomobject]@{ File = $f.FullName.Substring($root.Length + 1); BlockCommentLines = $c }
}
$counts | Sort-Object BlockCommentLines -Descending | Select-Object -First 15 | Format-Table -AutoSize

# Quoting styles in shipped files — single vs double vs bare scalars at value position.
Write-Host ""
Write-Host "--- QUOTING STYLE COUNTS (rough) ---"
$dq = ($files | ForEach-Object { Select-String -Path $_.FullName -Pattern ':\s*"' -AllMatches }).Count
$sq = ($files | ForEach-Object { Select-String -Path $_.FullName -Pattern ":\s*'" -AllMatches }).Count
Write-Host ("Double-quoted scalar values: {0}" -f $dq)
Write-Host ("Single-quoted scalar values: {0}" -f $sq)
