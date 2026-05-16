param([string]$Root = "rtp-plugin\src\main\resources")
$files = Get-ChildItem -Recurse $Root -Filter "*.yml"
$total = 0
$withInline = 0
$report = @()
foreach ($f in $files) {
    $lines = Get-Content -LiteralPath $f.FullName
    $count = 0
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*#') { continue }
        if ($line -match '^\s*$') { continue }
        $stripped = $line -replace '"[^"]*"', '""' -replace "'[^']*'", "''"
        if ($stripped -match '\S.*\s#') { $count++ }
    }
    $total += $count
    if ($count -gt 0) {
        $withInline++
        $report += [pscustomobject]@{ File = $f.FullName.Substring((Get-Location).Path.Length + 1); Inline = $count }
    }
}
"Files with inline comments: $withInline / $($files.Count). Total inline: $total"
$report | Sort-Object Inline -Descending | Format-Table -AutoSize
