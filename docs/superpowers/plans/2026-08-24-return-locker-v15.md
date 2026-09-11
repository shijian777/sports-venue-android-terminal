# 智能更衣柜 v15 离场还柜实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task, and `superpowers:test-driven-development` for every behavior change.

**Goal:** 在 v14 独立副本中实现安全、可模拟验证、未来可替换为正式服务的离场还柜闭环。

**Architecture:** 普通用柜流程保持不变；新增独立 `returnflow` 状态机和 source-set 服务组装。服务器逐柜授权精确开锁帧，串口策略只放行当前授权；开锁后由单锁状态监控器确认“关→开→连续两次关”，再幂等提交完成。

**Tech Stack:** Java 8、Android Camera/现有百度 SDK、现有进程级 RS485 `SerialGateway`、JUnit 4、PowerShell 自定义 Android 构建链。

**Spec:** `docs/superpowers/specs/2026-08-24-return-locker-design.md`

## 全局约束

- 项目不是 Git 仓库，不能创建 worktree 或提交；已用 `smart-locker-serial-test-v15` 独立目录隔离。每任务以测试输出和源码哈希作检查点。
- 只能修改 v15；不得修改 v14 或历史输出。
- 每项先写失败测试、确认 RED，再做最小实现、确认 GREEN，最后运行相关回归。
- localDemo 可以模拟服务通过；production 在接口缺失时必须失败关闭。
- 不修改用户给定的普通 12 柜指令；还柜指令只能来自不可变授权对象。
- 不把 ACK 当作关门完成，不自动开柜，不在未确认时发送开锁帧。

## Task 1：升级 v15 构建标识并保护 v14

**Files:**
- Modify: `app/build.gradle`
- Modify: `scripts/build-debug.ps1`
- Modify: `README.md`
- Test: `scripts/build-debug.ps1` 内的版本与历史产物守卫

1. 先把预期版本改为 15 的断言写入脚本，运行并确认因源码仍为 14 而失败。
2. 将 versionCode/versionName 改为 `15`/`15.0-demo`，构建目录改为 `manual-build/v15`，APK 改为 `智能更衣柜-离场还柜模拟联调版-v15.apk`。
3. 把当前 v14 APK 的绝对路径、大小和 SHA-256 加入历史保护；构建前后都校验。
4. 运行完整构建，确认 v15 标识与 v6-v14 历史保护通过。

## Task 2：测试先行建立还柜领域状态机与服务契约

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnIdentity.java`
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnLocker.java`
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnAuthorization.java`
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnServiceClient.java`
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnFlowModel.java`
- Test: `app/src/test/java/com/codex/lockertest/returnflow/ReturnFlowModelTest.java`
- Test: `app/src/test/java/com/codex/lockertest/returnflow/ReturnAuthorizationTest.java`

1. RED：覆盖首页→认证→查询→本人柜门→逐柜授权→开门→待关门→提交→下一柜/完成，以及失败、取消、过期和旧代次。
2. 实现不可变 DTO、防御复制和显式状态枚举；非法跳转返回 false/typed result，不隐式越权。
3. 授权对象必须包含操作 ID、目标、精确 command/success/failure、completionToken、expiresAt。
4. GREEN：运行新测试和现有 `KioskFlowModelTest`，证明普通用柜流程未改。

## Task 3：实现 localDemo 模拟服务与 production 失败关闭

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnServiceFactory.java`
- Create: `app/src/localDemo/java/com/codex/lockertest/returnflow/ReturnServiceAssembly.java`
- Create: `app/src/localDemo/java/com/codex/lockertest/returnflow/LocalDemoReturnServiceClient.java`
- Create: `app/src/production/java/com/codex/lockertest/returnflow/ReturnServiceAssembly.java`
- Create: `app/src/production/java/com/codex/lockertest/returnflow/FailClosedReturnServiceClient.java`
- Test: `app/src/localDemoTest/java/com/codex/lockertest/returnflow/LocalDemoReturnServiceClientTest.java`
- Test: `app/src/test/java/com/codex/lockertest/returnflow/ReturnSourceSetSafetyTest.java`

1. RED：ID `0014872138` 返回 A1；已知二维码返回 A1+A2；未知凭证返回空；每次授权固定且可过期；完成提交幂等。
2. 实现 localDemo 固定夹具，页面/结果携带 `LOCAL_DEMO` 标记；凭证日志只保留类型和摘要。
3. production 实现只返回接口未接入/网络不可用，`authorizeOpen` 永不返回命令。
4. GREEN：运行 common/localDemo 测试和 production 编译。

