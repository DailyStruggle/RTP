<#
.SYNOPSIS
  Recover translated `preceding_comment` blocks from a prior git commit
  and seed scripts/translations/<locale>.tsv with them.

.DESCRIPTION
  Earlier commits in this branch held native translations (comments AND
  values) for several locale .yml files. A round-trip cleanup pass
  overwrote the translated comments with English baseline. This script
  mines the prior commit (default: 1493285e~1) for translated comment
  prose and writes it into the per-locale translations sidecar, so the
  next `locale-files-from-csv.ps1` run restores the German/French/etc.
  comments without touching English values.

  Matching strategy: for each prior-commit `lang/<loc>/<file>.yml`, walk
  it in order, accumulating # comment lines until a `<key>:` line is hit.
  Then look up `<key>`'s baseline equivalent via the *current* per-locale
  TSV (which has a base_key column) and emit a translations row keyed by
  (locale, baseline_relpath, base_key, parent_path='', index='').

  Conservative behavior:
  - Only top-level keys (parent_path='') are mined. Nested rows are
    skipped (most translated prose lives at file-header / top-level).
  - Doc-tag-only comments (@type/@range/@unit/@default/@options/REQ-/ADR-)
    are filtered out (per project guidelines for user-facing content).
  - If a prior key cannot be mapped to a baseline key (no row in current
    per-locale TSV), the translation is dropped with a warning.
  - Existing rows in scripts/translations/<locale>.tsv are preserved;
    only missing keys are added.

.PARAMETER PriorCommit
  Git ref to mine. Defaults to 1493285e~1 (the commit before the cleanup
  pass that erased translated comments).

.PARAMETER ResourcesRoot
  Plugin resources dir. Resolved from $PSScriptRoot when omitted.

.PARAMETER OutDir
  scripts/out directory holding locale-<lang>.tsv. Resolved from
  $PSScriptRoot when omitted.

.PARAMETER TranslationsDir
  scripts/translations directory. Resolved from $PSScriptRoot when
  omitted.
#>
[CmdletBinding()]
param(
    [string]$PriorCommit,
    [string]$ResourcesRoot,
    [string]$OutDir,
    [string]$TranslationsDir
)

# --- Robust path defaults (mirrors locale-files-*.ps1) ---
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot }
             elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path }
             else { (Get-Location).Path }
$repoRoot = Split-Path -Parent $scriptDir
if (-not $PSBoundParameters.ContainsKey('ResourcesRoot') -or [string]::IsNullOrEmpty($ResourcesRoot)) {
    $ResourcesRoot = Join-Path $repoRoot 'rtp-plugin/src/main/resources'
}
if (-not $PSBoundParameters.ContainsKey('OutDir') -or [string]::IsNullOrEmpty($OutDir)) {
    $OutDir = Join-Path $scriptDir 'out'
}
if (-not $PSBoundParameters.ContainsKey('TranslationsDir') -or [string]::IsNullOrEmpty($TranslationsDir)) {
    $TranslationsDir = Join-Path $scriptDir 'translations'
}
if (-not $PSBoundParameters.ContainsKey('PriorCommit') -or [string]::IsNullOrEmpty($PriorCommit)) {
    $PriorCommit = '1493285e~1'
}

if (-not (Test-Path -LiteralPath $TranslationsDir)) {
    New-Item -ItemType Directory -Force -Path $TranslationsDir | Out-Null
}

# --- TSV escape helpers (same scheme as locale-files-from-csv.ps1) ---
function Escape-Cell([string]$s) {
    if ($null -eq $s) { return '' }
    $s = $s -replace '\\', '\\\\'
    $s = $s -replace "`r?`n", '\n'
    $s = $s -replace "`t", '\t'
    return $s
}
function Unescape-Cell([string]$s) {
    if ($null -eq $s) { return '' }
    $marker = [char]0x1A
    $s = $s -replace '\\\\', "$marker"
    $s = $s -replace '\\n', "`n"
    $s = $s -replace '\\t', "`t"
    $s = $s -replace "$marker", '\'
    return $s
}

