# 回复球 · ReplyBubble（v0.2）

聊天界面点悬浮球 → 自动读屏识别对话 → 生成 AI 回复 → 点击直接填入输入框，无需手动复制粘贴（未开启无障碍时自动退回剪贴板模式）。

一个**悬浮窗小 App**，零 NDK、零 C++、不碰输入法。用来先验证"AI 辅助回复到底有没有用"，跑通了再搬进 fcitx5-android。

---

## 1. 它怎么工作

```
在聊天界面直接点悬浮球（可拖动，贴边半透明）
        ↓
无障碍服务读取当前屏幕对话（按左右气泡区分「对方 / 我」）
        ↓
弹出透明底部面板，请求大模型生成多档位回复
        ↓
点击任意一条回复 → 面板关闭 → 自动填入当前聊天输入框
```

> 若未开启无障碍服务，仍兼容 v0.1 的「复制对方消息 → 点球 → 点卡片复制」流程。

关键设计取舍：

- **读剪贴板必须靠 Activity，不能靠悬浮窗**。Android 10 起剪贴板只对"有焦点"的应用开放；悬浮窗是 `FLAG_NOT_FOCUSABLE` 的，读不到。所以面板做成透明 Activity，不是悬浮 View。这是这个方案能成立的前提。
- **一次请求出 6 条**，不是两路并发。少一次握手，更快更便宜，JSON 也更稳。
- **悬浮球只是入口**，真正干活的都在 Activity 里，所以后面搬到输入法时，`ReplyEngine` 可以整个复用。

---

## 2. 编译运行

**环境**：Android Studio（Koala 或更新）、JDK 17、Android SDK 34。

1. Android Studio → `Open` → 选这个文件夹（`reply-bubble`）。
2. 第一次打开会自动下载 Gradle 8.7 和依赖，等它跑完。
   - 如果提示找不到 `gradlew`，说明 wrapper jar 没生成：在 Studio 里点一次 Sync 即可，或命令行执行 `gradle wrapper`。
3. 手机开 USB 调试，点 Run。

命令行等价操作：

```bash
gradle wrapper          # 只需一次
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

> 想在 GitHub Actions 上出 APK 也行，但没有原生代码，`ubuntu-latest` + `setup-java 17` + `./gradlew assembleDebug` 就够了。

---

## 3. 首次使用（4 步）

1. 打开 App → **设置接口和 Key**：填 API 地址、模型、Key。
   - DeepSeek：`https://api.deepseek.com/chat/completions` / `deepseek-chat`
   - 硅基流动：`https://api.siliconflow.cn/v1/chat/completions` / `deepseek-ai/DeepSeek-V3`
   - 任何 OpenAI 兼容的 `/chat/completions` 都能用
2. **填人设卡**。这是效果差距最大的一项，别跳过。例：
   > 对方是同事，认识两个月，最近聊得挺多但约不出来；我男，想自然推进到周末吃个饭。
3. 回主界面 → **开启悬浮球** → 授予"显示在其他应用上层"（有的系统叫"悬浮窗"）。
4. 去微信复制一条消息，点球。

**小米/华为/OPPO 必做**：系统设置里给本 App 打开「后台弹出界面」/「允许后台显示界面」，否则点球没反应——这是 OEM 对后台启动 Activity 的限制，不是代码问题。

---

## 4. 改哪里

| 想改的东西 | 文件 |
|---|---|
| 提示词（**最值得调的**） | `ReplyEngine.kt` 里的 `SYSTEM` 常量 |
| 6 条的分组/标签 | `SYSTEM` 里的 JSON 结构 + `ReplyAdapter.kt` 的前缀 |
| 面板 UI | `res/layout/activity_reply.xml` |
| 接口地址默认值 | `Prefs.kt` 的 `DEFAULT_URL` / `DEFAULT_MODEL` |
| 悬浮球样式/大小 | `res/layout/view_bubble.xml`、`res/drawable/bg_bubble.xml` |
| 气泡图标 | `res/drawable/ic_bubble.xml` |

调 prompt 的建议：一次只改一个变量，拿 20 条真实聊天记录当测试集，给"能直接发出去"打分。**不要凭感觉调**，6 条里有一条能用就算成功。

---

## 5. 已知限制（v0.1 故意没做）

- **没有流式输出**：等 2–3 秒才出全部 6 条。要更快就加 SSE，把卡片逐条填。
- **不能自动粘贴**：只负责复制，粘贴得你手动长按。想自动上屏就得上无障碍服务，那会有兼容和合规风险，MVP 阶段不值得。
- **只认纯文本**：图片、表情包、合并转发的聊天记录都不行。
- **剪贴板串味**：复制完回复后，剪贴板里是回复本身。已经做了检测（会提示你先复制对方的新消息），但别指望它聪明。
- **不支持多轮上下文**：只发对方最后一条。想带上文，把人设卡里写"最近三件事"。
- **Key 用 Keystore 加密**存在本机，但**别把这个 APK 发给别人**——它带着你的 key。
- **没有历史记录**，每次都是新的请求。

---

## 6. 隐私

- 请求会把你复制的文本 + 人设卡发给你配置的 API 服务商。
- 不采集任何数据、不上报、没有第三方 SDK。
- App 内无网络请求以外的权限使用（悬浮窗、通知、网络）。

用之前想清楚：**这是把别人的聊天内容发到云端**。涉及公司内部消息、他人隐私时，别用。

---

## 7. 跑通之后的下一步

验证标准（建议先跑一周）：

- 6 条里平均有 ≥1 条你会真的发出去 → 这功能成立，值得搬进输入法；
- 总是只有 B 组能用 → 砍掉 A 组，改成 6 条 B 风格；
- 6 条都太"AI" → 问题在提示词和上下文，不在 UI，继续调 prompt，别急着做输入法。

搬进 fcitx5-android 时，能直接复用的是：`ReplyEngine`（换个 Context 而已）、提示词、卡片列表 UI 的结构。要重写的是窗口那一层（`InputWindow.ExtendedInputWindow`）和入口按钮。
