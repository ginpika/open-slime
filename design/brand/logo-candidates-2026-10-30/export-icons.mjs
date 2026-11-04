import { mkdirSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
const root = dirname(fileURLToPath(import.meta.url));
const resize = (source, dest, size) => execFileSync('/usr/bin/sips', ['-z', String(size), String(size), source, '--out', dest], { stdio: 'pipe' });
mkdirSync(join(root, 'png'), { recursive: true });
mkdirSync(join(root, 'macos'), { recursive: true });
for (const color of ['graphite', 'yellow']) {
  for (const type of ['circle', 'rounded-square', 'macos-white']) {
    const name = 'slime-' + color + '-' + type;
    resize(join(root, 'generated', name + '.png'), join(root, 'png', name + '-1024.png'), 1024);
  }
  const name = 'slime-' + color + '-macos-white';
  const set = join(root, 'macos', name + '.iconset');
  mkdirSync(set, { recursive: true });
  for (const size of [16, 32, 128, 256, 512]) {
    for (const scale of [1, 2]) {
      resize(join(root, 'png', name + '-1024.png'), join(set, 'icon_' + size + 'x' + size + (scale === 2 ? '@2x' : '') + '.png'), size * scale);
    }
  }
  execFileSync('/usr/bin/iconutil', ['-c', 'icns', set, '-o', join(root, 'macos', name + '.icns')], { stdio: 'pipe' });
}
for (const file of readdirSync(join(root, 'png'))) {
  const png = readFileSync(join(root, 'png', file));
  if (png.readUInt32BE(16) !== 1024 || png.readUInt32BE(20) !== 1024 || png[25] !== 6) throw new Error('Invalid PNG: ' + file);
}
for (const color of ['graphite', 'yellow']) {
  const file = join(root, 'macos', 'slime-' + color + '-macos-white.icns');
  const buf = readFileSync(file);
  if (buf.toString('ascii', 0, 4) !== 'icns' || buf.readUInt32BE(4) !== statSync(file).size) throw new Error('Invalid ICNS: ' + file);
  console.log('Validated: ' + file);
}
console.log('Validated six 1024x1024 RGBA PNGs and two ICNS bundles.');

