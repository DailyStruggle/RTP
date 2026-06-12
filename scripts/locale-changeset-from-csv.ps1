# locale-changeset-from-csv.ps1
#
# SECONDARY component of the locale config pipeline (the "changeset" workflow).
#
# Reads the translated scripts/out/changeset.csv produced (and filled in) from
# locale-changeset-to-csv.ps1 and propagates each per-language column back into
# the corresponding scripts/out/locale-<lang>.tsv row, matched by the same
# langmap logic the reconcile step uses (relpath -> locale relpath, base_key ->
# translated key).
#
# After running this, regenerate the YAML tree the usual way:
#     .\scripts\locale-files-from-csv.ps1
# and verify:
#     .\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"
#
# Cell semantics:
#   - A non-empty per-language cell that differs from the row's `english`
#     column overwrites the locale TSV value (this is a real translation).
#   - A cell equal to `english`, or empty, is treated as "not translated yet"
#     and left unchanged by default (so a partially-filled changeset is safe to
#     apply repeatedly). Use -IncludeEnglish to also write English-equal cells
#     (e.g. when a string legitimately reads the same in that language).
#   - Only the `value` of an EXISTING locale TSV row is updated; rows absent
#     from a locale TSV are reported and skipped (run reconcile first to seed
#     them).
#
# Options:
#   -IncludeEnglish     Also write per-language cells that equal the `english`
#                       column (for strings that legitimately read the same).
#   -UntranslatedOnly   Only overwrite a locale TSV row when its CURRENT value
#                       is still untranslated (empty or equal to the English
#                       baseline). Protects existing human translations from
#                       being clobbered when re-applying an old or partial
#                       changeset.

[CmdletBinding()]
param(
    [string] $OutDir,
    [string] $InFile,
    [switch] $IncludeEnglish,
    [switch] $UntranslatedOnly
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
if (-not $scriptDir) {
    if ($MyInvocation.MyCommand.Path) {
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
}
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$repoRoot = Split-Path -Parent $scriptDir
if (-not $repoRoot) { $repoRoot = (Get-Location).Path }

if (-not $OutDir) { $OutDir = Join-Path $scriptDir 'out' }
if (-not $InFile) { $InFile = Join-Path $OutDir 'changeset.csv' }

if (-not (Test-Path -LiteralPath $InFile)) {
    throw "changeset.csv not found at $InFile. Run locale-changeset-to-csv.ps1 first."
}

. (Join-Path $scriptDir 'locale-changeset-common.ps1')

$csv = Read-Csv $InFile
$header = $csv.Header
$fixedCols = @('relpath','parent_path','base_key','index','english')
foreach ($c in $fixedCols) {
    if ($header -notcontains $c) {
        throw "changeset.csv is missing required column '$c'. Expected header: $($fixedCols -join ', '), <locale>, ..."
    }
}
$locales = @($header | Where-Object { $_ -notin $fixedCols })
if ($locales.Count -eq 0) {
    throw "changeset.csv has no per-locale columns to apply."
}

$totalApplied = 0
$missingByLoc = @{}

foreach ($loc in $locales) {
    $localeCsv = Join-Path $OutDir "locale-$loc.tsv"
    if (-not (Test-Path -LiteralPath $localeCsv)) {
        Write-Host "Skipping $loc (no locale-$loc.tsv)"
        continue
    }
    $rows = Read-Tsv $localeCsv

    # Index locale rows by (relpath|parent_path|key|index) for in-place update.
    $idx = @{}
    foreach ($r in $rows) {
        $k = "{0}|{1}|{2}|{3}" -f $r.relpath, $r.parent_path, $r.key, $r.index
        $idx[$k] = $r
    }
    $langmaps = Build-Langmaps -LocaleRows $rows -Loc $loc

    $applied = 0
    $skipped = 0
    $missing = New-Object System.Collections.Generic.List[string]

    foreach ($cr in $csv.Rows) {
        $cell = [string]$cr.$loc
        $english = [string]$cr.english
        if ([string]::IsNullOrEmpty($cell)) { continue }
        if (-not $IncludeEnglish -and $cell -eq $english) { continue }

        $basePath = [string]$cr.relpath
        $locPath = Get-LocaleRelpath $basePath $loc
        $langmap = $null
        if ($basePath -notlike 'lang/*' -and $langmaps.ContainsKey($basePath)) {
            $langmap = $langmaps[$basePath]
        }

        # base_key/parent_path in the changeset are baseline-space; translate to
        # the locale's effective key/parent. For list items base_key is the
        # parent's top segment and key is empty.
        $baseKey = [string]$cr.base_key
        $baseParent = [string]$cr.parent_path
        $index = [string]$cr.index

        # The baseline leaf key: for scalar/map rows it equals base_key; for
        # list items (index set, key empty) the leaf key is empty.
        $isListItem = ($index -ne '' -and ($baseKey -eq '' -or $baseParent -ne ''))
        $baseLeafKey = if ($index -ne '') { '' } else { $baseKey }

        $effKey = $baseLeafKey
        if ($null -ne $langmap -and $baseLeafKey -ne '' -and $langmap.ContainsKey($baseLeafKey)) {
            $effKey = $langmap[$baseLeafKey]
        }
        $effParent = $baseParent
        if ($null -ne $langmap -and $baseParent -ne '' -and $langmap.ContainsKey($baseParent)) {
            $effParent = $langmap[$baseParent]
        }

        $lk = "{0}|{1}|{2}|{3}" -f $locPath, $effParent, $effKey, $index
        if ($idx.ContainsKey($lk)) {
            $current = [string]$idx[$lk].value
            if ($UntranslatedOnly -and $current -ne '' -and $current -ne $english) {
                # Target already carries a real translation; do not clobber it.
                $skipped++
                continue
            }
            if ($current -ne $cell) {
                $idx[$lk].value = $cell
                $applied++
            }
        } else {
            $missing.Add(("{0} :: {1} :: {2}" -f $basePath, $baseKey, $index)) | Out-Null
        }
    }

    if ($applied -gt 0) {
        Write-Tsv $rows $localeCsv
    }
    $totalApplied += $applied
    $missingByLoc[$loc] = $missing
    if ($UntranslatedOnly) {
        Write-Host ("{0}: applied={1} skipped(already-translated)={2} missing={3}" -f $loc, $applied, $skipped, $missing.Count)
    } else {
        Write-Host ("{0}: applied={1} missing={2}" -f $loc, $applied, $missing.Count)
    }
}

Write-Host ("Done. Applied {0} translation(s) across {1} locale(s)." -f $totalApplied, $locales.Count)

$anyMissing = $false
foreach ($loc in $locales) {
    $m = $missingByLoc[$loc]
    if ($m -and $m.Count -gt 0) {
        if (-not $anyMissing) {
            Write-Host ""
            Write-Host "Rows not found in some locale TSVs (run reconcile-locale-csvs.ps1 to seed, then re-run):"
            $anyMissing = $true
        }
        foreach ($entry in $m) { Write-Host ("  [{0}] {1}" -f $loc, $entry) }
    }
}
