<#
.SYNOPSIS
  Exports a self-contained translation bundle for one locale.

.DESCRIPTION
  Reads scripts/out/bleed-<lang>.tsv (produced by audit-locale-bleed.ps1) and
  writes scripts/out/bundle-<lang>.md - a markdown document the user can hand
  to an external AI session for translation, then feed back through
  import-translation-bundle.ps1.

  Each bleed row becomes one fenced bundle block containing base_key,
  parent_path, index, relpath, blank_before, a sentinel legend, the masked
  English value and comment, and editable locale value/comment fields.

  Sentinels mask anything the translator must not change: bracketed
  placeholders, ampersand and hex color codes, section markers, @type /
  @range / @unit / @default / @options doc-tags, URLs, and version banners.
  The sentinels legend lists the original text for each masked token.

  The translator is instructed (in the bundle preamble) to:
    1. Only edit value_locale and comment_locale fields.
    2. Preserve every sentinel token verbatim.
    3. Skip rows by leaving the seeded English text in place; they will be
       re-seeded with a TODO(i18n) marker by the importer.

  Output is UTF-8, no BOM, LF line endings.

.PARAMETER Locale
  Optional. Locale code (e.g. "pl", "de"). Must have a matching
  scripts/out/bleed-<lang>.tsv produced by audit-locale-bleed.ps1. Mutually
  exclusive with -All. If neither is given, defaults to -All.

.PARAMETER All
  Optional. Export every available bleed manifest into a single combined
  bundle at scripts/out/bundle-all.md. Each block carries a `locale:` field
  so the importer can route patches to the right locale TSV. This is the
  recommended mode for an external translation session that handles all
  locales in one shot.

.PARAMETER MaxRows
  Optional. Cap the bundle at the first N bleed rows. In -All mode the cap
  is applied per-locale (e.g. -MaxRows 50 yields up to 50 rows from each
  locale). Default: no cap.

.OUTPUTS
  scripts/out/bundle-<lang>.md  (single-locale mode)
  scripts/out/bundle-all.md     (-All mode)
#>
[CmdletBinding()]
param(
  [string] $Locale = "",
  [switch] $All,
  [int] $MaxRows = 0
)

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$outDir  = Join-Path $root 'scripts\out'

# Default to -All when no locale is given. This makes the script "do the
# obvious thing" rather than throw for a missing parameter.
if (-not $Locale -and -not $All) { $All = $true }
if ($Locale -and $All) {
  throw "Pass either -Locale <code> or -All, not both."
}

# Discover available locales from bleed-*.tsv manifests.
$availableBleeds = Get-ChildItem -LiteralPath $outDir -Filter 'bleed-*.tsv' -ErrorAction SilentlyContinue
$availableLocales = $availableBleeds | ForEach-Object { $_.BaseName -replace '^bleed-', '' } | Sort-Object

if ($All) {
  if ($availableBleeds.Count -eq 0) {
    throw "No bleed-*.tsv manifests found in $outDir. Run .\scripts\audit-locale-bleed.ps1 first."
  }
  $bundle = Join-Path $outDir 'bundle-all.md'
  $bleedFiles = $availableBleeds
} else {
  $bleed   = Join-Path $outDir ('bleed-' + $Locale + '.tsv')
  $bundle  = Join-Path $outDir ('bundle-' + $Locale + '.md')
  if (-not (Test-Path $bleed)) {
    $known = if ($availableLocales) { ($availableLocales -join ', ') } else { '(none yet - run audit-locale-bleed.ps1 first)' }
    throw "bleed-$Locale.tsv not found. Known locales: $known. To export all locales in one bundle, run with -All (or no parameters)."
  }
  $bleedFiles = @(Get-Item $bleed)
}

# ----- Sentinel masker -------------------------------------------------------
# Regex patterns (order matters: longest/most-specific first). Each match is
# replaced with <<P#>> where # is a per-row counter. The original text is kept
# in $legend so the importer can unmask.
$maskPatterns = @(
  # Doc-tag lines first (most specific).
  '@(?:type|range|unit|default|options):[^\r\n]*'
  # URLs.
  'https?://[^\s]+'
  # Hex color codes.
  '#[0-9A-Fa-f]{6}\b'
  # Bukkit color/format codes.
  '&[0-9A-Fa-fk-orK-OR]'
  # Section markers (§ followed by code).
  '\u00A7[0-9A-Fa-fk-orK-OR]?'
  # Bracketed placeholders.
  '\[[A-Za-z_][A-Za-z0-9_]*\]'
)

