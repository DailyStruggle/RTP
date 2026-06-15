#!/usr/bin/env bash
# locale-changeset-to-csv.sh
#
# Bash port of locale-changeset-to-csv.ps1. Exports a single wide, RFC-4180
# comma-separated scripts/out/changeset.csv with one row per changed baseline
# key and one column per language:
#   relpath, parent_path, base_key, index, english, <loc1>, <loc2>, ...
#
# Row selection:
#   --keys "<spec>"     (repeatable) one of "<relpath>", "<relpath>:<base_key>",
#                       or "<base_key>". Explicit selection.
#   --untranslated-only (default when --keys omitted) include rows still equal
#                       to the English baseline in at least one locale.
#   --all               include every translatable baseline value row.
# Read-only with respect to the YAML tree and per-locale TSVs.
set -euo pipefail

OutDir=""
OutFile=""
AllFlag=0
UntransFlag=0
declare -a Keys=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir) OutDir="$2"; shift 2 ;;
    --out-file) OutFile="$2"; shift 2 ;;
    --keys) Keys+=("$2"); shift 2 ;;
    --all) AllFlag=1; shift ;;
    --untranslated-only) UntransFlag=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
. "$scriptDir/locale-changeset-common.sh"

[ -n "$OutDir" ] || OutDir="$scriptDir/out"
[ -n "$OutFile" ] || OutFile="$OutDir/changeset.csv"
baselineCsv="$OutDir/baseline.tsv"
[ -f "$baselineCsv" ] || { echo "baseline.tsv not found at $baselineCsv. Run locale-files-to-csv.sh first." >&2; exit 1; }

langRoot="$repoRoot/rtp-plugin/src/main/resources/lang"
mapfile -t allLocales < <(cs_discover_locales "$langRoot")

# Only feed locales that actually have a TSV (preserve discovery order).
declare -a locales=()
declare -a localeFiles=()
for loc in "${allLocales[@]}"; do
  f="$OutDir/locale-$loc.tsv"
  if [ -f "$f" ]; then locales+=("$loc"); localeFiles+=("$f"); fi
done

# Default selection mode.
if [ "${#Keys[@]}" -eq 0 ] && [ "$AllFlag" -eq 0 ]; then UntransFlag=1; fi

LOCALES_JOINED="$(printf '%s\n' "${locales[@]}")"
KEYS_JOINED="$(printf '%s\n' "${Keys[@]:-}")"

awk -v OUTFILE="$OutFile" -v LOCALES="$LOCALES_JOINED" -v KEYS="$KEYS_JOINED" -v NKEYS="${#Keys[@]}" -v UNTRANS="$UntransFlag" "$CS_AWK_FUNCS"'
function matchKeys(rp,bk,  i,n,arr,entry,p,ci){
  if(NKEYS==0) return 1
  n=split(KEYS,arr,"\n")
  for(i=1;i<=n;i++){ entry=arr[i]; if(entry=="") continue
    ci=index(entry,":")
    if(ci>0){ p1=substr(entry,1,ci-1); p2=substr(entry,ci+1); if(rp==p1 && bk==p2) return 1 }
    else if(entry ~ /\.yml$/){ if(rp==entry) return 1 }
    else { if(bk==entry) return 1 }
  }
  return 0
}
function isTranslatable(rp,key,idx,val){
  if(val=="__MAP_OR_LIST_PARENT__") return 0
  if(key=="" && idx=="") return 0
  if(rp ~ /\.lang\.yml$/ && rp !~ /^lang\/shape\// && rp !~ /^lang\/vert\//) return 0
  return 1
}
BEGIN{ FS="\t"; nloc=split(LOCALES,LOC,"\n"); ll=0; for(i=1;i<=nloc;i++) if(LOC[i]!="") loc2[++ll]=LOC[i] }
FNR==1{
  fn=FILENAME; sub(/^.*\//,"",fn)
  if(fn=="baseline.tsv"){ curloc="" } else { curloc=fn; sub(/^locale-/,"",curloc); sub(/\.tsv$/,"",curloc) }
  next
}
curloc==""{
  rp=dec($1)
  if(!(rp in bseen)){bseen[rp]=1; bord[++bno]=rp}
  k=++bc[rp]
  bP[rp,k]=dec($2); bK[rp,k]=dec($3); bI[rp,k]=dec($4); bV[rp,k]=dec($5); bBK[rp,k]=dec($8)
  next
}
{
  rp=dec($1); pp=dec($2); key=dec($3); idx=dec($4); val=dec($5); bk=dec($8)
  ik=curloc SUBSEP rp "|" pp "|" key "|" idx; locVal[ik]=val
  # langmap
  if(rp ~ ("^lang/" curloc "/.*\\.lang\\.yml$")) next
  if(index(rp,"lang/" curloc "/")!=1) next
  if(key=="" || bk=="") next
  if(bk==key) next
  tail=substr(rp,length("lang/" curloc "/")+1)
  if(tail ~ /^shape\// || tail ~ /^vert\//) vr="lang/" tail; else vr=tail
  mk=curloc SUBSEP vr SUBSEP bk
  if(!(mk in lmseen)){ lmseen[mk]=1; lm[mk]=key }
}
END{
  # header
  hdr="relpath,parent_path,base_key,index,english"
  for(l=1;l<=ll;l++) hdr=hdr "," csvfield(loc2[l])
  print hdr > OUTFILE
  count=0
  for(o=1;o<=bno;o++){ bp=bord[o]
    isValue=(bp !~ /^lang\//)
    for(i=1;i<=bc[bp];i++){
      pp=bP[bp,i]; key=bK[bp,i]; idx=bI[bp,i]; val=bV[bp,i]; bk=bBK[bp,i]
      if(!isTranslatable(bp,key,idx,val)) continue
      if(NKEYS>0 && !matchKeys(bp,bk)) continue
      anyUn=0
      delete cur
      for(l=1;l<=ll;l++){ loc=loc2[l]
        lp=gLocaleRel(bp,loc)
        effKey=key; effParent=pp
        if(isValue){
          if(key!="" && ((loc SUBSEP bp SUBSEP key) in lm)) effKey=lm[loc SUBSEP bp SUBSEP key]
          if(pp!="" && ((loc SUBSEP bp SUBSEP pp) in lm)) effParent=lm[loc SUBSEP bp SUBSEP pp]
        }
        ik=loc SUBSEP lp "|" effParent "|" effKey "|" idx
        v=(ik in locVal)?locVal[ik]:""
        cur[l]=v
        if(v=="" || v==val) anyUn=1
      }
      if(UNTRANS=="1" && !anyUn) continue
      lineout=csvfield(bp) "," csvfield(pp) "," csvfield(bk) "," csvfield(idx) "," csvfield(val)
      for(l=1;l<=ll;l++) lineout=lineout "," csvfield(cur[l])
      print lineout > OUTFILE
      count++
    }
  }
  close(OUTFILE)
  printf "Wrote %d changeset row(s) for %d locale(s) -> %s\n", count, ll, OUTFILE
  if(count==0) print "Nothing to translate with the current selection."
}
' "$baselineCsv" "${localeFiles[@]}"
