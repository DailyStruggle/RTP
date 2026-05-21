<#
.SYNOPSIS
  Imports a filled translation bundle back into the locale TSV.

.DESCRIPTION
  Reads scripts/out/bundle-<lang>.md (or the path passed via -BundlePath),
  validates each block's sentinel preservation and mojibake hygiene, unmasks
  sentinels using the per-row legend, and patches the matching rows in
  scripts/out/locale-<lang>.tsv in place.

  Match key: (relpath, parent_path, base_key, index).

  Block validation rules (per block, in order):
    1. Sentinel preservation: the multiset of "P-number" sentinel tokens
       (e.g. literal "P0", "P1", masked between double angle brackets) in
       value_locale and comment_locale must equal the multiset in the
       masked English value/comment. Extra or missing sentinels: skip
       the block and report an error.
    2. Mojibake check: value_locale and unmasked comment_locale must not
       contain CP-1252-misread UTF-8 markers or U+FFFD replacement chars.
       Hit: skip the block and report an error.
    3. No-op skip: if value_locale is byte-identical to value_english AND
       comment_locale is byte-identical to comment_english, the block is
       treated as untranslated; the locale TSV row gets a TODO(i18n)
       marker prepended to its comment (idempotent) and is left otherwise
       unchanged.

  Patches are written atomically: the new TSV is built in memory, then
  written via UTF-8 (no BOM) with LF line endings, overwriting the original.
  A .bak copy is created next to the locale TSV per the Backup Policy in
  .junie/AGENTS.md (locale TSVs are git-tracked source-of-truth, treat as
  code).

  After this script runs, the standard pipeline follows:
    .\scripts\reconcile-locale-csvs.ps1
    .\scripts\locale-files-from-csv.ps1
    .\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"
    .\gradlew build

.PARAMETER Locale
  Optional. Locale code (e.g. "pl"). Use this for a single-locale bundle
  produced by ``export-translation-bundle.ps1 -Locale <code>``. Mutually
  exclusive with -All.

.PARAMETER All
  Optional. Read a combined multi-locale bundle (default path
  ``scripts/out/bundle-all.md``) and dispatch each block to its locale's
  TSV based on the per-block ``locale:`` field. Mutually exclusive with
  -Locale. If neither is given, defaults to -All.

.PARAMETER BundlePath
  Optional. Override path to the filled bundle .md. Defaults to
  scripts/out/bundle-<lang>.md (single mode) or scripts/out/bundle-all.md
  (-All mode).

.PARAMETER DryRun
  Optional. Parse and validate every block, report what would change,
  but do not write any TSV.
