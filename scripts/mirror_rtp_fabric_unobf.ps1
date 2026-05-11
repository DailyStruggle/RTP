# One-shot helper for CHECKLIST-fabric-obf-unobf-split.md Phase D (D1+D2+D3).
# Mirrors the four NM-typed files from rtp-fabric-common into
# rtp-fabric-common-unobf, rewriting:
#   - package decl: rtp.fabric.{world,player} -> rtp.fabric.unobf.{world,player}
#                   rtp.fabric.effects (new package; FabricEffectsHandler is in rtp-plugin)
#   - intra-set imports & references: class names get the "Unobf" suffix
#   - Originals are left in place (intermediary path keeps working;
#     thinning of obf carriers is a Phase E3 concern, mirroring the
#     ADR-006 step 5 pattern used for effects-api/fabric_unobf).
$ErrorActionPreference = 'Stop'

$pairs = @(
  @{ Src = "rtp-fabric\rtp-fabric-common\src\main\java\io\github\dailystruggle\rtp\fabric\world\FabricRTPChunk.java";
     Dst = "rtp-fabric\rtp-fabric-common-unobf\src\main\java\io\github\dailystruggle\rtp\fabric\unobf\world\FabricRTPChunkUnobf.java" },
  @{ Src = "rtp-fabric\rtp-fabric-common\src\main\java\io\github\dailystruggle\rtp\fabric\world\FabricRTPWorld.java";
     Dst = "rtp-fabric\rtp-fabric-common-unobf\src\main\java\io\github\dailystruggle\rtp\fabric\unobf\world\FabricRTPWorldUnobf.java" },
  @{ Src = "rtp-fabric\rtp-fabric-common\src\main\java\io\github\dailystruggle\rtp\fabric\player\FabricRTPPlayer.java";
     Dst = "rtp-fabric\rtp-fabric-common-unobf\src\main\java\io\github\dailystruggle\rtp\fabric\unobf\player\FabricRTPPlayerUnobf.java" },
  @{ Src = "rtp-plugin\src\main\java\io\github\dailystruggle\rtp\fabric\effects\FabricEffectsHandler.java";
     Dst = "rtp-fabric\rtp-fabric-common-unobf\src\main\java\io\github\dailystruggle\rtp\fabric\unobf\effects\FabricEffectsHandlerUnobf.java" }
)

Remove-Item -LiteralPath "rtp-fabric\rtp-fabric-common-unobf\src\main\java\io\github\dailystruggle\rtp\fabric\unobf\package-info.txt" -ErrorAction SilentlyContinue

foreach ($p in $pairs) {
  $dstDir = Split-Path $p.Dst -Parent
  if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
  $content = Get-Content -Raw -LiteralPath $p.Src

  # Package: world/player -> unobf.world/unobf.player; effects has no source pkg in common,
  # but FabricEffectsHandler lives in rtp-plugin's rtp.fabric.effects -> rtp.fabric.unobf.effects.
  $rewritten = $content `
    -replace 'package\s+io\.github\.dailystruggle\.rtp\.fabric\.(world|player|effects);', 'package io.github.dailystruggle.rtp.fabric.unobf.$1;' `
    -replace 'import\s+io\.github\.dailystruggle\.rtp\.fabric\.world\.FabricRTPChunk\s*;', 'import io.github.dailystruggle.rtp.fabric.unobf.world.FabricRTPChunkUnobf;' `
    -replace 'import\s+io\.github\.dailystruggle\.rtp\.fabric\.world\.FabricRTPWorld\s*;', 'import io.github.dailystruggle.rtp.fabric.unobf.world.FabricRTPWorldUnobf;' `
    -replace 'import\s+io\.github\.dailystruggle\.rtp\.fabric\.player\.FabricRTPPlayer\s*;', 'import io.github.dailystruggle.rtp.fabric.unobf.player.FabricRTPPlayerUnobf;' `
    -replace 'import\s+io\.github\.dailystruggle\.effectsapi\.fabric\.FabricEffectRuntime\s*;', 'import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf;' `
    -replace 'import\s+io\.github\.dailystruggle\.effectsapi\.fabric\.FabricEffectsInitializer\s*;', 'import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectsInitializer;' `
    -replace '\bFabricEffectRuntime\b', 'FabricEffectRuntimeUnobf' `
    -replace '\bFabricRTPChunk\b', 'FabricRTPChunkUnobf' `
    -replace '\bFabricRTPWorld\b', 'FabricRTPWorldUnobf' `
    -replace '\bFabricRTPPlayer\b', 'FabricRTPPlayerUnobf' `
    -replace '\bFabricEffectsHandler\b', 'FabricEffectsHandlerUnobf'
  Set-Content -LiteralPath $p.Dst -Value $rewritten -NoNewline
  Write-Host "Wrote $($p.Dst)"
}
