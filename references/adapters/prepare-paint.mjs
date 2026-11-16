// Bind once to a fresh GLTFLoader result, before cloning or instancing it.
export async function preparePaint(gltf, materialIndices) {
  const selected=await Promise.all(materialIndices.map(i=>gltf.parser.getDependency('material',i)));
  const copies=new Map();const indexSet=new Set(materialIndices);
  function bind(material){
    const sourceIndex=gltf.parser.associations.get(material)?.materials;
    const match=indexSet.has(sourceIndex)||selected.some(s=>s===material||(s.name&&s.name===material.name));
    if(!match)return material;
    if(!copies.has(material))copies.set(material,material.clone());
    return copies.get(material);
  }
  gltf.scene.traverse(o=>{if(o.material)o.material=Array.isArray(o.material)?o.material.map(bind):bind(o.material);});
  if(!copies.size)throw new Error('No matching paint materials');
  return {
    materials:[...copies.values()],
    setColor(color){for(const m of copies.values())m.color.set(color);},
    dispose(){for(const m of copies.values())m.dispose();}
  };
}
