import type {GLTF} from 'three/addons/loaders/GLTFLoader.js';
import type {WheelController} from './types.mjs';
export function preparePlayableWheels(gltf:GLTF,id:string):Promise<WheelController>;
