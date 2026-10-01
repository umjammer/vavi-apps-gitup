# category: pre-push
# title: Protected branch guard
# description: rejects pushes to the protected branches

gitup_protected="@PARAM:Protected branches (space separated):main@"
printf '%s\n' "$gitup_stdin" | while read -r local_ref local_sha remote_ref remote_sha; do
  [ -n "$remote_ref" ] || continue
  for b in $gitup_protected; do
    if [ "$remote_ref" = "refs/heads/$b" ]; then
      echo "gitup: pushing to the protected branch '$b' is not allowed" >&2
      exit 1
    fi
  done
done || exit 1