# --- Filter: drop dev/contributor refs that should not appear in admin docs ---
function Is-DevOnlyCommentLine([string]$line) {
    if ($line -match 'REQ-RTP-[A-Z]') { return $true }
    if ($line -match '\bADR-\d') { return $true }
    if ($line -match '\bS-00\d\b') { return $true }
    if ($line -match '#\s*see\s+(ADR|REQ)') { return $true }
    return $false
}

# --- List of locale files to attempt restore on ---
$locales = @('cat','de','es','fr','ja','ko','nl','pt','ru','zh')
$stems = @('config','economy','effects','logging','messages','performance','safety','worlds','regions')

# --- Load each locale TSV to build (loc_key -> base_key) maps per file ---
$tsvLookup = @{}  # "$loc||$relpath" -> @{ loc_key -> base_key }
foreach ($loc in $locales) {
    $tsvPath = Join-Path $OutDir "locale-$loc.tsv"
    if (-not (Test-Path -LiteralPath $tsvPath)) { continue }
    $lines = Get-Content -LiteralPath $tsvPath -Encoding UTF8
    if ($lines.Count -lt 2) { continue }
    foreach ($line in ($lines | Select-Object -Skip 1)) {
        $p = $line -split "`t"
        if ($p.Count -lt 8) { continue }
        $rel = Unescape-Cell $p[0]
        $parent = Unescape-Cell $p[1]
        $key = Unescape-Cell $p[2]
        $base = Unescape-Cell $p[7]
        if ([string]::IsNullOrEmpty($key)) { continue }
        if (-not [string]::IsNullOrEmpty($parent)) { continue }
        # Map this locale relpath to its baseline relpath.
        $baseRel = $rel -replace "^lang/$loc/", ''
        if ($baseRel -eq $rel) {
            # No prefix stripped (must be a baseline path) -- skip non-locale rows.
            continue
        }
        # `messages.yml` lives at baseline root, `shape/x.lang.yml` lives under `lang/`.
        if (-not ($baseRel -like 'shape/*' -or $baseRel -like 'vert/*')) {
            $baseRel2 = $baseRel
        } else {
            $baseRel2 = "lang/$baseRel"
        }
        $mapKey = "$loc||$baseRel2"
        if (-not $tsvLookup.ContainsKey($mapKey)) { $tsvLookup[$mapKey] = @{} }
        $tsvLookup[$mapKey][$key] = $base
    }
}

# --- Load existing translations TSV per locale (preserve user edits) ---
function Load-TranslationsTsv([string]$loc) {
    $path = Join-Path $TranslationsDir "$loc.tsv"
    $existing = @{}
    if (-not (Test-Path -LiteralPath $path)) { return $existing }
    $lines = Get-Content -LiteralPath $path -Encoding UTF8
    if ($lines.Count -lt 2) { return $existing }
    foreach ($line in ($lines | Select-Object -Skip 1)) {
        $p = $line -split "`t"
        if ($p.Count -lt 5) { continue }
        $rel = Unescape-Cell $p[0]
        $parent = Unescape-Cell $p[1]
        $bk = Unescape-Cell $p[2]
        $idx = Unescape-Cell $p[3]
        $cm = Unescape-Cell $p[4]
        $k = "$rel||$parent||$bk||$idx"
        $existing[$k] = $cm
    }
    return $existing
}

