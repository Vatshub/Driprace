import type {GLTF} from 'three/addons/loaders/GLTFLoader.js';
import type {Object3D} from 'three';
export function disposeOwnedVehicle(options:{gltf:GLTF;root:Object3D;exclusiveOwnership?:boolean;sharedResources?:Set<unknown>}):{alreadyDisposed:true}|{geometries:number;materials:number;textures:number;skeletons:number};
