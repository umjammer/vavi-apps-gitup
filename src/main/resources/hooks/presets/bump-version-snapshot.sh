# category: pre-push
# title: "bump version" SNAPSHOT check
# description: rejects pushing a "bump version" commit whose pom.xml version is a -SNAPSHOT

printf '%s\n' "$gitup_stdin" | while read -r local_ref local_sha remote_ref remote_sha; do
  case "$local_sha" in *[!0]*) ;; *) continue ;; esac
  case "$remote_sha" in
    *[!0]*) git cat-file -e "$remote_sha" 2>/dev/null && range="$remote_sha..$local_sha" || range="$local_sha --not --remotes" ;;
    *) range="$local_sha --not --remotes" ;;
  esac
  for c in $(git rev-list $range); do
    if git log -1 --format=%s "$c" | grep -qi 'bump version'; then
      v=$(git show "$c:pom.xml" 2>/dev/null | sed -e '/<parent>.*<\/parent>/d' -e '/<parent>/,/<\/parent>/d' | grep -m1 '<version>')
      case "$v" in
        *-SNAPSHOT*)
          echo "gitup: $(git rev-parse --short "$c") is a \"bump version\" but pom.xml is $(echo $v)" >&2
          exit 1 ;;
      esac
    fi
  done
done || exit 1
