import type {GLTF} from 'three/addons/loaders/GLTFLoader.js';
import type {PaintController} from './types.mjs';
export function preparePaint(gltf:GLTF,materialIndices:readonly number[]):Promise<PaintController>;
