#!/usr/bin/env bash
# locale-files-to-csv.sh
#
# Bash port of locale-files-to-csv.ps1. Collapses the per-locale YAML file tree
# under rtp-plugin/src/main/resources/ into ONE TSV per locale (plus one
# baseline TSV) under scripts/out/.
#
# Output files (under scripts/out/):
#   - baseline.tsv         <- rtp-plugin/src/main/resources/*.yml (excluding
#                             plugin.yml, language.yml) + lang/shape/* +
#                             lang/vert/*
#   - locale-<lang>.tsv    <- everything under lang/<lang>/ for each locale
#
# Columns (tab-separated, backslash-escaped \\, \n, \t):
#   relpath, parent_path, key, index, value, preceding_comment, blank_before, base_key
#
# Read-only with respect to the YAML tree. See the .ps1 header for the full
# semantics; this port mirrors them exactly.
set -euo pipefail

ResourcesRoot=""
OutputDir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --resources-root) ResourcesRoot="$2"; shift 2 ;;
    --output-dir)     OutputDir="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
[ -n "$ResourcesRoot" ] || ResourcesRoot="$repoRoot/rtp-plugin/src/main/resources"
[ -n "$OutputDir" ] || OutputDir="$scriptDir/out"
mkdir -p "$OutputDir"

langRoot="$ResourcesRoot/lang"

