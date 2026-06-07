# AiChat

armv7a Android 4.3 (API 18) 词典笔 AI 对话应用。纯原生 Android，零第三方依赖。

## 功能

- 流式对话，支持 DeepSeek 和硅基流动双 API
- 思考模式（三档可调），思考内容实时显示 + 自动折叠/展开
- 多对话管理，自动生成标题（Qwen 3.5）
- 系统提示词自定义
- 长按发送键打断请求
- DeepSeek 余额查询
- 对话历史自动保存，支持重命名/删除

## 构建

### 本地构建

```bash
# Windows PowerShell
.\build.ps1 -DeepseekKey "sk-xxx" -SiliconflowKey "sk-xxx"

# 签名需要额外参数
.\build.ps1 -DeepseekKey "sk-xxx" -SiliconflowKey "sk-xxx" -KeystorePassword "pwd" -KeyAlias "mc"
```

```bash
# Linux / macOS
./build.sh --deepseek-key sk-xxx --siliconflow-key sk-xxx
./build.sh --deepseek-key sk-xxx --siliconflow-key sk-xxx --keystore-password pwd --key-alias mc
```

### 环境变量

| 变量 | 说明 |
|------|------|
| `KEYSTORE_PASSWORD` | 签名密钥库密码 |
| `KEY_ALIAS` | 签名密钥别名（默认 `mc`） |

### GitHub Actions

推送 `v*` 标签自动触发构建和发布。需要在仓库配置以下 Secrets：

| Secret | 说明 |
|--------|------|
| `DEEPSEEK_KEY` | DeepSeek API Key |
| `SILICONFLOW_KEY` | 硅基流动 API Key |
| `KEYSTORE_PASSWORD` | 签名密钥库密码 |
| `KEY_ALIAS` | 签名密钥别名 |

## 目标设备

- CPU: armv7a
- 系统: Android 4.3 (API 18)
- 屏幕: 竖屏窄宽（词典笔）
- TLS: 1.2（禁用 1.3，Android 4 不支持）

## 项目结构

```
├── app/
│   ├── build.gradle
│   ├── keystore/mc.jks          # 签名密钥（加密存储）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/xyz/zip8919/app/aichat/
│       │   ├── ApiClient.java         # HTTP/SSE/TLS
│       │   ├── ConfigManager.java     # 配置管理
│       │   ├── Conversation.java      # 对话模型
│       │   ├── ConversationAdapter.java
│       │   ├── ConversationManager.java
│       │   ├── ConversationManagerActivity.java
│       │   ├── MainActivity.java      # 主界面
│       │   ├── Message.java
│       │   ├── MessageAdapter.java    # 消息+思考展示
│       │   ├── ModelInfo.java
│       │   ├── ProviderInfo.java
│       │   ├── SettingsActivity.java
│       │   └── StorageManager.java
│       └── res/
├── build.gradle
├── build.ps1                   # Windows 构建脚本
├── build.sh                    # Linux/Mac 构建脚本
└── settings.gradle
```

## License

MIT
