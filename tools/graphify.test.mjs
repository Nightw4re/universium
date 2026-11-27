import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { statSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const entrypoint = fileURLToPath(new URL('graphify.mjs', import.meta.url));

function cli(...args) {
  return spawnSync(process.execPath, [entrypoint, ...args], { encoding: 'utf8', windowsHide: true });
}

test('lists distinct project names and existing source directories', () => {
  const result = cli('list');
  assert.equal(result.status, 0, result.stderr);
  const projects = result.stdout.trim().split(/\r?\n/).map(row => row.split('\t'));
  assert.deepEqual(projects[0], ['universium', '.']);
  assert.equal(new Set(projects.map(([name]) => name)).size, projects.length);
  for (const [, directory] of projects) {
    assert.ok(statSync(new URL(`../${directory}/`, import.meta.url)).isDirectory(), directory);
  }
});

test('rejects unknown refresh and query scopes before starting Graphify', () => {
  for (const args of [['refresh', '../other'], ['query', '../other', 'question']]) {
    const result = cli(...args);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Unknown project/);
  }
});

test('requires two symbols for a path and rejects CLI flag injection', () => {
  for (const args of [['path', 'universium', 'one'], ['query', 'universium', '--help']]) {
    const result = cli(...args);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Invalid command or arguments/);
  }
});