# awk program: parse one YAML file (on stdin) into escaped 8-column TSV rows.
# Vars: RELPATH (relpath cell), REVFILE (optional translated->baseline map).
PARSE_AWK='
function esc(v){ gsub(/\\/,"\\\\",v); gsub(/\n/,"\\n",v); gsub(/\t/,"\\t",v); return v }
function strip(s,  t,f,l){
  t=s; sub(/^[ \t]+/,"",t); sub(/[ \t]+$/,"",t)
  if(length(t)>=2){ f=substr(t,1,1); l=substr(t,length(t),1)
    if((f=="\"" && l=="\"")||(f==SQ && l==SQ)) return substr(t,2,length(t)-2) }
  return t
}
function basekey(key,parent,  a,top){
  if(key!=""){ return (key in rev)?rev[key]:key }
  else if(parent!=""){ split(parent,a,"."); top=a[1]; return (top in rev)?rev[top]:top }
  return ""
}
function emit(parent,key,index,value,  bk){
  bk=basekey(key,parent)
  print esc(RELPATH) "\t" esc(parent) "\t" esc(key) "\t" esc(index) "\t" esc(value) "\t" esc(pc) "\t" pendingBlank "\t" esc(bk)
  pc=""; havePC=0; pendingBlank=0
}
BEGIN{
  SQ=sprintf("%c",39)
  if(REVFILE!=""){ while((getline ln < REVFILE)>0){ ti=index(ln,"\t"); if(ti>0){ rev[substr(ln,1,ti-1)]=substr(ln,ti+1) } } close(REVFILE) }
  nstack=0; listIndex=-1; pendingBlank=0; pc=""; havePC=0
}
{
  line=$0; sub(/\r$/,"",line)
  if(line ~ /^[ \t]*$/){ pendingBlank=1; next }
  if(line ~ /^[ \t]*#/){ c=line; sub(/^[ \t]+/,"",c); if(havePC) pc=pc "\n" c; else { pc=c; havePC=1 } next }
  match(line,/^[ \t]*/); indent=RLENGTH; stripped=substr(line,indent+1)
  isList=(stripped=="-" || substr(stripped,1,2)=="- ")
  if(!isList){ while(nstack>0 && stackIndent[nstack]>=indent) nstack-- }
  parent=""; for(i=1;i<=nstack;i++){ parent=(i==1)?stackKey[i]:parent"."stackKey[i] }
  if(isList){ listIndex++; val=substr(stripped,2); sub(/^[ \t]+/,"",val); emit(parent,"",listIndex,strip(val)); next }
  listIndex=-1
  if(match(stripped,/^[^:#[:space:]][^:]*:/)){
    m=substr(stripped,1,RLENGTH); key=substr(m,1,length(m)-1); sub(/[ \t]+$/,"",key)
    rest=substr(stripped,RLENGTH+1); sub(/^[ \t]*/,"",rest); val=rest
    if(val ~ /^[ \t]*$/){
      emit(parent,key,"","__MAP_OR_LIST_PARENT__")
      nstack++; stackIndent[nstack]=indent; stackKey[nstack]=key
    } else {
      emit(parent,key,"",strip(val))
    }
    next
  }
  pc=""; havePC=0; pendingBlank=0
}'

# awk program: build a reverse lang-map (translated_key<TAB>baseline_key) from a
# <file>.lang.yml, first-occurrence wins. Mirrors Get-ReverseLangMap.
REVMAP_AWK='
function strip(s,  t,f,l){
  t=s; sub(/^[ \t]+/,"",t); sub(/[ \t]+$/,"",t)
  if(length(t)>=2){ f=substr(t,1,1); l=substr(t,length(t),1)
    if((f=="\"" && l=="\"")||(f==SQ && l==SQ)) return substr(t,2,length(t)-2) }
  return t
}
BEGIN{ SQ=sprintf("%c",39) }
{
  line=$0; sub(/\r$/,"",line)
  if(line ~ /^[ \t]*#/) next
  if(line ~ /^[ \t]*$/) next
  if(match(line,/^[^:#[:space:]][^:]*:/)){
    m=substr(line,1,RLENGTH); base=substr(m,1,length(m)-1); sub(/[ \t]+$/,"",base)
    rest=substr(line,RLENGTH+1); val=strip(rest)
    if(val=="" || val=="__MAP_OR_LIST_PARENT__") next
    if(!(val in seen)){ seen[val]=1; print val "\t" base }
  }
}'

strip_bom() { sed '1s/^\xEF\xBB\xBF//' "$1"; }

write_header() { printf 'relpath\tparent_path\tkey\tindex\tvalue\tpreceding_comment\tblank_before\tbase_key\n'; }

# ---- baseline: top-level *.yml (excluding plugin.yml/language.yml) ----
baselineOut="$OutputDir/baseline.tsv"
baselineFileCount=0
{
  write_header
  while IFS= read -r f; do
    name="$(basename "$f")"
    case "$name" in plugin.yml|language.yml) continue ;; esac
    baselineFileCount=$((baselineFileCount+1))
    strip_bom "$f" | awk -v RELPATH="$name" -v REVFILE="" "$PARSE_AWK"
  done < <(find "$ResourcesRoot" -maxdepth 1 -type f -name '*.yml' | LC_ALL=C sort)

  # fold shared shape/ then vert/ carriers into baseline (identity base_key)
  for sub in shape vert; do
    [ -d "$langRoot/$sub" ] || continue
    while IFS= read -r f; do
      rel="lang/$sub/${f#"$langRoot/$sub/"}"
      baselineFileCount=$((baselineFileCount+1))
      strip_bom "$f" | awk -v RELPATH="$rel" -v REVFILE="" "$PARSE_AWK"
    done < <(find "$langRoot/$sub" -type f -name '*.yml' | LC_ALL=C sort)
  done
} > "$baselineOut"
echo "Wrote baseline -> $baselineOut ($baselineFileCount files)"

# ---- per-locale: everything under lang/<locale>/ ----
while IFS= read -r ld; do
  locale="$(basename "$ld")"
  case "$locale" in shape|vert) continue ;; esac

  localeOut="$OutputDir/locale-$locale.tsv"
  localeFileCount=0
  tmpRev="$(mktemp)"
  {
    write_header
    while IFS= read -r f; do
      name="$(basename "$f")"
      rel="lang/$locale/${f#"$ld/"}"
      isLangMap=0; case "$name" in *.lang.yml) isLangMap=1 ;; esac
      if [ "$isLangMap" -eq 1 ]; then
        # keep only shape/vert .lang.yml (value files); drop rename-maps
        parentLeaf="$(basename "$(dirname "$f")")"
        if [ "$parentLeaf" != "shape" ] && [ "$parentLeaf" != "vert" ]; then continue; fi
        revArg=""
      else
        # build reverse map from sibling <file>.lang.yml if present
        sibling="${f%.yml}.lang.yml"
        if [ -f "$sibling" ]; then
          strip_bom "$sibling" | awk "$REVMAP_AWK" > "$tmpRev"
          revArg="$tmpRev"
        else
          revArg=""
        fi
      fi
      localeFileCount=$((localeFileCount+1))
      strip_bom "$f" | awk -v RELPATH="$rel" -v REVFILE="$revArg" "$PARSE_AWK"
    done < <(find "$ld" -type f -name '*.yml' | LC_ALL=C sort)
  } > "$localeOut"
  rm -f "$tmpRev"
  echo "Wrote locale-$locale -> $localeOut ($localeFileCount files)"
done < <(find "$langRoot" -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort)

echo "Done."
