# usage: python3 addstr.py file.json  ; json: {key: {"zh":..,"tw":..,"en":..,"ja":..,"de":..,"es":..,"hi":..}}
import json,sys,html
R='/workspace/work/iridis-src/app/src/main/res/'
loc={'zh':'values','tw':'values-zh-rTW','en':'values-en','ja':'values-ja','de':'values-de','es':'values-es','hi':'values-hi'}
d=json.load(open(sys.argv[1]))
def esc(t): return html.escape(t,quote=False).replace("'","\\'").replace('"','\\"').replace('\n','\\n')
for k,code in loc.items():
    p=R+code+'/strings.xml'; s=open(p,encoding='utf-8').read()
    add=''
    for key,v in d.items():
        if f'name="{key}"' in s: continue
        t=v.get(k) or v['en']
        add+=f'    <string name="{key}">{esc(t)}</string>\n'
    s=s.replace('</resources>',add+'</resources>'); open(p,'w',encoding='utf-8').write(s)
print('ok',len(d))
