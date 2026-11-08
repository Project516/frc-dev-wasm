// Runs command lines through the shell over an in-memory workspace, as bash would run them.
// Needs pnpm build:terminal for coreutils.wasm. Lines run in order, in one session.
import { close, createHarness } from './harness.mjs'
import { relativePath, relativizeArgs, resolvePath } from '../src/frc/terminal/paths.ts'

const failures = []
function check(name, actual, expected) {
  const ok = expected instanceof RegExp ? expected.test(actual) : JSON.stringify(actual) === JSON.stringify(expected)
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok ? '' : `: ${JSON.stringify(actual)}`}`)
  if (!ok) failures.push(name)
}

const cases = [
  // Lines that terminal.mjs runs in the browser, in order, in one session
  ['pwd', 'pwd', '/workspace'],
  ['ls on an empty workspace', 'ls', ''],
  ['mkdir, redirect and cat', 'mkdir -p a/b && echo hi > a/b/f.txt && cat a/b/f.txt', 'hi'],
  ['wc counts lines', 'wc -l a/b/f.txt', /^1 a\/b\/f\.txt$/],
  ['a pipe feeds the next command', 'cat a/b/f.txt | wc -c', /^3$/],
  ['cd changes the directory', 'cd a/b && pwd', '/workspace/a/b'],
  ['ls lists the directory', 'ls', 'f.txt'],
  ['ls with an absolute path', 'ls /workspace/a', 'b'],
  ['relative parent paths', 'cat ../b/f.txt', 'hi'],
  ['cp, mv and a glob', 'cp f.txt g.txt && mv g.txt h.txt && ls *.txt', /^f\.txt\s+h\.txt$/],
  ['rm removes a file', 'rm h.txt && ls', 'f.txt'],
  ['appending with >>', 'echo more >> f.txt && cat f.txt', 'hi\nmore'],
  ['stdin redirect', 'wc -l < f.txt', /^2$/],
  ['quoting and variables', `export X=1 && echo $X "a  b" 'c $X'`, /^1 a\s+b c \$X$/],
  ['stderr goes to the terminal and && stops', 'cat nope && echo after', /No such file/],
  ['|| runs after a failure', 'cat nope 2>/dev/null || echo fallback', 'fallback'],
  ['command substitution', 'echo $(pwd)', '/workspace/a/b'],
  ['sort and uniq', 'printf "b\\na\\nb\\n" | sort | uniq -c', /2 b[\s\S]*1 a|1 a[\s\S]*2 b/],
  ['grep finds a line', 'grep -n more f.txt', '2:more'],
  ['grep -r searches below', 'cd /workspace && grep -r hi a', 'a/b/f.txt:hi'],
  ['find by name', 'find . -name "*.txt"', './a/b/f.txt'],
  ['cp -r copies a directory', 'cp -r a a2 && ls a2/b', 'f.txt'],
  ['ls -R without an operand', 'cd a && ls -R', /f\.txt/],
  ['cd back', 'cd /workspace', ''],
  ['cp -r src/. merges', 'mkdir -p cpsrc cpdst && echo n > cpsrc/new && echo e > cpdst/extra && cp -r cpsrc/. cpdst && ls cpdst', /^extra\s+new$/],
  ['mv moves a directory', 'mkdir -p mvsrc/sub && echo m > mvsrc/sub/f && mv mvsrc mvdone && cat mvdone/sub/f && ls', /^m\n[\s\S]*mvdone/],
  ['mv onto a non-empty directory is refused', 'mkdir mvdst && echo k > mvdst/keep && mv -T mvdone mvdst', /Directory not empty/],
  ['the refused mv lost nothing', 'cat mvdst/keep && cat mvdone/sub/f', /^k\nm$/],
  ['rm -rf removes a tree', 'rm -rf mvdone mvdst && ls', /^(?!.*mv(done|dst))/s],
  ['rm -r refuses /', 'rm -rf /', /dangerous/],
  ['find -delete removes only the matches', 'mkdir -p fd/sub && echo 1 > fd/x.txt && echo 2 > fd/keep.md && find fd -name "*.txt" -delete && ls fd', /^keep\.md\s+sub$/],
  ['dirname keeps its text operand', 'dirname /a/b/c', '/a/b'],
  ['du summarizes', 'du -s a', /^\d+\s+a$/],
  ['an unknown command is reported', 'nosuchcommand', /command not found/],
  // Language
  ['variable default', 'echo ${Q:-def}', 'def'],
  ['assignment and use', 'N=5; echo $N', '5'],
  ['for loop', 'for i in 1 2 3; do echo n$i; done', 'n1\nn2\nn3'],
  ['for loop over a glob', 'for f in a/b/*.txt; do echo f=$f; done', 'f=a/b/f.txt'],
  ['while loop', 'i=0; while [ $i -lt 3 ]; do echo $i; i=$((i+1)); done', '0\n1\n2'],
  ['if and test', 'if [ -f a/b/f.txt ]; then echo yes; else echo no; fi', 'yes'],
  ['function', 'f() { echo "fn $1"; }; f x', 'fn x'],
  ['function kept for the next line', 'f y', 'fn y'],
  ['positional parameters', 'g() { echo $# $2; }; g a b c', '3 b'],
  ['2>&1 into a pipe', 'cat nope 2>&1 | wc -l', '1'],
  ['status of the last command', 'false; echo $?', '1'],
  ['status carried to the next line', 'echo $?', '0'],
  ['arithmetic', 'echo $((2+3*4))', '14'],
  ['string length and substitution', 'S=hello; echo ${#S} ${S/l/L}', '5 heLlo'],
  ['case', 'case abc in a*) echo match;; *) echo no;; esac', 'match'],
  ['brace expansion', 'echo {a,b}{1,2}', 'a1 a2 b1 b2'],
  ['here string', 'cat <<< hey', 'hey'],
  ['subshell does not leak cd', '(cd /; pwd); pwd', '/\n/workspace'],
  ['env var in front of a command', 'FOO=bar sh -c "echo $FOO"', 'bar'],
  ['export visible to the next line', 'export KEEP=1', ''],
  ['variable kept for the next line', 'echo $KEEP', '1'],
  // Tools the old shell lacks
  ['sed', 'echo hello | sed s/l/L/g', 'heLLo'],
  ['awk', 'printf "a 1\\nb 2\\n" | awk "{s+=\\$2} END {print s}"', '3'],
  ['jq', 'echo \'{"a":[1,2]}\' | jq -c .a', '[1,2]'],
  ['diff', 'echo a > d1; echo b > d2; diff d1 d2', /^1c1\n< a\n---\n> b$/],
  ['tree', 'tree a', /f\.txt/],
  ['xargs', 'echo a b | xargs echo X', 'X a b'],
  ['xargs -n 1', 'echo a b | xargs -n 1 echo', 'a\nb'],
  ['gzip round trip', 'echo zip | gzip | gunzip', 'zip'],
  ['tar create and list', 'tar czf t.tgz a && tar tzf t.tgz', 'a/\na/b/\na/b/f.txt'],
  ['tar extract', 'mkdir tx && tar xzf t.tgz -C tx && cat tx/a/b/f.txt', 'hi\nmore'],
  ['rg', 'rg -n hi a', 'a/b/f.txt:1:hi'],
  ['head via uutils', 'printf "1\\n2\\n3\\n" | head -n 2', '1\n2'],
  ['non-ASCII through a pipe', 'echo héllo | wc -c', '7'],
  ['ls in a pipe is one per line', 'ls a | cat', 'b'],
  ['git', 'git status', /^git status/],
  ['redirect to a file then read it', 'echo x > r.txt; cat r.txt', 'x']
]


const more = [
  ['xargs -n1 without a space', 'echo a b | xargs -n1 echo', 'a\nb'],
  ['xargs -I with the value glued on', 'echo z | xargs -I{} echo [{}]', '[z]'],
  ['stdout and stderr keep their order', 'echo a; cat nope; echo b', /^a\ncat: .*nope.*\nb$/],
  ['an error comes before the || branch', 'cat nope || echo fallback', /^cat: .*nope.*\nfallback$/],
  ['order inside a loop', 'for f in x y; do echo $f; cat n$f; done', /^x\ncat: .*nx.*\ny\ncat: .*ny.*$/],
  ['2>/dev/null drops the error', 'cat nope 2>/dev/null; echo ok', 'ok'],
  ['2>&1 into a pipe counts the error', 'cat nope 2>&1 | wc -l', '1'],
  ['an error from a command that writes a file', 'cat nope > out.txt; echo after', /^cat: .*nope.*\nafter$/],
  ['arrays persist', 'arr=(1 "b c" 3); declare -A m=([k]=v)', ''],
  ['an array from the line before', 'echo ${arr[1]} ${#arr[@]} ${m[k]}', 'b c 3 v'],
  ['an array grows', 'arr+=(4); echo ${#arr[@]}', '4'],
  ['positional parameters persist', 'set -- p "q r"', ''],
  ['set -- from the line before', 'echo "$2" $#', 'q r 2'],
  ['aliases persist', "alias hi='echo HI'", ''],
  ['an alias from the line before', 'hi there', 'HI there'],
  ['an alias can replace a command', "alias ls='echo mine'", ''],
  ['and is used instead of it', 'ls', 'mine'],
  ['unalias brings the command back', 'unalias ls; ls a', 'b'],
  ['a function can replace a command', 'git() { echo fn; }; git', 'fn'],
  ['and is used on the next line', 'git status', 'fn'],
  ['shell options persist', 'shopt -s nullglob', ''],
  ['an option from the line before', 'echo nomatch*', ''],
  ['help describes bash', 'help', /bash shell[\s\S]*Language/],
  ['help with a name is bash help', 'help cd', /cd/],
  ['type finds a command', 'type ls', 'ls is /usr/bin/ls'],
  ['which finds a command', 'which ls git', '/usr/bin/ls\n/usr/bin/git'],
  ['which reports a missing command', 'which nosuch; echo $?', /no nosuch in[\s\S]*\n1$/],
  ['ls shows columns on the terminal', 'cd /workspace/a/b && touch p q r && ls', /^f\.txt\s+p\s+q\s+r$/],
  ['a utility cut off at the output limit says so', 'yes > cap.txt', /cut off/],
  ['and leaves nothing in the file', 'wc -c < cap.txt', '0'],
  ['a runaway pipe is cut off', 'yes | head -n 1', /cut off/]
]
for (const c of more) cases.push(c)

async function run() {
  const h = createHarness()
  for (const [name, line, expected] of cases) {
    let out
    try {
      out = (await h.sh(line)).out.replace(/\n+$/, '')
    } catch (error) {
      out = `THROWN ${error.message}`
    }
    check(name, out, expected)
  }
  return h
}

const h = await run()
await h.sh('cd /workspace')

// find -delete in a fresh workspace
const tree = { '/workspace/a/x.txt': 'x', '/workspace/a/keep.md': 'k', '/workspace/a/sub/y.txt': 'y', '/workspace/b.txt': 'b' }
const f = createHarness({ tree })
await f.sh('find a -name "*.txt" -delete')
check('find -delete removes only what matches', f.files.paths(), ['/workspace', '/workspace/a', '/workspace/a/keep.md', '/workspace/a/sub', '/workspace/b.txt'])
let r = await f.sh('find . -name a -delete; echo $?')
check('find -delete keeps a matching directory that still has files', [r.out.includes('not empty'), f.files.nodes.has('/workspace/a/keep.md')], [true, true])
await f.sh('find . -delete')
check('find . -delete empties the project folder but keeps it', f.files.paths('/workspace'), ['/workspace'])
await f.sh('mkdir -p d/e && echo 1 > d/e/f && find d -delete')
check('find -delete removes children before their parent', f.files.paths('/workspace'), ['/workspace'])

// exit closes the terminal only when it runs
await h.sh('if false; then exit 7; fi')
check('exit that does not run leaves the terminal open', h.exits, [])
await h.sh('true && exit 3')
check('exit closes the terminal with its status', h.exits, [3])

// ./gradlew prints as it runs, and Ctrl+C stops what runs
const delay = (ms) => new Promise((r) => setTimeout(r, ms))
const services = {
  build: async ({ write }) => {
    for (const part of ['> Task :a\n', '> Task :b\n', 'BUILD SUCCESSFUL\n']) {
      write(part)
      await delay(120)
    }
    return true
  },
  simulate: async () => true,
  stop: async () => {}
}
const g = createHarness({ tree: { '/workspace/gradlew': '#!/bin/sh\n' }, gradle: services })
const stamps = []
const start = Date.now()
const push = g.output.push.bind(g.output)
g.output.push = (text) => (stamps.push([Date.now() - start, text]), push(text))
const firstAt = (needle) => stamps.find(([, text]) => text.includes(needle))?.[0]

r = await g.sh('./gradlew build')
check('gradlew prints its first line before the build ends', firstAt('Task :a') < 100 && firstAt('BUILD') >= 200, true)
r = await g.sh('echo before; ./gradlew build; echo after')
check('gradlew between other commands keeps the order', r.out, 'before\n> Task :a\n> Task :b\nBUILD SUCCESSFUL\nafter\n')
r = await g.sh('./gradlew build | tail -n 1')
check('gradlew in a pipe passes its output on', r.out, 'BUILD SUCCESSFUL\n')
stamps.length = 0
const t0 = Date.now() - start
r = await g.sh('echo start && ./gradlew build && echo ok')
check('gradlew in an && list', [r.out, firstAt('Task :a') - t0 < 100], ['start\n> Task :a\n> Task :b\nBUILD SUCCESSFUL\nok\n', true])
stamps.length = 0
const t1 = Date.now() - start
r = await g.sh('for t in a b; do ./gradlew build; done')
check('gradlew in a loop prints as it runs', [r.out.split('BUILD').length - 1, firstAt('Task :a') - t1 < 100], [2, true])
r = await g.sh('i=0; while [ $i -lt 2 ]; do ./gradlew build > /dev/null; i=$((i+1)); done; echo done')
check('gradlew into a file prints nothing', r.out, 'done\n')
r = await g.sh('if ./gradlew build; then echo ok; fi')
check('gradlew in an if keeps the order', r.out, '> Task :a\n> Task :b\nBUILD SUCCESSFUL\nok\n')
r = await g.sh('echo "$(./gradlew build | tail -n 1)"')
check('gradlew inside $( ) is captured', r.out, 'BUILD SUCCESSFUL\n')
r = await g.sh('./gradlew build; echo status $?')
check('the status of gradlew', r.out.endsWith('status 0\n'), true)

// The WPILib extension's simulate runs simulateExternalJavaRelease (simulateExternalJava in 2027) and reads what it writes
const written = new Map()
const simApi = {
  Uri: { file: (path) => ({ path }) },
  workspace: { fs: { writeFile: async (uri, bytes) => written.set(uri.path, new TextDecoder().decode(bytes)) } }
}
const simServices = {
  ...services,
  simulationInfo: async () => ({
    mainClass: 'frc.robot.Main',
    extensions: [{ name: 'Sim GUI', artifact: 'halsim_gui', defaultEnabled: true }],
    environment: { HALSIMWS_HOST: '10.0.0.2' }
  })
}
const sim = createHarness({ tree: { '/workspace/MyRobot/gradlew': '#!/bin/sh\n' }, cwd: '/workspace/MyRobot', gradle: simServices, api: simApi })
r = await sim.sh('./gradlew simulateExternalJavaRelease -Dorg.gradle.java.home="/jdk"')
check('simulateExternalJavaRelease builds, then writes the simulation file as GradleRIO does', [r.status, JSON.parse(written.get('/workspace/MyRobot/build/sim/release_java.json') ?? 'null')], [
  0,
  [
    {
      type: 'java',
      name: 'jar (in project MyRobot)',
      extensions: [{ name: 'Sim GUI', libName: '/workspace/MyRobot/build/jni/release/libhalsim_gui.so', defaultEnabled: true }],
      environment: { HALSIMWS_HOST: '10.0.0.2' },
      libraryDir: '/workspace/MyRobot/build/jni/release',
      mainClassName: 'frc.robot.Main'
    }
  ]
])
r = await sim.sh('./gradlew simulateExternalJava')
check('simulateExternalJava (2027) writes java.json with the run task and its debug port', [r.status, JSON.parse(written.get('/workspace/MyRobot/build/sim/java.json') ?? 'null')], [
  0,
  [
    {
      type: 'java',
      name: 'jar (in project MyRobot)',
      extensions: [{ name: 'Sim GUI', libName: '/workspace/MyRobot/build/jni/release/libhalsim_gui.so', defaultEnabled: true }],
      environment: { HALSIMWS_HOST: '10.0.0.2' },
      libraryDir: '/workspace/MyRobot/build/jni/release',
      mainClassName: 'frc.robot.Main',
      taskPath: ':run',
      debugPort: 5005
    }
  ]
])
r = await sim.sh('./gradlew simulateExternalJava -PhwSim')
check('hardware simulation says why it cannot run', [r.status, r.out.includes('real hardware')], [1, true])
r = await sim.sh('./gradlew deploy')
check('deploy says why it cannot run', [r.status, r.out.includes('roboRIO')], [1, true])

for (const [line, ms] of [['sleep 30', 100], ['for i in 1 2 3 4 5 6 7 8 9 10; do sleep 1; echo $i; done', 100], ['./gradlew build', 150]]) {
  const abort = new AbortController()
  setTimeout(() => abort.abort(), ms)
  const began = Date.now()
  r = await g.sh(line, abort)
  check(`Ctrl+C stops ${line.split(' ')[0]}`, [r.status, Date.now() - began < 1500], [130, true])
}

check('resolvePath', [resolvePath('/workspace/a', '../b/./c'), resolvePath('/workspace', '/x/y/')], ['/workspace/b/c', '/x/y'])
check('relativePath', [relativePath('/workspace/a', '/workspace/b/c'), relativePath('/workspace', '/workspace'), relativePath('/workspace/a', '/')], ['../b/c', '.', '../..'])
check('absolute operands become relative', relativizeArgs('cat', ['/workspace/a/f', '-n', 'x', '--file=/workspace/g'], '/workspace/a'), ['f', '-n', 'x', '--file=../g'])
check('text operands are left alone', relativizeArgs('tr', ['/', '_'], '/workspace'), ['/', '_'])

await close()
if (failures.length > 0) {
  console.error(`failed: ${failures.join(', ')}`)
  process.exit(1)
}
