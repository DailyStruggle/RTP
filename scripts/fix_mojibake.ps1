$files=@(
 'rtp-plugin\src\main\resources\lang\ja\logging.yml',
 'rtp-plugin\src\main\resources\lang\ja\messages.yml',
 'rtp-plugin\src\main\resources\lang\ja\performance.yml',
 'rtp-plugin\src\main\resources\lang\ko\logging.yml',
 'rtp-plugin\src\main\resources\lang\ko\messages.yml',
 'rtp-plugin\src\main\resources\lang\ko\performance.yml',
 'rtp-plugin\src\main\resources\lang\zh\logging.yml',
 'rtp-plugin\src\main\resources\lang\zh\messages.yml',
 'rtp-plugin\src\main\resources\lang\zh\performance.yml'
)
$cp1252=[System.Text.Encoding]::GetEncoding(1252)
foreach($f in $files){
  $b=[System.IO.File]::ReadAllBytes($f)
  $text=[System.Text.Encoding]::UTF8.GetString($b)
  $recovered=$cp1252.GetBytes($text)
  [System.IO.File]::WriteAllBytes($f,$recovered)
  Write-Host "Fixed $f"
}
