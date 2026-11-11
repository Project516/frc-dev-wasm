#!/usr/bin/env bash
# Prints the suite groups (scripts/ci-suites.sh) a pull request needs, as `groups=<json array>`
# for $GITHUB_OUTPUT. Without a base commit (a push or a manual run) every group runs.
# A changed file that no rule names runs every group, so a new folder is tested until it gets a rule.
set -euo pipefail
base=${1:-}
all='["ide","java","java-browser","teams"]'
[ -n "$base" ] || { echo "groups=$all"; exit 0; }

# Outside the loop, so set -e stops on a bad base instead of reporting no changes
changed=$(git diff --name-only "$base"...HEAD)

ide=0 java=0 jb=0 teams=0
while IFS= read -r file; do
  [ -n "$file" ] || continue
  case "$file" in
    # Text only, or tested by the checks job
    *.md | docs/* | LICENSE | renovate.json | packages/proxy/*) ;;
    # Run by hand, not in CI (scripts/ci-suites.sh)
    apps/ide/tests/spectrum.mjs | apps/ide/tests/junitTeam.mjs | apps/ide/tests/robotbuilder.mjs | apps/ide/tests/smartdashboard.mjs | packages/runtime/test/*) ;;
    # The IDE alone: the workbench, its git, terminal and Copilot, the tools
    apps/ide/src/copilot/* | apps/ide/src/frc/terminal/*) ide=1 ;;
    # test:git runs in the java group, git in the terminal in ide
    apps/ide/src/git/* | packages/vscode-git/*) ide=1 java=1 ;;
    apps/ide/tests/teams.mjs) teams=1 ;;
    apps/ide/tests/wpilib2027.mjs | apps/ide/tests/junit.mjs) jb=1 ;;
    apps/ide/tests/maven.mjs | apps/ide/tests/git.mjs | apps/ide/tests/gitBrowser.mjs) java=1 ;;
    apps/ide/tests/*) ide=1 ;;
    # Builds, simulation and vendor libraries, which the team projects exercise
    apps/ide/src/frc/* | packages/runtime/src/*) ide=1 java=1 jb=1 teams=1 ;;
    # test:2027 runs on java-web
    packages/java-web/*) java=1 jb=1 ;;
    packages/sim-gui/* | packages/wpigui-tools/*) ide=1 jb=1 ;;
    packages/git-wasm/*) ide=1 java=1 ;;
    packages/java/* | packages/vscode-wpilib/*) ide=1 java=1 jb=1 teams=1 ;;
    # Everything else: the workbench setup, dependencies, build scripts, CI itself
    *) ide=1 java=1 jb=1 teams=1 ;;
  esac
done <<< "$changed"

groups=()
[ $ide = 1 ] && groups+=('"ide"')
[ $java = 1 ] && groups+=('"java"')
[ $jb = 1 ] && groups+=('"java-browser"')
[ $teams = 1 ] && groups+=('"teams"')
echo "groups=[$(IFS=,; echo "${groups[*]}")]"
