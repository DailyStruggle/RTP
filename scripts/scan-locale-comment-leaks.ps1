# Scans scripts/out/locale-<lang>.tsv files for `preceding_comment` cells whose
# prose remains English (untranslated). Doc-tag lines (`# @type:`, `# @range:`,
# `# @default:`, `# @unit:`, `# @options:`, `# @source:`) and URL/banner/empty
# comment lines are stripped before scanning the residual prose for common
# English connector words.
#
# Output: one summary line per locale, plus optional samples of leaking rows.
# A row is reported when its residual prose contains at least two distinct
# English markers (heuristic; reduces false positives from short borrowed words).
#
# Usage:
#   .\scripts\scan-locale-comment-leaks.ps1
#   .\scripts\scan-locale-comment-leaks.ps1 -Locales ko -SampleCount 50

param(
    [string[]] $Locales = @('cat','de','es','fr','it','ja','ko','nl','pl','pt','ru','zh'),
    [int]      $SampleCount = 5
)

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot }
             elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path }
             else { (Get-Location).Path }
$outDir = Join-Path $scriptDir 'out'

# English connector words that almost never survive translation untouched.
$enMarkers = @(
    ' the ',' and ',' for ',' with ',' when ',' this ',' that ',' use ',' used ',
    ' will ',' must ',' should ',' does ',' from ',' into ',' before ',' after ',
    ' between ',' player ',' players ',' server ',' available ',' enable ',' enables ',
    ' disable ',' disabled ',' enabled ',' if ',' wait ',' time ',' set ',' default ',
    ' number ',' file ',' files ',' empty ',' list ',' value ',' values ',
    ' command ',' commands ',' world ',' chunks ',' loaded ',' Documentation',
    ' Configuration',' Wait ',' Time ',' Database ',' database ',' password ',
    ' username ',' host ',' port ',' Used by ',' Empty string ',' executed ',
    ' successful ',' target ',' name ',' ignored ',' string '
)

foreach ($loc in $Locales) {
    $path = Join-Path $outDir "locale-$loc.tsv"
    if (-not (Test-Path -LiteralPath $path)) {
        "{0,-4}  (no TSV)" -f $loc
        continue
    }
    $lines = Get-Content -LiteralPath $path -Encoding UTF8
    if ($lines.Count -lt 2) { continue }
    $rows = $lines | Select-Object -Skip 1
    $total = 0
    $leaks = New-Object System.Collections.ArrayList
    foreach ($r in $rows) {
        if (-not $r) { continue }
        $c = $r -split "`t"
        # locale-*.tsv columns: relpath, parent_path, key, index, value, preceding_comment, blank_before, base_key
        if ($c.Count -lt 6) { continue }
        $cmt = $c[5]
        if ([string]::IsNullOrWhiteSpace($cmt)) { continue }
        $total++
        # Split on literal \n (TSV-escaped newline) to get logical comment lines.
        $logical = $cmt -split '\\n'
        $residual = @()
        foreach ($ln in $logical) {
            $stripped = $ln.TrimStart()
            # Strip doc-tag-only lines (the values after @ are intentionally English).
            if ($stripped -match '^#\s*@(type|range|unit|default|options|source):') { continue }
            if ($stripped -match 'https?://') { continue }
            if ($stripped -match '^#\s*#+\s*$') { continue }   # banner # ##### #
            if ($stripped -match '^#\s*$') { continue }        # blank-comment
            $residual += $stripped
        }
        if ($residual.Count -eq 0) { continue }
        $hay = ' ' + (($residual -join ' ') -replace '#',' ') + ' '
        $matched = New-Object System.Collections.Generic.HashSet[string]
        foreach ($w in $enMarkers) {
            if ($hay -like "*$w*") { [void]$matched.Add($w.Trim()) }
        }
        if ($matched.Count -ge 2) {
            [void]$leaks.Add(@{
                relpath  = $c[0]
                parent   = $c[1]
                key      = $c[2]
                index    = $c[3]
                comment  = $cmt
                markers  = (($matched | Sort-Object) -join ',')
            })
        }
    }
    "{0,-4}  comments={1,4}  english-leak={2,4}" -f $loc, $total, $leaks.Count
    if ($SampleCount -gt 0 -and $leaks.Count -gt 0) {
        $shown = [Math]::Min($SampleCount, $leaks.Count)
        for ($i = 0; $i -lt $shown; $i++) {
            $e = $leaks[$i]
            $snippet = ($e.comment -replace '\\n',' / ')
            if ($snippet.Length -gt 160) { $snippet = $snippet.Substring(0,160) + '...' }
            "       [$($e.relpath)] $($e.parent)::$($e.key)[$($e.index)] <$($e.markers)>"
            "           $snippet"
        }
    }
}
