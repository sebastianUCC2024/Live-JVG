#!/usr/bin/env python3
"""Read-only application check. --publish adds five demo events with fresh identities."""
import argparse,datetime,json,time,urllib.request,uuid
p=argparse.ArgumentParser();p.add_argument('--publish',action='store_true');args=p.parse_args()
BASE='http://localhost:18080'
def request(path,body=None):
 data=None if body is None else json.dumps(body).encode()
 req=urllib.request.Request(BASE+path,data=data,headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=15) as response:return json.load(response)
assert request('/actuator/health')['status']=='UP'
matches=request('/api/matches');assert matches
match=matches[0]['match_id'];state=request('/api/matches/'+match)
print('PASS dashboard connected:',match,state['home_score'],state['away_score'],state['var_status'])
if args.publish:
 h,a=state['home_score'],state['away_score'];goal=None
 for kind,team,home,away,status in [('GOAL',state['home_team_id'],h+1,a,state['var_status']),('VAR_CHECK',None,h+1,a,'IN_PROGRESS'),('GOAL_ANNULLED',None,h,a,'CLEAR'),('MATCH_CONTINUES',None,h,a,'CLEAR'),('GOAL',state['away_team_id'],h,a+1,'CLEAR')]:
  body={'event_id':str(uuid.uuid4()),'event_type':kind,'team_id':team,'minute':10,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'reference_event_id':goal if kind in ('VAR_CHECK','GOAL_ANNULLED') else None}
  response=request('/api/matches/'+match+'/events',body);assert response['event_id']==body['event_id']
  if goal is None:goal=response['event_id']
  for _ in range(100):
   current=request('/api/matches/'+match)
   if any(e['event_id']==body['event_id'] for e in current['events']):break
   time.sleep(.2)
  else:raise SystemExit('FAIL: evento no observado en la proyección RabbitMQ')
  assert (current['home_score'],current['away_score'],current['var_status'])==(home,away,status)
  print('PASS real event:',kind,body['event_id'],home,away,status)
print('PASS MVP check')
