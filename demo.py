import argparse,json
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError

def call(base,path,body=None):
 data=None if body is None else json.dumps(body).encode('utf-8')
 request=Request(base+'/api'+path,data=data,headers={'Content-Type':'application/json'})
 try:
  with urlopen(request,timeout=10) as response:
   return json.loads(response.read()) if response.headers.get_content_type()=='application/json' else response.read()
 except HTTPError as exc:raise RuntimeError(f'HTTP {exc.code}: '+exc.read().decode('utf-8')) from exc
 except URLError as exc:raise RuntimeError('Start the local Java application first: '+str(exc)) from exc

from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8082');args=p.parse_args();base=args.base
 w=call(base,'/workflows',{'templateId':'standard-v1','guardian':True})
 id=w['ID'];print('Created workflow',id)
 while w['CURRENT']:
  current=w['CURRENT'];print('Acting',current)
  w=call(base,f'/workflows/{id}/actions',{'version':w['VERSION'],'role':current['role'],'payload':'synthetic demo acknowledgement','failArchiveOnce':True})
 call(base,'/worker/tick',{});first=call(base,f'/workflows/{id}')
 assert all(j['STATE']=='PENDING' for j in first['JOBS'])
 call(base,'/worker/tick',{});w=call(base,f'/workflows/{id}')
 assert w['STATUS']=='COMPLETED' and all(j['STATE']=='DONE' for j in w['JOBS'])
 out=Path('artifacts');out.mkdir(exist_ok=True)
 for job in w['JOBS']:
  archive=call(base,'/archives/'+job['ID']);(out/('archive-'+job['ID']+'.zip')).write_bytes(archive)
 print('Both file archives recovered after injected failure:',json.dumps(w,indent=2))
if __name__=='__main__':main()
