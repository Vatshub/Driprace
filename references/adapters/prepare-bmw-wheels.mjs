import * as T from 'three';
// Specific to the recorded BMW source hash. Run BEFORE placement on a fresh scene.
export function prepareBmwWheels(scene) {
  if (scene.userData.bmwWheelsPrepared) throw new Error('BMW wheels already prepared');
  scene.updateMatrixWorld(true);
  const inverse = scene.matrixWorld.clone().invert();
  const groups = [];
  for (const indices of [[145,147,149,151,153],[156,158,160,162,164]]) {
    const halves=[[],[]];
    for (const index of indices) {
      const mesh=scene.getObjectByName(`Object_${index}`);
      if(!mesh?.isMesh || !mesh.geometry.index || mesh.isSkinnedMesh) throw new Error(`Unsupported BMW wheel ${index}`);
      const geometry=mesh.geometry.clone().applyMatrix4(inverse.clone().multiply(mesh.matrixWorld));
      const positions=geometry.attributes.position, sourceIndex=geometry.index;
      const lists=[[],[]];
      for(let i=0;i<sourceIndex.count;i+=3){
        const tri=[sourceIndex.getX(i),sourceIndex.getX(i+1),sourceIndex.getX(i+2)];
        const signs=tri.map(v=>positions.getX(v)>0?1:0);
        if(!signs.every(s=>s===signs[0])) throw new Error('Wheel triangle crosses centre plane');
        lists[signs[0]].push(...tri);
      }
      for(let side=0;side<2;side++){
        const unique=[...new Set(lists[side])], remap=new Map(unique.map((v,i)=>[v,i]));
        const part=new T.BufferGeometry();
        for(const [name,attribute] of Object.entries(geometry.attributes)){
          const source=attribute.isInterleavedBufferAttribute?attribute.data.array:attribute.array;
          const stride=attribute.isInterleavedBufferAttribute?attribute.data.stride:attribute.itemSize;
          const offset=attribute.isInterleavedBufferAttribute?attribute.offset:0;
          const array=new source.constructor(unique.length*attribute.itemSize);
          unique.forEach((vi,i)=>{for(let c=0;c<attribute.itemSize;c++)array[i*attribute.itemSize+c]=source[vi*stride+offset+c];});
          part.setAttribute(name,new T.BufferAttribute(array,attribute.itemSize,attribute.normalized));
        }
        part.setIndex(lists[side].map(v=>remap.get(v)));
        // Bounds must use referenced indices; cloned attributes include both sides.
        const box=new T.Box3();for(const vi of lists[side])box.expandByPoint(new T.Vector3().fromBufferAttribute(positions,vi));
        const child=new T.Mesh(part,mesh.material);child.name=`${mesh.name}_${side}`;
        halves[side].push({child,box,isTire:index===indices[4],isRotor:index===indices[1]});
      }
      geometry.dispose();mesh.visible=false;
    }
    for(const half of halves){
      const pivot=half.find(h=>h.isTire).box.getCenter(new T.Vector3());
      // Tire envelope is slightly asymmetric; disc/rim share the actual axle.
      const rotorCenter=half.find(h=>h.isRotor).box.getCenter(new T.Vector3());
      pivot.y=rotorCenter.y;pivot.z=rotorCenter.z;
      const group=new T.Group();group.position.copy(pivot);
      for(const {child} of half){child.position.copy(pivot).negate();group.add(child);}
      scene.add(group);groups.push(group);
    }
  }
  scene.userData.bmwWheelsPrepared=true;
  return groups;
}
