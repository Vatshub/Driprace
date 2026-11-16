import * as THREE from 'three';
// Exact prepared pine-medium-1-lod-set.glb. Run before placement/rendering.
export function preparePineLOD(scene, distances = [0,35,90]) {
 if(scene.userData.pineLodPrepared)throw new Error('Pine LOD already prepared');
 if(distances.length!==3||distances[0]!==0||!(distances[1]>0&&distances[2]>distances[1]))throw new Error('Invalid LOD distances');
 const groups=[0,1,2].map(level=>scene.getObjectByName('Pine_medium_1_LOD'+level));
 if(groups.some(group=>!group))throw new Error('Missing authored pine LOD');
 scene.updateMatrixWorld(true);const lod=new THREE.LOD();lod.name='Pine_medium_1_active_LOD';scene.add(lod);scene.updateMatrixWorld(true);
 groups.forEach((group,i)=>{lod.attach(group);lod.addLevel(group,distances[i],.15);group.visible=i===0;});
 scene.userData.pineLodPrepared=true;return lod;
}
