import * as T from 'three';
export function fitCircleYZ(points) {
 if(points.length<12)throw new Error('Insufficient hub samples');
 const base=points[0],a=[[0,0,0],[0,0,0],[0,0,0]],b=[0,0,0];
 for(const p of points){const y=p.y-base.y,z=p.z-base.z,v=[y,z,1],q=-(y*y+z*z);for(let i=0;i<3;i++){b[i]+=v[i]*q;for(let j=0;j<3;j++)a[i][j]+=v[i]*v[j];}}
 const m=a.map((r,i)=>[...r,b[i]]);
 for(let i=0;i<3;i++){let k=i;for(let j=i+1;j<3;j++)if(Math.abs(m[j][i])>Math.abs(m[k][i]))k=j;[m[k],m[i]]=[m[i],m[k]];const d=m[i][i];if(Math.abs(d)<1e-15)throw new Error('Degenerate hub fit');m[i]=m[i].map(v=>v/d);for(let j=0;j<3;j++)if(j!==i){const d=m[j][i];m[j]=m[j].map((v,k)=>v-d*m[i][k]);}}
 const [ay,az,c]=m.map(r=>r[3]),radius=Math.sqrt((ay*ay+az*az)/4-c),center=new T.Vector3(points.reduce((s,p)=>s+p.x,0)/points.length,base.y-ay/2,base.z-az/2);
 const maxResidual=Math.max(...points.map(p=>Math.abs(Math.hypot(p.y-center.y,p.z-center.z)-radius)));
 if(!Number.isFinite(radius)||radius<=0)throw new Error('Invalid hub fit');
 return {center,radius,maxResidual};
}
// Exact recorded Olpen geometry: select the small outer hub ring, not tire envelope.
export function findOlpenHub(node,scene) {
 scene.updateMatrixWorld(true);const inverse=scene.matrixWorld.clone().invert(),refs=[],unique=new Set();
 node.traverse(o=>{if(!o.isMesh||o.isSkinnedMesh)return;for(let i=0;i<o.geometry.attributes.position.count;i++){const p=o.getVertexPosition(i,new T.Vector3()).applyMatrix4(o.matrixWorld).applyMatrix4(inverse),key=p.toArray().map(v=>v.toFixed(6)).join(',');if(!unique.has(key)){unique.add(key);refs.push({object:o,index:i,p});}}});
 const box=new T.Box3().setFromPoints(refs.map(r=>r.p)),center=box.getCenter(new T.Vector3()),sign=Math.sign(center.x);
 const samples=refs.filter(({p})=>{const outward=sign*(p.x-center.x),radius=Math.hypot(p.y-center.y,p.z-center.z);return outward>.17&&outward<.211&&radius>.30&&radius<.42;});
 const fit=fitCircleYZ(samples.map(r=>r.p));
 if(fit.maxResidual>.01)throw new Error('Olpen hub ring does not match recorded geometry');
 center.y=fit.center.y;center.z=fit.center.z;
 return {center,samples:samples.map(({object,index})=>({object,index})),fit:{radius:fit.radius,maxResidual:fit.maxResidual,count:samples.length}};
}
