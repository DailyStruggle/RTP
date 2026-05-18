# reconcile-locale-csvs.ps1
#
# For each scripts\out\locale-<lang>.csv, ensure it has the same set of
# (relpath-shape, parent_path, key, index) rows as scripts\out\baseline.csv:
#   - Add any baseline rows missing from the locale (seeded with the baseline
#     value, and the baseline preceding_comment/blank_before).
#   - Drop any locale rows whose (parent_path, key, index) tuple is not present
#     in the corresponding baseline file.
#   - Preserve existing locale values where the row already matches.
#
# Locale-to-baseline relpath mapping:
#   lang/<loc>/<file>.lang.yml      <- lang/<file>.lang.yml
#   lang/<loc>/<file>.yml           <- <file>.yml          (root config)
#   lang/<loc>/shape/<x>.lang.yml   <- lang/shape/<x>.lang.yml
#   lang/<loc>/vert/<x>.lang.yml    <- lang/vert/<x>.lang.yml
#
# Row identity key: (parent_path, key, index)

[CmdletBinding()]
param(
    [string] $OutDir
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    if ($MyInvocation.MyCommand.Path) {
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
}
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
if (-not $OutDir) { $OutDir = Join-Path $scriptDir 'out' }

$baselineCsv = Join-Path $OutDir 'baseline.tsv'
if (-not (Test-Path -LiteralPath $baselineCsv)) {
    throw "baseline.tsv not found at $baselineCsv. Run locale-files-to-csv.ps1 first."
}

$Script:TsvCols = @('relpath','parent_path','key','index','value','preceding_comment','blank_before','base_key')

function Read-Tsv([string]$path) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $lines = $text -split "`r?`n"
    $rows = New-Object System.Collections.Generic.List[object]
    $headerSeen = $false
    $PH = [char]0x1
    foreach ($line in $lines) {
        if ($line -eq '') { continue }
        if (-not $headerSeen) { $headerSeen = $true; continue }
        $parts = $line -split "`t", $Script:TsvCols.Count
        while ($parts.Count -lt $Script:TsvCols.Count) { $parts += '' }
        $decoded = foreach ($p in $parts) {
            $v = [string]$p
            $v = $v -replace '\\\\', [string]$PH
            $v = $v -replace '\\n', "`n"
            $v = $v -replace '\\t', "`t"
            $v = $v -replace [string]$PH, '\'
            $v
        }
        $rows.Add([pscustomobject]@{
            relpath           = $decoded[0]
            parent_path       = $decoded[1]
            key               = $decoded[2]
            index             = $decoded[3]
            value             = $decoded[4]
            preceding_comment = $decoded[5]
            blank_before      = $decoded[6]
            base_key          = $decoded[7]
        }) | Out-Null
    }
    return ,$rows
}

function Write-Tsv($rows, [string]$path) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append(($Script:TsvCols -join "`t")).Append("`n")
    foreach ($r in $rows) {
        $vals = foreach ($c in $Script:TsvCols) {
            $v = [string]$r.$c
            $v = $v -replace '\\', '\\\\'
            $v = $v -replace "`r`n", "`n"
            $v = $v -replace "`n", '\n'
            $v = $v -replace "`t", '\t'
            $v
        }
        [void]$sb.Append(($vals -join "`t")).Append("`n")
    }
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllBytes($path, $enc.GetBytes($sb.ToString()))
}

function Get-LocaleRelpath([string]$baselineRelpath, [string]$loc) {
    # lang/<file>.lang.yml -> lang/<loc>/<file>.lang.yml
    # lang/shape/x.lang.yml -> lang/<loc>/shape/x.lang.yml
    # lang/vert/x.lang.yml -> lang/<loc>/vert/x.lang.yml
    # <file>.yml -> lang/<loc>/<file>.yml
    if ($baselineRelpath -like 'lang/*') {
        $tail = $baselineRelpath.Substring('lang/'.Length)
        return "lang/$loc/$tail"
    }
    return "lang/$loc/$baselineRelpath"
}

function Get-BaselineRelpath([string]$localeRelpath, [string]$loc) {
    $prefix = "lang/$loc/"
    if (-not $localeRelpath.StartsWith($prefix)) { return $null }
    $tail = $localeRelpath.Substring($prefix.Length)
    # tail is e.g. "config.yml", "messages.lang.yml", "shape/CIRCLE.lang.yml"
    if ($tail -like '*.lang.yml' -or $tail -like 'shape/*' -or $tail -like 'vert/*') {
        return "lang/$tail"
    }
    return $tail   # root <file>.yml -> baseline <file>.yml
}

$baseline = Read-Tsv $baselineCsv

# Group baseline by relpath (preserve order).
$baselineByPath = [ordered]@{}
foreach ($r in $baseline) {
    if (-not $baselineByPath.Contains($r.relpath)) {
        $baselineByPath[$r.relpath] = New-Object System.Collections.Generic.List[object]
    }
    $baselineByPath[$r.relpath].Add($r) | Out-Null
}

