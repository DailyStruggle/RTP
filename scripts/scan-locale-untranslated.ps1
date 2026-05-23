# scan-locale-untranslated.ps1
#
# First-pass triage scanner over the TSV pipeline produced by
# `locale-files-to-csv.ps1`. Identifies rows in each `scripts/out/locale-<lang>.tsv`
# that are likely to need translation attention, in four categories:
#
#   1. DEPRECATED   - locale row whose (relpath, base_key) does not exist in
#                     baseline.tsv. These are stale rows the next reconcile pass
#                     will drop; surfacing them early lets us spot mass renames
#                     or missed baseline keys.
#   2. SAME_KEY     - locale row whose translated `key` column is identical to
#                     the baseline `base_key` (i.e. the per-locale key was never
#                     renamed). Identity is permitted per TRANSLATION_GUIDE.md
#                     section 8, but still a signal worth surfacing.
#   3. SAME_VALUE   - locale row whose `value` column is identical to baseline's
#                     English value. Either the row was freshly seeded by
#                     `reconcile-locale-csvs.ps1` and never translated, or the
#                     string is intentionally language-agnostic (numbers,
#                     enum tokens, URLs). Empty-on-both-sides is skipped.
#   4. SAME_COMMENT - locale row whose `preceding_comment` is byte-identical to
#                     baseline. Same caveats as SAME_VALUE; doc-tag-only blocks
#                     (`@type:`/`@range:` etc.) are skipped, since those stay
#                     verbatim by policy.
#
# This script is read-only: it does not modify any TSV. Pipe `-Csv` to get
# machine-readable output suitable for slicing into per-locale work bundles.
#
# Usage:
#   .\scripts\scan-locale-untranslated.ps1
#   .\scripts\scan-locale-untranslated.ps1 -Locales de,fr -SampleCount 5
#   .\scripts\scan-locale-untranslated.ps1 -Csv > scripts\out\untranslated.tsv
#
# See AGENTS.md "Locale Config TSV Pipeline" for the surrounding workflow.

param(
  [string[]]$Locales = @('cat','de','es','fr','it','ja','ko','nl','pl','pt','ru','zh'),
  [int]$SampleCount = 3,
  [switch]$Csv
)

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { (Get-Location).Path }
$outDir = Join-Path $scriptDir 'out'
$baselinePath = Join-Path $outDir 'baseline.tsv'

if (-not (Test-Path $baselinePath)) {
  Write-Error "baseline.tsv not found at $baselinePath - run locale-files-to-csv.ps1 first."
  exit 1
}

# --- Load baseline -----------------------------------------------------------
# Schema: relpath  parent_path  key  index  value  preceding_comment  blank_before  base_key
# Key the baseline by (relpath, base_key) so we can look it up from locale rows.
$baselineByKey = @{}
$baselineLines = Get-Content $baselinePath -Encoding UTF8
$header = $baselineLines[0]
foreach ($line in ($baselineLines | Select-Object -Skip 1)) {
  if (-not $line) { continue }
  $c = $line -split "`t"
  if ($c.Count -lt 8) { continue }
  $relpath  = $c[0]
  $key      = $c[2]
  $value    = $c[4]
  $comment  = $c[5]
  $base_key = $c[7]
  # Baseline stores shape/vert rename-map files under `lang/shape/...` and
  # `lang/vert/...` (shared across locales, no per-locale value file), while
  # value-bearing files like `messages.yml` are stored at the repo-root relpath
  # (no `lang/` prefix). Normalize by stripping a leading `lang/` so the lookup
  # key matches the locale-side normalized form below.
  $normRelpath = $relpath -replace '^lang/', ''
  $id = "$normRelpath`t$base_key"
  $baselineByKey[$id] = [PSCustomObject]@{
    relpath = $relpath
    base_key = $base_key
    key = $key
    value = $value
    comment = $comment
  }
}

function Test-IsDocTagOnlyComment {
  param([string]$Comment)
  if ([string]::IsNullOrWhiteSpace($Comment)) { return $true }
  $logical = $Comment -split '\\n'
  foreach ($ln in $logical) {
    $s = $ln.TrimStart()
    if ([string]::IsNullOrWhiteSpace($s)) { continue }
    if ($s -match '^#\s*@(type|range|unit|default|options|source):') { continue }
    if ($s -match '^#\s*#+\s*$') { continue }
    if ($s -match '^#\s*$') { continue }
    return $false
  }
  return $true
}

