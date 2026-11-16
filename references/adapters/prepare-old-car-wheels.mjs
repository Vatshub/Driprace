import * as T from 'three';
// Exact old_car hash in placements.json. Animate weighted joints, never empty wheel labels.
export async function prepareOldCarWheels(gltf) {
 const scene=gltf.scene;
 if(scene.userData.trafficWheelsPrepared||scene.userData.dripRacePrepared)throw new Error('Prepare once before placement');
 const wheels=await Promise.all([41,43,42,44].map(i=>gltf.parser.getDependency('node',i)));
 if(wheels.some(b=>!b.isBone))throw new Error('Expected wheel bones');
 const boundsObjects=await Promise.all([20,19,22,21].map(i=>gltf.parser.getDependency('mesh',i)));
 const skins=[];scene.traverse(o=>{if(o.isSkinnedMesh)skins.push(o);});
 // Every weighted vertex in each wheel must belong to the intended single joint.
 boundsObjects.forEach((mesh,i)=>{
  if(!mesh.isSkinnedMesh)throw new Error('Expected skinned wheel');
  const joint=mesh.skeleton.bones.indexOf(wheels[i]);
  const indices=mesh.geometry.attributes.skinIndex,weights=mesh.geometry.attributes.skinWeight;
  if(joint<0)throw new Error('Wheel bone absent');
  for(let v=0;v<indices.count;v++)for(let c=0;c<4;c++)if(weights.getComponent(v,c)>1e-5&&indices.getComponent(v,c)!==joint)throw new Error('Mixed wheel skin weights');
 });
 const bases=wheels.map(b=>b.quaternion.clone()),q=new T.Quaternion(),axis=new T.Vector3(0,1,0);
 function updateBounds(){scene.updateMatrixWorld(true);for(const mesh of skins){mesh.skeleton.update();mesh.computeBoundingBox();mesh.computeBoundingSphere();}}
 scene.userData.trafficWheelsPrepared=true;
 return {wheels,localAxes:wheels.map(()=>axis.clone()),boundsObjects,updateBounds,setAngle(a){if(!Number.isFinite(a))throw new Error('Invalid wheel angle');q.setFromAxisAngle(axis,a%(2*Math.PI));wheels.forEach((b,i)=>b.quaternion.copy(bases[i]).multiply(q));},reset(){wheels.forEach((b,i)=>b.quaternion.copy(bases[i]));}};
}
