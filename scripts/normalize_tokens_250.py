#!/usr/bin/env python3
"""2.5.0: snap hard-coded font sizes, paddings/gaps and corner radii in app UI code to the design
scale (IrisFontSize / IrisSpace / IrisCorner in ui/theme/Tokens.kt). Idempotent. Usage: run from repo root."""
import re, os, sys
ROOT='app/src/main/java/com/iris/localai'
SKIP={'ui/theme/Tokens.kt'}
FONT={28:'XL',20:'L',16:'M',14:'S',12:'XS'}
SPACE={4:'XS',8:'S',12:'M',16:'L',24:'XL'}
CORNER={12:'S',20:'M',28:'L'}
T='com.iris.localai.ui.theme.'
def near(v,sc): return sorted(sc,key=lambda x:(abs(x-v),x))[0]
stats={'font':0,'space':0,'corner':0}
def font(m):
    v=float(m.group(2))
    if v>32 or v<10: return m.group(0)
    stats['font']+=1
    return f'{m.group(1)}{T}IrisFontSize.{FONT[near(v,list(FONT))]}'
def scan_calls(s, names, fn):
    out=[];i=0
    pat=re.compile(r'\b('+'|'.join(names)+r')\(')
    while True:
        m=pat.search(s,i)
        if not m: out.append(s[i:]); break
        j=m.end(); depth=1; k=j
        while k<len(s) and depth:
            if s[k]=='(': depth+=1
            elif s[k]==')': depth-=1
            k+=1
        out.append(s[i:j]); out.append(fn(s[j:k-1])); out.append(')'); i=k
    return ''.join(out)
DP=re.compile(r'(?<![\w.])(\d+(?:\.\d+)?)\.dp\b')
def space_args(a):
    def r(m):
        v=float(m.group(1))
        if v==0 or v>32 or v<1: return m.group(0)   # keep 0, hairlines (<1) and layout offsets (>32)
        if v in SPACE: 
            stats['space']+=1; return f'{T}IrisSpace.{SPACE[int(v)]}'
        stats['space']+=1
        return f'{T}IrisSpace.{SPACE[near(v,list(SPACE))]}'
    return DP.sub(r,a)
def corner_args(a):
    def r(m):
        v=float(m.group(1))
        if v<8 or v>40: return m.group(0)   # tiny radii (progress bars, ticks) kept
        stats['corner']+=1
        return f'{T}IrisCorner.{CORNER[near(v,list(CORNER))]}'
    return DP.sub(r,a)
changed=0
for dp,_,fs in os.walk(ROOT):
    for f in fs:
        if not f.endswith('.kt'): continue
        p=os.path.join(dp,f); rel=os.path.relpath(p,ROOT)
        if rel in SKIP or not (rel.startswith('ui/') or rel.startswith('agent/gui/android/')): continue
        s=open(p,encoding='utf-8').read(); o=s
        s=re.sub(r'(fontSize\s*=\s*)(\d+(?:\.\d+)?)\.sp\b',font,s)
        s=scan_calls(s,['padding','absolutePadding','spacedBy'],space_args)
        # Spacer(Modifier.width(N.dp)) / height
        s=re.sub(r'(Spacer\(\s*Modifier\.(?:width|height)\()(\d+(?:\.\d+)?)\.dp\)', lambda m: m.group(1)+space_args(m.group(2)+'.dp')+')', s)
        s=scan_calls(s,['RoundedCornerShape','smoothShape'],corner_args)
        if s!=o: open(p,'w',encoding='utf-8').write(s); changed+=1
print('files',changed,stats)
