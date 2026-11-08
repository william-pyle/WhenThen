'use strict';
const map = L.map('map', {zoomControl:true, minZoom:2, maxZoom:19, worldCopyJump:true});
const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom:19, keepBuffer:0, updateWhenIdle:true,
  attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
}).addTo(map);
map.attributionControl.setPrefix(false);
L.control.scale({imperial:true,metric:false}).addTo(map);
let pin=null, circle=null, radius=250, ready=false, failed=false;
const bridge = (method,...args) => { if(window.WhenThenMap) window.WhenThenMap[method](...args); };
const valid = (lat,lng) => Number.isFinite(lat)&&Number.isFinite(lng)&&Math.abs(lat)<=90&&Math.abs(lng)<=180;
function place(lat,lng,notify,fit) {
  if(!valid(lat,lng))return;
  const point=L.latLng(lat,lng);
  if(!pin){
    pin=L.marker(point,{draggable:true,keyboard:true,title:'Selected location. Drag to move.',
      icon:L.divIcon({className:'location-pin',html:'<span></span>',iconSize:[30,36],iconAnchor:[14,34]})}).addTo(map);
    pin.on('dragend',()=>{const p=pin.getLatLng().wrap();place(p.lat,p.lng,true,false);});
    circle=L.circle(point,{radius:radius*0.3048,color:'#6b20d0',weight:3,fillColor:'#8b51de',fillOpacity:0.18,interactive:false}).addTo(map);
  }else{pin.setLatLng(point);circle.setLatLng(point);}
  circle.setRadius(radius*0.3048);
  if(fit)window.picker.fit();
  if(notify)bridge('selected',lat,lng);
}
window.picker={
  init(state){
    radius=state.radius;
    map.setView([state.centerLat,state.centerLng],state.zoom,{animate:false});
    if(state.selected)place(state.latitude,state.longitude,false,false);
    ready=true;
    bridge('ready');
  },
  select(lat,lng,fit){place(lat,lng,false,fit);},
  radius(feet){if(Number.isInteger(feet)&&feet>=50&&feet<=100000){radius=feet;if(circle)circle.setRadius(feet*0.3048);}},
  fit(){if(circle)map.fitBounds(circle.getBounds(),{padding:[28,28],maxZoom:19,animate:false});},
  retry(){failed=false;document.getElementById('map-status').hidden=true;tiles.redraw();},
  snapshot(){const c=map.getCenter();return {latitude:pin?.getLatLng().lat,longitude:pin?.getLatLng().lng,radiusMeters:circle?.getRadius(),zoom:map.getZoom(),centerLat:c.lat,centerLng:c.lng};}
};
map.on('click',e=>{const p=e.latlng.wrap();place(p.lat,p.lng,true,false);});
map.on('moveend',()=>{if(ready){const p=map.getCenter().wrap();bridge('viewport',p.lat,p.lng,map.getZoom());}});
tiles.on('tileerror',()=>{failed=true;document.getElementById('map-status').hidden=false;bridge('tileState',false);});
tiles.on('load',()=>{if(!failed)bridge('tileState',true);});
new ResizeObserver(()=>map.invalidateSize({pan:false})).observe(document.getElementById('map'));