# Pre-pass: ensure baseline lang/<file>.lang.yml contains an identity row for
# every top-level key in baseline <file>.yml. Keys missing here mean locales
# cannot translate them; downstream tests fail on "Baseline <file>.yml ships
# keys with no row in lang/<file>.lang.yml". Identity rows are the safe
# default; contributors can rename the right-hand side later.
$valueFiles = @($baselineByPath.Keys) | Where-Object { $_ -notlike 'lang/*' -and $_ -ne 'plugin.yml' -and $_ -ne 'language.yml' }
foreach ($vf in $valueFiles) {
    $langRelpath = "lang/" + ($vf -replace '\.yml$', '.lang.yml')
    if (-not $baselineByPath.Contains($langRelpath)) { continue }
    $existing = @{}
    foreach ($lr in $baselineByPath[$langRelpath]) {
        if ($lr.key -ne '' -and $lr.parent_path -eq '' -and $lr.index -eq '') {
            $existing[$lr.key] = $true
        }
    }
    foreach ($vr in $baselineByPath[$vf]) {
        # Only top-level scalar/map-parent keys get a langmap row.
        if ($vr.parent_path -ne '' -or $vr.key -eq '' -or $vr.index -ne '') { continue }
        if ($existing.ContainsKey($vr.key)) { continue }
        $baselineByPath[$langRelpath].Add([pscustomobject]@{
            relpath           = $langRelpath
            parent_path       = ''
            key               = $vr.key
            index             = ''
            value             = $vr.key   # identity
            preceding_comment = ''
            blank_before      = '0'
            base_key          = $vr.key
        }) | Out-Null
        $existing[$vr.key] = $true
    }
}

# Rewrite baseline.csv to include the added identity rows (so subsequent
# from-csv runs emit them too).
$baselineOut = New-Object System.Collections.Generic.List[object]
foreach ($p in $baselineByPath.Keys) {
    foreach ($r in $baselineByPath[$p]) { $baselineOut.Add($r) | Out-Null }
}
Write-Tsv $baselineOut $baselineCsv

