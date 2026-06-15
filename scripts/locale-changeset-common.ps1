# locale-changeset-common.ps1
#
# Shared helpers for the changeset (secondary) component of the locale config
# pipeline: locale-changeset-to-csv.ps1 and locale-changeset-from-csv.ps1.
#
# Dot-source this file; it defines no top-level side effects.
#
# The TSV read/write and langmap/relpath helpers below mirror the semantics
# already used by reconcile-locale-csvs.ps1 so that changeset rows align with
# the exact same per-locale TSV rows the reconcile step produces.

$Script:TsvCols = @('relpath','parent_path','key','index','value','preceding_comment','blank_before','base_key')

# --- Internal pipeline TSV (tab-separated, backslash-escaped) ---------------

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

# --- Comment-cell escaping (single-line CSV transport) ----------------------
#
# The changeset CSV is parsed line-by-line (Read-Csv below), so a comment block
# that spans multiple physical lines must be flattened to a single line. We use
# the same backslash convention as the per-locale TSV's preceding_comment
# column: '\' -> '\\', newline -> '\n', tab -> '\t'. This keeps each changeset
# row on one CSV line while round-tripping the comment exactly.

function ConvertTo-CommentCell([string]$s) {
    if ($null -eq $s) { return '' }
    $v = $s
    $v = $v -replace '\\', '\\\\'
    $v = $v -replace "`r`n", "`n"
    $v = $v -replace "`n", '\n'
    $v = $v -replace "`t", '\t'
    return $v
}

function ConvertFrom-CommentCell([string]$s) {
    if ($null -eq $s) { return '' }
    $PH = [char]0x1
    $v = $s
    $v = $v -replace '\\\\', [string]$PH
    $v = $v -replace '\\n', "`n"
    $v = $v -replace '\\t', "`t"
    $v = $v -replace [string]$PH, '\'
    return $v
}

# --- User-facing changeset CSV (RFC-4180 comma-quoted, UTF-8 no BOM) --------

function ConvertTo-CsvField([string]$s) {
    if ($null -eq $s) { $s = '' }
    if ($s -match '[",\r\n]') {
        return '"' + ($s -replace '"', '""') + '"'
    }
    return $s
}

function Write-Csv {
    param(
        [Parameter(Mandatory)] [AllowEmptyCollection()] $Rows,
        [Parameter(Mandatory)] [string[]] $Columns,
        [Parameter(Mandatory)] [string] $Path
    )
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append((($Columns | ForEach-Object { ConvertTo-CsvField $_ }) -join ',')).Append("`n")
    foreach ($r in $Rows) {
        $cells = foreach ($c in $Columns) { ConvertTo-CsvField ([string]$r.$c) }
        [void]$sb.Append(($cells -join ',')).Append("`n")
    }
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllBytes($Path, $enc.GetBytes($sb.ToString()))
}

# Parse a single CSV line (no embedded newlines; changeset values are
# single-line) into an array of fields, honouring RFC-4180 quoting.
function Split-CsvLine([string]$line) {
    $fields = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    $inQuotes = $false
    for ($i = 0; $i -lt $line.Length; $i++) {
        $ch = $line[$i]
        if ($inQuotes) {
            if ($ch -eq '"') {
                if ($i + 1 -lt $line.Length -and $line[$i + 1] -eq '"') {
                    [void]$sb.Append('"'); $i++
                } else {
                    $inQuotes = $false
                }
            } else {
                [void]$sb.Append($ch)
            }
        } else {
            if ($ch -eq '"') { $inQuotes = $true }
            elseif ($ch -eq ',') { $fields.Add($sb.ToString()) | Out-Null; [void]$sb.Clear() }
            else { [void]$sb.Append($ch) }
        }
    }
    $fields.Add($sb.ToString()) | Out-Null
    return ,$fields
}

function Read-Csv([string]$path) {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $lines = $text -split "`r?`n"
    $header = $null
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($line in $lines) {
        if ($line -eq '') { continue }
        $fields = Split-CsvLine $line
        if ($null -eq $header) { $header = $fields; continue }
        $o = [ordered]@{}
        for ($i = 0; $i -lt $header.Count; $i++) {
            $name = $header[$i]
            $o[$name] = if ($i -lt $fields.Count) { $fields[$i] } else { '' }
        }
        $rows.Add([pscustomobject]$o) | Out-Null
    }
    return [pscustomobject]@{ Header = $header; Rows = $rows }
}

# --- Mojibake / encoding hygiene scan ---------------------------------------

# Common AI-generated mojibake markers (see .junie/AGENTS.md "Markdown Encoding
# Hygiene"). These are double-encoding artifacts and the U+FFFD replacement
# char; legitimate UTF-8 locale text (accented Latin, CJK, Cyrillic) does not
# contain these multi-byte misread sequences, so false positives are unlikely.
$Script:MojibakeMarkers = @(
    [string]([char]0x00E2 + [char]0x20AC),  # 'â€'  : em/en dash, curly quotes
    [string]([char]0x00E2 + [char]0x0153),  # 'âœ'  : check-mark emoji family
    [string]([char]0x00E2 + [char]0x008C),  # 'âŒ'  : cross-mark
    [string]([char]0x00F0 + [char]0x0178),  # 'ðŸ'  : 4-byte emoji
    [string]([char]0x00C3 + [char]0x00A9),  # 'Ã©'  : accented Latin (e-acute)
    [string]([char]0x00C2 + [char]0x00A7),  # 'Â§'  : section sign
    [string]([char]0xFFFD)                  # U+FFFD replacement character
)

# Scan a string for mojibake markers. Returns the list of markers found
# (empty when clean).
function Find-Mojibake([string]$text) {
    $hits = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrEmpty($text)) { return ,$hits }
    foreach ($m in $Script:MojibakeMarkers) {
        if ($text.Contains($m)) { $hits.Add($m) | Out-Null }
    }
    return ,$hits
}

# --- Relpath / langmap helpers (mirrors reconcile-locale-csvs.ps1) ----------

function Get-LocaleRelpath([string]$baselineRelpath, [string]$loc) {
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
    if ($tail -like '*.lang.yml' -or $tail -like 'shape/*' -or $tail -like 'vert/*') {
        return "lang/$tail"
    }
    return $tail
}

# Build per-file langmap (baseValueRelpath -> hashtable(baseKey -> translatedKey))
# from a locale's TSV rows, using the base_key/key columns directly.
function Build-Langmaps {
    param(
        [Parameter(Mandatory)] $LocaleRows,
        [Parameter(Mandatory)] [string] $Loc
    )
    $langmaps = @{}
    foreach ($r in $LocaleRows) {
        if ($r.relpath -like "lang/$Loc/*.lang.yml") { continue }
        if (-not $r.relpath.StartsWith("lang/$Loc/")) { continue }
        if ([string]::IsNullOrEmpty($r.key)) { continue }
        if ([string]::IsNullOrEmpty($r.base_key)) { continue }
        if ($r.base_key -eq $r.key) { continue }
        $tail = $r.relpath.Substring("lang/$Loc/".Length)
        if ($tail -like 'shape/*' -or $tail -like 'vert/*') {
            $valueRelpath = "lang/$tail"
        } else {
            $valueRelpath = $tail
        }
        if (-not $langmaps.ContainsKey($valueRelpath)) { $langmaps[$valueRelpath] = @{} }
        if (-not $langmaps[$valueRelpath].ContainsKey($r.base_key)) {
            $langmaps[$valueRelpath][$r.base_key] = $r.key
        }
    }
    return $langmaps
}
