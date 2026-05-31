# 重复日程完整管理落地方案

## 背景

当前项目已经支持重复日程的基础能力：

- 后端有 `recurring_events` 规则表。
- 后端支持创建、查询、删除整条重复规则。
- 普通日程查询会动态合并重复日程实例。
- 前端能展示重复实例，并标记为“重复 / 规则日程”。
- Agent 审查模式支持创建重复日程和删除整条重复规则。
- 自动模式会拦截周期日程，避免误创建或误删除。

但现在还不算“完整管理”，缺少：

- 前端手动创建重复日程。
- 前端查看重复规则列表。
- 前端编辑重复规则。
- 前端删除重复规则。
- 只取消某一天。
- 修改某一天。
- 修改今天及以后。
- 删除今天及以后。
- Agent 对修改重复规则、只取消某一天的支持。

因此需要把重复日程管理从“能展示和部分 Agent 操作”升级为“用户可完整维护规则和实例”。

## 总体目标

重复日程管理分成两层：

1. 规则层：管理整条重复规则。
2. 实例层：管理某一天由规则生成出来的具体实例。

规则层解决：

- 每天晚上八点背单词，持续到 6 月 30 日。
- 每周一三五晚上跑步。
- 工作日早上九点打卡。

实例层解决：

- 今天不背单词了。
- 只把本周三的跑步改到晚上九点。
- 从下周开始，跑步改成晚上八点半。
- 删除今天及以后的重复日程。

## 设计原则

### 1. 继续坚持“存规则，不存展开结果”

重复日程不能展开成大量普通日程。

例如：

```text
今年每天晚上八点背单词
```

数据库中仍然只保存一条规则。

查询某天时，后端根据规则动态生成当天实例。

### 2. 用户操作必须区分作用范围

用户编辑或删除重复日程时，必须明确作用范围：

| 操作范围 | 含义 |
|---|---|
| `THIS_ONLY` | 只影响当前这一天 |
| `THIS_AND_FUTURE` | 当前这一天及以后都生效 |
| `ALL` | 修改或删除整条重复规则 |

前端必须用弹窗让用户选择范围，不能默认猜。

### 3. 高风险操作必须确认

以下操作必须确认：

- 删除整条重复规则。
- 删除今天及以后。
- 修改整条重复规则。
- 修改今天及以后。
- Agent 识别到重复规则操作。

只取消某一天虽然风险较低，但仍建议二次确认，避免误点。

### 4. Agent 不直接猜作用范围

如果用户说：

```text
取消背单词
```

但没有说明是取消今天、以后还是全部，Agent 应该返回需要补充范围，而不是直接删除整条规则。

如果用户说：

```text
今天不背单词了
```

可以解析为 `THIS_ONLY`。

如果用户说：

```text
以后都不背单词了
```

可以解析为 `THIS_AND_FUTURE`。

如果用户说：

```text
删除每天背单词这个重复日程
```

可以解析为 `ALL`。

## 数据模型方案

### 现有表：`recurring_events`

继续表示重复规则。

当前已有核心字段：

| 字段 | 说明 |
|---|---|
| `id` | 规则 ID |
| `user_id` | 用户 ID |
| `title` | 标题 |
| `start_date` | 规则开始日期 |
| `end_date` | 规则结束日期 |
| `start_time` | 每次开始时间 |
| `end_time` | 每次结束时间 |
| `recurrence_type` | `DAILY` / `WEEKLY` / `MONTHLY` |
| `interval_value` | 每 N 天 / 周 / 月 |
| `days_of_week` | 每周重复的星期 |
| `location` | 地点 |
| `description` | 备注 |
| `tag` | 标签 |
| `reminder_time` | 提醒时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

建议补充字段：

| 字段 | 说明 |
|---|---|
| `status` | `ACTIVE` / `ARCHIVED`，后续软删除可用 |
| `timezone` | 预留时区字段，第一版可默认 `Asia/Shanghai` |

第一版可以先不加 `status` 和 `timezone`，但接口设计要预留。

### 新增表：`recurring_event_exceptions`

用于记录某一天的例外。

| 字段 | 类型建议 | 说明 |
|---|---|---|
| `id` | BIGSERIAL | 主键 |
| `user_id` | BIGINT | 用户 ID |
| `recurring_event_id` | BIGINT | 对应重复规则 ID |
| `exception_date` | DATE | 例外日期 |
| `exception_type` | VARCHAR | `SKIP` / `OVERRIDE` |
| `override_title` | VARCHAR | 覆盖标题，`OVERRIDE` 时使用 |
| `override_start_time` | TIME | 覆盖开始时间 |
| `override_end_time` | TIME | 覆盖结束时间 |
| `override_location` | VARCHAR | 覆盖地点 |
| `override_description` | TEXT | 覆盖备注 |
| `override_tag` | VARCHAR | 覆盖标签 |
| `override_reminder_time` | TIME | 覆盖提醒时间 |
| `created_at` | TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | 更新时间 |

