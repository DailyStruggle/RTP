<#
.SYNOPSIS
  Audits English bleed across all shipped locale TSVs.

.DESCRIPTION
  For every row in scripts/out/locale-<lang>.tsv whose `value` column is
  byte-identical to the matching English baseline value, write a row to
  scripts/out/bleed-<lang>.tsv. Joins on (filename, parent_path, base_key, index)
  where filename is the locale TSV's relpath stripped of its `lang/<lang>/` prefix.

  Empty values and rows without a matching baseline are excluded (the former are
  fall-through placeholders that already passed reconcile; the latter are stale
  rows that the next reconcile will drop).

  Per the *Locale Config TSV Pipeline* contract in .junie/AGENTS.md, this script
  is read-only with respect to lang/<locale>/*.yml — it only produces the
  bleed-<lang>.tsv manifests used by the bundle export/import pair.

.PARAMETER Locale
  Optional. Restrict audit to a single locale code (e.g. "pl"). When omitted,
  audits every locale-*.tsv under scripts/out/.

.OUTPUTS
  scripts/out/bleed-<lang>.tsv  — header + matching rows.
  Console summary table.
#>
[CmdletBinding()]
param(
  [string] $Locale = ""
)

$ErrorActionPreference = 'Stop'

$root     = Split-Path -Parent $PSScriptRoot
$outDir   = Join-Path $root 'scripts\out'
$baseline = Join-Path $outDir 'baseline.tsv'

if (-not (Test-Path $baseline)) {
  throw "baseline.tsv not found at $baseline. Run .\scripts\locale-files-to-csv.ps1 first."
}

# Build baseline index keyed by (relpath, parent_path, base_key, index).
# Values are 2-tuples of (value, preceding_comment) so the exporter can reuse
# this module if needed; the auditor itself only consults the value.
$base = @{}
$header = $null
foreach ($line in Get-Content -LiteralPath $baseline -Encoding UTF8) {
  if (-not $header) { $header = $line; continue }
  $c = $line -split "`t", 8
  if ($c.Length -ne 8) { continue }
  $key = '{0}|{1}|{2}|{3}' -f $c[0], $c[1], $c[7], $c[3]
  $base[$key] = @($c[4], $c[5])
}

$locales = if ($Locale) {
  @(Get-Item (Join-Path $outDir ('locale-' + $Locale + '.tsv')))
} else {
  Get-ChildItem -LiteralPath $outDir -Filter 'locale-*.tsv'
}

$summary = @()

foreach ($tsv in $locales) {
  $lang = ($tsv.BaseName -replace '^locale-', '')
  $bleedPath = Join-Path $outDir ('bleed-' + $lang + '.tsv')
  $rows = @()
  $matched = 0; $empty = 0; $bled = 0; $nativised = 0
  $first = $true
  foreach ($line in Get-Content -LiteralPath $tsv.FullName -Encoding UTF8) {
    if ($first) { $first = $false; continue }
    $c = $line -split "`t", 8
    if ($c.Length -ne 8) { continue }
    $file = $c[0] -replace '^lang/[^/]+/', ''
    $key  = '{0}|{1}|{2}|{3}' -f $file, $c[1], $c[7], $c[3]
    if (-not $base.ContainsKey($key)) { continue }
    $matched++
    $bv = $base[$key][0]
    $lv = $c[4]
    if ([string]::IsNullOrEmpty($lv)) { $empty++; continue }
    if ($lv -ceq $bv -and $bv -ne '') {
      $bled++
      # Emit the locale row as-is (preserve its relpath/parent_path/key/index/value/comment/blank/base_key).
      $rows += $line
    } else {
      $nativised++
    }
  }

  # Write bleed-<lang>.tsv with the original header.
  $output = New-Object System.Collections.ArrayList
  [void]$output.Add($header)
  $rows | ForEach-Object { [void]$output.Add($_) }
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllLines($bleedPath, $output, $utf8NoBom)

  $pct = if ($matched -gt 0) { 100.0 * $bled / [math]::Max(($bled + $nativised), 1) } else { 0 }
  $summary += [pscustomobject]@{
    Locale         = $lang
    Matched        = $matched
    Bled           = $bled
    Nativised      = $nativised
    Empty          = $empty
    BleedPctNonEmpty = ('{0:N1}%' -f $pct)
    ManifestPath   = (Resolve-Path -LiteralPath $bleedPath -Relative)
  }
}

$summary | Sort-Object -Property @{Expression = 'Bled'; Descending = $true} | Format-Table -AutoSize

Write-Host ""
Write-Host "Wrote bleed manifests to scripts/out/bleed-<lang>.tsv. Pass one to:"
Write-Host "  .\scripts\export-translation-bundle.ps1 -Locale <lang>"
