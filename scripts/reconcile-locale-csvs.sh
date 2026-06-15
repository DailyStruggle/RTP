#!/usr/bin/env bash
# reconcile-locale-csvs.sh
#
# Bash port of reconcile-locale-csvs.ps1. Forces every scripts/out/locale-<lang>.tsv
# to match baseline.tsv's (relpath-shape, parent_path, key, index) row sequence:
#   - seed missing baseline rows (English value + baseline comment/blank);
#   - drop locale rows whose tuple is not in the baseline file;
#   - preserve existing locale values/comments where the row matches;
#   - heal stale-placeholder drift back to the English baseline value.
# Also pre-seeds identity langmap rows into baseline lang/<file>.lang.yml for
# every top-level baseline key, and rewrites baseline.tsv with them.
set -euo pipefail

OutDir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir) OutDir="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
[ -n "$OutDir" ] || OutDir="$scriptDir/out"
baselineCsv="$OutDir/baseline.tsv"
[ -f "$baselineCsv" ] || { echo "baseline.tsv not found at $baselineCsv. Run locale-files-to-csv.sh first." >&2; exit 1; }

langRoot="$repoRoot/rtp-plugin/src/main/resources/lang"

# ---- 1. augment baseline with identity langmap rows ----
AUG_AWK='
function dec(v,PH){PH=sprintf("%c",1);gsub(/\\\\/,PH,v);gsub(/\\n/,"\n",v);gsub(/\\t/,"\t",v);gsub(PH,"\\",v);return v}
function esc(v){gsub(/\\/,"\\\\",v);gsub(/\n/,"\\n",v);gsub(/\t/,"\\t",v);return v}
BEGIN{FS="\t"}
NR==1{next}
{
  raw=$0; rp=dec($1); pp=dec($2); key=dec($3); idx=dec($4)
  if(!(rp in seen)){seen[rp]=1; order[++no]=rp}
  k=++c[rp]; line[rp,k]=raw; P[rp,k]=pp; K[rp,k]=key; I[rp,k]=idx
}
END{
  for(o=1;o<=no;o++){ rp=order[o]
    if(rp ~ /^lang\//) continue
    if(rp=="plugin.yml"||rp=="language.yml") continue
    lang="lang/" rp; sub(/\.yml$/,".lang.yml",lang)
    if(!(lang in seen)) continue
    delete ex
    for(i=1;i<=c[lang];i++){ if(K[lang,i]!="" && P[lang,i]=="" && I[lang,i]=="") ex[K[lang,i]]=1 }
    for(i=1;i<=c[rp];i++){ if(P[rp,i]!=""||K[rp,i]==""||I[rp,i]!="") continue
      vk=K[rp,i]; if(vk in ex) continue
      add[lang, ++nadd[lang]]=vk; ex[vk]=1 }
  }
  print "relpath\tparent_path\tkey\tindex\tvalue\tpreceding_comment\tblank_before\tbase_key"
  for(o=1;o<=no;o++){ rp=order[o]
    for(i=1;i<=c[rp];i++) print line[rp,i]
    if(rp in nadd){ for(i=1;i<=nadd[rp];i++){ vk=add[rp,i]
      print esc(rp) "\t\t" esc(vk) "\t\t" esc(vk) "\t\t0\t" esc(vk) } }
  }
}'
tmpBase="$(mktemp)"
awk "$AUG_AWK" "$baselineCsv" > "$tmpBase"
mv "$tmpBase" "$baselineCsv"

# ---- 2. discover locales ----
declare -a locales=()
while IFS= read -r d; do
  n="$(basename "$d")"
  case "$n" in shape|vert) continue ;; esac
  locales+=("$n")
done < <(find "$langRoot" -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort)

# ---- 3. reconcile each locale ----
RECONCILE_AWK='
function dec(v,PH){PH=sprintf("%c",1);gsub(/\\\\/,PH,v);gsub(/\\n/,"\n",v);gsub(/\\t/,"\t",v);gsub(PH,"\\",v);return v}
function esc(v){gsub(/\\/,"\\\\",v);gsub(/\n/,"\\n",v);gsub(/\t/,"\\t",v);return v}
function localeRel(bp){ if(bp ~ /^lang\//) return "lang/" LOC "/" substr(bp,6); return "lang/" LOC "/" bp }
function row(rp,pp,key,idx,val,cmt,blank,bk){
  return esc(rp)"\t"esc(pp)"\t"esc(key)"\t"esc(idx)"\t"esc(val)"\t"esc(cmt)"\t"esc(blank)"\t"esc(bk)
}
function collectPH(s,arr,  t,tok){ delete arr; t=s
  while(match(t,/\[[^][:space:]]+\]/)){ tok=substr(t,RSTART,RLENGTH); arr[tok]=1; t=substr(t,RSTART+RLENGTH) } }
function hasStale(v,baseArr,  t,tok){ t=v
  while(match(t,/\[[^][:space:]]+\]/)){ tok=substr(t,RSTART,RLENGTH); if(!(tok in baseArr)) return 1; t=substr(t,RSTART+RLENGTH) }
  return 0 }
