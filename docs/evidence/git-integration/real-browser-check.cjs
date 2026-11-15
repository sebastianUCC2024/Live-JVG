// Run only against the separate live-jvg-integration project prepared in /private/tmp.
const {chromium}=require('/private/tmp/live-jvg-mvp-browser/node_modules/playwright');
const fs=require('node:fs'),assert=require('node:assert/strict'),cp=require('node:child_process');
const work='/private/tmp/live-jvg-merge-validation',out=__dirname;
const config=JSON.parse(fs.readFileSync(work+'/compose.json')),match=fs.readFileSync(work+'/match.txt','utf8');
const compose=['compose','-p','live-jvg-integration','-f',work+'/compose.json'];
const base='http://127.0.0.1:18082';
const env=config.services.dashboard.environment;
const auth='Basic '+Buffer.from(env.RABBITMQ_USERNAME+':'+env.RABBITMQ_PASSWORD).toString('base64');
async function rabbit(path,method,body){const r=await fetch('http://127.0.0.1:19673/api/'+path,{method,headers:{Authorization:auth,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});if(!r.ok)throw Error('Rabbit management '+r.status+' '+await r.text());return r.status===204||r.status===201?null:await r.json();}
// Read without consuming a response body twice.
async function get(path){const r=await fetch(base+path);assert.equal(r.status,200);return r.json();}
async function wait(check,message){for(let n=0;n<150;n++){try{if(await check())return;}catch{}await new Promise(r=>setTimeout(r,200));}throw Error(message);}
(async()=>{
 const queue='integration-observer-'+Date.now();
 await rabbit('queues/%2F/'+queue,'PUT',{durable:true,auto_delete:false,arguments:{}});
 await rabbit('bindings/%2F/e/live_updates/q/'+queue,'POST',{routing_key:'score.'+match,arguments:{}});
 const browser=await chromium.launch({headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),react=await browser.newPage();
 const errors=[];for(const p of [page,react])p.on('pageerror',e=>errors.push(e.message));
 await page.goto(base,{waitUntil:'networkidle'});
 await page.locator('#match-picker').selectOption(match);
 await page.waitForFunction(()=>document.querySelector('#connection').textContent==='Conectado al servidor');
 await react.goto('http://127.0.0.1:18084/',{waitUntil:'networkidle'});
 await react.getByText('Conectado',{exact:true}).waitFor();
 await page.evaluate(async match=>{
  window.observed=[];window.observer=new WebSocket('ws://127.0.0.1:18082/ws',['v12.stomp']);
  await new Promise((resolve,reject)=>{
   window.observer.onerror=reject;window.observer.onopen=()=>window.observer.send('CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0');
   window.observer.onmessage=e=>{for(const frame of e.data.split('\0')){
    if(frame.startsWith('CONNECTED')){window.observer.send('SUBSCRIBE\nid:observer\ndestination:/topic/matches/'+match+'/score\n\n\0');resolve();}
    if(frame.startsWith('MESSAGE'))window.observed.push(JSON.parse(frame.slice(frame.indexOf('\n\n')+2)));
   }};
  });
 },match);
 const initial=await get('/api/matches/'+match);assert.equal(initial.home_score,0);assert.equal(initial.away_score,0);
 const steps=[];
 for(const [button,type,h,a,status] of [['#goal-home','GOAL',1,0,'CLEAR'],['#var-check','VAR_CHECK',1,0,'IN_PROGRESS'],['#annul','GOAL_ANNULLED',0,0,'CLEAR'],['#continue','MATCH_CONTINUES',0,0,'CLEAR'],['#goal-away','GOAL',0,1,'CLEAR']]){
  const responsePromise=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/events'));
  await page.locator(button).click();const response=await responsePromise;if(response.status()!==202)throw Error('Publish '+response.status()+' '+await response.text()+' request '+response.request().postData());const event=await response.json();
  await wait(async()=>{const s=await get('/api/matches/'+match);return s.last_event_id===event.event_id;},'durable state update');
  const snapshot=await get('/api/matches/'+match+'/score');
  assert.deepEqual([snapshot.home_score,snapshot.away_score,snapshot.var_status],[h,a,status]);assert.equal(snapshot.event_id,event.event_id);
  await page.waitForFunction(({h,a})=>+document.querySelector('#home-score').textContent===h&&+document.querySelector('#away-score').textContent===a,{h,a});
  await wait(()=>page.evaluate(id=>window.observed.some(x=>x.event_id===id),event.event_id),'real STOMP update');
  await react.getByLabel('Local '+h+', Visitante '+a,{exact:true}).waitFor();
  steps.push({event,snapshot});console.log('PASS real UI -> producer -> Kafka -> state -> Rabbit -> PostgreSQL + STOMP + React',type,h,a,status,event.event_id);
 }
 assert.equal(steps[1].event.reference_event_id,steps[0].event.event_id);assert.equal(steps[2].event.reference_event_id,steps[0].event.event_id);
 const before=await get('/api/matches/'+match+'/score');
 const duplicate=await fetch('http://127.0.0.1:18083/api/matches/'+match+'/events',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(steps[0].event)});assert.equal(duplicate.status,202);
 await new Promise(r=>setTimeout(r,1500));assert.deepEqual(await get('/api/matches/'+match+'/score'),before);
 const frames=await page.evaluate(()=>window.observed);assert.equal(frames.length,5);
 for(let i=1;i<frames.length;i++)assert(frames[i].revision>frames[i-1].revision);
 console.log('PASS old Kafka duplicate: no score regression or extra STOMP publication');
 await page.screenshot({path:out+'/mvp-desktop.png',fullPage:true});await react.screenshot({path:out+'/react-desktop.png',fullPage:true});
 cp.execFileSync('docker',[...compose,'restart','dashboard','match-state'],{stdio:'pipe'});
 await wait(async()=>{const s=await get('/api/matches/'+match+'/score');return JSON.stringify(s)===JSON.stringify(before);},'snapshot recovery after restart');
 await react.getByText('Conectado',{exact:true}).waitFor({timeout:30000});await react.getByLabel('Local 0, Visitante 1',{exact:true}).waitFor();
 await page.reload({waitUntil:'networkidle'});await page.waitForFunction(()=>+document.querySelector('#home-score').textContent===0&&+document.querySelector('#away-score').textContent===1);
 const duplicate2=await fetch('http://127.0.0.1:18083/api/matches/'+match+'/events',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(steps[0].event)});assert.equal(duplicate2.status,202);
 await new Promise(r=>setTimeout(r,1500));assert.deepEqual(await get('/api/matches/'+match+'/score'),before);
 console.log('PASS restart dashboard + state: durable snapshot/revision, React reconnect, duplicate remains idempotent');
 const messages=await rabbit('queues/%2F/'+queue+'/get','POST',{count:20,ackmode:'ack_requeue_true',encoding:'auto',truncate:50000});
 assert.equal(messages.length,5);for(let i=0;i<5;i++){assert.equal(messages[i].exchange,'live_updates');assert.equal(messages[i].routing_key,'score.'+match);assert.equal(JSON.parse(messages[i].payload).event_id,steps[i].event.event_id);}
 fs.writeFileSync(out+'/rabbit-messages.json',JSON.stringify(messages,null,2));
 fs.writeFileSync(out+'/browser-results.json',JSON.stringify({match,initial,steps,frames,after_restart:await get('/api/matches/'+match+'/score'),page_errors:errors,observer_queue:queue},null,2));
 for(const p of [page,react]){await p.setViewportSize({width:390,height:844});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));}
 assert.deepEqual(errors,[]);await react.screenshot({path:out+'/react-mobile.png',fullPage:true});
 console.log('PASS Rabbit observer: five real topic messages; both frontends desktop/mobile without JavaScript errors');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