function Save-TranslationsTsv([string]$loc, [hashtable]$rows) {
    $path = Join-Path $TranslationsDir "$loc.tsv"
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("relpath`tparent_path`tbase_key`tindex`tpreceding_comment`n")
    # Stable ordering: by relpath, then base_key (parent_path/index empty for now).
    $keys = $rows.Keys | Sort-Object
    foreach ($k in $keys) {
        $parts = $k -split '\|\|'
        $rel = $parts[0]; $parent = $parts[1]; $bk = $parts[2]; $idx = $parts[3]
        $cm = $rows[$k]
        [void]$sb.Append((Escape-Cell $rel)).Append("`t")
        [void]$sb.Append((Escape-Cell $parent)).Append("`t")
        [void]$sb.Append((Escape-Cell $bk)).Append("`t")
        [void]$sb.Append((Escape-Cell $idx)).Append("`t")
        [void]$sb.Append((Escape-Cell $cm)).Append("`n")
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllBytes($path, $utf8NoBom.GetBytes($sb.ToString()))
}

# --- Mine prior-commit content of a single file ---
# Returns: ordered list of @{ key=...; comment=...(multiline string) }
function Extract-CommentBlocks($content) {
    $blocks = New-Object System.Collections.ArrayList
    if ($null -eq $content) { return $blocks }
    if ($content -is [array]) {
        $lines = $content
    } else {
        $lines = [string]$content -split "`r?`n"
    }
    $pending = New-Object System.Collections.ArrayList
    foreach ($line in $lines) {
        if ($line -match '^\s*#' -or $line -match '^\s*$') {
            # Accumulate comments (and blank lines preserved inside the run).
            [void]$pending.Add($line)
            continue
        }
        # Non-comment line. Is it a top-level key?
        if ($line -match '^([A-Za-z_][\w]*)\s*:') {
            $k = $matches[1]
            # Build comment block from $pending. Strip leading/trailing blank lines.
            $cmLines = New-Object System.Collections.ArrayList
            foreach ($pl in $pending) { [void]$cmLines.Add($pl) }
            # Trim trailing blanks.
            while ($cmLines.Count -gt 0 -and $cmLines[$cmLines.Count - 1] -match '^\s*$') {
                $cmLines.RemoveAt($cmLines.Count - 1)
            }
            # Trim leading blanks.
            while ($cmLines.Count -gt 0 -and $cmLines[0] -match '^\s*$') {
                $cmLines.RemoveAt(0)
            }
            # Filter dev-only lines.
            $filtered = New-Object System.Collections.ArrayList
            foreach ($cl in $cmLines) {
                if (Is-DevOnlyCommentLine $cl) { continue }
                [void]$filtered.Add($cl)
            }
            $cm = ($filtered -join "`n")
            [void]$blocks.Add(@{ key = $k; comment = $cm })
            $pending = New-Object System.Collections.ArrayList
        } else {
            # Indented / list / continuation line: reset pending.
            $pending = New-Object System.Collections.ArrayList
        }
    }
    return $blocks
}

# --- Mine each locale x file ---
$summary = @{}
foreach ($loc in $locales) {
    $rows = Load-TranslationsTsv $loc
    $added = 0
    $skippedNoMap = 0
    $skippedEmpty = 0
    $skippedExisting = 0
    foreach ($stem in $stems) {
        $relpath = "lang/$loc/$stem.yml"
        $priorContent = git show "${PriorCommit}:rtp-plugin/src/main/resources/$relpath" 2>$null
        if (-not $priorContent) { continue }
        $blocks = Extract-CommentBlocks $priorContent
        if ($blocks.Count -eq 0) { continue }
        $baseRel = "$stem.yml"
        $mapKey = "$loc||$baseRel"
        if (-not $tsvLookup.ContainsKey($mapKey)) {
            Write-Host "  $loc/$stem.yml : no TSV lookup; skipping" -ForegroundColor DarkYellow
            continue
        }
        $locToBase = $tsvLookup[$mapKey]
        $fileHeaderEmitted = $false
        foreach ($b in $blocks) {
            $cm = $b.comment
            if ([string]::IsNullOrWhiteSpace($cm)) { $skippedEmpty++; continue }
            $locKey = $b.key
            # Resolve baseline key.
            if (-not $locToBase.ContainsKey($locKey)) {
                $skippedNoMap++
                continue
            }
            $baseKey = $locToBase[$locKey]
            $rowKey = "$baseRel||||$baseKey||"
            if ($rows.ContainsKey($rowKey)) { $skippedExisting++; continue }
            $rows[$rowKey] = $cm
            $added++
        }
    }
    if ($added -gt 0) {
        Save-TranslationsTsv $loc $rows
    }
    $summary[$loc] = @{ added = $added; nomap = $skippedNoMap; empty = $skippedEmpty; existing = $skippedExisting }
}

# --- Report ---
"Restore from $PriorCommit ->"
foreach ($loc in $locales) {
    $s = $summary[$loc]
    if (-not $s) { "  {0,-4} : (no data at prior commit)" -f $loc; continue }
    "  {0,-4} : added={1,3}  skipped_no_baseline_key={2,3}  skipped_empty={3,3}  skipped_existing={4,3}" -f `
        $loc, $s.added, $s.nomap, $s.empty, $s.existing
}
"done"
