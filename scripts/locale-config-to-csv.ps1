# locale-config-to-csv.ps1
#
# Scans the RTP plugin baseline + every locale YAML config and emits a single
# sorted CSV row per leaf (scalar values and individual list items).
#
# Columns: relpath, language, parent_path, key, index, value, preceding_comment, blank_before
#   relpath          - path relative to rtp-plugin/src/main/resources (e.g. "messages.yml", "lang/de/messages.yml")
#   language         - "baseline" for files directly under resources/, otherwise the first lang/<dir> segment
#   parent_path      - dotted path of containing maps (e.g. "" for top-level, "worldInfo" for list items inside that key)
#   key              - the YAML key for scalars, or the parent map key for list items
#   index            - "" for scalars, 0-based index for list items
#   value            - the scalar value as written in the file, with surrounding quotes stripped
#   preceding_comment- '\n'-joined block of '#' comment lines immediately preceding the leaf (no trailing newline; '#' stripped, one leading space stripped)
#   blank_before     - "1" if a blank line precedes the leaf's comment block / leaf, else "0"
#
# Sister script: locale-config-from-csv.ps1 (regenerates the YAML files from an
# edited CSV). The pair is designed for round-trip on the current tree:
# running to-csv then from-csv yields an identical working directory.
#
# Notes / limitations:
#   - This intentionally handles the shape currently present in the repo:
#     top-level scalars, top-level lists of scalars, and one-level-nested maps
#     of scalars (e.g. "worldInfo:" followed by "  - \"...\"" or
#     "  key: value"). Indentation is two-space.
#   - Inline "# trailing comment" on a value line is captured as part of the
#     value column (kept verbatim after the value).
#   - The script reads/writes UTF-8 without BOM and preserves LF endings.

[CmdletBinding()]
param(
    [string] $ResourcesRoot,
    [string] $OutputCsv
)

$ErrorActionPreference = 'Stop'

# Resolve script directory robustly: $PSScriptRoot can be empty in some
# invocation contexts (e.g. when invoked indirectly). Fall back to
# $MyInvocation.MyCommand.Path, then to the current working directory.
$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    if ($MyInvocation.MyCommand.Path) {
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
}
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$repoRoot = Split-Path -Parent $scriptDir
if (-not $repoRoot) { $repoRoot = (Get-Location).Path }

if (-not $ResourcesRoot) {
    $ResourcesRoot = Join-Path $repoRoot 'rtp-plugin\src\main\resources'
}
if (-not $OutputCsv) {
    $OutputCsv = Join-Path $scriptDir 'out\locale-config.csv'
}

function Get-LangFromRelPath([string]$relpath) {
    $parts = $relpath -split '[\\/]'
    if ($parts.Length -ge 2 -and $parts[0] -eq 'lang') {
        # lang/<locale>/...; skip lang/<file>.lang.yml (locale = "" for baseline lang maps)
        if ($parts.Length -eq 2) { return 'baseline-langmap' }
        return $parts[1]
    }
    return 'baseline'
}

function Strip-Quotes([string]$s) {
    if ($null -eq $s) { return $s }
    $t = $s.Trim()
    if ($t.Length -ge 2) {
        if (($t.StartsWith('"') -and $t.EndsWith('"')) -or
            ($t.StartsWith("'") -and $t.EndsWith("'"))) {
            return $t.Substring(1, $t.Length - 2)
        }
    }
    return $t
}

# Scan target files: every *.yml under resources root and under lang/.
$files = @()
$files += Get-ChildItem -LiteralPath $ResourcesRoot -Filter '*.yml' -File |
    Where-Object { $_.Name -notin @('plugin.yml','language.yml') } |
    ForEach-Object { $_.FullName }
$files += Get-ChildItem -LiteralPath (Join-Path $ResourcesRoot 'lang') -Recurse -Filter '*.yml' -File |
    ForEach-Object { $_.FullName }

$rows = New-Object System.Collections.Generic.List[object]

