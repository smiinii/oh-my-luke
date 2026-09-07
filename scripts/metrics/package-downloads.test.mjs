import assert from 'node:assert/strict';
import test from 'node:test';
import { collectDownloads, decodePages } from './package-downloads.mjs';

const repository = 'example/oml';
const archive = (id, name, download_count) => ({ id, name, download_count });
const run = (assets) => collectDownloads(repository, (path) =>
  path.includes('/assets?') ? assets : [{ id: 1, draft: false }]);

test('counts archives from stable and prerelease assets, excluding drafts and duplicates', () => {
  const requested = [];
  const badge = collectDownloads(repository, (path) => {
    requested.push(path);
    if (!path.includes('/assets?')) return decodePages(JSON.stringify([
      [{ id: 1, draft: false, prerelease: true }],
      [{ id: 2, draft: false }, { id: 3, draft: true }],
    ]));
    if (path.includes('/1/')) return [
      archive(1, 'omluke-0.1.0-rc.1-macos-aarch64.tar.gz', 6),
      archive(2, 'omluke-0.1.0-rc.1-linux-x64.tar.gz', 2),
      archive(3, 'omluke-0.1.0-rc.1-linux-x64.tar.gz.sha256', 100),
      archive(4, 'omluke-0.1.0-rc.1-linux-x64.json', 100),
      archive(5, 'SHA256SUMS', 100),
      archive(6, 'source.zip', 100),
    ];
    return decodePages(JSON.stringify([
      [archive(7, 'omluke-0.2.0-windows-x64.zip', 4)],
      [archive(7, 'omluke-0.2.0-windows-x64.zip', 4)],
    ]));
  });
  assert.deepEqual(badge, { schemaVersion: 1, label: 'package downloads', message: '12', color: 'blue' });
  assert.equal(requested.length, 3);
});

test('empty public releases produce a valid zero', () => {
  assert.equal(collectDownloads(repository, () => []).message, '0');
});

test('missing, negative and nonnumeric counts fail instead of publishing zero', () => {
  for (const count of [undefined, -1, '8', NaN]) {
    assert.throws(() => run([archive(1, 'omluke-v1-linux-x64.tar.gz', count)]));
  }
});

test('API errors and malformed pages propagate', () => {
  assert.throws(() => collectDownloads(repository, () => { throw new Error('HTTP 403'); }), /HTTP 403/);
  assert.throws(() => decodePages('{"message":"API error"}'));
  assert.throws(() => decodePages('[{}]'));
  assert.throws(() => decodePages('invalid json'));
});

test('invalid repository, IDs and overflow fail', () => {
  assert.throws(() => collectDownloads('../bad', () => []));
  assert.throws(() => collectDownloads(repository, () => [{ id: '1' }]));
  assert.throws(() => run([archive(null, 'omluke-v1-linux-x64.tar.gz', 1)]));
  assert.throws(() => run([
    archive(1, 'omluke-v1-linux-x64.tar.gz', Number.MAX_SAFE_INTEGER),
    archive(2, 'omluke-v1-macos-aarch64.tar.gz', 1),
  ]));
});
