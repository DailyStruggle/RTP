#!/usr/bin/env bash
# locale-changeset-from-csv.sh
#
# Bash port of locale-changeset-from-csv.ps1. Reads the translated
# scripts/out/changeset.csv and propagates each per-language column back into
# the corresponding scripts/out/locale-<lang>.tsv row (matched by the same
# langmap logic the reconcile step uses).
#
# Cell semantics:
#   - non-empty cell differing from `english` overwrites the locale TSV value;
#   - empty or english-equal cells are skipped (use --include-english to also
#     write english-equal cells);
#   - only EXISTING locale rows are updated; missing rows are reported.
#
# Options:
#   --include-english     also write per-language cells equal to `english`.
#   --untranslated-only   only overwrite when the locale's current value is
#                         still untranslated (empty or equal to english).
set -euo pipefail

OutDir=""
InFile=""
IncludeEnglish=0
UntransFlag=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir) OutDir="$2"; shift 2 ;;
    --in-file) InFile="$2"; shift 2 ;;
    --include-english) IncludeEnglish=1; shift ;;
    --untranslated-only) UntransFlag=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$scriptDir/locale-changeset-common.sh"

[ -n "$OutDir" ] || OutDir="$scriptDir/out"
[ -n "$InFile" ] || InFile="$OutDir/changeset.csv"
[ -f "$InFile" ] || { echo "changeset.csv not found at $InFile. Run locale-changeset-to-csv.sh first." >&2; exit 1; }

