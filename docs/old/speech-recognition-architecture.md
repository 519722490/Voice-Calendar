# 语音识别技术方案

## 背景

本项目目标是实现语音版日历工具，用户可以通过语音添加、查看、修改和删除日程。当前项目已经具备基础日程 CRUD 接口、前端日历界面，以及基于 Spring AI Alibaba 的日历 Agent 能力。

下一阶段需要接入语音转文字能力，让前端可以采集用户语音，并将识别出的文本交给后端 Agent 处理。

## 架构

推荐采用：

```text
Vue 3 + TypeScript
+ Web Audio API / AudioWorklet
+ WebSocket
+ Spring Boot WebSocket
+ Java 21 WebSocket Client
+ DashScope fun-asr-realtime
```

整体链路：

```text
浏览器麦克风
-> 前端采集音频并转成 16kHz 单声道 PCM
-> WebSocket 发送给 Spring Boot
-> Spring Boot 转发给 DashScope fun-asr-realtime
-> DashScope 返回识别文本
-> 后端推送识别文本给前端
-> 前端将最终文本发送给现有 Agent 接口
-> Agent 调用日程工具完成增删查改
```

## 前端职责

前端负责用户交互和音频采集。

主要职责：

```text
请求麦克风权限
开始录音和停止录音
采集浏览器音频流
转换为 16kHz 单声道 PCM16 音频帧
通过 WebSocket 发送音频帧到后端
接收后端返回的实时识别文本
展示识别中的文本和最终文本
将最终文本提交给 /api/agent/chat
```

推荐前端技术：

```text
navigator.mediaDevices.getUserMedia
AudioContext
AudioWorklet
WebSocket
```

不优先推荐 `MediaRecorder` 作为最终方案。MediaRecorder 使用简单，但浏览器常输出 `webm/opus`，音频封装格式和采样率不够可控。当前接入的是实时语音识别模型，使用 PCM 音频流更稳定。

## 后端职责

后端负责维护前端与 DashScope 之间的实时语音识别会话。

主要职责：

```text
提供 /ws/speech WebSocket 接口
接收前端发送的 PCM 音频帧
读取语音识别配置
连接 DashScope WebSocket
发送 run-task 启动识别任务
收到 task-started 后转发音频帧
解析 result-generated 识别结果
将识别文本推送回前端
录音结束时发送 finish-task
处理异常、断连和资源释放
```

推荐后端技术：

```text
spring-boot-starter-websocket
org.springframework.web.socket.BinaryMessage
org.springframework.web.socket.TextMessage
java.net.http.HttpClient
java.net.http.WebSocket
```

## DashScope 交互流程

DashScope fun-asr-realtime 的典型流程：

```text
1. 后端连接 wss://dashscope.aliyuncs.com/api-ws/v1/inference
2. 请求头携带 Authorization: Bearer <api-key>
3. 发送 run-task 文本消息
4. 收到 task-started 事件
5. 持续发送二进制 PCM 音频帧
6. 持续接收 result-generated 识别结果
7. 录音结束后发送 finish-task
8. 收到 task-finished 后关闭连接
```

当前项目配置文件中已经准备了以下配置：

```properties
voice-calendar.speech.enabled=true
voice-calendar.speech.provider=dashscope
voice-calendar.speech.api-key=你的 DashScope API Key
voice-calendar.speech.model=fun-asr-realtime
voice-calendar.speech.endpoint=wss://dashscope.aliyuncs.com/api-ws/v1/inference
voice-calendar.speech.sample-rate=16000
voice-calendar.speech.format=pcm
```

注意：真实 API Key 只应填写在 `backend/config/application-local.properties` 中，不要提交到 Git 仓库。

## 为什么选择 WebSocket

语音识别属于实时流式场景，WebSocket 比普通 HTTP 更合适。

优点：

```text
可以边说边传输音频
可以边识别边返回文本
延迟更低
适合语音助手交互
前端和后端都容易维护一个会话状态
```

如果使用 HTTP 上传整段录音，开发初期会简单一些，但无法实现实时反馈，并且可能需要处理音频格式转换。

## 推荐模块设计

后端可以按以下结构扩展：

```text
com.cyx.backend.speech
  SpeechRecognitionProperties
  SpeechRecognitionController
  SpeechRecognitionWebSocketConfig
  SpeechRecognitionWebSocketHandler
  SpeechRecognitionClient
  DashScopeSpeechRecognitionClient
  SpeechRecognitionSession
  SpeechRecognitionMessage
```

其中：

```text
SpeechRecognitionWebSocketHandler
负责处理前端 WebSocket 连接、音频帧和停止指令。

SpeechRecognitionClient
定义语音识别客户端抽象，方便未来替换其他模型或厂商。

DashScopeSpeechRecognitionClient
负责 DashScope WebSocket 协议细节。

SpeechRecognitionSession
保存一次录音识别会话的状态，例如 sessionId、taskId、WebSocket 连接和当前识别文本。
```

## 最小可交付闭环

建议按以下顺序实现：

```text
1. 前端增加录音按钮和识别文本展示区域
2. 前端建立 ws://localhost:8080/ws/speech 连接
3. 前端采集音频并发送 PCM 帧到后端
4. 后端 WebSocket 接收音频帧
5. 后端连接 DashScope 并转发音频帧
6. 后端把识别结果推送给前端
7. 前端展示实时识别文本
8. 用户确认后调用 /api/agent/chat
9. Agent 调用日程工具完成日程操作
```

第一版可以先实现“按住或点击开始录音，点击停止后提交最终文本给 Agent”。实时识别文字展示可以保留，但日程操作先在停止录音后触发，这样交互更可控。

## 风险与注意事项

需要重点关注：

```text
浏览器麦克风权限
HTTPS 环境下的 getUserMedia 限制
音频采样率转换
PCM16 编码
WebSocket 断连重试
DashScope token 和错误码处理
录音结束后的资源释放
API Key 不能暴露给前端
识别文本需要二次确认，避免误操作日程
```

本项目的 API Key 必须只保存在后端配置中。前端只连接自己的 Spring Boot 后端，不能直接连接 DashScope。

## 结论

技术方案是：

```text
Vue AudioWorklet
+ Spring Boot WebSocket
+ Java 21 WebSocket Client
+ DashScope fun-asr-realtime
+ Spring AI Alibaba Agent
```

这套方案既能借鉴流式识别思想，又符合当前 Java Web 项目的技术栈，后续也方便扩展到其他语音识别服务。
