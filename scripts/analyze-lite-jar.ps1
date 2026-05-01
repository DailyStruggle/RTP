param([string]$Jar = "C:\Users\lxgol\Documents\GitHub\RTP\rtp-plugin\build\libs\RTP-3.0.0-beta.1.jar")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$entries = $z.Entries | ForEach-Object { [pscustomobject]@{ Name=$_.FullName; Compressed=$_.CompressedLength; Size=$_.Length } }
$z.Dispose()

"JAR: $Jar"
"File size on disk: $([math]::Round((Get-Item $Jar).Length/1KB,1)) KB"
"Entries: $($entries.Count)"
"Total uncompressed: $([math]::Round((($entries | Measure-Object Size -Sum).Sum)/1KB,1)) KB"
"Total compressed:   $([math]::Round((($entries | Measure-Object Compressed -Sum).Sum)/1KB,1)) KB"
""
"=== Top-level segment by compressed size ==="
$entries | Group-Object { ($_.Name -split '/')[0] } | ForEach-Object {
  [pscustomobject]@{
    Top=$_.Name
    Count=$_.Count
    CompKB=[math]::Round((($_.Group | Measure-Object Compressed -Sum).Sum)/1KB,1)
    SizeKB=[math]::Round((($_.Group | Measure-Object Size -Sum).Sum)/1KB,1)
  }
} | Sort-Object CompKB -Descending | Format-Table -AutoSize

""
"=== Class vs resource split ==="
$cls = $entries | Where-Object { $_.Name -match '\.class$' }
$res = $entries | Where-Object { $_.Name -notmatch '\.class$' -and $_.Name -notmatch '/$' }
"Classes: $($cls.Count) entries, comp=$([math]::Round((($cls|Measure-Object Compressed -Sum).Sum)/1KB,1)) KB, size=$([math]::Round((($cls|Measure-Object Size -Sum).Sum)/1KB,1)) KB"
"Resources: $($res.Count) entries, comp=$([math]::Round((($res|Measure-Object Compressed -Sum).Sum)/1KB,1)) KB, size=$([math]::Round((($res|Measure-Object Size -Sum).Sum)/1KB,1)) KB"

""
"=== Top 30 resource files ==="
$res | Sort-Object Compressed -Descending | Select-Object -First 30 | Format-Table @{n='CompKB';e={[math]::Round($_.Compressed/1KB,2)}},@{n='SizeKB';e={[math]::Round($_.Size/1KB,2)}},Name -AutoSize

""
"=== YAML resource files (.yml / .yaml) ==="
$yaml = $res | Where-Object { $_.Name -match '\.ya?ml$' }
$yaml | Format-Table @{n='CompKB';e={[math]::Round($_.Compressed/1KB,2)}},@{n='SizeKB';e={[math]::Round($_.Size/1KB,2)}},Name -AutoSize
"YAML totals: $($yaml.Count) files, comp=$([math]::Round((($yaml|Measure-Object Compressed -Sum).Sum)/1KB,2)) KB, size=$([math]::Round((($yaml|Measure-Object Size -Sum).Sum)/1KB,2)) KB"

""
"=== Class packages by compressed size (depth 4) ==="
$cls | Group-Object { ($_.Name -split '/' | Select-Object -First 4) -join '/' } | ForEach-Object {
  [pscustomobject]@{
    Pkg=$_.Name
    Count=$_.Count
    CompKB=[math]::Round((($_.Group | Measure-Object Compressed -Sum).Sum)/1KB,1)
  }
} | Sort-Object CompKB -Descending | Select-Object -First 25 | Format-Table -AutoSize
