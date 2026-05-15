$pluginTestDir = "C:\Users\lxgol\Documents\GitHub\RTP\rtp-plugin\src\test\java\io\github\dailystruggle\rtp\bukkit\commands\test"
$movedToCore = @{
  "TestCancelCmd"        = "io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd"
  "TestSchedulerCmd"     = "io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd"
  "TestConfigSetCmd"     = "io.github.dailystruggle.rtp.common.commands.test.TestConfigSetCmd"
  "TestApiCompatCmd"     = "io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd"
  "TestChunkTicketCmd"   = "io.github.dailystruggle.rtp.common.commands.test.TestChunkTicketCmd"
  "TestAnvilPrefilterCmd"= "io.github.dailystruggle.rtp.common.commands.test.TestAnvilPrefilterCmd"
  "TestBiomeSourceCmd"   = "io.github.dailystruggle.rtp.common.commands.test.TestBiomeSourceCmd"
  "TestSemaphore"        = "io.github.dailystruggle.rtp.common.commands.test.TestSemaphore"
  "ActiveTestJobs"       = "io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs"
  "TestCmd"              = "io.github.dailystruggle.rtp.common.commands.test.TestCmd"
}
$fileRefs = @{
  "ActiveTestJobsTest.java"        = @("ActiveTestJobs")
  "TestAnvilPrefilterCmdTest.java" = @("TestAnvilPrefilterCmd")
  "TestApiCompatCmdTest.java"      = @("TestApiCompatCmd")
  "TestBiomeSourceCmdTest.java"    = @("TestBiomeSourceCmd")
  "TestChunkTicketCmdTest.java"    = @("TestChunkTicketCmd")
  "TestCmdPlatformSplitTest.java"  = @("TestCmd")
  "TestFullCmdTest.java"           = @("TestCmd")
  "TestSemaphoreCancelTest.java"   = @("TestSemaphore","ActiveTestJobs")
  "TestSemaphoreTest.java"         = @("TestSemaphore")
}
foreach ($entry in $fileRefs.GetEnumerator()) {
  $file = Join-Path $pluginTestDir $entry.Key
  if (-not (Test-Path $file)) { Write-Host "MISSING: $($entry.Key)"; continue }
  $lines = [System.IO.File]::ReadAllLines($file)
  $needed = $entry.Value | ForEach-Object { "import $($movedToCore[$_]);" }
  $firstImportIdx = -1
  for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match '^\s*import ') { $firstImportIdx = $i; break }
  }
  if ($firstImportIdx -lt 0) {
    for ($i = 0; $i -lt $lines.Length; $i++) { if ($lines[$i] -match '^\s*package ') { $firstImportIdx = $i + 2; break } }
  }
  $before = if ($firstImportIdx -gt 0) { $lines[0..($firstImportIdx-1)] } else { @() }
  $after  = $lines[$firstImportIdx..($lines.Length-1)]
  $merged = @($before) + @($needed) + @($after)
  [System.IO.File]::WriteAllLines($file, $merged)
  Write-Host "patched $($entry.Key) with $($needed.Count) import(s)"
}
