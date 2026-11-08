// Runs the WPILib documentation lookups under Node on small index fixtures
import assert from 'node:assert/strict'
import { docsPageUrl, findTypes, memberAnchors, parseJavadocIndex, parseSphinxIndex, searchSphinx, suggestTypes, typeUrl } from '../src/copilot/wpilibDocs.ts'

let passed = 0
function test(name, body) {
  body()
  passed++
  console.log(`ok   ${name}`)
}

const types = parseJavadocIndex(
  'typeSearchIndex = [{"p":"org.wpilib.framework","l":"TimedRobot"},{"p":"org.wpilib.util","l":"Alert"},{"p":"org.wpilib.util","l":"Alert.Level","k":"9"},' +
    '{"p":"org.wpilib.command2","l":"CommandScheduler"},{"p":"org.wpilib.command3","l":"Scheduler"},{"p":"org.wpilib.command3","l":"Level"}];updateSearchResults();'
)
const members = parseJavadocIndex(
  'memberSearchIndex = [{"p":"org.wpilib.framework","c":"TimedRobot","l":"addPeriodic(Runnable, double)","u":"addPeriodic(java.lang.Runnable,double)"},' +
    '{"p":"org.wpilib.framework","c":"TimedRobot","l":"close()"},{"p":"org.wpilib.framework","c":"TimedRobot","l":"DEFAULT_PERIOD","k":"2"},' +
    '{"p":"org.wpilib.util","c":"Alert","l":"close()"}];updateSearchResults();'
)
const names = (found) => found.map((t) => `${t.p}.${t.l}`)

test('a javadoc index loses its prefix and suffix', () => assert.equal(types.length, 6))

test('class names match without case, qualified or as the name of a nested class', () => {
  assert.deepEqual(names(findTypes(types, 'timedrobot')), ['org.wpilib.framework.TimedRobot'])
  assert.deepEqual(names(findTypes(types, 'org.wpilib.util.Alert')), ['org.wpilib.util.Alert'])
  assert.deepEqual(names(findTypes(types, 'Alert.Level')), ['org.wpilib.util.Alert.Level'])
})

test('a name shared by classes lists them, and the whole name beats a nested name', () => {
  assert.deepEqual(names(findTypes(types, 'Level')), ['org.wpilib.command3.Level'])
  assert.equal(findTypes(types, 'Nothing').length, 0)
  assert.equal(findTypes([...types, { p: 'a', l: 'TimedRobot' }], 'TimedRobot').length, 2)
})

test('an old package name suggests the classes of the new one', () => {
  assert.deepEqual(suggestTypes(types, 'edu.wpi.first.wpilibj.Alert'), ['org.wpilib.util.Alert', 'org.wpilib.util.Alert.Level'])
  assert.ok(suggestTypes(types, 'Scheduler').includes('org.wpilib.command2.CommandScheduler'))
})

test('members are found by name, with the anchor of the page', () => {
  const [timed] = findTypes(types, 'TimedRobot')
  assert.deepEqual(memberAnchors(members, timed, 'addperiodic'), ['addPeriodic(java.lang.Runnable,double)'])
  assert.deepEqual(memberAnchors(members, timed, 'close'), ['close()'])
  assert.deepEqual(memberAnchors(members, timed, 'DEFAULT_PERIOD'), ['DEFAULT_PERIOD'])
})

test('class pages sit under their module when the package has one', () => {
  const root = 'https://example.org/java/'
  assert.equal(typeUrl(root, { p: 'org.wpilib.util', l: 'Alert.Level' }, 'wpilib.core'), `${root}wpilib.core/org/wpilib/util/Alert.Level.html`)
  assert.equal(typeUrl(root, { p: 'edu.wpi.first.wpilibj', l: 'Alert' }), `${root}edu/wpi/first/wpilibj/Alert.html`)
})

const sphinx = parseSphinxIndex(
  'Search.setIndex(' +
    JSON.stringify({
      docnames: ['intro', 'scheduler', 'pid', 'kinematics'],
      titles: ['Introduction', 'The Command Scheduler', 'PID Control', 'Kinematics'],
      alltitles: { Introduction: [[0, null]], 'The Command Scheduler': [[1, null]], 'Scheduling commands': [[1, 'scheduling']], 'PID Control': [[2, null]] },
      terms: { command: [0, 1, 3], schedul: [1, 3], control: [2], pid: 2 },
      titleterms: { command: 1, schedul: 1, pid: 2 }
    }) +
    ')'
)

test('the Sphinx search finds a page by a word with a different ending', () => {
  assert.equal(searchSphinx(sphinx, 'scheduling')[0].docname, 'scheduler')
  assert.equal(searchSphinx(sphinx, 'PIDs')[0].docname, 'pid')
})

test('pages that match more words and title words rank first', () => {
  assert.deepEqual(searchSphinx(sphinx, 'command scheduler').map((h) => h.docname), ['scheduler', 'kinematics', 'intro'])
  assert.deepEqual(searchSphinx(sphinx, 'nothing here'), [])
  assert.equal(searchSphinx(sphinx, 'command', 1).length, 1)
})

test('only pages of the docs site are read', () => {
  const base = 'https://docs.wpilib.org/en/latest/'
  assert.equal(docsPageUrl(base, '/docs/a/b.html'), `${base}docs/a/b.html`)
  assert.equal(docsPageUrl(base, 'https://docs.wpilib.org/en/stable/x.html'), 'https://docs.wpilib.org/en/stable/x.html')
  assert.throws(() => docsPageUrl(base, 'https://example.com/x.html'))
  assert.throws(() => docsPageUrl(base, 'https://docs.wpilib.org.evil.com/x.html'))
})

console.log(`${passed} passed`)
