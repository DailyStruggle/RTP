param([string]$file)

$orig = (git show "HEAD:$($file -replace '\\','/')") -join "`n"
$origLines = $orig -split "`n"
$cur = [System.IO.File]::ReadAllText((Resolve-Path $file))
$curLines = $cur -split "`n"

# Strip CR for comparison
function Norm($s) { return ($s -replace "`r$",'') }

# For each current line containing "??", find the matching original line by structural similarity:
# replace runs of 1-3 non-ASCII chars in original with "??" and compare.
$iconRegex = '[^\x00-\x7F]+'
$origIndexed = @{}
foreach ($ol in $origLines) {
    $olNorm = Norm $ol
    # Replace original em-dashes/special with the same chars the working copy uses now
    $working = $olNorm -replace [char]0x2014, ','
    $key = ($working -replace $iconRegex, '??')
    if (-not $origIndexed.ContainsKey($key)) {
        # Store the original (with icons) but with em-dashes already converted to commas
        $origIndexed[$key] = $working
    }
}

$replacedCount = 0
for ($i = 0; $i -lt $curLines.Count; $i++) {
    $line = Norm $curLines[$i]
    if ($line -match '\?\?') {
        if ($origIndexed.ContainsKey($line)) {
            $curLines[$i] = $origIndexed[$line]
            $replacedCount++
        }
    }
}

$out = $curLines -join "`n"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText((Resolve-Path $file), $out, $utf8NoBom)
Write-Host "Replaced $replacedCount lines in $file"
