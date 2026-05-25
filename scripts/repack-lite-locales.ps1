# repack-lite-locales.ps1
#
# Build a Lite-shaped lang/ tree under rtp-plugin/src/lite/resources/lang/
# by pruning rtp-plugin/src/main/resources/lang/<loc>/ down to the keys
# Lite's reduced English baselines actually carry.
#
# Run order in the locale pipeline:
#   1. .\scripts\locale-files-to-csv.ps1
#   2. .\scripts\reconcile-locale-csvs.ps1
#   3. (translate scripts/out/locale-<lang>.tsv)
#   4. .\scripts\locale-files-from-csv.ps1
#   5. .\scripts\repack-lite-locales.ps1     <-- this script
#   6. .\gradlew :rtp-plugin:shadowLiteJar
#
# Notes:
# - The set of files to prune is discovered from rtp-plugin/src/lite/resources/
#   (top-level *.yml). Files Lite does not ship (e.g. economy.yml,
#   integrations.yml, language.yml) are simply not emitted in the lite
#   locale tree.
# - messages.yml and lang/<loc>/messages.lang.yml are copied through
#   unchanged: per ADR-024 (2026-05-11(b) amendment) lite ships the full
#   messages.yml; unreachable keys are harmless.
# - The script preserves the lookup chain documented in .junie/AGENTS.md
#   "Locale Parity Maintenance":
#     localeLangMap.get(key) -> baselineLangMap.get(key) -> identity key
# - Per .junie/AGENTS.md "Markdown Encoding Hygiene": all output is written
#   UTF-8 (no BOM), LF line endings.

$ErrorActionPreference = 'Stop'

$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot '..')
$proMain     = Join-Path $repoRoot 'rtp-plugin\src\main\resources'
$liteMain    = Join-Path $repoRoot 'rtp-plugin\src\lite\resources'
$proLangDir  = Join-Path $proMain 'lang'
$liteLangOut = Join-Path $liteMain 'lang'

if (-not (Test-Path $liteMain))   { throw "Lite resources dir not found: $liteMain" }
if (-not (Test-Path $proLangDir)) { throw "Pro lang dir not found: $proLangDir" }

# --- helpers ----------------------------------------------------------------

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-LfUtf8 {
    param([string]$Path, [string]$Text)
    # Normalize line endings to LF and ensure trailing newline.
    $normalized = ($Text -replace "`r`n", "`n") -replace "`r", "`n"
    if (-not $normalized.EndsWith("`n")) { $normalized += "`n" }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8NoBom)
}

# Parse the top-level keys of a YAML file. We treat any line of the form
# `<name>:` at column 0 (no leading whitespace) as a top-level key. This
# matches the in-house RtpYamlReader's convention.
function Get-TopLevelKeys {
    param([string]$YamlPath)
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    if (-not (Test-Path $YamlPath)) { return $set }
    foreach ($line in [System.IO.File]::ReadAllLines($YamlPath)) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_\-]*)\s*:(\s|$)') {
            [void]$set.Add($Matches[1])
        }
    }
    return $set
}

# Parse a <file>.lang.yml into a translatedKey -> baseKey map.
# Row format: `base_key: "translated_key"` (the right-hand side may be
# unquoted; we strip quotes if present).
function Read-LocaleLangMap {
    param([string]$LangMapPath)
    $localeToBase = @{}
    if (-not (Test-Path $LangMapPath)) { return $localeToBase }
    foreach ($line in [System.IO.File]::ReadAllLines($LangMapPath)) {
        if ($line.TrimStart().StartsWith('#')) { continue }
        if ($line -match '^([A-Za-z_][A-Za-z0-9_\-]*)\s*:\s*(.+?)\s*$') {
            $base    = $Matches[1]
            $rhs     = $Matches[2]
            # Strip an inline trailing comment if any (rare in *.lang.yml).
            $rhs = ($rhs -replace '\s+#.*$','').Trim()
            $rhs = $rhs.Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($rhs)) {
                $localeToBase[$rhs] = $base
            }
        }
    }
    return $localeToBase
}

