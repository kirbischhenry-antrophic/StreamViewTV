#!/usr/bin/env bash
# Lädt aktuelle Filterlisten und die Public Suffix List als mitgelieferten Startstand.
# Schlägt ein Download fehl, bleibt der bisherige Stand im Repository erhalten.
set -u
cd "$(dirname "$0")/.."
A=app/src/main/assets
mkdir -p "$A/filters"
fetch() {
  local url="$1" out="$2"
  if curl -fsSL --proto '=https' --max-filesize 15000000 --retry 2 --connect-timeout 20 "$url" -o "$out.tmp"; then
    if [ "$(grep -cv '^!' "$out.tmp")" -gt 50 ]; then mv "$out.tmp" "$out"; echo "OK  $url"; return; fi
  fi
  rm -f "$out.tmp"; echo "WARNUNG: $url nicht geladen – bisheriger Stand bleibt"
}
fetch https://easylist.to/easylist/easylist.txt               "$A/filters/easylist.txt"
fetch https://easylist.to/easylistgermany/easylistgermany.txt "$A/filters/easylistgermany.txt"
fetch https://easylist.to/easylist/easyprivacy.txt            "$A/filters/easyprivacy.txt"
fetch https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt "$A/filters/urlhaus.txt"
fetch https://publicsuffix.org/list/public_suffix_list.dat    "$A/public_suffix_list.dat"
