// Encodes one glTF buffer view with meshoptimizer's official encoder, for the Strata:3D test-model suite.
//
// Usage: node tools/meshopt_encode.mjs <in.bin> <out.bin> <mode> <count> <stride> [version] [filter] [bits]
//   mode:   ATTRIBUTES | TRIANGLES | INDICES
//   filter: NONE | EXPONENTIAL   (EXPONENTIAL expects float32 input and is applied before encoding)
//
// MESHOPTIMIZER_DIR must point at an installed `meshoptimizer` npm package (it comes with @gltf-transform/cli).
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const [input, output, mode, countArg, strideArg, versionArg = '0', filter = 'NONE', bitsArg = '16'] = process.argv.slice(2);
const dir = process.env.MESHOPTIMIZER_DIR;
if (!dir) throw new Error('Set MESHOPTIMIZER_DIR to the meshoptimizer package folder');

const { MeshoptEncoder } = await import(pathToFileURL(join(dir, 'meshopt_encoder.js')).href);
await MeshoptEncoder.ready;

const count = Number(countArg);
const stride = Number(strideArg);
let data = new Uint8Array(readFileSync(input));

if (filter === 'EXPONENTIAL') {
	const floats = new Float32Array(data.buffer, data.byteOffset, data.byteLength / 4);
	data = MeshoptEncoder.encodeFilterExp(floats, count, stride, Number(bitsArg), 'Separate');
}

const encoded = MeshoptEncoder.encodeGltfBuffer(data, count, stride, mode, Number(versionArg));
writeFileSync(output, encoded);
console.log(`${mode} ${count}x${stride} v${versionArg} ${filter}: ${data.byteLength} -> ${encoded.byteLength} bytes`);
