$path = "CHANGELOG.md"
$lines = [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)
$entry = [System.IO.File]::ReadAllText("_new_entry.txt", [System.Text.Encoding]::UTF8).TrimEnd("`r","`n")
# Insert new bullet + blank line right after current line 13 (existing SQLite bullet)
# Line indices are 0-based: line 13 -> index 12
$out = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $lines.Length; $i++) {
    $out.Add($lines[$i]) | Out-Null
    if ($i -eq 12) {
        $out.Add("") | Out-Null
        $out.Add($entry) | Out-Null
    }
}
[System.IO.File]::WriteAllLines($path, $out, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "OK"
