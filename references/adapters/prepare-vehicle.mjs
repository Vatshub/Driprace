import * as THREE from 'three';

// Call on a fresh, independently cloned scene. Geometry/textures remain unchanged.
export function prepareVehicle(scene, config) {
  if (scene.userData.dripRacePrepared) throw new Error('Vehicle already prepared');
  if (!Number.isFinite(config.yawRadians) || !(config.targetLengthMeters > 0)) throw new Error('Invalid vehicle placement');
  if (config.id === 'starter_sports') {
    for (const name of ['WheelFrontL', 'WheelFrontR']) {
      const wheel = scene.getObjectByName(name);
      if (!wheel) throw new Error(`Missing starter wheel: ${name}`);
      wheel.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), Math.PI / 6));
      wheel.updateMatrix();
    }
  }
  const root = new THREE.Group();
  root.name = config.id;
  const orientation = new THREE.Group();
  orientation.rotation.y = config.yawRadians;
  orientation.add(scene);
  root.add(orientation);
  root.updateMatrixWorld(true);
  const bounds = new THREE.Box3().setFromObject(root, true);
  const size = bounds.getSize(new THREE.Vector3());
  if (!(size.z > 0) || !Number.isFinite(size.z)) throw new Error('Invalid model bounds');
  const factor = config.targetLengthMeters / size.z;
  orientation.scale.setScalar(factor);
  root.updateMatrixWorld(true);
  bounds.setFromObject(root, true);
  const center = bounds.getCenter(new THREE.Vector3());
  orientation.position.set(-center.x, -bounds.min.y, -center.z);
  root.updateMatrixWorld(true);
  bounds.setFromObject(root, true);
  scene.userData.dripRacePrepared = true;
  return { root, bounds, scale: factor };
}