BEGIN{FS="\t"}
FNR==1{ fileNo++; next }
fileNo==1{
  rp=dec($1)
  if(!(rp in bseen)){bseen[rp]=1; bord[++bno]=rp}
  k=++bc[rp]
  bP[rp,k]=dec($2); bK[rp,k]=dec($3); bI[rp,k]=dec($4); bV[rp,k]=dec($5)
  bC[rp,k]=dec($6); bB[rp,k]=dec($7); bBK[rp,k]=dec($8)
  next
}
{
  # locale rows
  li++
  lRP[li]=dec($1); lPP[li]=dec($2); lK[li]=dec($3); lI[li]=dec($4); lV[li]=dec($5)
  lC[li]=dec($6); lB[li]=dec($7); lBK[li]=dec($8)
  idxk=lRP[li]"|"lPP[li]"|"lK[li]"|"lI[li]; locIdx[idxk]=li
}
END{
  # build langmaps: valueRelpath -> baseKey -> translatedKey (first wins)
  for(i=1;i<=li;i++){ rp=lRP[i]
    if(rp ~ ("^lang/" LOC "/.*\\.lang\\.yml$")) continue
    if(index(rp,"lang/" LOC "/")!=1) continue
    if(lK[i]=="" || lBK[i]=="") continue
    if(lBK[i]==lK[i]) continue
    tail=substr(rp, length("lang/" LOC "/")+1)
    if(tail ~ /^shape\// || tail ~ /^vert\//) vr="lang/" tail; else vr=tail
    mk=vr SUBSEP lBK[i]
    if(!(mk in lmseen)){ lmseen[mk]=1; lm[vr,lBK[i]]=lK[i] }
  }
  kept=0; added=0
  out=""
  for(o=1;o<=bno;o++){ bp=bord[o]
    lp=localeRel(bp)
    isValue=(bp !~ /^lang\//)
    for(i=1;i<=bc[bp];i++){
      bpp=bP[bp,i]; bkey=bK[bp,i]; bidx=bI[bp,i]; bval=bV[bp,i]; bcmt=bC[bp,i]; bblank=bB[bp,i]; bbk=bBK[bp,i]
      effKey=bkey; effParent=bpp
      if(isValue){
        if(bkey!="" && ((bp SUBSEP bkey) in lm)) effKey=lm[bp,bkey]
        if(bpp!="" && ((bp SUBSEP bpp) in lm)) effParent=lm[bp,bpp]
      }
      collectPH(bval,basePH)
      lkEff=lp"|"effParent"|"effKey"|"bidx
      lkBase=lp"|"bpp"|"bkey"|"bidx
      if(lkEff in locIdx){
        e=locIdx[lkEff]; uv=lV[e]; if(hasStale(uv,basePH)) uv=bval
        uc=(lC[e]==""?bcmt:lC[e])
        out=out row(lp,effParent,effKey,bidx,uv,uc,bblank,bbk) "\n"; kept++
      } else if((lkBase in locIdx) && effKey!=bkey){
        e=locIdx[lkBase]; uv=lV[e]; if(hasStale(uv,basePH)) uv=bval
        uc=(lC[e]==""?bcmt:lC[e])
        out=out row(lp,effParent,effKey,bidx,uv,uc,bblank,bbk) "\n"; kept++
      } else {
        out=out row(lp,effParent,effKey,bidx,bval,bcmt,bblank,bbk) "\n"; added++
      }
    }
  }
  # dropped count (informational only)
  dropped=0
  for(i=1;i<=li;i++){ rp=lRP[i]
    if(index(rp,"lang/" LOC "/")!=1){ dropped++; continue }
    tail=substr(rp,length("lang/" LOC "/")+1)
    if(tail ~ /\.lang\.yml$/ || tail ~ /^shape\// || tail ~ /^vert\//) bp="lang/" tail; else bp=tail
    if(!(bp in bseen)){ dropped++; continue }
    found=0
    for(j=1;j<=bc[bp];j++){ if(bP[bp,j]==lPP[i] && bK[bp,j]==lK[i] && bI[bp,j]==lI[i]){ found=1; break } }
    if(!found) dropped++
  }
  printf "relpath\tparent_path\tkey\tindex\tvalue\tpreceding_comment\tblank_before\tbase_key\n" > OUTFILE
  printf "%s", out > OUTFILE
  close(OUTFILE)
  printf "%s: kept=%d added=%d dropped=%d\n", LOC, kept, added, dropped
}'

for loc in "${locales[@]}"; do
  localeCsv="$OutDir/locale-$loc.tsv"
  if [ ! -f "$localeCsv" ]; then echo "Skipping $loc (no TSV)"; continue; fi
  tmpOut="$(mktemp)"
  awk -v LOC="$loc" -v OUTFILE="$tmpOut" "$RECONCILE_AWK" "$baselineCsv" "$localeCsv"
  mv "$tmpOut" "$localeCsv"
done

echo "Done."
