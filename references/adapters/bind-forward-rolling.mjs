import * as T from 'three';
// Bind once at canonical placement (+Z forward), before a driving parent turns.
// Positive angle then rolls forward. Native controller remains source-local.
export function bindForwardRolling(controller, preparedRoot) {
 if(!controller.localAxes||controller.localAxes.length!==controller.wheels.length)throw new Error('Missing native wheel axes');
 controller.reset();preparedRoot.updateMatrixWorld(true);
 const sourceAxesWorld=controller.wheels.map((w,i)=>controller.localAxes[i].clone().transformDirection(w.matrixWorld));
 if(sourceAxesWorld.some(v=>Math.abs(v.x)<.99))throw new Error('Wheel axle needs steering/alignment review');
 const signs=sourceAxesWorld.map(v=>Math.sign(v.x));
 if(signs.some(v=>v!==signs[0]))throw new Error('Mixed rolling directions require per-wheel control');
 const sign=signs[0];
 const ensureAlive=()=>{if(preparedRoot.userData.vehicleResourcesDisposed)throw new Error('Vehicle resources disposed');};
 return {...controller,reset(){ensureAlive();controller.reset();},rollingDirectionSign:sign,sourceAxesWorld:sourceAxesWorld.map(v=>v.toArray()),effectiveAxesWorld:sourceAxesWorld.map(v=>v.multiplyScalar(sign).toArray()),setAngle(a){ensureAlive();if(!Number.isFinite(a))throw new Error('Invalid forward angle');controller.setAngle(a*sign);}};
}