#>
[CmdletBinding()]
param(
  [string] $Locale = "",
  [switch] $All,
  [string] $BundlePath = "",
  [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$outDir  = Join-Path $root 'scripts\out'

if (-not $Locale -and -not $All) { $All = $true }
if ($Locale -and $All) {
  throw "Pass either -Locale <code> or -All, not both."
}

if (-not $BundlePath) {
  $BundlePath = if ($All) { Join-Path $outDir 'bundle-all.md' } else { Join-Path $outDir ('bundle-' + $Locale + '.md') }
}

if (-not (Test-Path $BundlePath)) {
  throw "Bundle not found at $BundlePath."
}

# Single-locale guard: ensure that one locale's TSV exists. In -All mode each
# block's ``locale:`` field selects the TSV; missing TSVs are reported per block.
if (-not $All) {
  $tsvPath = Join-Path $outDir ('locale-' + $Locale + '.tsv')
  if (-not (Test-Path $tsvPath)) {
    throw "Locale TSV not found at $tsvPath."
  }
}

# ----- Helpers ---------------------------------------------------------------
$mojibakeMarkers = @("`u{00E2}`u{20AC}", "`u{00C2}", "`u{00C3}", "`u{FFFD}")
# Note: PowerShell string `u escapes are 5.1+. Above is two-byte CP-1252 reads
# of UTF-8 sequences: â€ (=E2 80), Â (=C2), Ã (=C3), and U+FFFD replacement.

function Has-Mojibake {
  param([string] $s)
  if ([string]::IsNullOrEmpty($s)) { return $false }
  foreach ($m in $mojibakeMarkers) {
    if ($s.Contains($m)) { return $true }
  }
  return $false
}

function Extract-Sentinels {
  param([string] $s)
  if ([string]::IsNullOrEmpty($s)) { return @() }
  $matches = [regex]::Matches($s, '<<P\d+>>')
  $list = @()
  foreach ($m in $matches) { $list += $m.Value }
  return $list
}

function Parse-Legend {
  param([string] $line)
  # Format: "P0=val1; P1=val2; P2=val3"
  $map = @{}
  if ([string]::IsNullOrEmpty($line) -or $line -eq '(none)') { return $map }
  # Split on '; P' boundaries while keeping each P-prefix.
  $parts = [regex]::Split($line, ';\s*(?=P\d+=)')
  foreach ($p in $parts) {
    if ($p -match '^(P\d+)=(.*)$') {
      $tok = '<<' + $matches[1] + '>>'
      $val = $matches[2] -replace '\\n', "`n" -replace '\\t', "`t"
      $map[$tok] = $val
    }
  }
  return $map
}

function Unmask {
  param([string] $s, [hashtable] $legend)
  if ([string]::IsNullOrEmpty($s)) { return $s }
  $out = $s
  foreach ($tok in $legend.Keys) {
    $out = $out.Replace($tok, $legend[$tok])
  }
  return $out
}

function Escape-TsvComment {
  # Inverse of the exporter's Unescape-TsvComment.
  param([string] $s)
  if ($null -eq $s) { return '' }
  # Strip any trailing newline left over from the YAML block scalar.
  $s = $s -replace "`r`n", "`n"
  $s = $s.TrimEnd("`n")
  $s = $s -replace "`t", '\t'
  $s = $s -replace "`n", '\n'
  return $s
}

# ----- Parse bundle blocks ---------------------------------------------------
$content = Get-Content -LiteralPath $BundlePath -Encoding UTF8 -Raw
# Normalise line endings.
$content = $content -replace "`r`n", "`n"

# Extract every ```bundle ... ``` block.
$blockMatches = [regex]::Matches($content, '(?ms)^```bundle\s*\n(.*?)\n```\s*$')
if ($blockMatches.Count -eq 0) {
  throw "No ```bundle blocks found in $BundlePath."
}

$blocks = @()

foreach ($bm in $blockMatches) {
  $body = $bm.Groups[1].Value
  $bodyLines = $body -split "`n"

  $fields = @{}
  $i = 0
  while ($i -lt $bodyLines.Count) {
    $ln = $bodyLines[$i]
    if ($ln -match '^([a-z_]+):\s*\|\s*$') {
      # YAML literal block; consume 2-space-indented continuation lines.
      $name = $matches[1]
      $i++
      $buf = New-Object System.Text.StringBuilder
      while ($i -lt $bodyLines.Count -and ($bodyLines[$i] -match '^  ' -or $bodyLines[$i] -eq '')) {
        $rest = $bodyLines[$i]
        if ($rest.Length -ge 2) { $rest = $rest.Substring(2) } else { $rest = '' }
        if ($buf.Length -gt 0) { [void]$buf.Append("`n") }
        [void]$buf.Append($rest)
        $i++
      }
      $fields[$name] = $buf.ToString()
      continue
    }
    elseif ($ln -match '^([a-z_]+):\s*(.*)$') {
      $fields[$matches[1]] = $matches[2]
      $i++
      continue
    }
    $i++
  }

  # In single-locale mode the bundle pre-dates the `locale:` field; fall back
  # to the -Locale parameter so legacy bundles keep working.
  $blockLocale = $fields['locale']
  if ([string]::IsNullOrEmpty($blockLocale)) { $blockLocale = $Locale }

  $blocks += [pscustomobject]@{
    locale         = $blockLocale
    base_key       = $fields['base_key']
    parent_path    = $fields['parent_path']
    index          = $fields['index']
    relpath        = $fields['relpath']
    blank_before   = $fields['blank_before']
    sentinels      = $fields['sentinels']
    value_english  = $fields['value_english']
    comment_english= $fields['comment_english']
    key_locale     = $fields['key_locale']
    value_locale   = $fields['value_locale']
    comment_locale = $fields['comment_locale']
  }
}

Write-Host ("Parsed {0} bundle blocks from {1}." -f $blocks.Count, $BundlePath)

# ----- Load locale TSVs (lazy, one per locale referenced by a block) ---------
# Each entry: $tsvStore[$lang] = @{ Header = ...; Data = @(...); Index = @{}; Dirty = $false }
$tsvStore = @{}

function Load-LocaleTsv {
  param([string] $lang)
  if ($tsvStore.ContainsKey($lang)) { return $tsvStore[$lang] }
  $path = Join-Path $outDir ('locale-' + $lang + '.tsv')
  if (-not (Test-Path $path)) { return $null }
  $tsvLines = Get-Content -LiteralPath $path -Encoding UTF8
  $data = @($tsvLines | Select-Object -Skip 1)
  $idx = @{}
  for ($i = 0; $i -lt $data.Count; $i++) {
    $c = $data[$i] -split "`t", 8
    if ($c.Length -ne 8) { continue }
    $key = '{0}|{1}|{2}|{3}' -f $c[0], $c[1], $c[7], $c[3]
    $idx[$key] = $i
  }
  $entry = @{ Path = $path; Header = $tsvLines[0]; Data = $data; Index = $idx; Dirty = $false }
  $tsvStore[$lang] = $entry
  return $entry
}

# ----- Apply patches ---------------------------------------------------------
$applied = 0; $skippedNoOp = 0; $errSentinel = 0; $errMojibake = 0; $errNoMatch = 0
$errNoLocale = 0; $errNoTsv = 0
$errors = @()

foreach ($b in $blocks) {
  if ([string]::IsNullOrEmpty($b.locale)) {
    $errNoLocale++
    $errors += ("[no-locale] {0}::{1} (block is missing 'locale:' field; only valid in -Locale mode)" -f $b.relpath, $b.base_key)
    continue
  }
  $store = Load-LocaleTsv $b.locale
  if ($null -eq $store) {
    $errNoTsv++
    $errors += ("[no-tsv] locale '{0}' has no scripts/out/locale-{0}.tsv; block {1}::{2} skipped" -f $b.locale, $b.relpath, $b.base_key)
    continue
  }
  $rowIndex = $store.Index
  $tsvData  = $store.Data

  $matchKey = '{0}|{1}|{2}|{3}' -f $b.relpath, $b.parent_path, $b.base_key, $b.index
  if (-not $rowIndex.ContainsKey($matchKey)) {
    $errNoMatch++
    $errors += "[no-match] $($b.locale): $matchKey"
    continue
  }

  # Sentinel preservation check.
  $valEnSent = Extract-Sentinels $b.value_english | Sort-Object
  $valLoSent = Extract-Sentinels $b.value_locale | Sort-Object
  $cmtEnSent = Extract-Sentinels $b.comment_english | Sort-Object
  $cmtLoSent = Extract-Sentinels $b.comment_locale | Sort-Object

  $valSentOk = ((($valEnSent -join '|') -ceq ($valLoSent -join '|')))
  $cmtSentOk = ((($cmtEnSent -join '|') -ceq ($cmtLoSent -join '|')))
  if (-not ($valSentOk -and $cmtSentOk)) {
    $errSentinel++
    $errors += ("[sentinel] {0}::{1} value:{2}->{3} comment:{4}->{5}" -f $b.relpath, $b.base_key, ($valEnSent -join ','), ($valLoSent -join ','), ($cmtEnSent -join ','), ($cmtLoSent -join ','))
    continue
  }

  # Mojibake check (on the masked locale strings; unmasking only adds known good values).
  if ((Has-Mojibake $b.value_locale) -or (Has-Mojibake $b.comment_locale)) {
    $errMojibake++
    $errors += ("[mojibake] {0}::{1}" -f $b.relpath, $b.base_key)
    continue
  }

  # No-op skip: locale identical to English in both fields.
  $isNoop = ($b.value_locale -ceq $b.value_english) -and ($b.comment_locale -ceq $b.comment_english)

  $legend = Parse-Legend $b.sentinels
  $newValue   = Unmask $b.value_locale $legend
  $newComment = Unmask $b.comment_locale $legend

  if ($isNoop) {
    # Prepend a TODO marker (idempotent).
    $todo = '# TODO(i18n): translation pending'
    if (-not ($newComment -like "*$todo*")) {
      if ([string]::IsNullOrEmpty($newComment)) {
        $newComment = $todo
      } else {
        $newComment = $todo + "`n" + $newComment
      }
    }
    $skippedNoOp++
  } else {
    $applied++
  }

  # Patch the row.
  $rowIdx = $rowIndex[$matchKey]
  $cols = $tsvData[$rowIdx] -split "`t", 8
  # cols: 0 relpath, 1 parent_path, 2 key, 3 index, 4 value, 5 preceding_comment, 6 blank_before, 7 base_key
  $cols[2] = if ([string]::IsNullOrEmpty($b.key_locale)) { $cols[2] } else { $b.key_locale }
  $cols[4] = $newValue
  $cols[5] = Escape-TsvComment $newComment
  $tsvData[$rowIdx] = ($cols -join "`t")
  $store.Dirty = $true
}

# ----- Report ----------------------------------------------------------------
Write-Host ""
Write-Host ("Applied translations:   {0}" -f $applied)
Write-Host ("Skipped (no-op + TODO): {0}" -f $skippedNoOp)
Write-Host ("Sentinel mismatches:    {0}" -f $errSentinel)
Write-Host ("Mojibake hits:          {0}" -f $errMojibake)
Write-Host ("Unmatched rows:         {0}" -f $errNoMatch)
Write-Host ("Missing locale field:   {0}" -f $errNoLocale)
Write-Host ("Missing locale TSV:     {0}" -f $errNoTsv)

if ($errors.Count -gt 0) {
  Write-Host ""
  Write-Host "Errors (block skipped, locale TSV row unchanged):"
  $errors | ForEach-Object { Write-Host ("  " + $_) }
}

if ($DryRun) {
  Write-Host ""
  Write-Host "Dry run: no changes written. Re-run without -DryRun to apply."
  return
}

# ----- Backup + write (per-locale, only stores with applied/no-op changes) ---
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$writtenCount = 0
foreach ($lang in ($tsvStore.Keys | Sort-Object)) {
  $store = $tsvStore[$lang]
  if (-not $store.Dirty) {
    Write-Host ("Skipped {0} (no changes)." -f $store.Path)
    continue
  }
  $bak = $store.Path + '.bak'
  Copy-Item -LiteralPath $store.Path -Destination $bak -Force

  $all = New-Object System.Collections.ArrayList
  [void]$all.Add($store.Header)
  foreach ($r in $store.Data) { [void]$all.Add($r) }
  [System.IO.File]::WriteAllLines($store.Path, $all, $utf8NoBom)
  $writtenCount++
  Write-Host ("Patched {0}. Backup at {1}." -f $store.Path, $bak)
}

Write-Host ""
Write-Host ("Wrote {0} locale TSV file(s)." -f $writtenCount)
Write-Host "Next steps:"
Write-Host "  .\scripts\reconcile-locale-csvs.ps1"
Write-Host "  .\scripts\locale-files-from-csv.ps1"
Write-Host "  .\gradlew :rtp-plugin:test --tests `"*LocaleParityTest*`""
Write-Host "  .\gradlew build"
