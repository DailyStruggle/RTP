#!/usr/bin/env bash
# locale-files-from-csv.sh
#
# Bash port of locale-files-from-csv.ps1. Regenerates the
# rtp-plugin/src/main/resources/ YAML tree from baseline.tsv + locale-<lang>.tsv
# under scripts/out/ (or --input-dir). Mirrors the .ps1 emission rules exactly:
#   - one file per distinct relpath, in row order;
#   - string scalars double-quoted, ints/floats/bools/null bare;
#   - "__MAP_OR_LIST_PARENT__" emits "<key>:" with no value;
#   - locale rows fall back to baseline English comments when their
#     preceding_comment is empty;
#   - synthesizes <file>.lang.yml rename-maps from (base_key,key) pairs.
#
# Options:
#   --only <glob>   (repeatable) regenerate only matching relpath/leaf/baseline-rel.
#   --verify        scan regenerated output for mojibake markers; fail if found.
set -euo pipefail

ResourcesRoot=""
InputDir=""
Verify=0
declare -a Only=()
while [ $# -gt 0 ]; do
  case "$1" in
    --resources-root) ResourcesRoot="$2"; shift 2 ;;
    --input-dir)      InputDir="$2"; shift 2 ;;
    --only)           Only+=("$2"); shift 2 ;;
    --verify)         Verify=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
[ -n "$ResourcesRoot" ] || ResourcesRoot="$repoRoot/rtp-plugin/src/main/resources"
[ -n "$InputDir" ] || InputDir="$scriptDir/out"
[ -d "$InputDir" ] || { echo "Input directory not found: $InputDir" >&2; exit 1; }

