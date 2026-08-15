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

    // ===== mod payload 拦截测试 SPI（双端 testmod 实现） =====

    /** 服务端向指定假人连接发送测试 clientbound payload（payload_a）。 */
    void sendTestPayloadToBot(String botName);

    /** 服务端向指定假人连接一次发送 count 个 payload_b（count 字段 0..count-1 递增）。 */
    void sendTestPayloadBToBot(String botName, int count);

    /** 服务端向主玩家连接发送测试 clientbound payload（对照组：主玩家链路不受拦截）。 */
    void sendTestPayloadToMainPlayer();

    /** 客户端测试 handler 是否被触发过（false = 假人连接的 handler 未被调用，拦截生效）。 */
    boolean isClientTestHandlerFired();

    /** 服务端测试 handler 是否收到过测试 serverbound payload（sendModPayload 链路验证）。 */
    boolean isServerTestHandlerFired();

    /** 复位双端测试 handler 标志。 */
    void resetTestPayloadFlags();

    /** 当前物理端名（"Fabric" / "NeoForge"，与 {@code Services.PLATFORM.getPlatformName()} 对照）。 */
    String platformName();

}