foreach ($full in $files) {
    $relpath = $full.Substring($ResourcesRoot.Length).TrimStart('\','/').Replace('\','/')
    $language = Get-LangFromRelPath $relpath

    # Read raw bytes, decode as UTF-8 (no BOM), split on LF (also handle CRLF).
    $bytes = [System.IO.File]::ReadAllBytes($full)
    $text  = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $lines = $text -split "`r?`n"

    $pendingComments = New-Object System.Collections.Generic.List[string]
    $pendingBlank    = $false
    $currentParent   = ''   # dotted path; empty = top level
    $listIndex       = -1   # tracking lists under currentParent

    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]

        # Strip trailing whitespace only for matching; preserve for value capture.
        if ($line -match '^\s*$') {
            $pendingBlank = $true
            continue
        }

        # Pure comment line.
        if ($line -match '^\s*#') {
            # Strip leading whitespace + leading '#' + at most one space.
            $c = $line -replace '^\s*#', ''
            if ($c.StartsWith(' ')) { $c = $c.Substring(1) }
            $pendingComments.Add($c) | Out-Null
            continue
        }

        # Measure indentation (count leading spaces).
        $indentMatch = [regex]::Match($line, '^(\s*)')
        $indent = $indentMatch.Groups[1].Value.Length
        $stripped = $line.Substring($indent)

        # Reset parent if dedented to top-level non-list.
        if ($indent -eq 0 -and -not $stripped.StartsWith('- ')) {
            $currentParent = ''
            $listIndex = -1
        }

        # List item under a parent.
        if ($stripped.StartsWith('- ') -or $stripped -eq '-') {
            if ($currentParent -eq '') {
                # Top-level list item (rare; treat with empty parent).
            }
            $listIndex++
            $value = $stripped.Substring(1).TrimStart()
            $commentBlock = ($pendingComments -join "`n")
            $rows.Add([pscustomobject]@{
                relpath           = $relpath
                language          = $language
                parent_path       = $currentParent
                key               = ''     # list items have no key of their own
                index             = $listIndex
                value             = Strip-Quotes $value
                preceding_comment = $commentBlock
                blank_before      = [int]$pendingBlank
            }) | Out-Null
            $pendingComments.Clear()
            $pendingBlank = $false
            continue
        }

        # key: value  OR  key:
        $kv = [regex]::Match($stripped, '^([^:#\s][^:]*?)\s*:(?:\s*(.*))?$')
        if (-not $kv.Success) {
            # Unknown line shape; skip silently (preserves robustness).
            $pendingComments.Clear()
            $pendingBlank = $false
            continue
        }
        $key   = $kv.Groups[1].Value.Trim()
        $value = if ($kv.Groups[2].Success) { $kv.Groups[2].Value } else { '' }

        if ($value -eq '' -or $value -match '^\s*$') {
            # Map/list parent. Open scope.
            # Decide if it's a parent for nested keys/list items by peeking ahead is overkill;
            # treat any colon-only line as a parent. Still emit a row so empty-parent ordering
            # is preserved on regeneration.
            $commentBlock = ($pendingComments -join "`n")
            $rows.Add([pscustomobject]@{
                relpath           = $relpath
                language          = $language
                parent_path       = $currentParent
                key               = $key
                index             = ''
                value             = '__MAP_OR_LIST_PARENT__'
                preceding_comment = $commentBlock
                blank_before      = [int]$pendingBlank
            }) | Out-Null
            $pendingComments.Clear()
            $pendingBlank = $false
            if ($currentParent -eq '') {
                $currentParent = $key
            } else {
                $currentParent = "$currentParent.$key"
            }
            $listIndex = -1
            continue
        }

        # Scalar key: value.
        $commentBlock = ($pendingComments -join "`n")
        $rows.Add([pscustomobject]@{
            relpath           = $relpath
            language          = $language
            parent_path       = if ($indent -gt 0) { $currentParent } else { '' }
            key               = $key
            index             = ''
            value             = Strip-Quotes $value
            preceding_comment = $commentBlock
            blank_before      = [int]$pendingBlank
        }) | Out-Null
        $pendingComments.Clear()
        $pendingBlank = $false

        # Closing top-level scalar resets parent.
        if ($indent -eq 0) {
            $currentParent = ''
            $listIndex = -1
        }
    }
}

# Sort: relpath, parent_path, then preserve discovery order via stable secondary key.
# We sort by (relpath, language) primarily; within a file, keep original order
# by tagging rows with a sequence number prior to sorting.
$seq = 0
foreach ($r in $rows) { $r | Add-Member -NotePropertyName _seq -NotePropertyValue ($seq++) }

$sorted = $rows | Sort-Object relpath, _seq

# Write CSV (UTF-8 no BOM, LF).
$outDir = Split-Path -Parent $OutputCsv
if (-not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

$csv = $sorted |
    Select-Object relpath, language, parent_path, key, index, value, preceding_comment, blank_before |
    ConvertTo-Csv -NoTypeInformation

$joined = ($csv -join "`n") + "`n"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllBytes($OutputCsv, $utf8NoBom.GetBytes($joined))

Write-Host "Wrote $($sorted.Count) rows from $($files.Count) files -> $OutputCsv"
