# v17 Phase 1 本地验证记录

- 验证日期：2026-09-03（Asia/Shanghai）
- 仓库：`C:\Users\Administrator\Documents\Codex\2026-08-13\to\work\smart-locker-serial-test-v17`
- 分支：`v17-production-safety`
- 被验证源码提交：`2d0a3842d778528b6bf807a1eb42d09867d8cfb5`
- 验证范围：仅本地构建、自动化测试、静态 APK 审计、哈希与签名检查；未发起 live 网络请求，未连接或操作真实物理端点。

## 结论

本阶段已验证：双 APK 可构建、production Demo 隔离、客户物理动作失败关闭、localDemo 回归、v16 未改动。

本阶段未验证：测试服务器协议、真实设备备案、真实动态布局、真实人脸上传、真实锁板开柜、真实关门反馈。

所有修正后的 canonical 本地验证命令均以退出码 `0` 结束。production 是服务器接入前的失败关闭测试变体；本报告不把自动化或模拟结果表述为服务器或真机验收。

## 计划命令更正

Task 12 原先从 v17 根调用复制件：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\zip-ui-v16\run-tool.ps1 test
```

该命令实际退出码为 `1`，输出为：

```text
run-tool.ps1 must live under smart-locker-serial-test-v16: C:\Users\Administrator\Documents\Codex\2026-08-13\to\work\smart-locker-serial-test-v17
```

根因是 `tools/zip-ui-v16/run-tool.ps1` 的目录安全 guard 有意要求项目根目录 basename 为 `smart-locker-serial-test-v16`。v17 复制件与冻结的 v16 原件保持一致，因此原相对命令的退出码 `1` 表示 guard 正常工作，不是应用或工具测试断言失败。计划与 Task 12 brief 随后更正为从 v17 根调用 canonical frozen-v16 工具；没有削弱 guard，也没有修改 v16 源码：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ..\smart-locker-serial-test-v16\tools\zip-ui-v16\run-tool.ps1 test
```

## 完整本地验证矩阵

