# Scans scripts/translations/<locale>.tsv files for comment rows whose translated_comment
# is still English prose. Used after restore-translations-from-git.ps1 to identify gaps.
#
# Heuristic: after stripping doc-tag (@type/@range/...), URL, banner, and empty-comment lines,
# look for common English connector words in the residual prose. Doc-tags are fine to leave
# verbatim; banner lines are language-agnostic; URLs stay verbatim.

param(
  [string[]]$Locales = @('cat','de','es','fr','ja','ko','nl','pt','ru','zh'),
  [int]$SampleCount = 3
)

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { (Get-Location).Path }
$translationsDir = Join-Path $scriptDir 'translations'

# English connector words/phrases that almost never survive translation untouched.
$enMarkers = @(
  ' the ',' and ',' for ',' with ',' when ',' this ',' that ',' use ',' used ',
  ' will ',' must ',' should ',' does ',' from ',' into ',' before ',' after ',
  ' between ',' player ',' players ',' server ',' available ',' enable ',' enables ',
  ' disable ',' disabled ',' enabled ',' if ',' wait ',' time ',' set ',' default ',
  ' number ',' file ',' files ',' empty ',' list ',' value ',' values ',
  ' command ',' commands ',' world ',' chunks ',' loaded ',' Documentation',
  ' Configuration',' Wait ',' Time ',' Database ',' database ',' password ',
  ' username ',' host ',' port ',' Used by ',' Empty string '
)

foreach ($loc in $Locales) {
  $path = Join-Path $translationsDir "$loc.tsv"
  if (-not (Test-Path $path)) {
    "{0,-4}  (no translations file)" -f $loc
    continue
  }
  $rows = Get-Content $path -Encoding UTF8 | Select-Object -Skip 1
  $total = ($rows | Where-Object { $_ -and ($_ -split "`t").Count -ge 5 }).Count
  $leaks = New-Object System.Collections.ArrayList
  foreach ($r in $rows) {
    if (-not $r) { continue }
    $c = $r -split "`t"
    if ($c.Count -lt 5) { continue }
    $cmt = $c[4]
    if ([string]::IsNullOrWhiteSpace($cmt)) { continue }
    # Split on literal \n in TSV cell to get logical comment lines.
    $logical = $cmt -split '\\n'
    $residual = @()
    foreach ($ln in $logical) {
      $stripped = $ln.TrimStart()
      if ($stripped -match '^#\s*@(type|range|unit|default|options|source):') { continue }
      if ($stripped -match 'https?://') { continue }
      if ($stripped -match '^#\s*#+\s*$') { continue }   # banner # ##### #
      if ($stripped -match '^#\s*$') { continue }        # blank-comment
      $residual += $stripped
    }
    if ($residual.Count -eq 0) { continue }
    $hay = ' ' + (($residual -join ' ') -replace '#',' ') + ' '
    $hits = 0
    $matched = @()
    foreach ($w in $enMarkers) {
      if ($hay -like "*$w*") { $hits++; $matched += $w.Trim() }
    }
    # Require >=2 distinct English markers to call it an english-leak (reduces
    # false positives from short translated lines that borrow one English word).
    if ($hits -ge 2) {
      [void]$leaks.Add(@{ relpath = $c[0]; base_key = $c[2]; comment = $cmt; markers = ($matched -join ',') })
    }
  }
  "{0,-4}  total={1,3}  english-leak={2,3}" -f $loc, $total, $leaks.Count
  if ($SampleCount -gt 0 -and $leaks.Count -gt 0) {
    $shown = [Math]::Min($SampleCount, $leaks.Count)
    for ($i = 0; $i -lt $shown; $i++) {
      $e = $leaks[$i]
      $snippet = ($e.comment -replace '\\n',' / ')
      if ($snippet.Length -gt 140) { $snippet = $snippet.Substring(0,140) + '...' }
      "       [$($e.relpath)] $($e.base_key) <$($e.markers)> :: $snippet"
    }
  }
}
