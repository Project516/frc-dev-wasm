// Which robot log paths the IDE copies into the project. Usage: node test/logs.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { isLogFile } from '../src/logs.ts';

test('logs under the logs folder are copied', () => {
  for (const path of ['logs/FRC_1.wpilog', 'logs/2026/a.wpilog', 'logs/..a.wpilog']) assert.ok(isLogFile(path), path);
});

test('paths that leave the logs folder are refused', () => {
  for (const path of ['../other/build.gradle', 'logs/../build.gradle', 'logs/../../other/build.gradle', 'logs/a/../../b', 'build.gradle', '.vscode/settings.json', 'logsx/a', 'logs', '']) {
    assert.ok(!isLogFile(path), path);
  }
});