function Test-IsLanguageAgnosticValue {
  param([string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) { return $true }
  # Pure numbers / booleans / enum tokens / hex colors / placeholder-only.
  if ($Value -match '^-?\d+(\.\d+)?$') { return $true }
  if ($Value -match '^(true|false|null|yes|no)$') { return $true }
  if ($Value -match '^&?#?[0-9A-Fa-f]{3,8}$') { return $true }
  if ($Value -match '^\[[^\]]+\]$') { return $true }   # bare [placeholder]
  return $false
}

# --- Output collectors -------------------------------------------------------
$csvRows = New-Object System.Collections.ArrayList

# --- Scan each locale --------------------------------------------------------
foreach ($loc in $Locales) {
  $localePath = Join-Path $outDir "locale-$loc.tsv"
  if (-not (Test-Path $localePath)) {
    if (-not $Csv) { "{0,-4}  (no locale-{0}.tsv)" -f $loc }
    continue
  }
  $rows = Get-Content $localePath -Encoding UTF8 | Select-Object -Skip 1

  $total       = 0
  $deprecated  = New-Object System.Collections.ArrayList
  $sameKey     = New-Object System.Collections.ArrayList
  $sameValue   = New-Object System.Collections.ArrayList
  $sameComment = New-Object System.Collections.ArrayList

  foreach ($r in $rows) {
    if (-not $r) { continue }
    $c = $r -split "`t"
    if ($c.Count -lt 8) { continue }
    $total++

    $relpath   = $c[0]
    $key       = $c[2]
    $value     = $c[4]
    $comment   = $c[5]
    $base_key  = $c[7]

    # Baseline lookup uses the baseline relpath, not the locale's prefixed one.
    # Locale relpath is `lang/<loc>/<file>`; strip the prefix to match baseline.
    $baseRelpath = $relpath -replace "^lang/$loc/", ''
    $id = "$baseRelpath`t$base_key"

    $row = [PSCustomObject]@{
      locale = $loc
      relpath = $relpath
      base_key = $base_key
      key = $key
      category = ''
    }

    if (-not $baselineByKey.ContainsKey($id)) {
      $row.category = 'DEPRECATED'
      [void]$deprecated.Add($row)
      [void]$csvRows.Add($row)
      continue
    }

    $b = $baselineByKey[$id]

    if ($key -eq $b.base_key) {
      $rk = $row.PSObject.Copy(); $rk.category = 'SAME_KEY'
      [void]$sameKey.Add($rk); [void]$csvRows.Add($rk)
    }
    if ($value -ceq $b.value -and -not (Test-IsLanguageAgnosticValue $value)) {
      $rv = $row.PSObject.Copy(); $rv.category = 'SAME_VALUE'
      [void]$sameValue.Add($rv); [void]$csvRows.Add($rv)
    }
    if ($comment -ceq $b.comment -and -not (Test-IsDocTagOnlyComment $comment)) {
      $rc = $row.PSObject.Copy(); $rc.category = 'SAME_COMMENT'
      [void]$sameComment.Add($rc); [void]$csvRows.Add($rc)
    }
  }

  if (-not $Csv) {
    "{0,-4}  total={1,4}  deprecated={2,3}  same_key={3,4}  same_value={4,4}  same_comment={5,4}" -f `
      $loc, $total, $deprecated.Count, $sameKey.Count, $sameValue.Count, $sameComment.Count

    if ($SampleCount -gt 0) {
      foreach ($pair in @(@('DEPRECATED',$deprecated), @('SAME_KEY',$sameKey), @('SAME_VALUE',$sameValue), @('SAME_COMMENT',$sameComment))) {
        $label = $pair[0]; $list = $pair[1]
        if ($list.Count -eq 0) { continue }
        $shown = [Math]::Min($SampleCount, $list.Count)
        for ($i = 0; $i -lt $shown; $i++) {
          $e = $list[$i]
          "       [$label] $($e.relpath) :: $($e.base_key) (key=$($e.key))"
        }
      }
    }
  }
}

if ($Csv) {
  "locale`trelpath`tbase_key`tkey`tcategory"
  foreach ($r in $csvRows) {
    "{0}`t{1}`t{2}`t{3}`t{4}" -f $r.locale, $r.relpath, $r.base_key, $r.key, $r.category
  }
}
