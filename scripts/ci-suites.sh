#!/usr/bin/env bash
# Runs one group of the browser and Java suites, each to the end, and fails if any failed.
set -uo pipefail
HAR_DIR=${HAR_DIR:-}
# Pull requests test 2027 and the IDE; suites of 2026 alone (RobotBuilder, SmartDashboard, the CheerpJ runtime
# scenarios, 2026 team projects) run by hand until 2026 is removed
ide=(smoke cache git github-link gh terminal copilot tools)
failed=()
# A suite that fails runs once more: browser suites have timing failures on a loaded runner. A pass on the
# second try is still reported, so a suite that keeps needing it stands out.
run() {
  echo "::group::$*"
  if "$@"; then echo "::endgroup::"; return; fi
  echo "::endgroup::"
  echo "::group::$* (second try)"
  if "$@"; then echo "::endgroup::"; echo "::warning::passed on the second try: $*"; return; fi
  echo "::endgroup::"
  echo "::error::failed: $*"
  failed+=("$*")
}
# A team suite that fails runs once more with only the repos that failed, which it lists in teams<year>.json.
# Without that file (the suite stopped before the end) every repo runs again.
team_suite() {
  local year=$1 file=apps/ide/test-results/teams$1.json repos
  rm -f "$file"
  echo "::group::test:teams$year"
  if env ${har:+TEAMS_HAR=$har/teams} pnpm --filter @frc-dev-wasm/ide test:teams$year; then echo "::endgroup::"; return; fi
  echo "::endgroup::"
  repos=$(node -e 'const { outcomes } = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); console.log([...new Set(outcomes.filter((o) => o.status === "fail").map((o) => o.repo))].join(","))' "$file" 2>/dev/null)
  echo "::group::test:teams$year (second try${repos:+: $repos})"
  if env ${har:+TEAMS_HAR=$har/teams} ${repos:+TEAMS_REPOS=$repos} pnpm --filter @frc-dev-wasm/ide test:teams$year; then
    echo "::endgroup::"
    echo "::warning::passed on the second try: test:teams$year${repos:+ ($repos)}"
    return
  fi
  echo "::endgroup::"
  echo "::error::failed: test:teams$year"
  failed+=("test:teams$year")
}
case "${1:?group: ide, java, java-browser or teams}" in
  ide)
    for s in "${ide[@]}"; do run pnpm --filter @frc-dev-wasm/ide test:$s; done
    ;;
  java)
    run pnpm --filter @frc-dev-wasm/java test --year 2027
    run pnpm --filter @frc-dev-wasm/java-web test
    run pnpm --filter @frc-dev-wasm/ide test:maven
    run pnpm --filter @frc-dev-wasm/git-wasm test
    # Here .cache has each season's vendordeps, so the proxy test also checks that it lists every marketplace repository
    run pnpm --filter @frc-dev-wasm/proxy test
    ;;
  java-browser)
    run pnpm --filter @frc-dev-wasm/ide test:2027
    run env YEARS=2027 pnpm --filter @frc-dev-wasm/ide test:junit
    run pnpm --filter @frc-dev-wasm/sim-gui test
    run pnpm --filter @frc-dev-wasm/wpigui-tools test --year 2027
    ;;
  teams)
    # With HAR_DIR the team projects' downloads are recorded once and replayed after that
    har=${HAR_DIR/#\~/$HOME}
    if [ -n "$har" ]; then mkdir -p "$har/teams" || exit 1; fi
    team_suite 2027
    ;;
  *) echo "unknown group $1" >&2; exit 2 ;;
esac
[ ${#failed[@]} -eq 0 ] || { printf 'failed: %s\n' "${failed[@]}"; exit 1; }
