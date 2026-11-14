// Decodes every meshopt-compressed buffer view in a GLB with meshoptimizer's reference decoder and prints the
// SHA-256 of each decoded view as JSON. The Strata:3D tests compare the app's Kotlin decoder against these.
//
// Usage: node tools/meshopt_reference_hashes.mjs <file.glb>      (MESHOPTIMIZER_DIR = meshoptimizer npm package)
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const dir = process.env.MESHOPTIMIZER_DIR;
if (!dir) throw new Error('Set MESHOPTIMIZER_DIR to the meshoptimizer package folder');
const { MeshoptDecoder } = await import(pathToFileURL(join(dir, 'meshopt_decoder_reference.js')).href);

const glb = readFileSync(process.argv[2]);
const jsonLength = glb.readUInt32LE(12);
const gltf = JSON.parse(glb.subarray(20, 20 + jsonLength).toString('utf8'));
const bin = glb.subarray(20 + jsonLength + 8);

const result = [];
gltf.bufferViews.forEach((view, index) => {
	const ext = view.extensions?.EXT_meshopt_compression ?? view.extensions?.KHR_meshopt_compression;
	if (!ext) return;
	const source = new Uint8Array(bin.subarray(ext.byteOffset ?? 0, (ext.byteOffset ?? 0) + ext.byteLength));
	const target = new Uint8Array(ext.count * ext.byteStride);
	MeshoptDecoder.decodeGltfBuffer(target, ext.count, ext.byteStride, source, ext.mode, ext.filter);
	result.push({ view: index, sha256: createHash('sha256').update(target).digest('hex') });
});
console.log(JSON.stringify(result));
