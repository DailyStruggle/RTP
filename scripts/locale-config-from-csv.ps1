# locale-config-from-csv.ps1
#
# Regenerates the RTP plugin baseline + every locale YAML config from a CSV
# produced (and optionally edited) by locale-config-to-csv.ps1.
#
# Expected columns: relpath, language, parent_path, key, index, value,
#                   preceding_comment, blank_before
# (See locale-config-to-csv.ps1 header for column semantics.)
#
# Output contract:
#   - One file per distinct `relpath`, written under $ResourcesRoot.
#   - Rows belonging to a file are emitted in CSV order (the to-csv script
#     preserves discovery order via a hidden sequence column).
#   - String scalars are emitted double-quoted; integers, floats, booleans,
#     and the literal "" are emitted bare. List items are emitted with a
#     two-space indent under their parent key.
#   - The special sentinel value "__MAP_OR_LIST_PARENT__" produces an
#     opening "<key>:" line (no value) for nested maps / list parents.
#   - UTF-8 (no BOM), LF line endings.
#
# Intended workflow:
#   1. .\scripts\locale-config-to-csv.ps1
#   2. Edit scripts\out\locale-config.csv in a spreadsheet or text editor.
#   3. .\scripts\locale-config-from-csv.ps1
#   4. Inspect `git diff` and run :rtp-plugin:test --tests "*LocaleParityTest*".
#
# This pair is designed for the YAML shapes currently in the tree (top-level
# scalars, top-level lists, one-level-nested maps). Extending support for
# deeper nesting requires extending both scripts in lockstep.

[CmdletBinding()]
param(
    [string] $ResourcesRoot,
    [string] $InputCsv
)

$ErrorActionPreference = 'Stop'

# Resolve script directory robustly: $PSScriptRoot can be empty in some
# invocation contexts. Fall back to $MyInvocation.MyCommand.Path, then to
# the current working directory. Mirrors locale-config-to-csv.ps1.
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
if (-not $InputCsv) {
    $InputCsv = Join-Path $scriptDir 'out\locale-config.csv'
}

function Test-IsBareScalar([string]$v) {
    # Emit numerics and booleans bare (the Spanish content guard expects
    # numeric YAML for keys like fadeIn/stay/fadeOut/version). The plugin
    # config layer coerces string<->numeric on load, so this is safe.
    if ($null -eq $v) { return $false }
    if ($v -eq '') { return $false }
    if ($v -match '^-?\d+$')                 { return $true }   # integer
    if ($v -match '^-?\d+\.\d+$')            { return $true }   # float
    if ($v -match '^(true|false)$')          { return $true }   # boolean
    if ($v -match '^(null|~)$')              { return $true }   # null
    return $false
}

function Format-Scalar([string]$v) {
    if ($v -eq '__MAP_OR_LIST_PARENT__') {
        throw "Format-Scalar called with parent sentinel"
    }
    if ($v -eq '') {
        return '""'
    }
    if (Test-IsBareScalar $v) {
        return $v
    }
    # Double-quote and escape backslashes + double quotes.
    $escaped = $v.Replace('\', '\\').Replace('"', '\"')
    return '"' + $escaped + '"'
}

function Emit-Comment([System.Text.StringBuilder]$sb, [string]$comment, [string]$indent) {
    # Comments are stored verbatim in the CSV (including the leading '#'),
    # so we re-emit them as-is under the current indent without re-prefixing.
    if ([string]::IsNullOrEmpty($comment)) { return }
    foreach ($line in ($comment -split "`n")) {
        if ($line -eq '') {
            [void]$sb.Append("`n")
        } else {
            [void]$sb.Append($indent).Append($line).Append("`n")
        }
    }
}

if (-not (Test-Path -LiteralPath $InputCsv)) {
    throw "Input CSV not found: $InputCsv"
}

# Read CSV (Import-Csv handles quoting/escaping per RFC 4180-ish).
$rows = Import-Csv -LiteralPath $InputCsv

# Group by relpath; preserve first-seen order.
$grouped = [ordered]@{}
foreach ($r in $rows) {
    if (-not $grouped.Contains($r.relpath)) {
        $grouped[$r.relpath] = New-Object System.Collections.Generic.List[object]
    }
    $grouped[$r.relpath].Add($r) | Out-Null
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$writtenCount = 0

foreach ($relpath in $grouped.Keys) {
    $fileRows = $grouped[$relpath]
    $sb = New-Object System.Text.StringBuilder

    $currentParent = ''
    $firstRow = $true

    foreach ($r in $fileRows) {
        $parent = $r.parent_path
        $key    = $r.key
        $idx    = $r.index
        $val    = $r.value
        $comment= $r.preceding_comment
        $blank  = ($r.blank_before -eq '1' -or $r.blank_before -eq 1)

        # Determine indent based on parent_path depth.
        $depth = if ([string]::IsNullOrEmpty($parent)) { 0 } else { ($parent -split '\.').Length }
        $indent = '  ' * $depth

        if ($blank -and -not $firstRow) {
            [void]$sb.Append("`n")
        }

        Emit-Comment $sb $comment $indent

        if ($val -eq '__MAP_OR_LIST_PARENT__') {
            # Opening "<key>:" line for a nested map / list.
            [void]$sb.Append($indent).Append($key).Append(":`n")
        }
        elseif (-not [string]::IsNullOrEmpty($idx) -and $idx -ne '') {
            # List item under parent.
            [void]$sb.Append($indent).Append('- ').Append((Format-Scalar $val)).Append("`n")
        }
        else {
            # Scalar key: value.
            [void]$sb.Append($indent).Append($key).Append(': ').Append((Format-Scalar $val)).Append("`n")
        }

        $firstRow = $false
    }

    $outFull = Join-Path $ResourcesRoot ($relpath -replace '/', [System.IO.Path]::DirectorySeparatorChar)
    $outDir  = Split-Path -Parent $outFull
    if (-not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    }
    [System.IO.File]::WriteAllBytes($outFull, $utf8NoBom.GetBytes($sb.ToString()))
    $writtenCount++
}

Write-Host "Wrote $writtenCount files from $InputCsv"
