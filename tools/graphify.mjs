import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repository = fileURLToPath(new URL('../', import.meta.url));
const artifacts = join(repository, '.local-artifacts', 'graphify');
const projects = new Map([
  ['universium', '.'],
  ['webcraft', 'modules/webcraft'],
  ['webcraft-core', 'modules/webcraft/core'],
  ['webcraft-equipment', 'modules/webcraft/equipment'],
  ['webcraft-catalog', 'modules/webcraft/catalog'],
  ['webcraft-designs', 'modules/webcraft/designs'],
  ['webcraft-quests', 'modules/webcraft/quests'],
  ['webcraft-frontend', 'modules/webcraft/frontend'],
  ['webcraft-exporter', 'modules/webcraft/exporter'],
  ['webcraft-marketplace', 'archive/webcraft-marketplace'],
]);

function runGraphify(args) {
  const result = spawnSync('uv', [
    'tool', 'run', '--python', '3.13', '--from', 'graphifyy==0.9.120', 'graphify', ...args,
  ], {
    cwd: repository,
    env: {
      ...process.env,
      UV_CACHE_DIR: join(artifacts, 'cache'),
      UV_PYTHON_INSTALL_DIR: join(artifacts, 'python'),
      UV_PYTHON_BIN_DIR: join(artifacts, 'bin'),
      UV_TOOL_DIR: join(artifacts, 'tools'),
      GRAPHIFY_OUT: 'graphify-out',
      GRAPHIFY_BACKEND: 'none',
      GRAPHIFY_QUERY_LOG_DISABLE: '1',
      PYTHONUTF8: '1',
    },
    stdio: 'inherit',
    windowsHide: true,
  });
  if (result.error?.code === 'ENOENT') {
    throw new Error('Install uv and make it available on PATH.');
  }
  if (result.error) throw result.error;
  return result.status ?? 1;
}

function main([command = 'help', project = 'universium', ...args]) {
  if (command === 'help' || command === '--help') {
    console.log([
      'npm run graphify -- list',
      'npm run graphify -- refresh [project|all]',
      'npm run graphify -- query <project> "<question>"',
      'npm run graphify -- explain <project> "<symbol>"',
      'npm run graphify -- path <project> "<from>" "<to>"',
    ].join('\n'));
    return 0;
  }
  if (command === 'list') {
    for (const [name, directory] of projects) console.log(`${name}\t${directory}`);
    return 0;
  }
  if (command === 'refresh' && args.length === 0) {
    if (project !== 'all' && !projects.has(project)) throw new Error(`Unknown project: ${project}`);
    const excludes = readFileSync(join(repository, '.graphifyignore'), 'utf8')
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(line => line && !line.startsWith('#'))
      .flatMap(pattern => ['--exclude', pattern]);
    const selected = project === 'all' ? projects.keys() : [project];
    for (const name of selected) {
      console.log(`Refreshing ${name}`);
      const output = join(artifacts, name);
      const status = runGraphify([
        'extract', projects.get(name), '--code-only', '--out', output, ...excludes,
      ]);
      if (status !== 0) return status;
      const reportStatus = runGraphify(['cluster-only', output, '--no-label']);
      if (reportStatus !== 0) return reportStatus;
    }
    return 0;
  }
  const argumentCounts = new Map([['query', 1], ['explain', 1], ['path', 2]]);
  if (argumentCounts.get(command) !== args.length || args.some(arg => !arg || arg.startsWith('-'))) {
    throw new Error('Invalid command or arguments. Run npm run graphify -- help.');
  }
  if (!projects.has(project)) throw new Error(`Unknown project: ${project}`);
  const graph = join(artifacts, project, 'graphify-out', 'graph.json');
  if (!existsSync(graph)) {
    throw new Error(`Graph missing. Run npm run graphify -- refresh ${project}.`);
  }
  return runGraphify([command, ...args, '--graph', graph]);
}

try {
  process.exitCode = main(process.argv.slice(2));
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
