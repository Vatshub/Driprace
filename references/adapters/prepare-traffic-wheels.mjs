import * as T from 'three';
import {findOlpenHub} from './fit-wheel-hub.mjs';
import {prepareOldCarWheels} from './prepare-old-car-wheels.mjs';
// Candidate bindings for exact sources in placements.json; before placement.
export const trafficWheelBindings = {
 'old_car': {skinned:true},
 'generic-town-bus': {axis:'z', groups:[[10],[11],[12,13,14,15]]},
 'generic-sedan-mmcworks': {axis:'x', groups:[[240,242,14],[246,248,16],[252,254,18],[258,260,20]]},
 'mmc-generic-small-suv': {axis:'x', groups:[[59,61,57],[67,69,65],[75,77,73],[83,85,81]]},
 'european-delivery-van': {axis:'x', groups:[[131],[133],[135],[137]]},
 'conquer-89-pickup': {axis:'z', groups:[[63],[66],[69],[72]]},
 'lct-3000-95': {axis:'z', groups:[[63],[66],[69],[72]]},
 'post-apocalypse__derby_car_-_olpen_aster': {axis:'x', groups:[[40],[46],[52],[58]]},
 'post-apocalypse__derby_car_toytyta_doubtlux': {axis:'z', groups:[[172],[180],[188],[196]]}
};
export async function prepareTrafficWheels(gltf,id) {
 if(id==='old_car')return prepareOldCarWheels(gltf);
 const scene=gltf.scene, binding=trafficWheelBindings[id];
 if(!binding)throw new Error('Traffic wheel binding not verified for '+id);
 if(scene.userData.trafficWheelsPrepared||scene.userData.dripRacePrepared)throw new Error('Prepare once before placement');
 const all=await Promise.all(binding.groups.map(indices=>Promise.all(indices.map(i=>gltf.parser.getDependency('node',i)))));
 scene.updateMatrixWorld(true);
 for(const nodes of all)for(const node of nodes){if(!node)throw new Error('Missing wheel');node.traverse(o=>{if(o.isSkinnedMesh)throw new Error('Requires skeleton-aware preparation');});}
 // Front tire/rim pose contains source steering; discs/calipers are already straight.
 if(id==='generic-sedan-mmcworks')for(const nodes of all.slice(0,2)){
  const axis=new T.Vector3(1,0,0).transformDirection(nodes[0].matrixWorld).transformDirection(scene.matrixWorld.clone().invert());
  const center=new T.Box3().setFromObject(nodes[0],true).getCenter(new T.Vector3());
  const correction=new T.Group();correction.position.copy(scene.worldToLocal(center));scene.add(correction);scene.updateMatrixWorld(true);
  nodes.slice(0,2).forEach(node=>correction.attach(node));
  correction.quaternion.setFromUnitVectors(axis,new T.Vector3(1,0,0));scene.updateMatrixWorld(true);
  nodes.slice(0,2).forEach(node=>scene.attach(node));scene.remove(correction);scene.updateMatrixWorld(true);
 }
 const hubReferences=[];
 const wheels=all.map((nodes,i)=>{
  const axle=binding.axis==='x'?new T.Vector3(1,0,0):new T.Vector3(0,0,1);
  if(id==='generic-sedan-mmcworks')axle.set(1,0,0).transformDirection(nodes[0].matrixWorld).transformDirection(scene.matrixWorld.clone().invert());
  const center=new T.Box3().setFromObject(nodes[0],true).getCenter(new T.Vector3());
  if(id==='post-apocalypse__derby_car_-_olpen_aster'){const hub=findOlpenHub(nodes[0],scene);center.copy(scene.localToWorld(hub.center));hubReferences.push(hub);}
  const group=new T.Group();group.name=`TrafficWheel_${i}`;group.userData.axle= axle;group.position.copy(scene.worldToLocal(center));scene.add(group);scene.updateMatrixWorld(true);nodes.forEach(node=>group.attach(node));return group;
 });
 scene.userData.trafficWheelsPrepared=true;
 return {wheels,hubReferences,localAxes:wheels.map(w=>w.userData.axle.clone()),setAngle(angle){if(!Number.isFinite(angle))throw new Error('Invalid wheel angle');wheels.forEach(w=>w.quaternion.setFromAxisAngle(w.userData.axle,angle%(2*Math.PI)));},reset(){wheels.forEach(w=>w.rotation.set(0,0,0));}};
}