第一版建议先实现 `SKIP`，也就是“只取消某一天”。

`OVERRIDE` 可以第二阶段实现，用来支持“只修改某一天”。

唯一索引：

```text
user_id + recurring_event_id + exception_date
```

避免同一天重复创建多个例外。

## 后端接口设计

### 1. 重复规则列表

```http
GET /api/recurring-events
```

用途：

- 前端“重复日程管理”页面展示所有重复规则。

返回：

```json
[
  {
    "id": 1,
    "title": "背单词",
    "startDate": "2026-05-31",
    "endDate": "2026-06-30",
    "startTime": "20:00",
    "recurrenceType": "DAILY",
    "intervalValue": 1,
    "daysOfWeek": null,
    "tag": "学习"
  }
]
```

### 2. 创建重复规则

```http
POST /api/recurring-events
```

当前已有，后续前端直接复用。

### 3. 修改整条重复规则

```http
PUT /api/recurring-events/{id}
```

作用范围：

```text
ALL
```

说明：

- 直接修改 `recurring_events` 中这条规则。
- 已经过去的历史实例是否受影响，第一版可以不单独处理，因为实例是动态生成的。
- 前端文案要提示：“修改整条规则会影响该重复日程的所有日期展示。”

### 4. 删除整条重复规则

```http
DELETE /api/recurring-events/{id}
```

当前已有。

建议删除时同时删除该规则下的 exceptions。

### 5. 只取消某一天

```http
POST /api/recurring-events/{id}/exceptions
```

请求：

```json
{
  "date": "2026-06-03",
  "type": "SKIP"
}
```

效果：

- 不删除规则。
- 在 `recurring_event_exceptions` 新增一条 `SKIP`。
- 查询这一天时，该重复实例不再展示。

### 6. 恢复某一天

```http
DELETE /api/recurring-events/{id}/exceptions/{date}
```

效果：

- 删除该日期的例外记录。
- 这一天的重复实例恢复展示。

### 7. 删除今天及以后

```http
DELETE /api/recurring-events/{id}/future?from=2026-06-03
```

效果：

- 不删除整条规则。
- 把原规则的 `endDate` 改成 `from - 1 day`。

示例：

```text
原规则：2026-05-31 至 2026-06-30
从 2026-06-03 以后删除
新规则：2026-05-31 至 2026-06-02
```

如果 `from` 等于规则开始日期，可以直接删除整条规则。

### 8. 修改今天及以后

```http
POST /api/recurring-events/{id}/split
```

请求：

```json
{
  "from": "2026-06-03",
  "title": "背单词",
  "startDate": "2026-06-03",
  "endDate": "2026-06-30",
  "startTime": "21:00",
  "recurrenceType": "DAILY",
  "intervalValue": 1,
  "tag": "学习"
}
```

效果：

1. 原规则 `endDate` 改成 `from - 1 day`。
2. 创建一条新规则，从 `from` 开始，使用新的配置。

示例：

```text
原规则：每天 20:00 背单词，5 月 31 日至 6 月 30 日
用户说：从 6 月 3 日开始改到 21:00

更新后：
规则 A：每天 20:00 背单词，5 月 31 日至 6 月 2 日
规则 B：每天 21:00 背单词，6 月 3 日至 6 月 30 日
```

## 查询逻辑改造

当前查询逻辑：

```text
普通日程 + 重复规则动态生成实例
```

需要改成：

```text
普通日程
+ 重复规则动态生成实例
- SKIP 例外日期
+ OVERRIDE 覆盖实例
```

第一版只做 `SKIP`：

```text
如果 recurring_event_exceptions 中存在 SKIP(date)
则这一天不返回该重复实例
```

第二版做 `OVERRIDE`：

```text
如果存在 OVERRIDE(date)
则用 override 字段生成当天实例
```

## 前端设计

### 入口设计

在顶部操作区新增一个按钮：

```text
重复日程
```

点击后打开“重复日程管理”弹窗或页面。

考虑当前项目规模，第一版建议用弹窗：

- 不引入复杂页面层级。
- 和现有添加日程、语音设置体验保持一致。
- 后续再升级成独立路由 `/recurring-events`。

### 重复日程管理弹窗

内容：

- 重复规则列表。
- 每条显示标题、频率、时间、日期范围、标签。
- 操作按钮：编辑、删除。
- 顶部按钮：新增重复日程。

列表示例：

```text
背单词
每天 20:00
2026-05-31 至 2026-06-30
学习

[编辑] [删除]
```

### 新增 / 编辑重复日程表单

字段：

