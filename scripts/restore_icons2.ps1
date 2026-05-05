param([string]$file)

$gitPath = $file -replace '\\','/'
$origText = (git show "HEAD:$gitPath") -join "`n"
$origLines = $origText -split "`n"

# Build dictionary: ascii-only-fingerprint -> original line content (icons preserved, em-dash -> comma).
function ToFingerprint($s) {
    $s = $s -replace "`r$",''
    # remove all non-ascii (icons, em-dash, special punctuation)
    $s = -join ($s.ToCharArray() | Where-Object { [int]$_ -lt 128 })
    # remove punctuation that varies between original and current (em-dash became comma, icons became ?)
    $s = $s -replace '[\?,\-]',''
    # collapse whitespace
    $s = $s -replace '\s+',''
    return $s.Trim()
}

$map = @{}
foreach ($ol in $origLines) {
    $fp = ToFingerprint $ol
    if ($fp.Length -gt 20 -and -not $map.ContainsKey($fp)) {
        # Convert em-dash to comma in the original to match current file's voice
        $val = ($ol -replace "`r$",'') -replace [char]0x2014, ','
        $map[$fp] = $val
    }
}

$cur = [System.IO.File]::ReadAllText((Resolve-Path $file))
$curLines = $cur -split "`n"

$replaced = 0
$candidates = 0
$missed = 0
for ($i = 0; $i -lt $curLines.Count; $i++) {
    $line = $curLines[$i] -replace "`r$",''
    if ($line -notmatch '\?') { continue }
    $candidates++
    $fp = ToFingerprint $line
    if ($map.ContainsKey($fp)) {
        $curLines[$i] = $map[$fp]
        $replaced++
    } else {
        $missed++
        if ($missed -le 3) { Write-Host "MISS fp=[$fp]" }
    }
}
Write-Host "candidates=$candidates replaced=$replaced missed=$missed"

$out = $curLines -join "`n"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText((Resolve-Path $file), $out, $utf8NoBom)
Write-Host "Replaced $replaced lines in $file"
