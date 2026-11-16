import * as T from 'three';
// Apply to the recorded Nissan source before vehicle placement.
export async function prepareNissanWheels(gltf) {
  const scene=gltf.scene;
  if(scene.userData.nissanWheelsPrepared) throw new Error('Nissan wheels already prepared');
  scene.updateMatrixWorld(true);
  const wheels=[];
  for(const indices of [[93,42,44,46],[98,36,38,40],[95,22,52,56],[100,8,50,54]]){
    const nodes=await Promise.all(indices.map(i=>gltf.parser.getDependency('node',i)));
    const pivot=new T.Box3().setFromObject(nodes[0],true).getCenter(new T.Vector3());
    const group=new T.Group();group.name=`NissanWheel_${indices[0]}`;
    group.position.copy(scene.worldToLocal(pivot));scene.add(group);scene.updateMatrixWorld(true);
    for(const node of nodes)group.attach(node);
    wheels.push(group);
  }
  scene.userData.nissanWheelsPrepared=true;
  return wheels;
}