| 字段 | 控件 |
|---|---|
| 标题 | 输入框 |
| 开始日期 | 日期选择 |
| 结束日期 | 日期选择 |
| 开始时间 | 时间选择 |
| 结束时间 | 时间选择，可选 |
| 重复类型 | 单选或下拉：每天、每周 |
| 每周星期 | 复选框：周一至周日 |
| 间隔 | 数字输入：每 N 天 / 周 |
| 标签 | 下拉 |
| 地点 | 输入框 |
| 备注 | 文本域 |
| 提醒时间 | 时间选择，可选 |

第一版不开放 `MONTHLY`。

### 点击重复实例时的操作弹窗

当前日程列表中重复实例只显示“规则日程”，没有操作。

需要改成点击后显示操作：

```text
这是重复日程实例，你想操作哪一部分？

[只取消这一天]
[编辑整条规则]
[删除整条规则]
[取消]
```

第二阶段再增加：

```text
[只修改这一天]
[修改今天及以后]
[删除今天及以后]
```

## 前端状态管理

新增 Pinia store：

```text
frontend/src/stores/recurring.ts
```

职责：

- 加载重复规则列表。
- 创建重复规则。
- 修改重复规则。
- 删除重复规则。
- 创建例外日期。
- 删除未来规则。
- 管理重复日程弹窗状态。
- 管理重复实例操作弹窗状态。

新增 API 文件：

```text
frontend/src/api/recurringEvents.ts
```

职责：

- 封装 `/api/recurring-events` 相关请求。

新增组件：

```text
frontend/src/components/RecurringEventManagerModal.vue
frontend/src/components/RecurringEventFormModal.vue
frontend/src/components/RecurringInstanceActionModal.vue
```

## Agent 改造方案

### 新增意图字段

在 `CalendarAgentIntent` 中增加：

| 字段 | 说明 |
|---|---|
| `recurringScope` | `THIS_ONLY` / `THIS_AND_FUTURE` / `ALL` |
| `effectiveDate` | 实例操作或今天及以后操作的生效日期 |
| `recurringTargetTitleKeyword` | 重复规则标题关键词，可复用 `targetTitleKeyword` |

### 操作映射

| 用户输入 | action | recurring | recurringScope |
|---|---|---|---|
| 今天不背单词了 | DELETE | true | `THIS_ONLY` |
| 本周三不跑步了 | DELETE | true | `THIS_ONLY` |
| 以后都不背单词了 | DELETE | true | `THIS_AND_FUTURE` |
| 删除每天背单词这个重复日程 | DELETE | true | `ALL` |
| 从明天开始背单词改到九点 | UPDATE | true | `THIS_AND_FUTURE` |
| 把每天背单词改成九点 | UPDATE | true | `ALL` |

### 待确认操作

扩展 `PendingRecurringAgentAction`：

| 字段 | 说明 |
|---|---|
| `action` | `CREATE_RECURRING` / `UPDATE_RECURRING` / `DELETE_RECURRING` / `SKIP_RECURRING_INSTANCE` / `SPLIT_RECURRING` |
| `scope` | 操作范围 |
| `effectiveDate` | 生效日期 |
| `recurringEventId` | 重复规则 ID |
| `updatePayload` | 修改后的规则字段 |

### Agent 安全策略

审查模式：

- 创建重复规则：需要确认。
- 删除整条规则：需要确认。
- 只取消某一天：需要确认。
- 删除今天及以后：需要确认。
- 修改整条规则：需要确认。
- 修改今天及以后：需要确认。

自动模式：

- 第一版继续拒绝所有重复规则写操作。
- 可以允许查询重复日程。
- 后续再开放低风险操作，例如“只取消今天”。

## 开发阶段规划

### 第一阶段：前端可管理整条重复规则

目标：

- 前端可以查看重复规则列表。
- 前端可以手动新增重复规则。
- 前端可以编辑整条重复规则。
- 前端可以删除整条重复规则。

后端改动：

- `PUT /api/recurring-events/{id}`。
- 删除规则时级联删除 exceptions，虽然第一阶段还没有 exceptions 表，也可以先预留。

前端改动：

- 新增“重复日程”入口。
- 新增重复规则管理弹窗。
- 新增重复规则表单。
- 新增 `recurring` store。

验收：

- 手动创建每天背单词规则。
- 月历中每天都能看到实例。
- 修改时间后，月历中实例时间同步变化。
- 删除规则后，月历中实例消失。

### 第二阶段：只取消某一天

目标：

- 用户可以对某个重复实例执行“只取消这一天”。
- 被取消的那一天不再展示该实例。
- 可以在重复规则管理里查看或恢复被取消日期。

后端改动：

- 新增 `recurring_event_exceptions` 表。
- 新增 `POST /api/recurring-events/{id}/exceptions`。
- 新增 `DELETE /api/recurring-events/{id}/exceptions/{date}`。
- 查询实例时过滤 `SKIP`。

前端改动：