# Validate header and extract per-locale columns (fields after the fixed five).
header="$(head -n1 "$InFile")"
mapfile -t locales < <(printf '%s' "$header" | awk '
function splitCsv(line,arr,  i,c,f,inq,nf){ nf=0; f=""; inq=0
  for(i=1;i<=length(line);i++){ c=substr(line,i,1)
    if(inq){ if(c=="\""){ if(substr(line,i+1,1)=="\""){ f=f "\""; i++ } else inq=0 } else f=f c }
    else { if(c=="\""){ inq=1 } else if(c==","){ arr[++nf]=f; f="" } else f=f c } }
  arr[++nf]=f; return nf }
{ n=splitCsv($0,a); fixed="relpath parent_path base_key index english"
  for(i=1;i<=n;i++){ if(index(" " fixed " "," " a[i] " ")==0) print a[i] } }')

for c in relpath parent_path base_key index english; do
  if ! printf '%s,' "$header" | grep -q "$c"; then
    echo "changeset.csv is missing required column '$c'." >&2; exit 1
  fi
done
[ "${#locales[@]}" -gt 0 ] || { echo "changeset.csv has no per-locale columns to apply." >&2; exit 1; }

UPDATE_AWK="$CS_AWK_FUNCS"'
function splitCsv(line,arr,  i,c,f,inq,nf){ nf=0; f=""; inq=0
  for(i=1;i<=length(line);i++){ c=substr(line,i,1)
    if(inq){ if(c=="\""){ if(substr(line,i+1,1)=="\""){ f=f "\""; i++ } else inq=0 } else f=f c }
    else { if(c=="\""){ inq=1 } else if(c==","){ arr[++nf]=f; f="" } else f=f c } }
  arr[++nf]=f; return nf }
BEGIN{ FS="\t"; fileNo=0; ncs=0; nl=0 }
FNR==1{ fileNo++
  if(fileNo==1){ n=splitCsv($0,h); for(i=1;i<=n;i++) col[h[i]]=i
    locCol=col[LOC]; cRel=col["relpath"]; cPP=col["parent_path"]; cBK=col["base_key"]; cIdx=col["index"]; cEng=col["english"] }
  next
}
fileNo==1{
  n=splitCsv($0,f); ncs++
  cs_rel[ncs]=f[cRel]; cs_pp[ncs]=f[cPP]; cs_bk[ncs]=f[cBK]; cs_idx[ncs]=f[cIdx]; cs_eng[ncs]=f[cEng]
  cs_cell[ncs]=(locCol<=n)?f[locCol]:""
  next
}
{
  nl++
  R_rp[nl]=dec($1); R_pp[nl]=dec($2); R_key[nl]=dec($3); R_idx[nl]=dec($4); R_val[nl]=dec($5)
  R_cmt[nl]=dec($6); R_blank[nl]=dec($7); R_bk[nl]=dec($8)
  ik=R_rp[nl]"|"R_pp[nl]"|"R_key[nl]"|"R_idx[nl]; locIdx[ik]=nl
  # langmap
  rp=R_rp[nl]
  if(rp ~ ("^lang/" LOC "/.*\\.lang\\.yml$")) next
  if(index(rp,"lang/" LOC "/")!=1) next
  if(R_key[nl]=="" || R_bk[nl]=="") next
  if(R_bk[nl]==R_key[nl]) next
  tail=substr(rp,length("lang/" LOC "/")+1)
  if(tail ~ /^shape\// || tail ~ /^vert\//) vr="lang/" tail; else vr=tail
  mk=vr SUBSEP R_bk[nl]
  if(!(mk in lmseen)){ lmseen[mk]=1; lm[vr,R_bk[nl]]=R_key[nl] }
}
END{
  applied=0; skipped=0; missing=0
  for(c=1;c<=ncs;c++){
    cell=cs_cell[c]; english=cs_eng[c]
    if(cell=="") continue
    if(INCLUDE_ENGLISH!="1" && cell==english) continue
    bp=cs_rel[c]; lp=gLocaleRel(bp,LOC)
    isValue=(bp !~ /^lang\//)
    baseKey=cs_bk[c]; baseParent=cs_pp[c]; index=cs_idx[c]
    baseLeafKey=(index!="")?"":baseKey
    effKey=baseLeafKey; effParent=baseParent
    if(isValue){
      if(baseLeafKey!="" && ((bp SUBSEP baseLeafKey) in lm)) effKey=lm[bp,baseLeafKey]
      if(baseParent!="" && ((bp SUBSEP baseParent) in lm)) effParent=lm[bp,baseParent]
    }
    lk=lp"|"effParent"|"effKey"|"index
    if(lk in locIdx){
      e=locIdx[lk]; current=R_val[e]
      if(UNTRANS=="1" && current!="" && current!=english){ skipped++; continue }
      if(current!=cell){ R_val[e]=cell; applied++ }
    } else {
      missing++
      mlist[++nm]= bp " :: " baseKey " :: " index
    }
  }
  if(applied>0){
    printf "relpath\tparent_path\tkey\tindex\tvalue\tpreceding_comment\tblank_before\tbase_key\n" > OUTFILE
    for(i=1;i<=nl;i++){
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", esc(R_rp[i]),esc(R_pp[i]),esc(R_key[i]),esc(R_idx[i]),esc(R_val[i]),esc(R_cmt[i]),esc(R_blank[i]),esc(R_bk[i]) > OUTFILE
    }
    close(OUTFILE)
  }
  if(UNTRANS=="1") printf "%s: applied=%d skipped(already-translated)=%d missing=%d\n", LOC, applied, skipped, missing
  else printf "%s: applied=%d missing=%d\n", LOC, applied, missing
  for(i=1;i<=nm;i++) print "  [" LOC "] " mlist[i] > "/dev/stderr"
}'

totalApplied=0
for loc in "${locales[@]}"; do
  localeCsv="$OutDir/locale-$loc.tsv"
  if [ ! -f "$localeCsv" ]; then echo "Skipping $loc (no locale-$loc.tsv)"; continue; fi
  tmpOut="$(mktemp)"; : > "$tmpOut"
  out="$(awk -v LOC="$loc" -v INCLUDE_ENGLISH="$IncludeEnglish" -v UNTRANS="$UntransFlag" -v OUTFILE="$tmpOut" \
        "$UPDATE_AWK" "$InFile" "$localeCsv")"
  echo "$out"
  if [ -s "$tmpOut" ]; then mv "$tmpOut" "$localeCsv"; else rm -f "$tmpOut"; fi
done

echo "Done."
