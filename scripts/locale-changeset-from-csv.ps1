# locale-changeset-from-csv.ps1
#
# SECONDARY component of the locale config pipeline (the "changeset" workflow).
#
# Reads the translated scripts/out/changeset.csv produced (and filled in) from
# locale-changeset-to-csv.ps1 and propagates each per-language value column, its
# paired '<locale>_comment' column, AND its paired '<locale>_key' column back
# into the corresponding scripts/out/locale-<lang>.tsv row, matched by the same
# langmap logic the reconcile step uses (relpath -> locale relpath, base_key ->
# translated key).
#
# After running this, regenerate the YAML tree the usual way:
#     .\scripts\locale-files-from-csv.ps1
# and verify:
#     .\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"
#
# Cell semantics (applied independently to the value, comment and key of a row):
#   - A non-empty per-language cell that differs from the matching `english` /
#     `english_comment` column (or, for the key, the `base_key` column)
#     overwrites the locale TSV value / preceding comment / key (a real
#     translation).
#   - A cell equal to its English column, or empty, is treated as "not
#     translated yet" and left unchanged by default (so a partially-filled
#     changeset is safe to apply repeatedly). Use -IncludeEnglish to also write
#     English-equal cells (e.g. a string that legitimately reads the same).
#   - Comment cells use the same backslash escaping as the per-locale TSV's
#     preceding_comment column (newline -> \n, tab -> \t) so a multi-line
#     comment block round-trips on a single CSV line.
#   - The '<locale>_key' cell localizes the key name itself (fed into the
#     synthesized <file>.lang.yml rename map on regen). It is compared against
#     the `base_key` (English key) column.
#   - Rows absent from a locale TSV are SEEDED automatically from the changeset's
#     baseline-space data (relpath -> locale relpath, base_key, parent_path,
#     index) and then the translated value/comment/key is applied. This is what
#     reconcile-locale-csvs.ps1 would have produced, so the changeset workflow is
#     self-sufficient: running reconcile first is an optimization (it pre-fills
#     the changeset with current per-locale context), not a hard prerequisite.
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
$fixedCols = @('relpath','parent_path','base_key','index','english','english_comment')
foreach ($c in $fixedCols) {
    if ($header -notcontains $c) {
        throw "changeset.csv is missing required column '$c'. Expected header: $($fixedCols -join ', '), <locale>, <locale>_comment, ..."
    }
}
# Per-locale value columns are everything that is neither a fixed column nor a
# paired '<locale>_comment' / '<locale>_key' column.
$locales = @($header | Where-Object { $_ -notin $fixedCols -and $_ -notlike '*_comment' -and $_ -notlike '*_key' })
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
    $seeded = 0
    $missing = New-Object System.Collections.Generic.List[string]

    $commentCol = "${loc}_comment"
    $hasCommentCol = ($csv.Header -contains $commentCol)
    $keyCol = "${loc}_key"
    $hasKeyCol = ($csv.Header -contains $keyCol)

    foreach ($cr in $csv.Rows) {
        $cell = [string]$cr.$loc
        $english = [string]$cr.english
        $commentCell = if ($hasCommentCol) { ConvertFrom-CommentCell ([string]$cr.$commentCol) } else { '' }
        $englishComment = ConvertFrom-CommentCell ([string]$cr.english_comment)
        $keyCell = if ($hasKeyCol) { [string]$cr.$keyCol } else { '' }
        $englishKey = [string]$cr.base_key
        $hasValue = -not [string]::IsNullOrEmpty($cell) -and ($IncludeEnglish -or $cell -ne $english)
        $hasComment = -not [string]::IsNullOrEmpty($commentCell) -and ($IncludeEnglish -or $commentCell -ne $englishComment)
        $hasKey = -not [string]::IsNullOrEmpty($keyCell) -and ($IncludeEnglish -or $keyCell -ne $englishKey)
        if (-not $hasValue -and -not $hasComment -and -not $hasKey) { continue }

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
            $row = $idx[$lk]
            $rowApplied = $false
            if ($hasValue) {
                $current = [string]$row.value
                if ($UntranslatedOnly -and $current -ne '' -and $current -ne $english) {
                    # Target already carries a real translation; do not clobber it.
                    $skipped++
                } elseif ($current -ne $cell) {
                    $row.value = $cell
                    $rowApplied = $true
                }
            }
            if ($hasComment) {
                $currentComment = [string]$row.preceding_comment
                if ($UntranslatedOnly -and $currentComment -ne '' -and $currentComment -ne $englishComment) {
                    # Target already carries a real comment translation; keep it.
                    $skipped++
                } elseif ($currentComment -ne $commentCell) {
                    $row.preceding_comment = $commentCell
                    $rowApplied = $true
                }
            }
            if ($hasKey) {
                $currentKey = [string]$row.key
                if ($UntranslatedOnly -and $currentKey -ne '' -and $currentKey -ne $englishKey) {
                    # Target already carries a localized key; keep it.
                    $skipped++
                } elseif ($currentKey -ne $keyCell) {
                    $row.key = $keyCell
                    $rowApplied = $true
                }
            }
            if ($rowApplied) { $applied++ }
        } else {
            # No matching locale TSV row exists yet (reconcile was not run after
            # the baseline edit, or the locale never had this key). Rather than
            # silently dropping the translation, SEED the row here from the
            # changeset's baseline-space data - exactly what reconcile would have
            # produced - then apply the translated value/comment/key. This makes
            # the changeset workflow self-sufficient and order-independent.
            $seedKey = $effKey
            if ($hasKey) { $seedKey = $keyCell }
            $seedValue = if ($hasValue) { $cell } else { $english }
            $seedComment = if ($hasComment) { $commentCell } else { $englishComment }
            $newRow = [pscustomobject]@{
                relpath           = $locPath
                parent_path       = $effParent
                key               = $seedKey
                index             = $index
                value             = $seedValue
                preceding_comment = $seedComment
                blank_before      = ''
                base_key          = $baseKey
            }
            $rows.Add($newRow) | Out-Null
            $idx[$lk] = $newRow
            # Re-key the index entry to reflect the (possibly localized) seed key
            # so a later changeset row for the same baseline key resolves to it.
            $seedLk = "{0}|{1}|{2}|{3}" -f $locPath, $effParent, $seedKey, $index
            $idx[$seedLk] = $newRow
            $seeded++
            $applied++
        }
    }

    if ($applied -gt 0) {
        Write-Tsv $rows $localeCsv
    }
    $totalApplied += $applied
    $missingByLoc[$loc] = $missing
    if ($UntranslatedOnly) {
        Write-Host ("{0}: applied={1} seeded={2} skipped(already-translated)={3} missing={4}" -f $loc, $applied, $seeded, $skipped, $missing.Count)
    } else {
        Write-Host ("{0}: applied={1} seeded={2} missing={3}" -f $loc, $applied, $seeded, $missing.Count)
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
