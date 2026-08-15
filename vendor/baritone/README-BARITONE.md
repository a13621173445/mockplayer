# Baritone (vendored)

本目录为 [Baritone](https://github.com/cabaletta/baritone) 官方源码，
引入自 cabaletta/baritone 的 `26.2` 分支（对应 Minecraft 26.2），
commit 以 vendor 引入时的上游 26.2 分支最新提交为准。

- 许可：LGPL-3.0（见本目录 LICENSE）
- 用途：为 mockplayer 假人提供寻路能力（路径规划/执行）
- 修改状态：原样引入，未修改
- 集成方式：本目录作为独立 Gradle 子项目构建，产物被 mockplayer 依赖；
  mockplayer 自身代码（MIT）不与此目录混编

若修改本目录代码：改动必须保持 LGPL-3.0，并在仓库根 NOTICE 与本文件
记录修改内容与日期。

## 构建配置修改记录（仅构建环境，不影响源码/行为）

- 2026-08-15：gradle-wrapper distributionUrl 改为腾讯镜像
  （mirrors.cloud.tencent.com/gradle），settings.gradle 的 pluginManagement
  与 build.gradle 的 allprojects repositories 增加阿里云 public 镜像
  （maven.aliyun.com/repository/public）加速依赖下载。仅构建环境改动，
  无源码/行为变更。