| 顺序 | 命令 | 退出码 | 关键证据 |
|---:|---|---:|---|
| 1 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\assert-v16-frozen.ps1` | 0 | `V16_SOURCE_FROZEN=PASS`; `V16_APK_FROZEN=PASS` |
| 2 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1 -Variant all` | 0 | common 907 tests; localDemo 54 tests; production 70 tests; 两 APK 签名/打包成功；`VERIFICATION=PASS` |
| 3 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\audit-production-apk.ps1 -Apk ..\..\outputs\智能更衣柜-v17-production测试版.apk` | 0 | production 独立审计十项 PASS |
| 4 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ..\smart-locker-serial-test-v16\tools\zip-ui-v16\run-tool.ps1 test` | 0 | 四个 zip-ui-v16 工具测试 PASS |
| 5 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\assert-v16-frozen.ps1`（工具测试后复核） | 0 | `V16_SOURCE_FROZEN=PASS`; `V16_APK_FROZEN=PASS` |

`build-debug.ps1 -Variant all` 的关键测试与最终状态输出：

```text
OK (907 tests)
OK (54 tests)
OK (907 tests)
OK (70 tests)
PRODUCTION_JVM=PASS TEST_CLASSES=7
PRODUCTION_APK_AUDIT=PASS
OUTPUT_SOURCE_HASH_IDENTICAL=True
OUTPUT_SOURCE_SIZE_IDENTICAL=True
APK_NEWER_THAN_ALL_PRODUCTION_INPUTS=True
V16_SOURCE_FROZEN=PASS
V16_APK_FROZEN=PASS
PRODUCTION_OUTPUT_APK_COUNT=1
VERIFICATION=PASS
```

构建期间 D8 对第三方百度 FaceSDK `liantian.jar` 输出了 `Expected stack map table for method with non-linear control flow` 警告；D8、后续打包、签名验证与独立安全审计均以退出码 `0` 完成。

独立 production APK 审计的精确输出：

```text
PRODUCTION_SOURCE_SECURITY=PASS
PRODUCTION_APK_DEX=PASS
PRODUCTION_APK_SIGNATURE=PASS
PRODUCTION_APK_MANIFEST=PASS
PRODUCTION_APK_PERMISSIONS=PASS
PRODUCTION_APK_FILES=PASS
PRODUCTION_APK_RESOURCES=PASS
PRODUCTION_APK_NATIVE=PASS
PRODUCTION_APK_MODELS=PASS
PRODUCTION_APK_ISOLATION=PASS
```

canonical zip-ui-v16 工具测试的精确输出：

```text
PASS ZipUiManifestTest screens=57 uniqueDrawables=57 protectedBrand=OK
PASS ZipUiPathSafetyTest root=bounded drawable=bounded output=bounded
PASS ZipUiSemanticContractTest home=4 face=8 palm=2 choice=2 locker=5 unlock=8 return=13 admin=18
PASS ZipUiAssetTest assets=57 dimensions=1280x800 minSimilarity=0.952239 screen=LOCKER_DISCOVERING
```

工具测试后的冻结复核精确输出：

```text
V16_SOURCE_FROZEN=PASS
V16_APK_FROZEN=PASS
```

## APK 哈希

计划中的两个哈希命令均原样执行并以退出码 `0` 结束。由于宿主的默认表格会缩略长路径，随后用 `Format-List Algorithm,Hash,Path` 读取同一 PowerShell 对象的完整字段；以下记录该未缩略的精确字段输出。

```powershell
Get-FileHash -Algorithm SHA256 ..\..\outputs\智能更衣柜-v17-localDemo.apk
```

```text
Algorithm : SHA256
Hash      : EEFE9567D528B29C6EC498686F4676E1B62E83FF26DAFF1D5BA85EC4EDDD04B9
Path      : C:\Users\Administrator\Documents\Codex\2026-08-13\to\outputs\智能更衣柜-v17-localDemo.apk
```

```powershell
Get-FileHash -Algorithm SHA256 ..\..\outputs\智能更衣柜-v17-production测试版.apk
```

```text
Algorithm : SHA256
Hash      : 75B38BDC718E5EAB5A2248F446DE2BACA92CFFC92548F4F6704250590238F364
Path      : C:\Users\Administrator\Documents\Codex\2026-08-13\to\outputs\智能更衣柜-v17-production测试版.apk
```

构建记录中的文件大小分别为：localDemo `33,251,553` bytes；production `33,247,457` bytes。

## production APK 签名证据

命令（退出码 `0`）：

```powershell
& 'C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat' verify --print-certs ..\..\outputs\智能更衣柜-v17-production测试版.apk
```

精确输出：

```text
Signer #1 certificate DN: CN=Android Debug, O=Codex, C=CN
Signer #1 certificate SHA-256 digest: 8a04a1200db74368bf67d8982990d56d81d05c3197df2ce3806be9e95f8362e3
Signer #1 certificate SHA-1 digest: 2fa7e578aa69bd79001c88641bfec16ac1e84232
Signer #1 certificate MD5 digest: 7dfdb68f781c3264ca73adb600b76998
```

该证书明确是 Android Debug 证书；此 APK 是 `production测试版`，不是使用正式发布密钥签署的生产发布包。

## 已验证边界

- 双变体完整本地构建、JVM 测试、Android Java 编译、DEX、资源、Manifest、原生库、模型、zipalign 与签名验证。
- production 交付 APK 的 Demo 类/凭证隔离与 fail-closed 源码/DEX 测试；无完整服务器一次性授权时，自动化测试确认客户物理动作不会获得串口写入权限。
- localDemo 的自动化回归，以及 57 个 1280×800 ZIP UI 资产的 manifest、路径、语义与相似度测试。
- canonical zip-ui-v16 工具运行前后，v16 源码清单与既有 v16 APK 均与冻结基线一致。

## 未验证与后续阻断项

- 尚未接入或验证测试服务器 HTTPS 协议、签名/取消、设备 SN，以及 `checkDevice`、`baseSetting`、`basicData`。
- 尚未验证真实设备备案、服务器动态布局、服务器手机号/密码/扫码认证、真实人脸上传与比对。
- 尚未在 RK3288/RS-485 真机验证锁板开柜、门磁/关门反馈、串口权限和异常恢复。
- 尚未使用正式发布签名；当前 production 测试 APK 使用 Android Debug 证书。

上述外部验证必须留待后续 HTTPS/服务器集成计划与真机验收，不得用本阶段的 localDemo 或自动化结果替代。
