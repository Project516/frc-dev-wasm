#!/usr/bin/env bash
# Prints the suite groups (scripts/ci-suites.sh) a pull request needs, as `groups=<json array>`
# for $GITHUB_OUTPUT. Without a base commit (a push or a manual run) every group runs.
# A changed file that no rule names runs every group, so a new folder is tested until it gets a rule.
set -euo pipefail
base=${1:-}
all='["ide","java","teams"]'
[ -n "$base" ] || { echo "groups=$all"; exit 0; }

ide=0 java=0 teams=0
while IFS= read -r file; do
  case "$file" in
    # Text only, or tested by the checks job
    *.md | docs/* | LICENSE | renovate.json | packages/proxy/*) ;;
    # The IDE alone: the workbench, its git, terminal and Copilot, the tools
    apps/ide/src/copilot/* | apps/ide/src/git/* | apps/ide/src/frc/terminal/* | packages/vscode-git/*) ide=1 ;;
    apps/ide/tests/teams.mjs | apps/ide/tests/spectrum.mjs | apps/ide/tests/junitTeam.mjs) teams=1 ;;
    apps/ide/tests/wpilib2027.mjs | apps/ide/tests/junit.mjs) java=1 ;;
    apps/ide/tests/*) ide=1 ;;
    # Builds, simulation and vendor libraries, which the team projects exercise
    apps/ide/src/frc/* | packages/runtime/src/*) ide=1 java=1 teams=1 ;;
    packages/runtime/test/* | packages/java-web/*) java=1 ;;
    packages/sim-gui/* | packages/wpigui-tools/* | packages/git-wasm/*) ide=1 java=1 ;;
    packages/java/* | packages/vscode-wpilib/*) ide=1 java=1 teams=1 ;;
    # Everything else: the workbench setup, dependencies, build scripts, CI itself
    *) ide=1 java=1 teams=1 ;;
  esac
done < <(git diff --name-only "$base"...HEAD)

groups=()
[ $ide = 1 ] && groups+=('"ide"')
[ $java = 1 ] && groups+=('"java"')
[ $teams = 1 ] && groups+=('"teams"')
echo "groups=[$(IFS=,; echo "${groups[*]}")]"
