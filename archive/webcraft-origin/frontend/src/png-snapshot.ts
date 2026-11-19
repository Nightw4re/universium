import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const updateSnapshots =
  process.env.UPDATE_SNAPSHOTS === "1" ||
  process.env.VITEST_UPDATE_SNAPSHOTS === "1" ||
  process.env.npm_lifecycle_event === "test:shield-snapshot:update" ||
  process.argv.includes("--update") ||
  process.argv.includes("-u");

const pngPrefix = "data:image/png;base64,";

export const pngBufferFromDataUrl = (dataUrl: string) => {
  if (!dataUrl.startsWith(pngPrefix)) {
    throw new Error(`Expected PNG data URL, received: ${dataUrl.slice(0, 64)}`);
  }

  return Buffer.from(dataUrl.slice(pngPrefix.length), "base64");
};

export const pngDataUrlFromBuffer = (buffer: Buffer) => `${pngPrefix}${buffer.toString("base64")}`;

const findFirstDifferentByte = (actual: Buffer, expected: Buffer) => {
  const limit = Math.min(actual.length, expected.length);
  for (let index = 0; index < limit; index += 1) {
    if (actual[index] !== expected[index]) return index;
  }

  return actual.length === expected.length ? -1 : limit;
};

export const readPngSnapshotDataUrl = async (snapshotPath: string) => pngDataUrlFromBuffer(await readFile(snapshotPath));

export const expectPngDataUrlToMatchFile = async (dataUrl: string, snapshotPath: string) => {
  const actual = pngBufferFromDataUrl(dataUrl);

  if (updateSnapshots) {
    await mkdir(dirname(snapshotPath), { recursive: true });
    await writeFile(snapshotPath, actual);
    return;
  }

  let expected: Buffer;
  try {
    expected = await readFile(snapshotPath);
  } catch (error) {
    throw new Error(
      `Missing PNG snapshot: ${snapshotPath}\n` +
        "Run `npm run test:shield-snapshot:update` to create/update snapshot baselines."
    );
  }

  if (!actual.equals(expected)) {
    const firstDiff = findFirstDifferentByte(actual, expected);
    throw new Error(
      `PNG snapshot mismatch: ${snapshotPath}\n` +
        `Expected ${expected.length} bytes, received ${actual.length} bytes, first differing byte: ${firstDiff}.\n` +
        "Run `npm run test:shield-snapshot:update` only if this rendering change is intentional."
    );
  }
};
