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
  - 动作：移动、跳跃、潜行、疾跑、攻击、交互、使用物品、挖掘/放置方块、容器交互、
    骑乘/解除骑乘、聊天/执行命令、睡觉、重生、写书/告示牌、信标、物品改名等
- `/query <player> <query>`：查询假人状态（与 `/control` 分离，不改变假人状态）
  - 查询：`list`、`info`、`inventory`、`container`、`near [r]`、`block x y z`、
    `online`、`chatlog`、`memory`
  - 监听：`/query <player> listen on/off` 实时推送假人事件，
    `/query <player> events [n]` 查看最近事件缓存
  - `memory`：JVM 堆为真实值，Mod 侧跟踪字节为精确记账，原版世界内部报实体/区块数

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
