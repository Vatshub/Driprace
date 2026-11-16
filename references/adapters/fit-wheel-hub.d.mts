import type {Object3D,Vector3} from 'three';
import type {HubReference} from './types.mjs';
export function fitCircleYZ(points:readonly Vector3[]):{center:Vector3;radius:number;maxResidual:number};
export function findOlpenHub(node:Object3D,scene:Object3D):HubReference;