# Prune a <file>.lang.yml by base_key (left column).
function Repack-LangMap {
    param(
        [string]$SrcLangMap,
        [string]$DstLangMap,
        $AllowedBaseKeys # HashSet[string]
    )
    $out = New-Object System.Text.StringBuilder
    foreach ($line in [System.IO.File]::ReadAllLines($SrcLangMap)) {
        $trimmed = $line.TrimStart()
        if ([string]::IsNullOrWhiteSpace($line) -or $trimmed.StartsWith('#')) {
            [void]$out.AppendLine($line)
            continue
        }
        if ($line -match '^([A-Za-z_][A-Za-z0-9_\-]*)\s*:') {
            if ($AllowedBaseKeys.Contains($Matches[1])) {
                [void]$out.AppendLine($line)
            }
            continue
        }
        # Anything else (extremely rare in *.lang.yml): pass through.
        [void]$out.AppendLine($line)
    }
    Write-LfUtf8 -Path $DstLangMap -Text $out.ToString()
}

# Prune a translated <file>.yml. Block ownership rule:
#   - The block of top-level key K runs from the start of its leading
#     comment/blank run (the consecutive `#`/blank lines directly above K)
#     through K's line and any subsequent indented (children) lines, up to
#     the start of the next top-level key's leading-comment run.
#   - Top-level header comments at the very start of the file (before any
#     top-level key) are always kept.
#   - K is kept iff localeLangMap.get(K) (or identity K when absent) is in
#     $AllowedBaseKeys.
function Repack-LocaleValueFile {
    param(
        [string]$SrcYaml,
        [string]$DstYaml,
        [hashtable]$LocaleToBase,  # may be empty
        $AllowedBaseKeys           # HashSet[string]
    )

    $lines = [System.IO.File]::ReadAllLines($SrcYaml)

    # Pass 1: identify top-level key line indices.
    $topIdx = New-Object System.Collections.Generic.List[int]
    $topKey = @{}
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $ln = $lines[$i]
        if ($ln -match '^([A-Za-z_][A-Za-z0-9_\-]*)\s*:(\s|$)') {
            $topIdx.Add($i) | Out-Null
            $topKey[$i] = $Matches[1]
        }
    }

    # Pass 2: compute block start index for each top-level key (climb backwards
    # over consecutive comment/blank lines, stopping at the previous block's end).
    $blockStart = @{}
    for ($k = 0; $k -lt $topIdx.Count; $k++) {
        $i = $topIdx[$k]
        $prevTopEnd = if ($k -eq 0) { -1 } else { $topIdx[$k-1] }
        $s = $i
        while ($s - 1 -gt $prevTopEnd) {
            $cand = $lines[$s - 1]
            if ([string]::IsNullOrWhiteSpace($cand) -or $cand.TrimStart().StartsWith('#')) {
                $s = $s - 1
            } else {
                break
            }
        }
        # Trim leading blank lines off the front of the leading-comment run so
        # we do not steal a separator that belonged to the previous block.
        while ($s -lt $i -and [string]::IsNullOrWhiteSpace($lines[$s])) { $s++ }
        $blockStart[$i] = $s
    }

    # Pass 3: compute block end index for each top-level key.
    # End = (next top key's blockStart - 1), or last line index for the final key.
    $blockEnd = @{}
    for ($k = 0; $k -lt $topIdx.Count; $k++) {
        $i = $topIdx[$k]
        if ($k -lt $topIdx.Count - 1) {
            $nextI = $topIdx[$k + 1]
            $blockEnd[$i] = $blockStart[$nextI] - 1
        } else {
            $blockEnd[$i] = $lines.Length - 1
        }
    }

    # Pass 4: emit.
    $out = New-Object System.Text.StringBuilder

    # File header: everything before the first top-level key (if any) goes
    # through unchanged. If the first top-level key's blockStart > 0, those
    # lines are its leading comments and belong to its block, not the header.
    $firstTopIdx = if ($topIdx.Count -gt 0) { $topIdx[0] } else { $lines.Length }
    $firstBlockStart = if ($topIdx.Count -gt 0) { $blockStart[$firstTopIdx] } else { $lines.Length }
    for ($i = 0; $i -lt $firstBlockStart; $i++) {
        [void]$out.AppendLine($lines[$i])
    }

    foreach ($idx in $topIdx) {
        $localeKey = $topKey[$idx]
        $baseKey = if ($LocaleToBase -and $LocaleToBase.ContainsKey($localeKey)) {
            $LocaleToBase[$localeKey]
        } else {
            $localeKey  # identity fallback
        }
        if (-not $AllowedBaseKeys.Contains($baseKey)) { continue }

        $s = $blockStart[$idx]
        $e = $blockEnd[$idx]
        for ($j = $s; $j -le $e; $j++) {
            [void]$out.AppendLine($lines[$j])
        }
    }

    Write-LfUtf8 -Path $DstYaml -Text $out.ToString()
}