# Collect baseline.tsv + locale-<2-3 letter>.tsv.
declare -a csvFiles=()
for f in "$InputDir"/*.tsv; do
  [ -e "$f" ] || continue
  bn="$(basename "$f")"
  if [ "$bn" = "baseline.tsv" ] || printf '%s' "$bn" | grep -Eq '^locale-[a-z]{2,3}\.tsv$'; then
    csvFiles+=("$f")
  fi
done
[ "${#csvFiles[@]}" -gt 0 ] || { echo "No baseline.tsv or locale-*.tsv found in $InputDir" >&2; exit 1; }

ONLY_JOINED="$(printf '%s\n' "${Only[@]:-}")"
LISTFILE="$(mktemp)"

awk -v RESROOT="$ResourcesRoot" -v ONLY="$ONLY_JOINED" -v NONLY="${#Only[@]}" -v LISTFILE="$LISTFILE" '
function unesc(v,  PH){ PH=sprintf("%c",1)
  gsub(/\\\\/,PH,v); gsub(/\\n/,"\n",v); gsub(/\\t/,"\t",v); gsub(PH,"\\",v); return v }
function isbare(v){
  if(v=="") return 0
  if(v ~ /^-?[0-9]+$/) return 1
  if(v ~ /^-?[0-9]+\.[0-9]+$/) return 1
  if(v ~ /^(true|false)$/) return 1
  if(v ~ /^(null|~)$/) return 1
  return 0
}
function fscalar(v,  e){
  if(v=="") return "\"\""
  if(isbare(v)) return v
  e=v; gsub(/\\/,"\\\\",e); gsub(/"/,"\\\"",e); return "\"" e "\""
}
function depthOf(p,  a){ if(p=="") return 0; return split(p,a,".") }
function indentOf(p){ return sprintf("%*s","",2*depthOf(p)) }
# wildcard match (glob with * and ?)
function gmatch(s,pat,  r,c,i,out){
  r="^"; for(i=1;i<=length(pat);i++){ c=substr(pat,i,1)
    if(c=="*") r=r ".*"; else if(c=="?") r=r "."; else if(c ~ /[].[^$(){}+|\\]/) r=r "\\" c; else r=r c }
  r=r "$"; return (s ~ r)
}
function resolveBaselineRel(rp,  a,n,loc,tail,seg,i){
  if(rp ~ /^lang\/[a-z]{2,3}\/(shape|vert)\//){
    n=split(rp,a,"/"); loc=a[2]; seg=a[3]; tail=""
    for(i=4;i<=n;i++) tail=(tail==""?a[i]:tail"/"a[i])
    return "lang/" seg "/" tail
  }
  if(rp ~ /^lang\/[a-z]{2,3}\//){
    n=split(rp,a,"/"); tail=""
    for(i=3;i<=n;i++) tail=(tail==""?a[i]:tail"/"a[i])
    return tail
  }
  return rp
}
function inScope(rp,  leaf,bRel,i,n,arr,p){
  if(NONLY==0) return 1
  n=split(rp,arr,"/"); leaf=arr[n]; bRel=resolveBaselineRel(rp)
  n=split(ONLY,parr,"\n")
  for(i=1;i<=n;i++){ p=parr[i]; if(p=="") continue
    if(gmatch(rp,p)||gmatch(leaf,p)||gmatch(bRel,p)) return 1 }
  return 0
}
BEGIN{ FS="\t" }
{
  # skip header lines (any line whose first field == "relpath")
  if($1=="relpath" && $2=="parent_path"){ next }
  rp=unesc($1); pp=unesc($2); key=unesc($3); idx=unesc($4); val=unesc($5)
  cmt=unesc($6); blank=unesc($7); bk=unesc($8)
  # group rows by relpath, preserve order
  if(!(rp in seenRP)){ seenRP[rp]=1; order[++norder]=rp }
  n=++cnt[rp]
  R_pp[rp,n]=pp; R_key[rp,n]=key; R_idx[rp,n]=idx; R_val[rp,n]=val
  R_cmt[rp,n]=cmt; R_blank[rp,n]=blank; R_bk[rp,n]=bk
}
END{
  # build baseline comment lookup
  for(o=1;o<=norder;o++){ rp=order[o]
    if(rp ~ /^lang\/[a-z]{2,3}\//) continue
    for(i=1;i<=cnt[rp];i++){ b=(R_bk[rp,i]==""?R_key[rp,i]:R_bk[rp,i])
      lk=rp "||" b "||" R_pp[rp,i] "||" R_idx[rp,i]
      if(!(lk in bcmt)) bcmt[lk]=R_cmt[rp,i] }
  }
  written=0
  for(o=1;o<=norder;o++){ rp=order[o]
    if(!inScope(rp)) continue
    isLoc=(rp ~ /^lang\/[a-z]{2,3}\//)
    bRel=resolveBaselineRel(rp)
    content=""; first=1
    for(i=1;i<=cnt[rp];i++){
      pp=R_pp[rp,i]; key=R_key[rp,i]; idx=R_idx[rp,i]; val=R_val[rp,i]
      cmt=R_cmt[rp,i]; blank=R_blank[rp,i]; bk=R_bk[rp,i]
      if(isLoc && cmt==""){ b=(bk==""?key:bk); blk=bRel "||" b "||" pp "||" idx
        if(blk in bcmt) cmt=bcmt[blk] }
      ind=indentOf(pp)
      if((blank=="1") && !first) content=content "\n"
      if(cmt!=""){ nc=split(cmt,cl,"\n"); for(j=1;j<=nc;j++){
          if(cl[j]=="") content=content "\n"; else content=content ind cl[j] "\n" } }
      if(val=="__MAP_OR_LIST_PARENT__") content=content ind key ":\n"
      else if(idx!="") content=content ind "- " fscalar(val) "\n"
      else content=content ind key ": " fscalar(val) "\n"
      first=0
    }
    out=RESROOT "/" rp
    sub(/\/[^\/]*$/,"",out); od=out
    cmd="mkdir -p \"" od "\""; system(cmd)
    of=RESROOT "/" rp
    printf "%s",content > of
    close(of)
    print of > LISTFILE
    written++
  }
  # synthesize <file>.lang.yml maps
  langStems="config economy effects logging messages performance regions safety worlds"
  synth=0
  for(o=1;o<=norder;o++){ rp=order[o]
    name=rp; sub(/^.*\//,"",name)
    if(name ~ /\.lang\.yml$/) continue
    if(name !~ /\.yml$/) continue
    stem=name; sub(/\.yml$/,"",stem)
    if(index(" " langStems " "," " stem " ")==0) continue
    if(!inScope(rp)) continue
    dir=rp; if(dir==name) dir=""; else sub(/\/[^\/]*$/,"",dir)
    if(dir=="") langRel="lang/" stem ".lang.yml"; else langRel=dir "/" stem ".lang.yml"
    delete pkeys; np=0
    for(i=1;i<=cnt[rp];i++){ if(R_key[rp,i]=="") continue; if(R_pp[rp,i]!="") continue
      b=(R_bk[rp,i]==""?R_key[rp,i]:R_bk[rp,i])
      if(!(b in pmap)){ pmap[b]=R_key[rp,i]; pkeys[++np]=b } }
    if(np==0){ continue }
    lc="# --- Language Mapping: " name " ---\n"
    lc=lc "# Maps internal baseline keys (left) to user-visible key names used in\n"
    lc=lc "# " name " (right). Generated from the per-locale TSV; edit the TSV" sprintf("%c",39) "s\n"
    lc=lc "# base_key/key columns and regenerate, do not hand-edit this file.\n"
    for(i=1;i<=np;i++){ k=pkeys[i]; lc=lc k ": " fscalar(pmap[k]) "\n" }
    delete pmap
    of=RESROOT "/" langRel; od=of; sub(/\/[^\/]*$/,"",od)
    system("mkdir -p \"" od "\"")
    printf "%s",lc > of; close(of)
    print of > LISTFILE
    synth++
  }
  printf "Wrote %d files (+ %d synthesized .lang.yml)\n", written, synth
}
' "${csvFiles[@]}"

if [ -n "${Only[*]:-}" ]; then
  echo "  (scoped by --only: ${Only[*]})"
fi

if [ "$Verify" -eq 1 ]; then
  # Mojibake markers: UTF-8 byte encodings of the PS char-pair markers.
  #   U+00E2U+20AC, U+00E2U+0153, U+00E2U+008C, U+00F0U+0178,
  #   U+00C3U+00A9, U+00C2U+00A7, U+FFFD
  pattern='\xC3\xA2\xE2\x82\xAC|\xC3\xA2\xC5\x93|\xC3\xA2\xC2\x8C|\xC3\xB0\xC5\xB8|\xC3\x83\xC2\xA9|\xC3\x82\xC2\xA7|\xEF\xBF\xBD'
  hits=0
  while IFS= read -r wf; do
    if LC_ALL=C grep -qaP "$pattern" "$wf" 2>/dev/null; then
      echo "  mojibake: ${wf#"$ResourcesRoot/"}"
      hits=$((hits+1))
    fi
  done < "$LISTFILE"
  rm -f "$LISTFILE"
  if [ "$hits" -gt 0 ]; then
    echo "Mojibake found in $hits file(s); fix the offending TSV cell(s) and regenerate." >&2
    exit 1
  fi
  echo "Verify: no mojibake markers found in regenerated output."
else
  rm -f "$LISTFILE"
fi
