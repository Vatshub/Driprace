// For an exclusively owned fresh GLTF load. Shared caches/clones need reference counts.
export function disposeOwnedVehicle({gltf, root, exclusiveOwnership = false, sharedResources = new Set()}) {
 if(!exclusiveOwnership)throw new Error('Exclusive resource ownership must be established');
 if(root.parent)throw new Error('Detach the vehicle root before disposal');
 if(root.userData.vehicleResourcesDisposed)return {alreadyDisposed:true};
 const geometry=new Set(),materials=new Set(),textures=new Set(),skeletons=new Set();
 gltf.scene.traverse(object=>{
  if(object.geometry)geometry.add(object.geometry);
  if(object.skeleton)skeletons.add(object.skeleton);
  for(const material of object.material?(Array.isArray(object.material)?object.material:[object.material]):[]){
   materials.add(material);for(const value of Object.values(material))if(value?.isTexture)textures.add(value);
  }
 });
 for(const set of [geometry,materials,textures,skeletons])for(const resource of set)if(!sharedResources.has(resource))resource.dispose();
 root.userData.vehicleResourcesDisposed=true;gltf.scene.userData.vehicleResourcesDisposed=true;
 // ImageBitmap.close() is deliberately not called: decoded images may be cached/shared.
 return {geometries:geometry.size,materials:materials.size,textures:textures.size,skeletons:skeletons.size};
}
