import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

// Only install archives count; release evidence and GitHub source archives do not.
const packageName = /^omluke-.+\.(?:tar\.gz|zip)$/;

export function collectDownloads(repository, pages) {
  if (!/^[\w-]+\/[\w.-]+$/.test(repository)) {
    throw new Error('Expected owner/repository');
  }
  const seen = new Set();
  let total = 0;
  for (const release of pages(`repos/${repository}/releases?per_page=100`)) {
    if (release.draft) continue;
    if (!Number.isSafeInteger(release.id) || release.id <= 0) {
      throw new Error('Invalid release ID');
    }
    for (const asset of pages(`repos/${repository}/releases/${release.id}/assets?per_page=100`)) {
      if (!packageName.test(asset.name)) continue;
      if (!Number.isSafeInteger(asset.id) || asset.id <= 0 ||
          !Number.isSafeInteger(asset.download_count) || asset.download_count < 0) {
        throw new Error('Invalid package download record');
      }
      if (seen.has(asset.id)) continue;
      seen.add(asset.id);
      total += asset.download_count;
      if (!Number.isSafeInteger(total)) throw new Error('Download total overflow');
    }
  }
  return { schemaVersion: 1, label: 'package downloads', message: String(total), color: 'blue' };
}

export function decodePages(output) {
  const pages = JSON.parse(output);
  if (!Array.isArray(pages) || !pages.every(Array.isArray)) {
    throw new Error('Expected paginated GitHub arrays');
  }
  return pages.flat();
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const badge = collectDownloads(process.argv[2], (endpoint) => decodePages(execFileSync(
    'gh', ['api', '--paginate', '--slurp', endpoint],
    { encoding: 'utf8', timeout: 60_000, maxBuffer: 32 * 1024 * 1024 },
  )));
  process.stdout.write(`${JSON.stringify(badge, null, 2)}\n`);
}
