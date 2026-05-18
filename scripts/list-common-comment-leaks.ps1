# Lists baseline `(relpath, base_key, index)` rows whose `preceding_comment`
# is still English in MULTIPLE shipped locales. Output is sorted by the
# number of affected locales (descending), so the most-shared leaks come
# first. This identifies "translate once, apply to every locale" candidates.
#
# Reuses the heuristic from scan-locale-comment-leaks.ps1 (strip doc-tag
# lines, URLs, banners, blank-comments; require >=2 distinct English
# connector markers in the residual prose).

param(
    [string[]] $Locales = @('cat','de','es','fr','it','ja','ko','nl','pl','pt','ru','zh'),
    [int]      $MinLocales = 2
)

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot }
             elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path }
             else { (Get-Location).Path }
$outDir = Join-Path $scriptDir 'out'

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

function Test-IsEnglishLeak([string]$cmt) {
    if ([string]::IsNullOrWhiteSpace($cmt)) { return $false }
    $logical = $cmt -split '\\n'
    $residual = @()
    foreach ($ln in $logical) {
        $s = $ln.TrimStart()
        if ($s -match '^#\s*@(type|range|unit|default|options|source):') { continue }
        if ($s -match 'https?://') { continue }
        if ($s -match '^#\s*#+\s*$') { continue }
        if ($s -match '^#\s*$') { continue }
        $residual += $s
    }
    if ($residual.Count -eq 0) { return $false }
    $hay = ' ' + (($residual -join ' ') -replace '#',' ') + ' '
    $matched = New-Object System.Collections.Generic.HashSet[string]
    foreach ($w in $enMarkers) {
        if ($hay -like "*$w*") { [void]$matched.Add($w.Trim()) }
    }
    return ($matched.Count -ge 2)
}

# (base_relpath|base_key|index) -> @{ locales = [list]; sample = first-comment }
$shared = @{}

foreach ($loc in $Locales) {
    $path = Join-Path $outDir "locale-$loc.tsv"
    if (-not (Test-Path -LiteralPath $path)) { continue }
    $lines = Get-Content -LiteralPath $path -Encoding UTF8
    if ($lines.Count -lt 2) { continue }
    foreach ($r in ($lines | Select-Object -Skip 1)) {
        if (-not $r) { continue }
        $c = $r -split "`t"
        if ($c.Count -lt 8) { continue }
        $relpath = $c[0]
        $idx     = $c[3]
        $cmt     = $c[5]
        $baseKey = $c[7]
        # Map locale relpath -> baseline relpath.
        # lang/<loc>/<file>.yml  -> <file>.yml
        # lang/<loc>/shape/x.lang.yml -> lang/shape/x.lang.yml
        $baseRel = $relpath
        if ($baseRel -match "^lang/$loc/") {
            $tail = $baseRel.Substring("lang/$loc/".Length)
            if ($tail -like 'shape/*' -or $tail -like 'vert/*') {
                $baseRel = "lang/$tail"
            } else {
                $baseRel = $tail
            }
        }
        if (-not (Test-IsEnglishLeak $cmt)) { continue }
        $sig = "$baseRel|$baseKey|$idx"
        if (-not $shared.ContainsKey($sig)) {
            $shared[$sig] = @{
                locales = New-Object System.Collections.ArrayList
                sample  = $cmt
                relpath = $baseRel
                baseKey = $baseKey
                index   = $idx
            }
        }
        [void]$shared[$sig].locales.Add($loc)
    }
}

$rows = @()
foreach ($k in $shared.Keys) {
    $e = $shared[$k]
    if ($e.locales.Count -lt $MinLocales) { continue }
    $rows += [pscustomobject]@{
        Count   = $e.locales.Count
        Relpath = $e.relpath
        BaseKey = $e.baseKey
        Index   = $e.index
        Locales = ($e.locales | Sort-Object -Unique) -join ','
        Comment = ($e.sample -replace '\\n',' / ')
    }
}

$rows = $rows | Sort-Object -Property @{Expression='Count';Descending=$true}, Relpath, BaseKey, Index

"Shared English-leak comments (present in >= $MinLocales locales):"
"  total signatures: $($rows.Count)"
""
foreach ($r in $rows) {
    $snippet = if ($r.Comment.Length -gt 140) { $r.Comment.Substring(0,140) + '...' } else { $r.Comment }
    "{0,2}x  [{1}] {2}[{3}]   ({4})" -f $r.Count, $r.Relpath, $r.BaseKey, $r.Index, $r.Locales
    "        $snippet"
}