$langRoot = Join-Path (Split-Path -Parent $scriptDir) 'rtp-plugin/src/main/resources/lang'
$locales = @(Get-ChildItem -LiteralPath $langRoot -Directory |
    Where-Object { $_.Name -notin @('shape','vert') } |
    Select-Object -ExpandProperty Name |
    Sort-Object)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($loc in $locales) {
    $localeCsv = Join-Path $OutDir "locale-$loc.tsv"
    if (-not (Test-Path -LiteralPath $localeCsv)) {
        Write-Host "Skipping $loc (no TSV)"
        continue
    }
    $localeRows = Read-Tsv $localeCsv

    # Index existing locale rows by (relpath, parent_path, key, index).
    $localeIdx = @{}
    foreach ($r in $localeRows) {
        $k = "{0}|{1}|{2}|{3}" -f $r.relpath, $r.parent_path, $r.key, $r.index
        $localeIdx[$k] = $r
    }

    # Build per-file langmap (baselineKey -> translatedKey) from the locale
    # TSV's `base_key`/`key` columns directly. `.lang.yml` files are no longer
    # exported (they are synthesized at regeneration time), so the mapping
    # lives in the row itself.
    # Keyed by baseline value-file relpath (e.g. "messages.yml") so we can
    # look it up when processing baseline rows below.
    $langmaps = @{}   # baseValueRelpath -> hashtable(baseKey -> translatedKey)
    foreach ($r in $localeRows) {
        if ($r.relpath -like "lang/$loc/*.lang.yml") { continue }   # ignore legacy
        if (-not $r.relpath.StartsWith("lang/$loc/")) { continue }
        if ([string]::IsNullOrEmpty($r.key)) { continue }
        if ([string]::IsNullOrEmpty($r.base_key)) { continue }
        if ($r.base_key -eq $r.key) { continue }                    # identity carries no info
        $tail = $r.relpath.Substring("lang/$loc/".Length)
        if ($tail -like 'shape/*' -or $tail -like 'vert/*') {
            $valueRelpath = "lang/$tail"
        } else {
            $valueRelpath = $tail
        }
        if (-not $langmaps.ContainsKey($valueRelpath)) {
            $langmaps[$valueRelpath] = @{}
        }
        if (-not $langmaps[$valueRelpath].ContainsKey($r.base_key)) {
            $langmaps[$valueRelpath][$r.base_key] = $r.key
        }
    }

    $out = New-Object System.Collections.Generic.List[object]
    $added = 0
    $kept  = 0

    foreach ($basePath in $baselineByPath.Keys) {
        $locPath = Get-LocaleRelpath $basePath $loc

        # Determine whether this baseline file is a value file (<file>.yml at
        # root, not under lang/). If so, translate keys via the langmap for it.
        $isValueFile = ($basePath -notlike 'lang/*')
        $langmap = $null
        if ($isValueFile -and $langmaps.ContainsKey($basePath)) {
            $langmap = $langmaps[$basePath]
        }

        foreach ($br in $baselineByPath[$basePath]) {
            # Determine the effective key in the locale file. For value files,
            # use langmap[baseKey] when present; otherwise fall back to baseKey.
            $effKey = $br.key
            if ($null -ne $langmap -and $br.key -ne '' -and $langmap.ContainsKey($br.key)) {
                $effKey = $langmap[$br.key]
            }
            # parent_path may also need translation if the parent is a mapped key.
            $effParent = $br.parent_path
            if ($null -ne $langmap -and $br.parent_path -ne '' -and $langmap.ContainsKey($br.parent_path)) {
                $effParent = $langmap[$br.parent_path]
            }

            # Compute baseline placeholder set for this value (tokens in [brackets]).
            $basePlaceholders = @{}
            foreach ($m in [regex]::Matches($br.value, '\[[^\]\s]+\]')) {
                $basePlaceholders[$m.Value] = $true
            }

            # Helper: detect locale value with non-baseline placeholders.
            $valueHasStalePlaceholder = {
                param($v)
                foreach ($m in [regex]::Matches($v, '\[[^\]\s]+\]')) {
                    if (-not $basePlaceholders.ContainsKey($m.Value)) { return $true }
                }
                return $false
            }

            # Look up existing locale row by the translated tuple first, then by baseline tuple as fallback.
            $lkEff  = "{0}|{1}|{2}|{3}" -f $locPath, $effParent, $effKey, $br.index
            $lkBase = "{0}|{1}|{2}|{3}" -f $locPath, $br.parent_path, $br.key, $br.index
            if ($localeIdx.ContainsKey($lkEff)) {
                $existing = $localeIdx[$lkEff]
                $useValue = $existing.value
                if (& $valueHasStalePlaceholder $useValue) { $useValue = $br.value }
                # Preserve locale's existing translated comment; fall back to
                # baseline English only when the locale row has no comment.
                $useComment = if ([string]::IsNullOrEmpty($existing.preceding_comment)) { $br.preceding_comment } else { $existing.preceding_comment }
                $out.Add([pscustomobject]@{
                    relpath           = $locPath
                    parent_path       = $effParent
                    key               = $effKey
                    index             = $br.index
                    value             = $useValue
                    preceding_comment = $useComment
                    blank_before      = $br.blank_before
                    base_key          = $br.base_key
                }) | Out-Null
                $kept++
            } elseif ($localeIdx.ContainsKey($lkBase) -and $effKey -ne $br.key) {
                # Locale file still uses English key but langmap was added later;
                # adopt translated key while keeping the locale's value.
                $existing = $localeIdx[$lkBase]
                $useValue = $existing.value
                if (& $valueHasStalePlaceholder $useValue) { $useValue = $br.value }
                $useComment = if ([string]::IsNullOrEmpty($existing.preceding_comment)) { $br.preceding_comment } else { $existing.preceding_comment }
                $out.Add([pscustomobject]@{
                    relpath           = $locPath
                    parent_path       = $effParent
                    key               = $effKey
                    index             = $br.index
                    value             = $useValue
                    preceding_comment = $useComment
                    blank_before      = $br.blank_before
                    base_key          = $br.base_key
                }) | Out-Null
                $kept++
            } else {
                # Missing row: seed from baseline (English value, translated key).
                $out.Add([pscustomobject]@{
                    relpath           = $locPath
                    parent_path       = $effParent
                    key               = $effKey
                    index             = $br.index
                    value             = $br.value
                    preceding_comment = $br.preceding_comment
                    blank_before      = $br.blank_before
                    base_key          = $br.base_key
                }) | Out-Null
                $added++
            }
        }
    }

    # Count dropped: rows in locale whose relpath was touched but tuple not in baseline,
    # OR whose relpath is not in the touched set at all (orphan file).
    $dropped = 0
    foreach ($r in $localeRows) {
        $basePath = Get-BaselineRelpath $r.relpath $loc
        if ($null -eq $basePath -or -not $baselineByPath.Contains($basePath)) {
            $dropped++
            continue
        }
        $brList = $baselineByPath[$basePath]
        $found = $false
        foreach ($br in $brList) {
            if ($br.parent_path -eq $r.parent_path -and $br.key -eq $r.key -and $br.index -eq $r.index) {
                $found = $true; break
            }
        }
        if (-not $found) { $dropped++ }
    }

    Write-Tsv $out $localeCsv

    Write-Host ("{0}: kept={1} added={2} dropped={3} total={4}" -f $loc, $kept, $added, $dropped, $out.Count)
}

Write-Host "Done."
