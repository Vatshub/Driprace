import * as T from 'three';
import {prepareBmwWheels} from './prepare-bmw-wheels.mjs';
import {prepareNissanWheels} from './prepare-nissan-wheels.mjs';

// Exact source versions recorded in placements.json. Fresh loaded instances only.
// Call before prepareVehicle; that function owns Starter steering correction.
export async function preparePlayableWheels(gltf, id) {
  const scene = gltf.scene;
  if (scene.userData.playableWheelsPrepared || scene.userData.dripRacePrepared)
    throw new Error('Prepare wheels once, before placement');
  let wheels, axis = new T.Vector3(1, 0, 0);
  if (id === 'starter_sports') {
    const parents = ['WheelFrontL','WheelFrontR','WheelRearL','WheelRearR'].map(name => {
      const node = scene.getObjectByName(name);
      if (!node) throw new Error(`Missing wheel ${name}`);
      return node;
    });
    wheels = parents.map(parent => {
      const moving = parent.children.filter(child => !child.name.includes('BrakePad'));
      const spin = new T.Group(); spin.name = `${parent.name}_Spin`;
      parent.add(spin); moving.forEach(child => spin.add(child));
      return spin;
    });
  } else if (id === 'super_racing' || id === 'muscle') {
    const indices = id === 'super_racing' ? [157,188,219,250] : [144,181,218,255];
    wheels = await Promise.all(indices.map(index => gltf.parser.getDependency('node', index)));
    if (wheels.some(wheel => !wheel)) throw new Error('Missing wheel node');
    // Original Crimson includes a -14 degree front steering display pose.
    // Remove only that carrier yaw, including its fixed caliper, before spin.
    if(id==='muscle')for(const index of [140,177]){
      const carrier=await gltf.parser.getDependency('node',index);
      carrier.quaternion.premultiply(new T.Quaternion().setFromAxisAngle(new T.Vector3(0,1,0),14*Math.PI/180));
      carrier.updateMatrix();
    }
  } else if (id === 'hyper_sports') {
    wheels = await prepareNissanWheels(gltf); axis.set(0,0,1);
  } else if (id === 'legendary_supercar') {
    wheels = prepareBmwWheels(scene);
  } else throw new Error(`Unsupported playable vehicle: ${id}`);
  const bases = wheels.map(wheel => wheel.quaternion.clone());
  const rotation = new T.Quaternion();
  scene.userData.playableWheelsPrepared = true;
  return {
    wheels, localAxes:wheels.map(()=>axis.clone()),
    setAngle(radians) {
      if (!Number.isFinite(radians)) throw new Error('Wheel angle must be finite');
      rotation.setFromAxisAngle(axis, radians % (2 * Math.PI));
      wheels.forEach((wheel, index) => wheel.quaternion.copy(bases[index]).multiply(rotation));
    },
    reset() { wheels.forEach((wheel, index) => wheel.quaternion.copy(bases[index])); }
  };
}