## Task 4：实现柜门状态协议与稳定关门状态机

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/protocol/DoorStateProtocol.java`
- Create: `app/src/main/java/com/codex/lockertest/protocol/DoorStateResponseDetector.java`
- Create: `app/src/main/java/com/codex/lockertest/returnflow/DoorCloseMonitor.java`
- Test: `app/src/test/java/com/codex/lockertest/protocol/DoorStateProtocolTest.java`
- Test: `app/src/test/java/com/codex/lockertest/protocol/DoorStateResponseDetectorTest.java`
- Test: `app/src/test/java/com/codex/lockertest/returnflow/DoorCloseMonitorTest.java`

1. RED：任意板/锁查询、XOR、碎片/粘包、错误地址、主动 `82` 提示、两种极性。
2. RED：只有 `CLOSED→OPEN→CLOSED→CLOSED` 完成；重复 CLOSED、单次 CLOSED、未知、断线、超时和旧操作均不完成。
3. 实现纯协议解析器和纯状态机；主动推送只返回“需要立即轮询”，不直接设置完成。
4. GREEN：运行新测试及现有 `BoardStatus*`、`LockerProtocol*` 回归。

## Task 5：为还柜增加精确命令与状态查询串口权限

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/unlock/AuthorizedUnlockRequest.java`
- Modify: `app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java`
- Modify: `app/src/main/java/com/codex/lockertest/integration/CustomerSerialPhase.java`
- Modify: `app/src/main/java/com/codex/lockertest/integration/CustomerSerialWritePolicy.java`
- Modify: `app/src/main/java/com/codex/lockertest/integration/CustomerSerialTransmitter.java`
- Test: `app/src/test/java/com/codex/lockertest/unlock/AuthorizedUnlockRequestTest.java`
- Test: `app/src/test/java/com/codex/lockertest/unlock/UnlockCoordinatorTest.java`
- Test: `app/src/test/java/com/codex/lockertest/integration/CustomerSerialWritePolicyTest.java`

1. RED：授权请求防御复制、过期/目标不符拒绝；协调器只发送授权 command 并只接受授权响应。
2. RED：`RETURN_UNLOCK` 每操作只允许一次精确 payload；`DOOR_STATUS` 只允许当前目标精确查询；其他 `8A/80` 全拒绝。
3. 增加新入口但保留普通 `start(...)` 行为和历史命令测试。
4. GREEN：运行 coordinator、serial policy、12 柜协议和全量 common 测试。

## Task 6：实现还柜页面与首页入口

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ReturnAuthView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ReturnLockerView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ReturnProgressView.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/ReturnUiSourceTest.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/ZipHomeViewSourceTest.java`

1. RED：首页有独立 `onReturnRequested()` 和“离场还柜”；普通确认按钮不触发它。
2. RED：认证页明确还柜语义；柜门页只接受传入的服务器列表；进度页包含开柜、关门、提交、错误、重试和倒计时状态。
3. 用现有蓝绿 1280×800 shell 构建 View，不在 View 中写服务/串口逻辑。
4. GREEN：运行 UI 源契约测试，确认原首页手机号/密码、人脸、掌纹、管理员交互仍存在。

## Task 7：在 MainActivity 编排完整离场还柜流程

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Create: `app/src/main/java/com/codex/lockertest/returnflow/ReturnFlowController.java`
- Test: `app/src/test/java/com/codex/lockertest/returnflow/ReturnFlowControllerTest.java`
- Test: `app/src/test/java/com/codex/lockertest/ReturnMainIntegrationSourceTest.java`

1. RED：入口进入 RETURN journey；ID/QR 持续监听路由到还柜服务；人脸/掌纹只有点击后启动；普通用柜路径不变。
2. RED：无网络绝不授权/写串口；用户确认后才发送一次精确开锁；ACK 后进入待关门而非成功。
3. RED：轮询只针对当前柜；稳定关门后提交；多柜逐个；取消、后台、旧回调、串口断开和提交失败安全恢复。
4. 实现控制器管理服务、授权、串口、轮询代次和幂等提交；MainActivity 只负责页面路由与生命周期转发。
5. 复用现有人脸验证票据校验；掌纹在 localDemo 显式模拟，production 未接入时失败关闭。
6. GREEN：运行新测试、现有 face、passive scan、discovery、unlock、serial 和 UI 回归。

## Task 8：全量验证、独立代码复核与 APK 交付

**Files:**
- Modify: `README.md`
- Create: `.superpowers/sdd/2026-08-24-return-v15/verification.md`

1. 运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

2. 独立检查 APK versionCode/name、签名、ABI、权限、localDemo 标记、production 无模拟放行、v14 哈希未变。
3. 请求独立代码复核，重点检查越权开柜、精确指令、ACK/关门混淆、旧回调、生产失败关闭、串口长期占用和隐私日志；修复所有 Critical/Important。
4. 再跑一次完整构建并记录测试数、APK 路径/大小/SHA-256。
5. 明确交付边界：桌面已完成模拟闭环与构建验证；真实 A/B 板、门状态极性、断网和 50 次循环必须在 RK3288 真机验收后才可宣称现场稳定。

