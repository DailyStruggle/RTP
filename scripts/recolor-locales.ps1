$files = Get-ChildItem $PSScriptRoot\out\locale-*.tsv
foreach ($f in $files) {
  $c = Get-Content -Raw -Encoding UTF8 $f.FullName
  $arrow = [char]0x25B6
  $c = $c -replace '&6&l', '#5FB3B3&l'
  $c = $c -replace "&6$arrow", "#9D7CD8$arrow"
  $c = $c -replace "&7$arrow admin", "#9D7CD8$arrow admin"  # heal: only admin-panel row used &6 originally; teleport rows use &a
  $c = $c -replace '&6', '&7'
  Set-Content -Path $f.FullName -Value $c -Encoding UTF8 -NoNewline
  Write-Host "Updated $($f.Name)"
}
