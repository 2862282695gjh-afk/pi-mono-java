#!/usr/bin/env python3
"""Rebuild the SVG diagrams and the editable, five-page draw.io source.

Uses only Python's standard library. Optional PNG export: rsvg-convert -z 1.5.
"""

from html import escape
from pathlib import Path
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
BLUE, RED, GREEN = '#2886CE', '#C74738', '#398D42'
PURPLE, BROWN, GRAY, INK = '#805AC7', '#A17F72', '#72777D', '#302E2A'
MX = ET.Element('mxfile', host='app.diagrams.net', type='device')


class Figure:
    def __init__(self, name, title, width, height, subtitle):
        self.name, self.width, self.height = name, width, height
        self.n = 1
        self.svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
                    f'<title>{escape(title)}</title>', '<defs>']
        for color in (BLUE, RED, GREEN, PURPLE, BROWN, GRAY, INK):
            self.svg.append(f'<marker id="a{color[1:]}" viewBox="0 0 12 10" refX="11" refY="5" markerWidth="13" markerHeight="11" orient="auto" markerUnits="userSpaceOnUse"><path d="M0 0 L12 5 L0 10 Z" fill="{color}"/></marker>')
        self.svg += ['</defs>', f'<rect width="{width}" height="{height}" fill="white"/>']
        dia = ET.SubElement(MX, 'diagram', name=title, id=name)
        model = ET.SubElement(dia, 'mxGraphModel', grid='1', gridSize='10', page='1', pageScale='1', pageWidth=str(width), pageHeight=str(height), background='#FFFFFF')
        self.root = ET.SubElement(model, 'root')
        ET.SubElement(self.root, 'mxCell', id='0')
        ET.SubElement(self.root, 'mxCell', id='1', parent='0')
        self.text(width/2, 65, title, 42)
        self.text(width/2, 121, subtitle, 27, GRAY)

    def uid(self):
        self.n += 1
        return str(self.n)

    def text(self, x, y, value, size=27, color=INK, anchor='middle'):
        self.svg.append(f'<text x="{x}" y="{y}" font-family="Times New Roman, Songti SC, serif" font-size="{size}" fill="{color}" text-anchor="{anchor}">{escape(value)}</text>')
        w = sum(size*(.60 if ord(c)<128 else 1.01) for c in value)+30
        align = {'middle': 'center', 'end': 'right', 'start': 'left'}[anchor]
        tx = x-w/2 if anchor=='middle' else x-w if anchor=='end' else x
        cell = ET.SubElement(self.root, 'mxCell', id=self.uid(), value=value, style=f'text;html=1;strokeColor=none;fillColor=none;align={align};verticalAlign=middle;whiteSpace=wrap;fontFamily=Songti SC;fontSize={size};fontColor={color};spacing=0;', vertex='1', parent='1')
        ET.SubElement(cell, 'mxGeometry', x=str(tx), y=str(y-size*.94), width=str(w), height=str(size*1.4), attrib={'as':'geometry'})

    def shape(self, x, y, w, h, kind='round', fill='#FFFFFF', stroke=GRAY):
        i = self.uid()
        if kind == 'note':
            self.svg.append(f'<path d="M{x} {y} H{x+w-22} L{x+w} {y+22} V{y+h} H{x} Z M{x+w-22} {y} V{y+22} H{x+w}" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
            style = 'shape=note;size=22;'
        elif kind == 'cylinder':
            self.svg.append(f'<path d="M{x} {y+19} A{w/2} 19 0 0 1 {x+w} {y+19} V{y+h-19} A{w/2} 19 0 0 1 {x} {y+h-19} Z" fill="{fill}" stroke="{stroke}" stroke-width="2"/><ellipse cx="{x+w/2}" cy="{y+19}" rx="{w/2}" ry="19" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
            style = 'shape=cylinder3;boundedLbl=1;size=19;'
        else:
            self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="20" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
            style = 'rounded=1;arcSize=8;'
        cell = ET.SubElement(self.root, 'mxCell', id=i, value='', style=style+f'html=1;whiteSpace=wrap;fillColor={fill};strokeColor={stroke};strokeWidth=2;', vertex='1', parent='1')
        ET.SubElement(cell, 'mxGeometry', x=str(x), y=str(y), width=str(w), height=str(h), attrib={'as':'geometry'})
        return i

    def node(self, x, y, w, h, title, body=(), kind='round', fill='#FFFFFF', color=INK, size=29):
        self.shape(x,y,w,h,kind,fill,color)
        title = [title] if isinstance(title,str) else title
        lines = [(s,size,color) for s in title] + [(s,25,INK) for s in body]
        total = sum(s+13 for _,s,_ in lines)-13
        cursor = y+(h-total)/2+(12 if kind=='cylinder' else 0)
        for s,fs,c in lines:
            cursor += fs
            self.text(x+w/2,cursor,s,fs,c)
            cursor += 13

    def panel(self, x,y,w,h,title,fill='#FFFBF3',stroke='#D7A342'):
        self.shape(x,y,w,h,'round',fill,stroke)
        self.text(x+w/2,y+44,title,31)

    def edge(self, points, color=GRAY, dashed=False, arrow=True, curve=False):
        if curve:
            d='M'+','.join(map(str,points[0]))+' C'+' '.join(f'{x},{y}' for x,y in points[1:])
        else:
            d='M'+' L'.join(f'{x},{y}' for x,y in points)
        self.svg.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2.6" stroke-linejoin="round"'+(' stroke-dasharray="8 7"' if dashed else '')+(f' marker-end="url(#a{color[1:]})"' if arrow else '')+'/>')
        cell=ET.SubElement(self.root,'mxCell',id=self.uid(),value='',style=f'edgeStyle=none;curved={int(curve)};rounded=0;html=1;endArrow={"block" if arrow else "none"};endFill=1;strokeColor={color};strokeWidth=2.6;dashed={int(dashed)};',edge='1',parent='1')
        geo=ET.SubElement(cell,'mxGeometry',relative='1',attrib={'as':'geometry'})
        ET.SubElement(geo,'mxPoint',x=str(points[0][0]),y=str(points[0][1]),attrib={'as':'sourcePoint'})
        ET.SubElement(geo,'mxPoint',x=str(points[-1][0]),y=str(points[-1][1]),attrib={'as':'targetPoint'})
        if len(points)>2:
            arr=ET.SubElement(geo,'Array',attrib={'as':'points'})
            for x,y in points[1:-1]: ET.SubElement(arr,'mxPoint',x=str(x),y=str(y))

    def save(self):
        self.text(self.width-80,self.height-32,'源码核验：553dcd5e · CampusClaw / pi-mono-java',22,GRAY,anchor='end')
        self.svg.append('</svg>')
        (OUT/(self.name+'.svg')).write_text('\n'.join(self.svg)+'\n',encoding='utf-8')


def service_overview():
    f=Figure('service-overview','工具调用总览 · CampusClaw ↔ CampusMate',3600,2500,'蓝：Agent 级业务工具　红：Skill 级业务工具　绿：共用执行链　虚线：装配 / 发现 / 工作流')
    f.panel(55,195,2430,2130,'CampusClaw · Agent Runtime 服务','#F7F9FC','#ABB5C1')
    f.panel(2570,195,975,2130,'CampusMate · mate-service','#F3F3F3','#A3A3A3')
    f.panel(105,585,1080,740,'① Agent 级：Session 创建时装配')
    f.panel(1275,585,1160,740,'② Skill 级：按需读正文 / 取定义 / 调用')
    f.panel(105,1400,2330,450,'③ Session 共用工具执行与解析层')
    # The upper branches separate model-visible tool entry points from assembly.
    f.edge([(750,400),(585,400)],GRAY)
    f.edge([(885,480),(885,525),(145,525),(145,1130),(220,1130)],BLUE)
    f.text(430,508,'直接调用真实业务工具名',26,BLUE)
    f.edge([(1555,480),(1555,700)],RED)
    f.edge([(1700,480),(1700,550),(2380,550),(2380,660),(2100,660),(2100,700)],RED)
    f.edge([(1420,480),(1420,540),(1295,540),(1295,1140),(1530,1140)],RED)
    f.text(1980,530,'按需使用框架工具',26,RED)
    f.edge([(650,905),(650,1050)],BLUE,True)
    f.text(925,986,'动态包装 / 注入',25,BLUE)
    f.edge([(1790,800),(1850,800)],RED,True)
    f.edge([(2100,910),(2100,980),(1930,980),(1930,1050)],RED,True)
    # Invocation paths converge into the same execution pipeline.
    f.edge([(650,1230),(650,1510)],BLUE)
    f.edge([(1750,1230),(1750,1360),(960,1360),(960,1510)],RED)
    # Discovery paths go to the Session resolver; they are not invocation permission.
    f.edge([(1090,810),(1225,810),(1225,1375),(1630,1375),(1630,1510)],BLUE,True)
    f.edge([(2350,810),(2405,810),(2405,1470),(2060,1470),(2060,1510)],RED,True)
    f.edge([(1840,1770),(1840,1950),(1680,1950),(1680,2010)],GRAY,True)
    f.edge([(750,1770),(750,1915),(1120,1915),(1120,2010)],GREEN)
    f.text(948,1888,'固定的远端 handler',25,GREEN)
    f.text(2095,1930,'发现：不带凭据',25,GRAY)
    # Only the HTTP client crosses the service boundary for business metadata / execution.
    f.edge([(2110,2060),(2515,2060),(2515,1580),(2670,1580)],GRAY,True)
    f.edge([(2110,2160),(2670,2160)],GREEN)
    f.text(2380,2135,'HTTP execute',24,GREEN)
    f.edge([(1430,1640),(1300,1640)],GRAY,True)
    f.text(1365,1618,'可信定义',24,GRAY)
    f.node(110,330,475,150,'本地框架工具',['Read / Find / Grep / Ls 等'],fill='#F1F3F5',color=GRAY)
    f.node(750,315,1050,165,'AgentLoop · 模型 ↔ 工具循环',['初始 tools = 框架工具 + Agent 直接绑定业务代理'],fill='#DCEAF9',color=BLUE,size=35)
    f.node(220,710,870,195,'Session 装配',['Factory / Assembler → listAgentTools(agentId)','读取可信 Agent 绑定与完整工具元数据'],kind='note',fill='#FFF3D3',color=BLUE)
    f.node(220,1050,870,180,'MateToolAdapter × N',['以真实 name / inputSchema 注入初始 tools','模型直接调用；无需 SearchTools / CallTool'],kind='note',fill='#EDF6FF',color=BLUE)
    f.node(1320,700,470,210,'Skill({name})',['读受管 SKILL.md 正文','获得业务工具技术名'],kind='note',fill='#FFF3D3',color=RED)
    f.node(1850,700,500,210,'SearchTools({tool})',['精确取单个工具完整定义','不追加到初始 tools'],kind='note',fill='#FFF3D3',color=RED)
    f.node(1530,1050,820,180,'CallTool({tool, args})',['按工具技术名与业务参数调用','框架从可信 Skill 绑定解析真实 toolId'],kind='note',fill='#FFF1ED',color=RED)
    f.node(230,1510,1070,260,'ToolExecutionPipeline + MateToolExecutor',['真实业务 Schema 校验 → prepare 固定 handler / 快照','Host 权限 → 执行同一份 PreparedToolInvocation','远端走 MateRemoteToolHandler；结果经 Pipeline 回模型'],kind='note',fill='#F0F8ED',color=GREEN,size=29)
    f.node(1430,1510,830,260,'MateToolResolver',['Agent 绑定 / Skill 绑定 → 可信工具定义','缓存与 Executor 均为 Session 私有','CallTool.prepareSkill 同样使用 Resolver'],kind='note',fill='#FFF3D3',size=30)
    f.node(650,2010,1460,240,'MateToolClient → HttpMateToolClient',['listAgentTools(agentId) / listSkillTools(skillId)：查询绑定及元数据','callTool(toolId, args, credentials)：携带执行凭据','跨 Session 共享无状态 HTTP 客户端'],fill='#EAF2FA',color=BLUE,size=32)
    f.node(2670,365,775,275,'绑定关系管理',['Agent → bindingTools','Skill → bindingTools','工具元数据：name / description / inputSchema'],kind='cylinder',fill='#EEEEEE',size=32)
    f.node(2670,1340,775,300,'工具发现 API',['GET /agents/{agentId}','GET /skill/query/{skillId}','POST /runtime/tools/query','先查绑定 toolId，再批量取完整定义'],kind='cylinder',fill='#EEEEEE',size=31)
    f.node(2670,2010,775,240,'业务工具执行 API',['POST /runtime/tools/{toolId}/execute','body = {arguments: ...}','Mate 校验凭据并执行对应业务能力'],kind='cylinder',fill='#EAF4E7',color=GREEN,size=31)
    f.node(2655,795,805,310,'服务职责',['CampusClaw：模型循环 / 工具注册 / Skill 读取','可信解析 / 参数校验 / Host 权限 / 调用','CampusMate：绑定与定义 / 业务执行','业务 HTTP 路径统一前缀：/mate-service/v1'],kind='note',fill='#FFF3D3',size=31)
    f.text(1800,2390,'本地例外：confirm_with_user → BusinessConfirmationAdapter → Runtime 问答，失败不转远端。',29,PURPLE)
    f.text(1800,2440,'沿用早期 ListMateTool / CallMateTool 草图的服务分层；节点名称与调用方式按 553dcd5e 当前实现绘制。',26,GRAY)
    f.save()


def agent_tools():
    f=Figure('agent-tools','模块一 · Agent 绑定工具的装配与直接调用',3200,2070,'蓝：Session 装配与发现　红：调用准备　绿：权限通过后执行　虚线：元数据 / 结果传递')
    f.panel(55,195,3090,835,'① 装配期：每个 Session 独立创建 Resolver / Executor / 缓存')
    f.panel(55,1160,3090,780,'② 调用期：真实工具 Schema 校验 → 准备快照 → Host 权限 → 执行同一快照','#F7F9FC','#B2BAC4')
    f.edge([(530,440),(665,440)],BLUE)
    f.edge([(1165,440),(1300,440)],BLUE)
    f.edge([(1600,570),(1600,632),(920,632),(920,750)],BLUE)
    f.edge([(1165,835),(1300,835)],BLUE)
    f.edge([(1860,835),(1940,835),(1940,610),(2070,610),(2070,460),(2110,460)],BLUE)
    f.edge([(2540,440),(2670,440)],BLUE)
    f.edge([(1860,875),(2110,875)],BLUE)
    f.edge([(2540,835),(2670,835)],BLUE)
    f.node(100,350,430,180,'受管 Agent 元数据',['agent/{agentId}/','.campusclaw/agent.json'],'note','#FFF3D3')
    f.node(665,340,500,200,'AgentSessionFactory',['Runtime / Cron / Child 共用','prepare 目录 → createSession'],color=BLUE,fill='#EFF7FF')
    f.node(1300,305,600,265,['MateToolsetFactory','→ MateToolSessionState'],['Session 私有 Resolver / Executor','持有本次执行凭据快照'],'note','#FFF3D3')
    f.node(2110,330,430,220,'HttpMateToolClient',['listAgentTools(agentId)','发现不带凭据'],color=BLUE,fill='#EFF7FF')
    f.node(2670,310,430,260,'CampusMate',['GET agents/{id}','POST runtime/tools/query','绑定 ID → 完整元数据'],'cylinder','#F1F1F1')
    f.node(675,750,490,210,['DefaultConfiguredToolAssembler','#assemble'],['按入口配置创建框架工具','请求业务代理并检查名称冲突'],size=25)
    f.node(1300,750,560,210,'MateToolResolver',['resolveAgentTools()','缓存可信 ResolvedTool'],'note','#FFF3D3')
    f.node(2110,750,430,210,'MateToolAdapter × N',['Executor 包装解析结果','真实 name / inputSchema','AgentTool · SEQUENTIAL'],'note','#FFF3D3')
    f.node(2670,750,430,210,'初始工具集合',['框架工具 + 业务代理','Agent.setTools(...)'],color=BLUE,fill='#EFF7FF')
    f.text(1600,1000,'显式空工具配置直接返回空集合；业务代理也不追加。业务工具不能与框架工具或彼此重名。',27,GRAY)
    f.edge([(2885,960),(2885,1095),(320,1095),(320,1260)],BLUE,True)
    f.text(1760,1080,'装配后暴露给模型：从第一轮即可按真实业务名调用',25,BLUE)
    f.edge([(540,1360),(680,1360)],RED)
    f.edge([(1240,1360),(1370,1360)],RED)
    f.edge([(2130,1360),(2310,1360)],RED)
    f.edge([(2675,1480),(2675,1580),(1695,1580),(1695,1680)],GREEN)
    f.edge([(2675,1580),(2675,1680)],GREEN)
    f.node(100,1260,440,200,'模型 tool_use',['name = 真实业务工具名','arguments = 业务参数'],color=BLUE,fill='#EFF7FF')
    f.node(680,1260,560,200,'ToolExecutionPipeline',['先按 Adapter 的真实 Schema 校验','再调用 Adapter.prepare(...)'],color=RED,fill='#FFF1ED')
    f.node(1370,1240,760,240,['MateToolAdapter → MateToolExecutor','prepareAgent(...)'],['Dispatcher 在此选择并固定 handler','生成 PreparedToolInvocation 不可变快照'],'note','#FFF3D3')
    f.node(2310,1260,730,220,'Host 权限检查',['按快照中的真实工具身份判断','通过后仅执行同一份 invocation'],color=GREEN,fill='#F0F8ED')
    f.node(1320,1680,750,190,'已固定的本地 handler',['confirm_with_user → BusinessConfirmationAdapter','Runtime 问答；本地失败不转远端'],color=PURPLE,fill='#F5EFFF',size=29)
    f.node(2310,1680,730,190,'已固定的远端 handler',['MateRemoteToolHandler → HttpMateToolClient','POST runtime/tools/{toolId}/execute','携带凭据；发送一次，不自动重放'],color=GREEN,fill='#F0F8ED')
    f.text(1600,1990,'边界：发现不授权；普通业务工具遵循 allow / ask / deny；业务问答仅 ALLOW 可进入本地问答流程。',28)
    f.save()


def invocation_sequence():
    f=Figure('invocation-sequence','模块一 · 同一执行快照贯穿权限与调用',3400,2170,'蓝：工具输入与 Schema 校验　红：prepare 固定身份 / 参数 / handler　绿：Host 通过后执行')
    xs=[230,805,1380,1970,2575,3160]
    titles=[('模型',[]),('ToolExecutionPipeline',[]),('MateToolAdapter',[]),('MateToolExecutor',['+ BusinessToolDispatcher']),('Host 权限',[]),('已固定的 handler',['本地 / 远端'])]
    for x,(title,body) in zip(xs,titles):
        f.edge([(x,300),(x,1910)],GRAY,True,False)
        f.node(x-205,200,410,110,title,body,fill='#F6F8FB',color=BLUE,size=26)
    def msg(a,b,y,label,c=BLUE,dashed=False):
        f.edge([(xs[a],y),(xs[b],y)],c,dashed)
        f.text((xs[a]+xs[b])/2,y-18,label,25,c)
    def selfmsg(a,y,label,c):
        x=xs[a]
        f.edge([(x,y),(x+170,y),(x+170,y+60),(x,y+60)],c)
        f.text(x+188,y+34,label,24,c,anchor='start')
    msg(0,1,395,'tool_use(name, arguments)')
    selfmsg(1,500,'校验 Adapter 的真实业务 Schema',BLUE)
    msg(1,2,675,'prepare(toolCallId, arguments)',RED)
    msg(2,3,805,'prepareAgent(tool, schema, args)',RED)
    selfmsg(3,900,'select(handler) → handler.prepare',RED)
    msg(3,1,1080,'PreparedToolInvocation：身份、参数、Schema、handler 均固定',RED,True)
    msg(1,4,1220,'before hook：对真实目标工具执行权限检查',PURPLE)
    msg(4,1,1350,'通过 / 拒绝；普通工具 ASK 时先确认',PURPLE,True)
    msg(1,5,1510,'仅通过时 invocation.execute(signal, onUpdate)',GREEN)
    f.node(2680,1570,660,130,'执行准备阶段已固定的分支',['远端：HttpMateToolClient.callTool → CampusMate','本地：BusinessConfirmationAdapter → Runtime 问答'],kind='note',fill='#FFF3D3',size=28)
    msg(5,1,1780,'AgentToolResult / isError',GREEN,True)
    msg(1,0,1885,'after hook → ToolResultMessage',GREEN,True)
    f.node(80,1980,1540,105,'校验顺序',['正常 Pipeline 路径只在外层按真实 Schema 校验；prepareAgent 不重复做业务参数校验。'],kind='note',fill='#EFF7FF',color=BLUE,size=29)
    f.node(1760,1980,1560,105,'权限与执行边界',['业务问答仅 ALLOW；本地失败不转远端。execute 不自动重放，结果经 Pipeline 回到模型。'],kind='note',fill='#F0F8ED',color=GREEN,size=29)
    f.save()


def skill_tools():
    f=Figure('skill-tools','模块二 · Skill → SearchTools → CallTool 渐进发现',3400,2240,'三步是模型的典型工作流；读取与发现不注册业务工具、不授予权限，也不是 CallTool 的强制状态门禁')
    f.panel(55,205,3290,425,'① Skill({name})：按需读取正文，获得工具技术名','#F4F8FE','#90B4D0')
    f.panel(55,690,3290,690,'② SearchTools({tool})：按精确技术名披露单个完整定义')
    f.panel(55,1430,3290,560,'③ CallTool({tool, args})：可信解析 → 校验 → 权限 → 执行','#F3F9F0','#91B28A')
    for a,b in [(530,670),(1300,1450),(2240,2410)]: f.edge([(a,420),(b,420)],BLUE)
    f.node(110,320,420,190,'模型',['Skill({name})'],fill='#EFF7FF',color=BLUE)
    f.node(670,310,630,215,'SkillTool',['直接绑定且模型可见','工作区边界 / 符号链接 / UTF-8 校验'],fill='#EFF7FF',color=BLUE)
    f.node(1450,300,790,240,'受管 SKILL.md',['agent/{agentId}/.campusclaw/','skills/{name}/SKILL.md','业务工具技术名写在正文中'],kind='note',fill='#FFF3D3')
    f.node(2410,310,845,230,'工具结果 → 模型',['去 frontmatter 正文 + 基础目录','最多 10 条可读资源路径','不向初始 tools 集合追加业务工具'],fill='#EFF7FF',color=BLUE)
    f.text(1700,592,'资源来自整个 Skill 目录的可读普通文件抽样；不局限于 references / templates。',26,GRAY)
    f.edge([(590,940),(755,940)],RED)
    f.edge([(1460,940),(1620,940)],RED)
    f.edge([(2410,940),(2570,940)],RED)
    f.edge([(2910,1095),(2910,1175)],RED)
    f.edge([(2015,1095),(2015,1175)],RED,True)
    f.text(2205,1145,'缓存命中',25,RED)
    f.edge([(1630,1240),(365,1240),(365,1040)],RED,True)
    f.node(110,845,480,195,'SearchTools',['{tool: 精确技术名}','无查询词 / 分页 / 模糊匹配'],fill='#FFF1ED',color=RED)
    f.node(755,815,705,280,'MateToolExecutor',['resolveSkillDefinition()','返回 name / description / inputSchema','范围不匹配 → TOOL_NOT_FOUND'],kind='note',fill='#FFF3D3')
    f.node(1620,815,790,280,'MateToolResolver',['resolveDisclosableSkill()','直接绑定且模型可见 Skill 的工具','Session 缓存命中即复用'],kind='note',fill='#FFF3D3')
    f.node(2570,815,680,280,'HttpMateToolClient → CampusMate',['仅未命中缓存时 listSkillTools(skillId)','GET skill/query/{id}','POST runtime/tools/query；不带凭据'],kind='cylinder',fill='#F1F1F1',size=27)
    f.node(1630,1175,1620,145,'Executor 校验定义 / SearchTools 检查结果预算',['完整定义 ≤ 50 KiB → 返回模型；超限返回 TOOL_DEFINITION_TOO_LARGE，不截断 Schema。'],kind='note',fill='#FFF3D3')
    f.text(875,1215,'一份完整定义',25,RED)
    f.edge([(670,1680),(820,1680)],GREEN)
    f.edge([(1710,1680),(1840,1680)],GREEN)
    f.edge([(2460,1680),(2535,1680),(2535,1600),(2660,1600)],GREEN)
    f.edge([(2535,1680),(2535,1820),(2660,1820)],GREEN)
    f.node(110,1580,560,210,'CallTool',['{tool, args}','仅提交技术名与业务参数'],fill='#F0F8ED',color=GREEN)
    f.node(820,1535,890,290,'MateToolExecutor.prepareSkill()',['resolveSkill：按全部直接 Skill 绑定解析','业务 Schema 校验；选择并固定 handler','生成不可变 PreparedToolInvocation'],kind='note',fill='#FFF3D3')
    f.node(1840,1570,620,230,'Host 权限检查',['按真实目标工具判断','通过后执行同一份快照'],fill='#F0F8ED',color=GREEN)
    f.node(2660,1510,590,180,'固定本地 handler',['confirm_with_user','Runtime 问答；失败不转远端'],fill='#F5EFFF',color=PURPLE)
    f.node(2660,1730,590,180,'固定远端 handler',['MateRemoteToolHandler → HttpClient','execute 带凭据；一次请求'],fill='#F0F8ED',color=GREEN)
    f.text(1700,2070,'披露范围与调用权限不同：SearchTools 只披露模型可见 Skill；CallTool 仍须独立解析和通过 Host 权限检查。',28)
    f.text(1700,2125,'同一 Session 复用元数据缓存；读取 / 发现没有调用授权效果；跨 Session 同步与自动刷新仍属待实现范围。',27,GRAY)
    f.save()


def credentials():
    f=Figure('credential-lifecycle','凭据生命周期 · 本次执行内存快照与 execute-only 透传',3200,2010,'蓝：快照建立与只读继承　绿：执行请求透传　灰：无凭据发现　红：控制与持久化边界')
    f.panel(55,205,620,1650,'① 入站请求 / 触发','#F7F9FC','#B2BAC4')
    f.panel(755,205,1290,1650,'② 本次 Agent 执行期间')
    f.panel(2140,205,1005,1650,'③ HTTP 客户端与 CampusMate','#F4F4F4','#A3A3A3')
    f.edge([(625,465),(850,465)],BLUE)
    f.edge([(1395,605),(1395,660),(1165,660),(1165,740)],BLUE)
    f.edge([(1480,850),(1590,850)],BLUE)
    f.edge([(1480,950),(1520,950),(1520,1060),(2070,1060),(2070,970),(2240,970)],GREEN)
    f.edge([(625,1130),(710,1130),(710,550),(850,550)],BLUE)
    f.edge([(625,1530),(850,1530)],RED,True)
    f.edge([(2640,1145),(2640,1320)],GREEN)
    f.node(100,330,525,290,'POST Events / Command',['读取四项可选 Header','X-HW-ID · X-HW-APPKEY','Authorization · access-token'],kind='note',fill='#EFF7FF',color=BLUE)
    f.node(850,330,1090,275,'MateCredentials · 不可变快照',['缺失 Header 字段为 null；无入站凭据用 empty()','AppKey 与 JWT 同时存在时均保留','原名原值，不补默认值 / Bearer 前缀'],kind='note',fill='#FFF3D3')
    f.node(850,740,630,260,'执行上下文传递',['EventService → SessionRegistry','→ SessionFactory → Remote Handler','只在本次执行期间内存持有'],fill='#EFF7FF',color=BLUE)
    f.node(1590,750,405,230,'Child Agent',['只读继承父快照','独立工具解析缓存'],kind='note',fill='#FFF3D3',size=29)
    f.node(100,1040,525,180,'Cron 触发',['MateCredentials.empty()','没有入站调用方凭据'],fill='#EFF7FF',color=BLUE)
    f.node(100,1440,525,190,'控制请求',['停止 / 确认 / 问答响应','不替换原执行凭据'],fill='#FFF1ED',color=RED)
    f.node(850,1390,1090,230,'凭据边界',['控制请求沿用原执行快照','不写数据库 / Agent 目录 / Event / Prompt','不进入模型消息或日志；结束后 Runtime 不再持有'],kind='note',fill='#FFF3D3',color=RED)
    f.node(2240,330,800,275,'发现请求：不传凭据',['listAgentTools(agentId)','listSkillTools(skillId)','接口签名没有 credentials 参数','GET 绑定 + POST tools/query'],kind='note',fill='#F0F0F0',color=GRAY)
    f.node(2240,770,800,375,'执行请求：callTool(..., credentials)',['POST runtime/tools/{toolId}/execute','仅过滤 null / 空字符串 Header','四项凭据原名原值透传','参数 body = {arguments: ...}','空快照也发送一次；不自动重放'],fill='#F0F8ED',color=GREEN,size=29)
    f.node(2320,1320,640,260,'CampusMate',['认证与授权由 Mate 最终裁决','Runtime 不校验凭据组合完整性','execute 错误统一返回，不泄露敏感响应'],kind='cylinder',fill='#F1F1F1')
    f.text(1600,1920,'共享的 HttpMateToolClient 无 Session 状态；凭据由调用参数显式传入，不从其他请求借值，也不存入发现缓存。',28)
    f.save()


if __name__ == '__main__':
    service_overview()
    agent_tools()
    invocation_sequence()
    skill_tools()
    credentials()
    ET.ElementTree(MX).write(OUT/'tool-service-client.drawio',encoding='utf-8',xml_declaration=True)
    print('Generated five SVG diagrams and tool-service-client.drawio')
