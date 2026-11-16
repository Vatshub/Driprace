import type {GLTF} from 'three/addons/loaders/GLTFLoader.js';
import type {WheelController} from './types.mjs';
export function prepareTrafficWheels(gltf:GLTF,id:string):Promise<WheelController>;
export const trafficWheelBindings:Record<string,{axis?:'x'|'z';groups?:number[][];skinned?:boolean}>;
