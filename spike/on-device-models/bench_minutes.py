import json,urllib.request,time,statistics,sys
prompt=("你是会议纪要助手。把下面的转写整理成 Markdown 纪要，必须包含六个章节：\n"
"## 一句话总结\n## 议题与讨论\n## 决议\n## 待办\n## 待定问题与风险\n## 原因分析\n"
"原因分析一节里放一个 ```fishbone 代码块。\n转写：\n"
"张三：这周注册转化率掉了30%。\n李四：注册页首屏要4秒，很多人没等到就关了。\n"
"张三：先修首屏，下周一上线，李四负责。\n李四：风险是后端改接口，王五还没确认。")
def run():
    body=json.dumps({"messages":[{"role":"user","content":prompt}],"temperature":0,"max_tokens":700,"chat_template_kwargs":{"enable_thinking":False}}).encode()
    req=urllib.request.Request("http://127.0.0.1:8099/v1/chat/completions",data=body,headers={"Content-Type":"application/json"})
    t=time.time(); r=json.load(urllib.request.urlopen(req,timeout=300)); dt=time.time()-t
    u=r.get("usage",{}); msg=r["choices"][0]["message"]["content"]
    return dt,u.get("completion_tokens",0),msg
label=sys.argv[1]; speeds=[]; secs=[]; fences=[]
for i in range(3):
    dt,tok,msg=run(); speeds.append(tok/dt); secs.append(sum(1 for l in msg.splitlines() if l.startswith('## '))); fences.append('```fishbone' in msg)
    if i==2: open(f'/tmp/models/{label}.minutes.md','w').write(msg)
print(f"{label}: median {statistics.median(speeds):.1f} t/s  sections={secs} fishbone_fence={fences}")
