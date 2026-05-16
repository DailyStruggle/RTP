[CmdletBinding()]
param(
    [string]$Root = "rtp-plugin\src\main\resources",
    [switch]$DryRun,
    [switch]$Verbose2
)

# Convert inline trailing `# ...` comments to above-key block comments.
# Per ADR-025/ADR-042 (block-only YAML comments). Operates on a single YAML
# file at a time; the entire pass is mechanical and per-line.
#
# Algorithm per line:
#   1. If the trimmed line is empty or starts with `#`, emit unchanged.
#   2. Walk characters; track double-quoted (`"`) and single-quoted (`'`)
#      string state with backslash escapes inside double-quotes only
#      (YAML single-quote escape is `''`, which we honor).
#   3. Find the first unquoted `#` that is preceded by at least one
#      whitespace character (so we don't mis-split `#FFAA00` in an
#      unquoted scalar — although our subset effectively forbids those,
#      we still want to be conservative).
#   4. Split into [pre][space-run + `#` + comment-tail].
#      Trim trailing whitespace from `pre` (the value line).
#   5. Compute the indent of the source line (leading whitespace, copied
#      verbatim — does NOT include the `-` of a list item).
#      For list items (` - foo: bar # ...`), the comment must go above
#      the LIST ITEM, so we use the indent up to and including the
#      column of `-` (e.g. `  ` for `  - foo`).
#   6. Emit `${indent}#${commentTail}` then the cleaned line.
#
# Notes:
#   - `commentTail` keeps its leading space if any: `# comment` not `#comment`,
#     which matches the typical style in the existing files.
#   - We never split a line whose only `#` is inside a quoted string.
#   - We never alter a pure comment, blank line, or list item without a comment.

function Convert-YamlFile {
    param([string]$Path, [switch]$DryRun, [switch]$Verbose2)

    $original = Get-Content -LiteralPath $Path -Raw
    if ($null -eq $original) { return @{ Changed = $false; Inline = 0 } }

    # Preserve original line endings: detect CRLF vs LF
    $useCrlf = $original -match "`r`n"
    $nl = if ($useCrlf) { "`r`n" } else { "`n" }

    # Split preserving structure; trim only the trailing newline we add back
    $hadTrailingNl = $original.EndsWith("`n")
    $lines = $original -split "`r?`n"
    if ($hadTrailingNl -and $lines[-1] -eq '') {
        # The final empty element is the artifact of a trailing newline; drop it
        $lines = $lines[0..($lines.Length - 2)]
    }

    $out = New-Object System.Collections.Generic.List[string]
    $inlineCount = 0

    foreach ($line in $lines) {
        $trimmed = $line.TrimStart()
        if ($trimmed.Length -eq 0 -or $trimmed[0] -eq '#') {
            $out.Add($line) | Out-Null
            continue
        }

        # Walk characters to find the comment split point
        $inDouble = $false
        $inSingle = $false
        $splitAt = -1
        for ($i = 0; $i -lt $line.Length; $i++) {
            $c = $line[$i]
            if ($inDouble) {
                if ($c -eq '\') { $i++; continue }    # consume escape pair
                if ($c -eq '"') { $inDouble = $false }
                continue
            }
            if ($inSingle) {
                if ($c -eq "'") {
                    # YAML single-quote escape: '' inside single-quoted string
                    if ($i + 1 -lt $line.Length -and $line[$i + 1] -eq "'") { $i++; continue }
                    $inSingle = $false
                }
                continue
            }
            if ($c -eq '"') { $inDouble = $true; continue }
            if ($c -eq "'") { $inSingle = $true; continue }
            if ($c -eq '#') {
                # require whitespace immediately before, otherwise it's part of a token
                if ($i -gt 0 -and ($line[$i - 1] -eq ' ' -or $line[$i - 1] -eq "`t")) {
                    $splitAt = $i
                    break
                }
            }
        }

        if ($splitAt -lt 0) {
            $out.Add($line) | Out-Null
            continue
        }

        # Pre is everything before $splitAt; strip trailing whitespace.
        $pre = $line.Substring(0, $splitAt).TrimEnd()
        $tail = $line.Substring($splitAt + 1)    # contents after the '#'

        # Determine indent for the block comment.
        # For a list item, the comment should go ABOVE the `- ` line at the
        # same column as the `-`. The leading whitespace of the source line
        # is therefore the correct indent in both regular-key and list-item
        # cases.
        $indent = ''
        for ($j = 0; $j -lt $line.Length; $j++) {
            if ($line[$j] -eq ' ' -or $line[$j] -eq "`t") { $indent += $line[$j] } else { break }
        }

        # Build the new block-comment line. Preserve the existing convention
        # of "# comment" (one space after #) by ensuring exactly one leading
        # space in the tail.
        $tailNorm = $tail
        if ($tailNorm.Length -eq 0 -or $tailNorm[0] -ne ' ') { $tailNorm = ' ' + $tailNorm }
        $blockLine = $indent + '#' + $tailNorm

        $out.Add($blockLine) | Out-Null
        $out.Add($pre) | Out-Null
        $inlineCount++

        if ($Verbose2) {
            Write-Host "  [$Path] split:" -ForegroundColor DarkGray
            Write-Host "    orig : $line" -ForegroundColor DarkGray
            Write-Host "    above: $blockLine" -ForegroundColor DarkGreen
            Write-Host "    line : $pre" -ForegroundColor DarkGreen
        }
    }

    $result = [string]::Join($nl, $out)
    if ($hadTrailingNl) { $result += $nl }

    $changed = ($result -ne $original)
    if ($changed -and -not $DryRun) {
        # Write without BOM, using the original line ending convention
        [System.IO.File]::WriteAllText($Path, $result, (New-Object System.Text.UTF8Encoding $false))
    }
    return @{ Changed = $changed; Inline = $inlineCount }
}

$files = Get-ChildItem -Recurse $Root -Filter "*.yml"
$totalChanged = 0
$totalInline = 0
foreach ($f in $files) {
    $r = Convert-YamlFile -Path $f.FullName -DryRun:$DryRun -Verbose2:$Verbose2
    if ($r.Changed) {
        $totalChanged++
        $totalInline += $r.Inline
        $marker = if ($DryRun) { '[dry]' } else { '[wrote]' }
        Write-Host "$marker $($f.FullName.Substring((Get-Location).Path.Length + 1))  ($($r.Inline) inline → block)"
    }
}
Write-Host ""
Write-Host "Done. Files changed: $totalChanged. Inline comments converted: $totalInline."
