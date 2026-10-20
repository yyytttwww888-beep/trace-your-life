# 华为运动健康接入：设计阶段核实

核实日期：2026-10-16。范围为个人自用 App 获取华为运动健康的睡眠与运动数据。尚不知道用户手机系统、版本、华为账号地区与穿戴设备；没有创建应用、申请权限或访问私人数据。

2026-10-20 后续确认：用户使用 Android；精确型号、系统版本、账号地区和穿戴设备仍待确认。[Android 接入准备与验收](../HUAWEI_SETUP.md)整理了当前包名与实际 APK 签名指纹、开发者控制台操作、仓库外配置位置和一次性准备清单，并明确当前 SDK 与设备健康主存尚未实现。下文保留最初研究的时间和证据范围。

## 对当前产品方案的结论

保留“华为运动健康同步”需求，有官方开放途径，但暂不能承诺个人自用 App 已具备申请资格，也不能承诺能取得华为运动健康界面展示的全部睡眠指标。每日随记和复盘可以独立设计；同步需在明确手机系统后做一个小范围可行性验证。用户已明确本阶段不做资料导入，本研究不把导入作为替代方案。

## 官方已明确的内容

| 项目 | 核实结果 | 适用范围与来源 |
| --- | --- | --- |
| 官方数据开放服务 | 服务支持用户授权后读写已开放的运动健康数据。英文主页列出 Android、iOS、Web、Quick App、HarmonyOS。 | [英文 Health Kit 主页](https://developer.huawei.com/consumer/en/hms/huaweihealth?ha_source=hms1)。平台列举不等于每个平台拥有相同 SDK、数据字段或准入条件。 |
| 授权边界 | 应用可读范围同时受平台批准范围和用户授予的权限约束；华为运动健康的分享授权也需开启。 | [官方授权说明](https://developer.huawei.com/consumer/en/doc/HMS-Plugin-Guides-V1/signing-in-and-pplying-for-permissions-0000001074001642-V1)，2026-06-11 更新。 |
| 睡眠读取实例 | 官方 SleepTracker 示例展示通过睡眠读权限获取穿戴设备睡眠数据，目标是在安装 HMS Core 的安卓手机运行。 | [睡眠记录应用 Codelab](https://developer.huawei.com/consumer/cn/codelab/SleepTracker/index.html?cardName=SleepTracker&lang=cn)。这是能力实例，不能替代最新准入审核或证明用户全部字段可用。 |
| 运动同步链路 | 穿戴设备记录先同步到华为运动健康，再同步至云端；已接入的第三方应用需要绑定授权，读取可能存在延迟。 | [华为官方第三方同步说明](https://consumer.huawei.com/cn/support/content/zh-cn01115094/)。该说明区分 HarmonyOS 5 及以上与旧 HarmonyOS、第三方 Android、iOS 的设置入口。 |
| 云端数据订阅通知 | 官方中文文档明确：注册订阅通知能力要求企业开发者，个人开发者不支持申请该订阅能力。 | [云侧订阅数据](https://developer.huawei.com/consumer/cn/doc/hmscore-guides/subscription-0000001078496860)，2026-09-03 更新。此限制针对订阅通知，不能推导成所有查询接口均禁止个人开发者。 |
| iOS 转 Apple 健康 | 华为国内支持页明确了步数可同步至 iOS 健康的操作方法。 | [同步步数数据至 iOS 健康](https://consumer.huawei.com/cn/support/content/zh-cn15923276/)。该页没有证明睡眠分期、睡眠评分或全部运动记录可同步，不能承诺 Apple Health 转接满足需求。 |

## 需谨慎使用的申请条件证据

[HarmonyOS（JavaScript）服务申请指南](https://developer.huawei.com/consumer/en/doc/HMSCore-Guides/harmonyos-apply-kitservice-0000001194699502)（英文，2026-08-10）列出了个人和企业申请材料，测试权限需人工审核，测试阶段有用户数限制，且后续需申请验证。说明“个人申请入口存在”与“本项目一定合资格”是两回事，测试权限也不应视为永久自用权限。

[HarmonyOS 原子服务申请指南](https://developer.huawei.com/consumer/ru/doc/atomic-guides/health-apply-as)（官方 ru 路径、英文正文，2026-07-21）在个人资质章节要求手机应用已上架 AppGallery，且个人只可申请基础开放级别，不能申请高级开放级别。但这次未成功读到相应中文页，也没有完成 Android 或 REST 独立资质页的核实。因此，该限制只能按所引指南的范围报告，不能直接泛化到境内所有接入途径。

名称也不足以区分平台：[中文 HMS 服务首页](https://developer.huawei.com/consumer/cn/hms/huaweihealth/)目前展示 Health Service Kit，而英文同类主页展示 Health Kit。选型应看具体开发指南、运行系统、申请产品类型和权限，而不是仅凭两个名称划分“安卓”和“鸿蒙”。

## 当前明确未知项

- 手机型号与系统：iOS、Android、旧 HarmonyOS，还是 HarmonyOS 5 及以上；华为账号所属地区。
- 穿戴设备和实际记录：是否有整晚睡眠、睡眠阶段、心率及运动明细；设备产生数据不等于该字段对第三方开放。
- 本项目在对应平台、地区与开发者身份下能否获批；是否必须上架；测试权限期限；基础开放级别包含哪些目标睡眠字段。
- iOS 设备上华为运动健康实际写入 Apple 健康的数据类别，尤其睡眠与运动记录；本次官方国内文档只确认步数。
- 需要的最小数据集合是否可用：睡眠起止与时长、睡眠阶段、运动类型、时长、步数等，分项确认，不承诺“全部数据”。

## 之后的最小验证

先确认系统、账号地区和穿戴设备。再选对应官方指南，核实个人开发者准入和最小只读权限，最后在用户实际设备完成一条睡眠记录和一条运动记录的授权读取验证。这里是后续设计建议，本次没有执行申请或真机验证。

## 简短查询记录

- 路由：AgentSearch 的公开网页路线；当前无可调用 Exa/Jina，使用 web 搜索和华为官方页面，不安装额外工具。
- 官方开发文档：搜索 Health Kit / Health Service Kit、个人开发者、睡眠、资格、云侧订阅；多批查询，找到能力、授权、订阅限制和两个 HarmonyOS 申请页。
- 官方国内支持：搜索华为运动健康与 iOS 健康、第三方同步；两批主要查询，确认步数同步和云端同步条件。
- 直接阅读限制：部分 developer.huawei.com 页面可通过搜索索引读到正文，但再次 open/find 返回内部错误；中文申请页超时；直接 HTML 仅返回渲染外壳。未据此补写缺失的中文准入条款。
- 非官方讨论未作为结论来源。
