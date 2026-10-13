#!/usr/bin/env bash
set -euo pipefail

# Exercises check-dco.sh directly, against disposable git repositories, so the rule it
# enforces is an assertion this repository can fail on, not only a reading of the script's
# own comment. Run by build.yml on every push and pull request.

cd "$(dirname "${BASH_SOURCE[0]}")"
script="$(pwd)/check-dco.sh"

failures=0
workdir=""

cleanup() {
  if [ -n "$workdir" ] && [ -d "$workdir" ]; then
    rm -rf "$workdir"
  fi
}
trap cleanup EXIT

new_repo() {
  cleanup
  workdir="$(mktemp -d)"
  git -C "$workdir" init -q -b main
  git -C "$workdir" config user.name "Test Committer"
  git -C "$workdir" config user.email "committer@example.test"
  git -C "$workdir" commit -q --allow-empty -m "base"
}

base_sha() {
  git -C "$workdir" rev-parse HEAD
}

commit_as() {
  # commit_as <author name> <author email> <message>
  git -C "$workdir" commit -q --allow-empty --author="$1 <$2>" -m "$3"
}

assert_pass() {
  local description="$1" base="$2" output
  if output="$(cd "$workdir" && "$script" "${base}..HEAD" 2>&1)"; then
    return
  fi
  echo "FAIL: ${description} -- expected the check to pass"
  sed 's/^/  /' <<< "$output"
  failures=$((failures + 1))
}

assert_fail() {
  local description="$1" base="$2" expected="$3" output
  if output="$(cd "$workdir" && "$script" "${base}..HEAD" 2>&1)"; then
    echo "FAIL: ${description} -- expected the check to fail, it passed"
    failures=$((failures + 1))
    return
  fi
  if ! grep -q "$expected" <<< "$output"; then
    echo "FAIL: ${description} -- failed, but did not mention: ${expected}"
    sed 's/^/  /' <<< "$output"
    failures=$((failures + 1))
  fi
}

new_repo
base="$(base_sha)"
commit_as "Jane Doe" "jane@example.test" \
  "$(printf 'add a thing\n\nSigned-off-by: Jane Doe <jane@example.test>')"
assert_pass "a signed-off commit passes" "$base"

new_repo
base="$(base_sha)"
commit_as "Jane Doe" "jane@example.test" "add a thing, unsigned"
assert_fail "a commit with no Signed-off-by trailer fails" "$base" "no Signed-off-by trailer"

new_repo
base="$(base_sha)"
commit_as "Jane Doe" "jane@example.test" \
  "$(printf 'add a thing\n\nSigned-off-by: Someone Else <someone@example.test>')"
assert_fail "a trailer naming a different person fails" "$base" "matches neither"

new_repo
base="$(base_sha)"
commit_as "Jane Doe" "jane@example.test" \
  "$(printf 'on main\n\nSigned-off-by: Jane Doe <jane@example.test>')"
git -C "$workdir" checkout -q -b topic "$base"
commit_as "Jane Doe" "jane@example.test" \
  "$(printf 'on topic\n\nSigned-off-by: Jane Doe <jane@example.test>')"
git -C "$workdir" checkout -q main
git -C "$workdir" merge -q --no-ff -m "merge topic into main, no trailer of its own" topic
assert_pass "a merge commit with no trailer of its own is skipped, not failed" "$base"

new_repo
base="$(base_sha)"
commit_as "Jane Doe" "jane@example.test" "$(printf 'add a thing, reviewed too\n\nSigned-off-by: Reviewer Person <reviewer@example.test>\nSigned-off-by: Jane Doe <jane@example.test>')"
assert_pass "several trailers, one of which matches the author, passes" "$base"

new_repo
base="$(base_sha)"
commit_as "Jane Doe" "1234+janedoe@users.noreply.github.com" \
  "$(printf 'add a thing\n\nSigned-off-by: Jane Doe <jane@example.test>')"
assert_pass "a trailer matching the author by name rather than email still passes" "$base"

new_repo
base="$(base_sha)"
assert_fail "a range covering no commits fails, instead of passing on nothing" "$base" "produced no commits"

if [ "$failures" -gt 0 ]; then
  echo "${failures} assertion(s) failed"
  exit 1
fi
echo "all check-dco assertions passed"
