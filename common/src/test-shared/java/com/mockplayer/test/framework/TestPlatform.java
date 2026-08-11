package com.mockplayer.test.framework;

/**
 * 平台 SPI：双端 testmod 只实现本接口，测试本体零平台代码。
 *
 * 输入：平台入口在客户端 tick 时调用 {@link SuiteRunner#tick}。
 * 输出：Fabric 用 FabricLoader / ClientCommandInternals，NeoForge 用 ModList / ClientCommandHandler。
 */
public interface TestPlatform {

    /** 平台模组加载器查询（YACL 等可选依赖冒烟）。 */
    boolean isModLoaded(String modId);

    /** 走平台客户端命令链路执行一条聊天命令（true = 执行成功）。 */
    boolean executeClientCommand(String command);

    /** 注册层 dispatcher 是否含根命令（热重载断言，Fabric 与 NeoForge 实现不同）。 */
    boolean hasActiveRoot(String commandName);

    /** 执行层 dispatcher（ClientPacketListener.commands）是否含根命令。 */
    boolean hasExecRoot(String commandName);
}