- 重复实例操作弹窗。
- “只取消这一天”按钮。
- 重复规则详情中展示例外日期。

验收：

- 创建每天背单词。
- 取消 6 月 3 日。
- 6 月 2 日和 6 月 4 日仍展示，6 月 3 日不展示。
- 恢复 6 月 3 日后重新展示。

### 第三阶段：删除今天及以后

目标：

- 用户可以选择从某一天开始删除后续重复实例。

后端改动：

- 新增 `DELETE /api/recurring-events/{id}/future?from=yyyy-MM-dd`。
- 将原规则 `endDate` 截断到 `from - 1 day`。

前端改动：

- 重复实例操作弹窗增加“删除今天及以后”。
- 操作前确认影响范围。

验收：

- 创建 5 月 31 日到 6 月 30 日每天背单词。
- 从 6 月 10 日起删除。
- 6 月 9 日之前仍展示，6 月 10 日之后不展示。

### 第四阶段：修改今天及以后

目标：

- 用户可以从某一天开始修改重复规则。

后端改动：

- 新增 `POST /api/recurring-events/{id}/split`。
- 原规则截断。
- 新建一条从 `from` 开始的新规则。

前端改动：

- 重复实例操作弹窗增加“修改今天及以后”。
- 复用重复规则表单，但需要展示作用范围。

验收：

- 每天 20:00 背单词。
- 从 6 月 10 日改成 21:00。
- 6 月 9 日之前是 20:00，6 月 10 日之后是 21:00。

### 第五阶段：只修改某一天

目标：

- 用户可以只修改某一天的重复实例。

后端改动：

- `recurring_event_exceptions` 支持 `OVERRIDE`。
- 查询实例时使用 override 字段生成当天实例。

前端改动：

- 重复实例操作弹窗增加“只修改这一天”。
- 复用日程表单，但提交到 exception override 接口。

验收：

- 每天 20:00 背单词。
- 只把 6 月 10 日改成 21:00。
- 只有 6 月 10 日变化，其它日期仍是 20:00。

### 第六阶段：Agent 支持完整重复日程操作

目标：

- Agent 能识别重复规则操作范围。
- 所有重复规则写操作仍走审查确认。

后端改动：

- 扩展 `CalendarAgentIntent`。
- 扩展 `PendingRecurringAgentAction`。
- 新增确认执行分支：
  - `SKIP_RECURRING_INSTANCE`
  - `UPDATE_RECURRING`
  - `SPLIT_RECURRING`
  - `DELETE_RECURRING_FUTURE`

验收语句：

| 语音文本 | 预期 |
|---|---|
| 今天不背单词了 | 只取消今天 |
| 明天不跑步了 | 只取消明天 |
| 以后都不背单词了 | 删除今天及以后 |
| 删除每天背单词这个重复日程 | 删除整条规则 |
| 从明天开始背单词改到九点 | 修改明天及以后 |

## 推荐优先级

建议按这个顺序开发：

1. 前端重复规则管理入口。
2. 编辑整条重复规则。
3. 删除整条重复规则。
4. 只取消某一天。
5. 删除今天及以后。
6. 修改今天及以后。
7. 只修改某一天。
8. Agent 支持完整范围语义。

原因：

- 先让用户能手动管理重复规则，立刻提升产品完整度。
- 再补实例级操作，解决真实使用中的临时变更。
- 最后让 Agent 接入这些能力，避免一开始就把复杂范围语义交给模型。

## 测试计划

### 后端测试

需要新增测试：

- 创建重复规则。
- 修改整条重复规则。
- 删除整条重复规则。
- 创建 `SKIP` 例外。
- 查询实例时跳过 `SKIP` 日期。
- 删除今天及以后会截断 `endDate`。
- 修改今天及以后会拆分成两条规则。
- 用户隔离：不能操作别人的规则和例外。

### 前端测试

需要手动验证：

- 重复日程管理弹窗能打开。
- 新增规则后月历出现实例。
- 编辑规则后实例同步变化。
- 删除规则后实例消失。
- 只取消某一天后当天实例消失，其它日期不受影响。
- 路由刷新后状态能重新加载。

### Agent 测试

需要验证：

- 周期写操作在自动模式下仍被拦截。
- 审查模式能返回正确确认弹窗。
- 模糊范围不执行。
- 明确范围执行正确接口。

## 第一版建议范围

为了控制风险，下一次开发建议只做第一阶段：

- 前端“重复日程”入口。
- 重复规则列表。
- 新增重复规则。
- 编辑整条重复规则。
- 删除整条重复规则。
- 后端补 `PUT /api/recurring-events/{id}`。

暂时不做：

- 只取消某一天。
- 修改今天及以后。
- 只修改某一天。
- Agent 修改重复规则。

这个版本完成后，用户就可以不用 Agent，也能完整维护重复规则本身。
