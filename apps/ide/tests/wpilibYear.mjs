// Which season's WPILib extension a window loads, and the Create a New Project hand-off, under Node.
// Usage: node tests/wpilibYear.mjs
import assert from 'node:assert/strict'
import test from 'node:test'
import { CREATOR_YEAR_KEY, extensionYear, seasonChoice, startCreator, takeHandoff } from '../src/frc/wpilibYear.ts'

const both = { years: ['2026', '2027'], fallback: '2026' }
const only2026 = { years: ['2026'], fallback: '2026' }

function storage(items = {}) {
  const map = new Map(Object.entries(items))
  return { getItem: (k) => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: (k) => map.delete(k), map }
}

test('the extension is the season of the open project', () => {
  assert.equal(extensionYear(both, '2027', null), '2027')
  assert.equal(extensionYear(both, '2026', null), '2026')
})

test('a window without a robot project gets the default season', () => {
  assert.equal(extensionYear(both, undefined, null), '2026')
})

test('a season the site lacks gives the default season', () => {
  assert.equal(extensionYear(only2026, '2027', null), '2026')
  assert.equal(extensionYear(both, '2025', null), '2026')
})

test('the hand-off wins over the open project', () => {
  assert.equal(extensionYear(both, '2026', '2027'), '2027')
  assert.equal(extensionYear(both, '2027', '2026'), '2026')
})

test('a hand-off for a season the site lacks is ignored', () => {
  assert.equal(extensionYear(only2026, '2026', '2027'), '2026')
})

test('the hand-off is taken once', () => {
  const store = storage({ [CREATOR_YEAR_KEY]: '2027' })
  assert.equal(takeHandoff(store), '2027')
  assert.equal(takeHandoff(store), null)
})

test('with one season there is no question', async () => {
  let asked = false
  const store = storage()
  const open = await startCreator(only2026, '2026', async () => ((asked = true), '2026'), store, () => assert.fail('reloaded'))
  assert.equal(open, true)
  assert.equal(asked, false)
  assert.equal(store.map.size, 0)
})

test('the question lists the default season first', async () => {
  let offered
  await startCreator({ years: ['2026', '2027'], fallback: '2027' }, '2027', async (years) => ((offered = years), undefined), storage(), () => {})
  assert.deepEqual(offered, ['2027', '2026'])
})

test('the loaded season opens its creator', async () => {
  const store = storage()
  assert.equal(await startCreator(both, '2026', async () => '2026', store, () => assert.fail('reloaded')), true)
  assert.equal(store.map.size, 0)
})

test('another season saves the hand-off and reloads', async () => {
  const store = storage()
  let reloads = 0
  assert.equal(await startCreator(both, '2026', async () => '2027', store, () => reloads++), false)
  assert.equal(reloads, 1)
  assert.equal(store.map.get(CREATOR_YEAR_KEY), '2027')
  // The reload then loads that season's extension, whatever project is open
  assert.equal(extensionYear(both, '2026', takeHandoff(store)), '2027')
})

test('dismissing the question does nothing', async () => {
  const store = storage()
  assert.equal(await startCreator(both, '2026', async () => undefined, store, () => assert.fail('reloaded')), false)
  assert.equal(store.map.size, 0)
})

test('a pre-release season is labeled with its stage and version, a stable one is plain', () => {
  assert.deepEqual(seasonChoice('2027', '2027.0.0-alpha-7'), { label: 'WPILib 2027 (alpha)', description: '2027.0.0-alpha-7', year: '2027' })
  assert.deepEqual(seasonChoice('2026', '2026.2.1'), { label: 'WPILib 2026', year: '2026' })
  assert.deepEqual(seasonChoice('2026', undefined), { label: 'WPILib 2026', year: '2026' })
})
