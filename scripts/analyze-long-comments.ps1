<#
.SYNOPSIS
  Find excessively long / verbose comments in Java sources to help strip the
  "AI-generated" feel from the codebase.

.DESCRIPTION
  Scans .java files under one or more roots, extracts every comment block
  ("//" runs are coalesced into a single block; "/* ... */" and Javadoc are
  one block each), and ranks them by line count, char count, and a simple
  verbosity heuristic. Emits a CSV report plus a console top-N summary.

  Heuristic flags (printed in the Flags column):
    L = long          (>= MinLines lines)
    C = many chars    (>= MinChars characters)
    R = ratio-heavy   (block is long AND its file is comment-dense)
    A = AI-tells      (contains hedging/filler phrases commonly emitted by LLMs)
    B = banner        (decorative ====/----/#### lines or boxed banner)
    T = TODO/FIXME    (kept for triage; usually NOT a strip candidate)

.PARAMETER Roots
  One or more directories to scan. Defaults to the main source modules.

.PARAMETER MinLines
  A comment block must have at least this many lines to be reported. Default 6.

.PARAMETER MinChars
  Or at least this many characters. Default 400.

.PARAMETER TopN
  How many worst offenders to print to the console. Default 30.

.PARAMETER OutCsv
  Output CSV path. Default: scripts\out\long-comments.csv

.EXAMPLE
  .\scripts\analyze-long-comments.ps1
  .\scripts\analyze-long-comments.ps1 -Roots rtp-core,rtp-api -MinLines 8 -TopN 50
#>
param(
    [string[]] $Roots = @(
        'rtp-core', 'rtp-api', 'rtp-plugin',
        'rtp-bukkit', 'rtp-paper', 'rtp-folia', 'rtp-fabric',
        'rtp-anvil', 'commands-api', 'effects-api', 'rtp-tags', 'addons'
    ),
    [int]    $MinLines = 6,
    [int]    $MinChars = 400,
    [int]    $TopN     = 30,
    [string] $OutCsv   = 'scripts\out\long-comments.csv'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# Resolve roots that actually exist.
$resolvedRoots = @()
foreach ($r in $Roots) {
    $p = Join-Path $repoRoot $r
    if (Test-Path $p) { $resolvedRoots += (Resolve-Path $p).Path }
}
if (-not $resolvedRoots) { Write-Error "No valid roots found."; return }

# Phrases that frequently betray LLM-authored prose. Lowercased substring match.
$aiTells = @(
    "let's", "let us ", "we'll ", "we will ",
    "in summary", "in conclusion", "to summarize",
    "it's worth noting", "it is worth noting",
    "note that", "please note", "as mentioned",
    "as you can see", "as we can see",
    "this method is responsible for",
    "this class is responsible for",
    "this function is responsible for",
    "essentially,", "basically,", "fundamentally,",
    "furthermore,", "moreover,", "additionally,",
    "in other words", "that is to say",
    "step-by-step", "step by step",
    "high-level overview", "at a high level",
    "for the sake of", "out of the box",
    "robust", "seamless", "leverage", "leverages",
    "comprehensive", "elegant solution",
    "best practice", "industry standard"
)

$bannerRegex = '^[\s/*#]*[-=*#_]{6,}[\s/*#]*$'

function New-CommentBlock {
    param($File, $Kind, $StartLine, $EndLine, $Text, $RawLines)
    $charCount = $Text.Length
    $lineCount = $EndLine - $StartLine + 1

    $lower = $Text.ToLowerInvariant()
    $aiHits = 0
    foreach ($t in $aiTells) { if ($lower.Contains($t)) { $aiHits++ } }

    $bannerLines = 0
    foreach ($ln in $RawLines) { if ($ln -match $bannerRegex) { $bannerLines++ } }

    $isTodo = ($lower -match '\b(todo|fixme|xxx|hack)\b')

    [pscustomobject]@{
        File       = $File
        Kind       = $Kind          # line | block | javadoc
        StartLine  = $StartLine
        EndLine    = $EndLine
        Lines      = $lineCount
        Chars      = $charCount
        AITells    = $aiHits
        BannerLn   = $bannerLines
        IsTodo     = $isTodo
        Preview    = $(
            $p = ($Text -replace '\s+', ' ').TrimStart()
            if ($p.Length -gt 120) { $p.Substring(0, 120) } else { $p }
        )
    }
}

function Get-CommentsFromFile {
    param([string] $Path)

    $text = [System.IO.File]::ReadAllText($Path)
    $lines = $text -split "`r?`n"
    $n = $lines.Length

    $results = New-Object System.Collections.Generic.List[object]

    # 1) Block + Javadoc comments via regex over full text, then map indices to lines.
    $blockMatches = [regex]::Matches($text, '/\*[\s\S]*?\*/')
    foreach ($m in $blockMatches) {
        $startIdx = $m.Index
        $endIdx   = $m.Index + $m.Length - 1
        $startLine = ($text.Substring(0, $startIdx) -split "`n").Length
        $endLine   = ($text.Substring(0, $endIdx + 1) -split "`n").Length
        $kind = if ($m.Value.StartsWith('/**')) { 'javadoc' } else { 'block' }
        $raw = $m.Value -split "`r?`n"
        $results.Add( (New-CommentBlock -File $Path -Kind $kind `
            -StartLine $startLine -EndLine $endLine `
            -Text $m.Value -RawLines $raw) )
    }

    # 2) Line comments: coalesce consecutive `//` lines (ignoring those inside strings is
    #    approximated — we accept some noise; the goal is triage, not refactoring).
    $i = 0
    while ($i -lt $n) {
        $ln = $lines[$i]
        # Skip lines that are inside a /* */ already counted; cheap check: if line has
        # only block-comment content, the // detector below ignores it because there is
        # no leading // on those lines.
        if ($ln -match '^\s*//') {
            $start = $i
            $bufLines = New-Object System.Collections.Generic.List[string]
            while ($i -lt $n -and $lines[$i] -match '^\s*//') {
                $bufLines.Add($lines[$i])
                $i++
            }
            $end = $i - 1
            if (($end - $start + 1) -ge 2 -or ($bufLines -join "`n").Length -ge 80) {
                $joined = ($bufLines -join "`n")
                $results.Add( (New-CommentBlock -File $Path -Kind 'line' `
                    -StartLine ($start + 1) -EndLine ($end + 1) `
                    -Text $joined -RawLines $bufLines) )
            }
        } else {
            $i++
        }
    }

    return $results
}

# --- Scan ---
Write-Host "Scanning Java sources under:" -ForegroundColor Cyan
$resolvedRoots | ForEach-Object { Write-Host "  $_" }

$javaFiles = @()
foreach ($r in $resolvedRoots) {
    $javaFiles += Get-ChildItem -Path $r -Recurse -Filter *.java -File `
        | Where-Object { $_.FullName -notmatch '[\\/](build|target|out|\.gradle|\.idea)[\\/]' }
}
Write-Host ("Files: {0}" -f $javaFiles.Count)

$all = New-Object System.Collections.Generic.List[object]
foreach ($f in $javaFiles) {
    try {
        $blocks = Get-CommentsFromFile -Path $f.FullName
        foreach ($b in $blocks) { $all.Add($b) }
    } catch {
        Write-Warning "Failed to scan $($f.FullName): $_"
    }
}

# Per-file comment density (chars of comments / chars of file) for ratio flag.
$fileSize = @{}
foreach ($f in $javaFiles) { $fileSize[$f.FullName] = (Get-Item $f.FullName).Length }
$commentCharsByFile = @{}
foreach ($b in $all) {
    if (-not $commentCharsByFile.ContainsKey($b.File)) { $commentCharsByFile[$b.File] = 0 }
    $commentCharsByFile[$b.File] += $b.Chars
}

# Score and flag.
$rows = foreach ($b in $all) {
    $flags = ''
    if ($b.Lines  -ge $MinLines) { $flags += 'L' }
    if ($b.Chars  -ge $MinChars) { $flags += 'C' }
    if ($b.AITells -gt 0)        { $flags += 'A' }
    if ($b.BannerLn -ge 2)       { $flags += 'B' }
    if ($b.IsTodo)               { $flags += 'T' }

    $fSize = [Math]::Max(1, [int]$fileSize[$b.File])
    $density = ($commentCharsByFile[$b.File] / $fSize)
    if ($density -ge 0.30 -and $b.Lines -ge $MinLines) { $flags += 'R' }

    # Score: line count + char/40 + 5 per AI tell + 3 per banner line; T halves it.
    $score = $b.Lines + ($b.Chars / 40) + ($b.AITells * 5) + ($b.BannerLn * 3)
    if ($b.IsTodo) { $score = $score / 2 }

    [pscustomobject]@{
        Score      = [math]::Round($score, 1)
        Flags      = $flags
        Lines      = $b.Lines
        Chars      = $b.Chars
        AITells    = $b.AITells
        BannerLn   = $b.BannerLn
        Kind       = $b.Kind
        File       = ($b.File.Substring($repoRoot.Length).TrimStart('\','/'))
        StartLine  = $b.StartLine
        EndLine    = $b.EndLine
        Preview    = $b.Preview
    }
}

# Filter: only blocks meeting at least one length threshold OR carrying AI-tells.
$candidates = $rows | Where-Object {
    $_.Lines -ge $MinLines -or $_.Chars -ge $MinChars -or $_.AITells -gt 0
} | Sort-Object Score -Descending

# --- Output ---
$outDir = Split-Path -Parent (Join-Path $repoRoot $OutCsv)
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$csvPath = Join-Path $repoRoot $OutCsv
$candidates | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "Wrote $($candidates.Count) candidate comment blocks to:" -ForegroundColor Green
Write-Host "  $csvPath"

Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
"Total comment blocks scanned : {0}" -f $all.Count
"Candidates (>= thresholds)   : {0}" -f $candidates.Count
"  with AI-tells (A)          : {0}" -f (($candidates | Where-Object { $_.Flags -match 'A' }).Count)
"  banners (B)                : {0}" -f (($candidates | Where-Object { $_.Flags -match 'B' }).Count)
"  ratio-heavy files (R)      : {0}" -f (($candidates | Where-Object { $_.Flags -match 'R' }).Count)
"  TODO/FIXME (T, deprio)     : {0}" -f (($candidates | Where-Object { $_.Flags -match 'T' }).Count)

Write-Host ""
Write-Host "=== Top files by total candidate comment chars ===" -ForegroundColor Cyan
$candidates | Group-Object File | ForEach-Object {
    [pscustomobject]@{
        File       = $_.Name
        Blocks     = $_.Count
        TotalLines = ($_.Group | Measure-Object Lines -Sum).Sum
        TotalChars = ($_.Group | Measure-Object Chars -Sum).Sum
    }
} | Sort-Object TotalChars -Descending | Select-Object -First 20 | Format-Table -AutoSize

Write-Host ""
Write-Host "=== Top $TopN worst offenders (by score) ===" -ForegroundColor Cyan
$candidates | Select-Object -First $TopN |
    Format-Table Score, Flags, Lines, Chars, AITells, Kind,
        @{n='Location';e={ "$($_.File):$($_.StartLine)-$($_.EndLine)" }},
        Preview -AutoSize -Wrap

Write-Host ""
Write-Host "Flag legend: L=long  C=many-chars  A=AI-tells  B=banner  R=ratio-heavy-file  T=TODO/FIXME(deprio)" -ForegroundColor DarkGray
Write-Host "Next step: open the CSV, sort by Score desc, and triage top entries by hand." -ForegroundColor DarkGray