function Mask-Text {
  param([string] $text, [System.Collections.ArrayList] $legend)
  if ([string]::IsNullOrEmpty($text)) { return $text }
  $masked = $text
  foreach ($pat in $maskPatterns) {
    $masked = [regex]::Replace($masked, $pat, {
      param($m)
      $token = '<<P{0}>>' -f $legend.Count
      [void]$legend.Add($m.Value)
      return $token
    })
  }
  return $masked
}

# ----- Comment escape (TSV uses literal \n / \t) ----------------------------
function Unescape-TsvComment {
  param([string] $s)
  if ($null -eq $s) { return '' }
  $s = $s -replace '\\n', "`n"
  $s = $s -replace '\\t', "`t"
  return $s
}

function Indent-Block {
  param([string] $s, [string] $prefix = '  ')
  if ([string]::IsNullOrEmpty($s)) { return $prefix }
  return (($s -split "`n") | ForEach-Object { $prefix + $_ }) -join "`n"
}

# ----- Emit bundle -----------------------------------------------------------
$sb = New-Object System.Text.StringBuilder

$titleLocale = if ($All) { 'all locales' } else { '`' + $Locale + '`' }
[void]$sb.AppendLine("# Translation bundle: $titleLocale")
[void]$sb.AppendLine()
[void]$sb.AppendLine("Generated by ``scripts\export-translation-bundle.ps1``.")
if ($All) {
  [void]$sb.AppendLine("Sources: " + (($bleedFiles | ForEach-Object { '`scripts\out\' + $_.Name + '`' }) -join ', ') + ".")
} else {
  [void]$sb.AppendLine("Source manifest: ``scripts\out\bleed-$Locale.tsv``.")
}
[void]$sb.AppendLine()
[void]$sb.AppendLine("## How to use this file")
[void]$sb.AppendLine()
[void]$sb.AppendLine("1. **Only edit ``value_locale``, ``comment_locale``, and (optionally) ``key_locale``** fields in each block.")
[void]$sb.AppendLine("   Do not touch ``locale``, ``relpath``, ``parent_path``, ``base_key``, ``index``, ``sentinels``, ``value_english``,")
[void]$sb.AppendLine("   ``comment_english``, or ``blank_before``. Do not reorder or delete blocks.")
[void]$sb.AppendLine("2. **The ``locale:`` field at the top of each block names the target language** (e.g. ``de``, ``fr``, ``ja``).")
[void]$sb.AppendLine("   Translate ``value_locale`` and ``comment_locale`` into that language. The importer dispatches each")
[void]$sb.AppendLine("   block to the matching ``scripts\out\locale-<locale>.tsv``.")
[void]$sb.AppendLine("3. **Preserve every sentinel of the form ``<<P0>>``, ``<<P1>>``, etc. verbatim.**")
[void]$sb.AppendLine("   They mask placeholders, color codes, doc-tags, and URLs that must round-trip without modification.")
[void]$sb.AppendLine("   The ``sentinels:`` field lists what each token represents - use it as a hint when ordering them")
[void]$sb.AppendLine("   in the translated sentence, but never inline them.")
[void]$sb.AppendLine("4. **Lines starting with ``@type:`` / ``@range:`` / ``@unit:`` / ``@default:`` / ``@options:`` are masked")
[void]$sb.AppendLine("   as a single sentinel** and must remain a single sentinel in the translation.")
[void]$sb.AppendLine("5. Leave a block as-is to skip it; the importer will re-seed those rows with a ``# TODO(i18n):`` marker")
[void]$sb.AppendLine("   so contributors can pick them up later.")
[void]$sb.AppendLine("6. Special locales: ``cat`` is **Internet Cat dialect** (not Catalan) - use the cat-themed vocabulary")
[void]$sb.AppendLine("   from ``.junie/AGENTS.md`` (e.g. ``scratching_post`` for database, ``yarn_ball`` for network).")
[void]$sb.AppendLine("7. Save as UTF-8 (no BOM) and feed back through:")
if ($All) {
  [void]$sb.AppendLine("   ``.\scripts\import-translation-bundle.ps1 -All``")
} else {
  [void]$sb.AppendLine("   ``.\scripts\import-translation-bundle.ps1 -Locale $Locale``")
}
[void]$sb.AppendLine()
[void]$sb.AppendLine("## Rows")
[void]$sb.AppendLine()

$ordinal = 0
$perLocaleCounts = @{}
foreach ($bf in $bleedFiles) {
  $lang = $bf.BaseName -replace '^bleed-', ''
  $lines = Get-Content -LiteralPath $bf.FullName -Encoding UTF8
  if ($lines.Count -le 1) {
    Write-Host "No bleed rows for $lang. Skipping."
    continue
  }
  $rows = $lines | Select-Object -Skip 1
  if ($MaxRows -gt 0) { $rows = $rows | Select-Object -First $MaxRows }
  $perLocaleCounts[$lang] = 0

  foreach ($line in $rows) {
    $c = $line -split "`t", 8
    if ($c.Length -ne 8) { continue }
    $ordinal++
    $perLocaleCounts[$lang]++

    $relpath        = $c[0]
    $parentPath     = $c[1]
    $keyCol         = $c[2]
    $index          = $c[3]
    $valueEn        = $c[4]
    $commentEnRaw   = $c[5]
    $blankBefore    = $c[6]
    $baseKey        = $c[7]

    $file = $relpath -replace '^lang/[^/]+/', ''

    $legend = New-Object System.Collections.ArrayList
    $valueMasked   = Mask-Text -text $valueEn -legend $legend
    $commentRaw    = Unescape-TsvComment -s $commentEnRaw
    $commentMasked = Mask-Text -text $commentRaw -legend $legend

    $legendStr = if ($legend.Count -eq 0) { '(none)' } else {
      (0..($legend.Count - 1) | ForEach-Object {
        'P{0}={1}' -f $_, ($legend[$_] -replace "`r?`n", '\n')
      }) -join '; '
    }

    [void]$sb.AppendLine(("### {0}. [{1}] {2}::{3} [{4}:{5}]" -f $ordinal, $lang, $file, $baseKey, $parentPath, $index))
    [void]$sb.AppendLine('```bundle')
    [void]$sb.AppendLine(("locale:           {0}" -f $lang))
    [void]$sb.AppendLine(("base_key:         {0}" -f $baseKey))
    [void]$sb.AppendLine(("parent_path:      {0}" -f $parentPath))
    [void]$sb.AppendLine(("index:            {0}" -f $index))
    [void]$sb.AppendLine(("relpath:          {0}" -f $relpath))
    [void]$sb.AppendLine(("blank_before:     {0}" -f $blankBefore))
    [void]$sb.AppendLine(("sentinels:        {0}" -f $legendStr))
    [void]$sb.AppendLine("comment_english: |")
    [void]$sb.AppendLine((Indent-Block -s $commentMasked -prefix '  '))
    [void]$sb.AppendLine(("value_english:    {0}" -f $valueMasked))
    [void]$sb.AppendLine(("key_locale:       {0}" -f $keyCol))
    [void]$sb.AppendLine(("value_locale:     {0}" -f $valueMasked))
    [void]$sb.AppendLine("comment_locale: |")
    [void]$sb.AppendLine((Indent-Block -s $commentMasked -prefix '  '))
    [void]$sb.AppendLine('```')
    [void]$sb.AppendLine()
  }
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($bundle, $sb.ToString(), $utf8NoBom)

Write-Host ("Wrote {0} rows -> {1}" -f $ordinal, $bundle)
if ($All -and $perLocaleCounts.Count -gt 0) {
  Write-Host ""
  Write-Host "Per-locale row counts:"
  $perLocaleCounts.GetEnumerator() | Sort-Object Name | ForEach-Object {
    Write-Host ("  {0,-5} {1,5}" -f $_.Key, $_.Value)
  }
}
Write-Host ""
Write-Host "Hand the bundle to your external translation session, save it back to the same"
Write-Host "path (or pass -BundlePath), then run:"
if ($All) {
  Write-Host "  .\scripts\import-translation-bundle.ps1 -All"
} else {
  Write-Host ("  .\scripts\import-translation-bundle.ps1 -Locale {0}" -f $Locale)
}
