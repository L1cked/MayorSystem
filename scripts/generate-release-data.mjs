import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import project from '../src/data/project.json' with { type: 'json' };

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'src', 'data', 'generated-release.json');
const apiJarUrl = mavenCentralJarUrl(project.apiCoordinate);

function mavenCentralJarUrl(coordinate) {
  const [groupId, artifactId, version] = coordinate.split(':');
  if (!groupId || !artifactId || !version) {
    throw new Error(`Invalid Maven coordinate: ${coordinate}`);
  }
  const groupPath = groupId.replaceAll('.', '/');
  return `https://repo1.maven.org/maven2/${groupPath}/${artifactId}/${version}/${artifactId}-${version}.jar`;
}

function assetUrl(release, matcher) {
  return release.assets?.find((asset) => matcher(asset.name))?.browser_download_url ?? null;
}

async function latestRelease() {
  const headers = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'MayorSystem-docs-build',
  };
  if (process.env.GITHUB_TOKEN) {
    headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  }

  const response = await fetch('https://api.github.com/repos/L1cked/MayorSystem/releases/latest', { headers });
  if (!response.ok) {
    throw new Error(`GitHub releases API returned ${response.status}`);
  }
  return response.json();
}

async function main() {
  let data = {
    version: project.version,
    name: `MayorSystem ${project.version}`,
    publishedAt: null,
    releaseUrl: 'https://github.com/L1cked/MayorSystem/releases/latest',
    pluginJarUrl: 'https://github.com/L1cked/MayorSystem/releases/latest',
    apiJarUrl,
    source: 'fallback',
  };

  try {
    const release = await latestRelease();
    const version = release.tag_name?.replace(/^v/i, '') || project.version;
    data = {
      version,
      name: `MayorSystem ${version}`,
      publishedAt: release.published_at || null,
      releaseUrl: release.html_url || data.releaseUrl,
      pluginJarUrl:
        assetUrl(release, (name) => /^MayorSystem-.*\.jar$/i.test(name) && !/-api\.jar$/i.test(name) && !/-all\.jar$/i.test(name)) ||
        release.html_url ||
        data.pluginJarUrl,
      apiJarUrl:
        assetUrl(release, (name) => /^MayorSystem-.*-api\.jar$/i.test(name)) ||
        apiJarUrl,
      source: 'github-release',
    };
  } catch (error) {
    console.warn(`[site] Using fallback release data: ${error.message}`);
  }

  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, `${JSON.stringify(data, null, 2)}\n`);
}

await main();
