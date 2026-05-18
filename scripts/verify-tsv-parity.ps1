$ErrorActionPreference = 'Stop'
$outDir = Join-Path $PSScriptRoot 'out'
$blRows = (Get-Content (Join-Path $outDir 'baseline.tsv') -Encoding UTF8 | Select-Object -Skip 1) | ForEach-Object {
    $c = $_ -split "`t"
    $rel = $c[0] -replace "^lang/(shape|vert)/",'$1/'
    [PSCustomObject]@{ rel=$rel; base=$c[7]; idx=$c[3] }
}
$locs = 'cat','de','es','fr','ja','ko','nl','pt','ru','zh'
foreach($l in $locs){
    $path = Join-Path $outDir "locale-$l.tsv"
    $locRows = (Get-Content $path -Encoding UTF8 | Select-Object -Skip 1) | ForEach-Object {
        $c = $_ -split "`t"
        $rel = $c[0] -replace "^lang/$l/(shape|vert)/",'$1/' -replace "^lang/$l/",''
        [PSCustomObject]@{ rel=$rel; base=$c[7]; idx=$c[3] }
    }
    $mismatch = 0
    $samples = @()
    for($i=0; $i -lt [Math]::Min($blRows.Count,$locRows.Count); $i++){
        if($blRows[$i].rel -ne $locRows[$i].rel -or $blRows[$i].base -ne $locRows[$i].base -or $blRows[$i].idx -ne $locRows[$i].idx){
            $mismatch++
            if($samples.Count -lt 3){ $samples += "row $i baseline=[$($blRows[$i].rel)|$($blRows[$i].base)|$($blRows[$i].idx)] locale=[$($locRows[$i].rel)|$($locRows[$i].base)|$($locRows[$i].idx)]" }
        }
    }
    "{0,-4} mismatches={1}" -f $l, $mismatch
    if($l -eq 'de'){ $samples | ForEach-Object { "    $_" } }
}
