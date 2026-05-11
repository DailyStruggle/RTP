# One-shot helper for CHECKLIST-fabric-obf-unobf-split.md step C3.
# Mirrors the 11 NM-typed files from effectsapi/fabric/ into the new sibling
# module's effectsapi/fabric_unobf/ package, rewriting `package` declarations
# and intra-set imports. Originals are left in place (Phase E3 thinning gates
# their removal per effects-api-ADR-006 step 5).
$ErrorActionPreference = 'Stop'
$src = "effects-api\src\main\java\io\github\dailystruggle\effectsapi\fabric"
$dst = "effects-api\effects-api-fabric-unobf\src\main\java\io\github\dailystruggle\effectsapi\fabric_unobf"
$files = @(
  "FabricEffectsInitializer.java",
  "FabricRegistryCompat.java",
  "FabricValueCoercer.java",
  "LocalEffects\FabricParticleEffect.java",
  "LocalEffects\FabricPotionEffect.java",
  "LocalEffects\FabricSoundEffect.java",
  "LocalEffects\FabricTitleEffect.java",
  "LocalEffects\enums\FabricParticleKeys.java",
  "LocalEffects\enums\FabricPotionKeys.java",
  "LocalEffects\enums\FabricSoundKeys.java",
  "LocalEffects\enums\FabricTitleKeys.java"
)
Remove-Item -Path "$dst\package-info.txt" -ErrorAction SilentlyContinue
foreach ($rel in $files) {
  $srcPath = Join-Path $src $rel
  $dstPath = Join-Path $dst $rel
  $dstDir = Split-Path $dstPath -Parent
  if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
  $content = Get-Content -Raw -LiteralPath $srcPath
  $rewritten = $content `
    -replace 'package\s+io\.github\.dailystruggle\.effectsapi\.fabric(\.[^;\s]+)?;', 'package io.github.dailystruggle.effectsapi.fabric_unobf$1;' `
    -replace 'import\s+io\.github\.dailystruggle\.effectsapi\.fabric\.(FabricEffectsInitializer|FabricRegistryCompat|FabricValueCoercer|LocalEffects)', 'import io.github.dailystruggle.effectsapi.fabric_unobf.$1' `
    -replace 'import\s+io\.github\.dailystruggle\.effectsapi\.fabric\.FabricEffectRuntime\s*;', 'import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf;' `
    -replace '\bFabricEffectRuntime\b', 'FabricEffectRuntimeUnobf'
  Set-Content -LiteralPath $dstPath -Value $rewritten -NoNewline
  Write-Host "Wrote $dstPath"
}
