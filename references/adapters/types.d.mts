import type {Box3, ColorRepresentation, Group, Mesh, MeshStandardMaterial, Object3D, Vector3} from 'three';
export interface VehiclePlacement {id:string; yawRadians:number; targetLengthMeters:number; sourcePath?:string; sourceSha256?:string; placement?:string; status?:string;}
export interface WheelController {wheels:Object3D[];localAxes:Vector3[];setAngle(radians:number):void;reset():void;boundsObjects?:Object3D[];updateBounds?():void;hubReferences?:HubReference[];}
export interface HubReference {center:Vector3;samples:{object:Mesh;index:number}[];fit:{radius:number;maxResidual:number;count:number};}
export interface ForwardRollingController extends WheelController {rollingDirectionSign:1|-1;sourceAxesWorld:number[][];effectiveAxesWorld:number[][];}
export interface PreparedVehicle {root:Group;bounds:Box3;scale:number;}
export interface PaintController {materials:MeshStandardMaterial[];setColor(color:ColorRepresentation):void;dispose():void;}