# --- discover lite-shipped baseline files -----------------------------------

# Top-level *.yml files Lite ships (excluding plugin.yml, which has no locale).
$liteTopLevel = Get-ChildItem -Path $liteMain -Filter '*.yml' -File |
    Where-Object { $_.Name -ne 'plugin.yml' } |
    Select-Object -ExpandProperty Name

# regions/*.yml under lite (each is its own per-region config; the locale
# tree mirrors the same path).
$liteRegions = @()
$liteRegionsDir = Join-Path $liteMain 'regions'
if (Test-Path $liteRegionsDir) {
    $liteRegions = Get-ChildItem -Path $liteRegionsDir -Filter '*.yml' -File |
        ForEach-Object { "regions/$($_.Name)" }
}

# --- wipe and rebuild -------------------------------------------------------

if (Test-Path $liteLangOut) { Remove-Item -Recurse -Force $liteLangOut }
[void](New-Item -ItemType Directory -Path $liteLangOut -Force)

$locales = Get-ChildItem -Path $proLangDir -Directory |
    Where-Object { $_.Name -notin @('shape','vert') } |
    Select-Object -ExpandProperty Name
Write-Host "Repacking lite locale tree for $($locales.Count) locales: $($locales -join ', ')"

foreach ($loc in $locales) {
    $proLoc  = Join-Path $proLangDir $loc
    $liteLoc = Join-Path $liteLangOut $loc
    [void](New-Item -ItemType Directory -Path $liteLoc -Force)

    # Copy through the shape/ and vert/ subdirectories untouched. Their
    # rename-map files (shape/<x>.lang.yml, vert/<x>.lang.yml) have no
    # sibling value file and are not key-pruned.
    foreach ($sub in @('shape','vert')) {
        $srcSub = Join-Path $proLoc $sub
        if (Test-Path $srcSub) {
            $dstSub = Join-Path $liteLoc $sub
            Copy-Item -Recurse -Force $srcSub $dstSub
        }
    }

    foreach ($rel in $liteTopLevel) {
        # messages.yml + messages.lang.yml: copy through (lite ships full).
        if ($rel -eq 'messages.yml') {
            foreach ($name in @('messages.yml','messages.lang.yml')) {
                $src = Join-Path $proLoc $name
                if (Test-Path $src) { Copy-Item $src (Join-Path $liteLoc $name) -Force }
            }
            continue
        }

        $proValPath = Join-Path $proLoc $rel
        if (-not (Test-Path $proValPath)) { continue }

        $liteBaselinePath = Join-Path $liteMain $rel
        $allowed = Get-TopLevelKeys $liteBaselinePath
        if ($allowed.Count -eq 0) { continue }

        $dstValPath = Join-Path $liteLoc $rel

        $langSibling = $rel -replace '\.yml$', '.lang.yml'
        $proLangPath = Join-Path $proLoc $langSibling
        $dstLangPath = Join-Path $liteLoc $langSibling

        if (Test-Path $proLangPath) {
            Repack-LangMap -SrcLangMap $proLangPath -DstLangMap $dstLangPath -AllowedBaseKeys $allowed
            $localeToBase = Read-LocaleLangMap -LangMapPath $proLangPath
            Repack-LocaleValueFile -SrcYaml $proValPath -DstYaml $dstValPath -LocaleToBase $localeToBase -AllowedBaseKeys $allowed
        } else {
            # No rename map for this file: identity lookup only.
            Repack-LocaleValueFile -SrcYaml $proValPath -DstYaml $dstValPath -LocaleToBase @{} -AllowedBaseKeys $allowed
        }
    }

    # regions/<x>.yml: pure value files, no .lang.yml siblings; identity lookup.
    foreach ($rel in $liteRegions) {
        $proValPath = Join-Path $proLoc ($rel -replace '/', '\')
        if (-not (Test-Path $proValPath)) { continue }
        $liteBaselinePath = Join-Path $liteMain ($rel -replace '/', '\')
        $allowed = Get-TopLevelKeys $liteBaselinePath
        if ($allowed.Count -eq 0) { continue }
        $dstValPath = Join-Path $liteLoc ($rel -replace '/', '\')
        [void](New-Item -ItemType Directory -Path (Split-Path $dstValPath) -Force)
        Repack-LocaleValueFile -SrcYaml $proValPath -DstYaml $dstValPath -LocaleToBase @{} -AllowedBaseKeys $allowed
    }
}

Write-Host "Done. Lite locale tree at: $liteLangOut"
