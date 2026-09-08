const fs=require('fs'),path=require('path');
process.env.FONTCONFIG_FILE=path.join(__dirname,'fonts/fonts.conf');
const sharp=require('/Users/atrium/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp');
const root=__dirname;
const esc=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;');
const t=(x,y,s,size=24,color='#F6F5EF',font='IBM Plex Sans',weight=400)=>`<text x="${x}" y="${y}" font-family="${font}" font-size="${size}" font-weight="${weight}" fill="${color}">${esc(s)}</text>`;
const rect=(x,y,w,h,c,extra='')=>`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${c}" ${extra}/>`;
const defs=`<defs><linearGradient id="blue" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#08285C"/><stop offset=".52" stop-color="#176AC6"/><stop offset="1" stop-color="#1C85F3"/></linearGradient><radialGradient id="light"><stop stop-color="#F6F5EF" stop-opacity=".95"/><stop offset=".45" stop-color="#ABCFFF" stop-opacity=".55"/><stop offset="1" stop-color="#80BFFF" stop-opacity="0"/></radialGradient><filter id="grain"><feTurbulence type="fractalNoise" baseFrequency=".75" numOctaves="3" seed="23" stitchTiles="stitch"/><feColorMatrix type="saturate" values="0"/><feComponentTransfer><feFuncA type="linear" slope=".14"/></feComponentTransfer><feBlend in="SourceGraphic" mode="soft-light"/></filter></defs>`;
const bg=(w,h)=>rect(0,0,w,h,'url(#blue)')+`<ellipse cx="${w*.72}" cy="${h*.62}" rx="${w*.8}" ry="${h*.25}" transform="rotate(-32 ${w*.72} ${h*.62})" fill="url(#light)"/>`+rect(0,0,w,h,'transparent','filter="url(#grain)"');
const cross=(x,y,c='#ABCFFF')=>`<path d="M${x-10} ${y}h20M${x} ${y-10}v20" stroke="${c}" stroke-width="1"/>`;
const data=f=>'data:image/png;base64,'+fs.readFileSync(f).toString('base64');
const img=(f,x,y,w,h)=>`<image href="${data(f)}" x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="xMidYMid meet"/>`;
async function save(name,w,h,body,dir='exports') {let s=`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${defs}${body}</svg>`;fs.writeFileSync(path.join(root,'editable',name+'.svg'),s);await sharp(Buffer.from(s)).flatten({background:'#F6F5EF'}).removeAlpha().png().toFile(path.join(root,dir,name+'.png'));}
const shots=[
['01-live','Dein Look.','Schon live.','Sieh deinen Filter direkt im Sucher.','Kamera mit aktivem Filter und geöffnetem Filterkarussell'],
['02-library','Ein Motiv.','Viele Stimmungen.','Finde deinen Stil in der Filterbibliothek.','Filterbibliothek mit verschiedenen Kollektionen'],
['03-edit','Mach den Look','zu deinem.','Intensität, Grain und Halation anpassen.','Look bearbeiten mit drei Reglern'],
['04-exposure','Licht bewusst','gestalten.','ISO und Belichtung direkt im Blick.','Sucher mit ISO- und EV-Regler'],
['05-composition','Dein Blick.','Dein Bildaufbau.','Raster und Wasserwaage für deine Komposition.','Sucher mit Raster und Wasserwaage'],
['06-import','Deine Presets.','In deiner Kamera.','Kompatible XMP-Presets importieren.','Filterbibliothek mit sichtbarem Preset-Import']
];
(async()=>{
 await save('feature-graphic-de',1024,500,bg(1024,500)+cross(52,54)+t(86,62,'hooru',28,'#F6F5EF','Departure Mono')+t(86,220,'Licht. Look.',78,'#F6F5EF','IBM Plex Sans',700)+t(86,307,'Dein Moment.',78,'#F6F5EF','IBM Plex Sans',700)+t(88,377,'Kamera & Live-Filter',25)+`<path d="M800 116h90v70M890 324v70h-90" fill="none" stroke="#F6F5EF" stroke-width="5"/>`);
 await sharp(path.join(root,'../hooru-app-icon-512.png')).ensureAlpha().png().toFile(path.join(root,'exports/app-icon-512.png'));
 for(let i=0;i<shots.length;i++){
 const [id,a,b,c,desc]=shots[i], file=path.join(root,'source-screenshots',id+'.png'),exists=fs.existsSync(file);
 let body=bg(1080,1920)+t(68,98,'hooru',36,'#F6F5EF','Departure Mono')+t(905,96,String(i+1).padStart(2,'0'),24,'#F6F5EF','Departure Mono')+t(68,220,a,76,'#F6F5EF','IBM Plex Sans',700)+t(68,308,b,76,'#F6F5EF','IBM Plex Sans',700)+t(68,377,c,29)+cross(1010,434);
 if(exists){body+=rect(200,460,680,1360,'#05070A')+img(file,200,460,680,1360);}
 else {body+=rect(200,460,680,1360,'#08285C','stroke="#ABCFFF" stroke-width="2"')+t(255,1060,'SCREENSHOT AUSSTEHEND',28,'#ABCFFF','Departure Mono')+t(255,1110,'Originalaufnahme hier einsetzen',24,'#ABCFFF');}
 body+=t(68,1870,'LIVE-FILTER / HOORU',21,'#F6F5EF','Departure Mono')+t(910,1870,`${i+1} / 6`,21,'#F6F5EF','Departure Mono');
 await save(id,1080,1920,body,exists?'phone-screenshots':'exports');
 }
 let board=rect(0,0,1600,1900,'#F6F5EF')+rect(0,0,1600,470,'#08285C')+t(72,92,'HOORU / VISUAL SYSTEM 01',22,'#ABCFFF','Departure Mono')+t(72,243,'Licht wird Look.',110,'#F6F5EF','IBM Plex Sans',700)+t(76,325,'Fotografische Ruhe. Technische Präzision.',34)+t(76,389,'Markensystem & Google Play · September 2026',22,'#ABCFFF');
 const cols=['#08285C','#176AC6','#1C85F3','#ABCFFF','#F6F5EF','#05070A'];
 const labels=['Deep blue','Film blue','App accent','Light blue','Paper','App surface'];
 cols.forEach((c,i)=>{let x=72+i*245;board+=rect(x,550,220,150,c)+t(x,744,labels[i],23,'#05070A')+t(x,781,c,20,'#176AC6','Departure Mono');});
 board+=t(72,891,'01 / TYPOGRAFIE',20,'#176AC6','Departure Mono')+t(72,984,'Dein Blick zählt.',72,'#05070A','IBM Plex Sans',700)+t(76,1040,'IBM Plex Sans · Aussagen, Beschreibungen, Fließtext',25,'#05070A')+t(76,1110,'HOORU / LIVE / 01',38,'#176AC6','Departure Mono')+t(76,1160,'Departure Mono · Marke, Indizes, technische Details',25,'#05070A');
 board+=t(72,1260,'02 / FORM & RHYTHMUS',20,'#176AC6','Departure Mono')+rect(72,1300,680,370,'#176AC6')+rect(440,1300,312,90,'#F6F5EF')+rect(520,1390,232,90,'#F6F5EF')+cross(110,1340)+t(110,1570,'Licht. Look.',62,'#F6F5EF','IBM Plex Sans',700)+t(840,1360,'8er-Raster · klare Kanten',29,'#05070A')+t(840,1420,'Große Aussagen, kleine Indizes',29,'#05070A')+t(840,1480,'Korn nur auf Bildflächen',29,'#05070A')+t(840,1540,'Echte UI bleibt unverändert',29,'#05070A')+t(840,1600,'Weißraum vor Dekoration',29,'#05070A');
 board+=t(72,1780,'03 / STIMME',20,'#176AC6','Departure Mono')+t(72,1840,'Direkt. Fotografisch. Persönlich. Ein Nutzen pro Motiv.',32,'#05070A');
 await save('design-system',1600,1900,board);
 fs.writeFileSync(path.join(root,'screenshot-plan.json'),JSON.stringify(shots.map(([id,a,b,c,desc])=>({file:id+'.png',headline:a+' '+b,caption:c,capture:desc,alt:desc})),null,2));
 console.log('Assets generated');
})();
