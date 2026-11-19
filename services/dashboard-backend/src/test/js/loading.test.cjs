const { test }=require('node:test');
const assert=require('node:assert/strict');
const { DashboardLoading }=require('../../main/resources/static/loading.js');
function clock(){let id=0;const tasks=new Map();return {
  tasks,schedule(fn,delay){const token=++id;tasks.set(token,{fn,delay});return token;},cancel(token){tasks.delete(token);},
  async next(){const [token,task]=[...tasks].sort((a,b)=>a[1].delay-b[1].delay)[0];tasks.delete(token);await task.fn();await new Promise(r=>setImmediate(r));return task.delay;}
};}
function harness(fetch){const time=clock(),states=[],catalogs=[],selections=[],views=[];const calls=[];
 const loader=new DashboardLoading({fetch:(url,options)=>{calls.push({url,...options});return fetch(url,options);},
  schedule:time.schedule,cancel:time.cancel,onCatalogState:(state,delay)=>states.push({state,delay}),
  onCatalog:matches=>{catalogs.push(matches);return matches[0]?.match_id;},onSelection:id=>selections.push(id),
  onMatch:data=>views.push(data),onMatchError:(error,delay)=>states.push({state:'match-error',delay})});
 return {loader,time,states,catalogs,selections,views,calls};}
const ok=data=>({ok:true,json:async()=>data});

test('initial catalog failure recovers automatically with loading and visible error states',async()=>{
 let attempts=0;const h=harness(async url=>url==='/api/matches'?(++attempts===1?{ok:false}:ok([{match_id:'m'}])):ok({match_id:'m'}));
 await h.loader.start();assert.deepEqual(h.states,[{state:'loading',delay:0},{state:'error',delay:1000}]);
 assert.equal(await h.time.next(),1000);assert.equal(h.views[0].match_id,'m');
 assert.equal(attempts,2);assert.equal(h.time.tasks.size,1);h.loader.stop();
});
test('repeated failures back off to thirty seconds, never a tight retry loop',async()=>{
 const h=harness(async()=>({ok:false}));await h.loader.start();const delays=[];
 for(let i=0;i<7;i++)delays.push(await h.time.next());
 assert.deepEqual(delays,[1000,2000,4000,8000,16000,30000,30000]);h.loader.stop();
});
test('late superseded catalog response cannot overwrite a newer selection',async()=>{
 let release;let count=0;const h=harness(async url=>{
  if(url==='/api/matches'&&++count===1)return new Promise(resolve=>{release=()=>resolve(ok([{match_id:'old'}]));});
  return url==='/api/matches'?ok([{match_id:'new'}]):ok({match_id:'new'});
 });
 const first=h.loader.start();await h.loader.loadCatalog();release();await first;
 assert.equal(h.calls[0].signal.aborted,true);assert.equal(h.catalogs.length,1);
 assert.equal(h.views.at(-1).match_id,'new');h.loader.stop();
});
test('navigation aborts the old request and ignores its late match response',async()=>{
 let release;const h=harness(async url=>{
  if(url==='/api/matches')return ok([{match_id:'one'},{match_id:'two'}]);
  if(url.endsWith('/one'))return new Promise(resolve=>{release=()=>resolve(ok({match_id:'one'}));});
  return ok({match_id:'two'});
 });
 const first=h.loader.start();await new Promise(r=>setImmediate(r));await h.loader.select('two');
 release();await first;assert.deepEqual(h.views,[{match_id:'two'}]);
 assert.equal(h.calls.find(call=>call.url.endsWith('/one')).signal.aborted,true);h.loader.stop();
});
test('slow match response does not start overlapping polls; stop cancels navigation work',async()=>{
 let release;const h=harness(async url=>url==='/api/matches'?ok([{match_id:'m'}]):new Promise(resolve=>{release=()=>resolve(ok({match_id:'m'}));}));
 const started=h.loader.start();await new Promise(r=>setImmediate(r));
 assert.equal(h.calls.length,2);assert.equal([...h.time.tasks.values()].filter(task=>task.delay===1000).length,0);
 h.loader.stop();release();await started;assert.equal(h.views.length,0);assert.equal(h.time.tasks.size,0);
});
test('timed-out GET retains backoff and never issues a publication',async()=>{
 const h=harness((url,options)=>new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new Error('timeout')))));
 const started=h.loader.start();assert.equal(await h.time.next(),8000);await started;
 assert.equal(h.states.at(-1).delay,1000);assert.equal(h.calls.every(call=>!call.method),true);h.loader.stop();
});
