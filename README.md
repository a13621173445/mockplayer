# Mockplayer

纯客户端实现的 Minecraft 假人（Fake Player）模组，支持 Fabric 与 NeoForge 双端。

## 简介

在 offline-mode 服务器上创建与真玩家零区别的假人：每个假人拥有独立的会话、连接与状态，
服务端视角就是普通玩家。支持通过命令遥控假人的移动、攻击、交互、挖掘、容器、骑乘、聊天，
以及查询假人状态和监听事件。

## 当前状态

当前可能存在不稳定情况，请按需使用。

## 功能

- `/newplayer <name>`：创建假人并加入当前服务器
- `/delplayer <name>`：移除假人
- `/connect <name> <host> [port]`：让已存在的假人连接指定服务器
- `/control <player> <action>`：遥控假人
  - 用 `/control <player> help` 查看全部动作列表
  - 动作：移动、跳跃、潜行、疾跑、攻击、交互、使用物品、挖掘/放置方块、容器交互、
    骑乘/解除骑乘、聊天/执行命令、睡觉、重生、写书/告示牌、信标、物品改名等
- `/query <player> <query>`：查询假人状态（与 `/control` 分离，不改变假人状态）
  - 查询：`list`、`info`、`inventory`、`container`、`near [r]`、`block x y z`、
    `online`、`chatlog`、`memory`
  - 监听：`/query <player> listen on/off` 实时推送假人事件，
    `/query <player> events [n]` 查看最近事件缓存
  - `memory`：JVM 堆为真实值，Mod 侧跟踪字节为精确记账，原版世界内部报实体/区块数

## 命令参考（部分动作语义）

- `useItemOn <x> <y> <z> <side>`：对着方块指定面使用物品（打开箱子/炉子等容器的底层操作）
- `placeBlock <x> <y> <z> <side>`：手持方块对着指定面放置
- `interact <target>`：右键实体（村民交易、喂食、骑乘等）
- `mount [target|anything]`：`mount` 自动骑最近可骑乘实体；`mount anything` 骑任意实体；
  `mount <target>` 骑指定实体（类型 id 或名字）
- `dismount`：解除骑乘（按住潜行约 0.5 秒的原版等效行为）
- 容器交互：`click <slot> <button> <mode>` 点击槽位、`setSlot <slot>` 写入槽位、
  `button <id>` 点菜单按钮、`trade <index>` 选交易、`close` 关闭容器
- `setBeacon <primary> [secondary]`：需要先 `click` 把支付物品
  （金锭/铁锭/钻石/绿宝石）放入信标菜单槽 0，否则服务端拒绝激活

## 配置

所有根命令名（`control`/`query`/`newplayer`/`delplayer`/`connect`）都可以在
`config/mockplayer.json` 的 `commands` 对象里改名，与其他模组冲突时直接改配置：

```json
{
  "commands": {
    "control": "control",
    "query": "query",
    "newplayer": "newplayer",
    "delplayer": "delplayer",
    "connect": "connect"
  }
}
```

- 命令名留空 = 禁用该命令
- 非法值（空格/超长/非法字符）或启用项之间重名会自动回退默认名
- 其余设置（聊天/音效/粒子日志保留条数、事件缓存与采样参数等）同样在
  `config/mockplayer.json` 或 GUI 中修改
- YACL 是可选依赖：装有 YACL 时可在模组列表的「配置」界面修改全部设置，
  保存后立即热重载，无需重进游戏；不装 YACL 时无图形界面，直接手改 JSON 即可
- 改完 JSON 后重进游戏生效

## 环境

- Minecraft 26.2
- Fabric 或 NeoForge 客户端
- offline-mode（离线模式）服务器

## 感谢

- [Carpet](https://github.com/gnembon/fabric-carpet)（假人遥控与事件思路）
- [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template)（双端工程模板）
- [Fabric API](https://github.com/FabricMC/fabric)
- [NeoForge](https://github.com/neoforged/NeoForge)
- [Mixin](https://github.com/SpongePowered/Mixin)

## 免责声明

本模组与 Mojang Studios / Microsoft 无关，未经官方认可。
使用本模组请遵守 Minecraft 最终用户许可协议（EULA）。
本模组不包含任何 Minecraft 原版代码或资源。

## 协议

MIT License

Copyright (c) 2026 1cyberlangke1

## AI 生成声明

本项目的代码由大语言模型（LLM）辅助生成。
