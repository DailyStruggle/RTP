#!/usr/bin/env bash
# locale-changeset-common.sh
#
# Bash port of locale-changeset-common.ps1. Shared helpers for the changeset
# component (locale-changeset-to-csv.sh / locale-changeset-from-csv.sh).
#
# Source this file; it defines no top-level side effects beyond functions and
# the CS_AWK_FUNCS string (a block of awk helper functions shared by the
# changeset awk programs).

# Discover shipped locales under a lang root (excludes shape/vert), sorted.
cs_discover_locales() {
  local langRoot="$1" d n
  while IFS= read -r d; do
    n="$(basename "$d")"
    case "$n" in shape|vert) continue ;; esac
    printf '%s\n' "$n"
  done < <(find "$langRoot" -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort)
}

# Shared awk helper functions:
#   dec(v)       - decode internal TSV escapes (\\, \n, \t)
#   esc(v)       - encode internal TSV escapes
#   csvfield(s)  - RFC-4180 comma-quote a CSV field
#   gLocaleRel(bp,loc), build via the lm[] array populated by callers.
CS_AWK_FUNCS='
function dec(v,PH){PH=sprintf("%c",1);gsub(/\\\\/,PH,v);gsub(/\\n/,"\n",v);gsub(/\\t/,"\t",v);gsub(PH,"\\",v);return v}
function esc(v){gsub(/\\/,"\\\\",v);gsub(/\n/,"\\n",v);gsub(/\t/,"\\t",v);return v}
function csvfield(s,  e){
  if(s ~ /[",\r\n]/){ e=s; gsub(/"/,"\"\"",e); return "\"" e "\"" }
  return s
}
function gLocaleRel(bp,loc){ if(bp ~ /^lang\//) return "lang/" loc "/" substr(bp,6); return "lang/" loc "/" bp }
'
