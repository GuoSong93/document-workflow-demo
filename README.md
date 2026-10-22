# Document Workflow Demo

两份虚构文件、多个角色、一条可版本化的办理流程。业务操作和归档任务在同一个持久化提交中保存；归档失败不会撤销已经完成的业务步骤。

## 快速开始

需要 JDK 21、Maven 3.6.3+。

```shell
mvn test
mvn spring-boot:run
```

打开 http://127.0.0.1:8082，创建流程，按页面显示的角色逐步提交。
默认勾选“注入一次归档失败”：完成文件 A 后执行归档任务，第一次失败，第二次成功；两个文件分别完成时各自产生 ZIP 归档。
下载 ZIP 后可查看 manifest.json，包括当前文件的动作和模板版本。

```shell
mvn package
java -jar target/document-workflow-demo-1.0.0.jar
python demo.py
```

## 默认流程

1. 文件 A：APPLICANT 填写 → REVIEWER 确认 → GUARDIAN 确认（可选）。
2. 文件 B：APPLICANT 填写 → REVIEWER 确认。
3. 每份文件业务节点完成时，自动创建一条 PENDING 归档任务；页面继续进入下一份文件。
4. 手工点击“执行一轮归档任务”运行 worker，便于稳定复现故障；没有后台定时执行。

“业务完成”和“归档完成”分别记录。页面完成所有节点并不意味着归档已成功。
这里是流程确认记录，**没有接入法律电子签、身份认证、证书或手写签名**。

## 验证故障

| 场景 | 操作与结果 |
| --- | --- |
| 旧页面重复提交 | 把版本号调回旧值，返回 409 |
| 错误角色提交 | 选择不属于当前节点的角色，返回 403 |
| 并发提交 | 相同版本只允许一次推进，测试用 8 个线程验证 |
| 文件生成失败 | 首次 worker 将错误持久化，任务回到 PENDING |
| 提交后服务重启 | 重启后执行 worker，原动作和 PENDING 任务仍存在 |
| worker 中途崩溃 | 启动时回收 RUNNING；超过 3 次则 DEAD |
| 新一轮办理 | 旧轮业务完成后创建新实例，旧动作及归档保留 |

## 模板配置 API

`POST /api/templates`：不可变 ID，变更使用新版本 ID。第一版仅支持 FORM/APPROVE 节点及 guardianOnly 条件，不含图形设计器。

```json
{"id":"custom-v2","stages":[
 {"document":"A","role":"APPLICANT","kind":"FORM","guardianOnly":false},
 {"document":"A","role":"REVIEWER","kind":"APPROVE","guardianOnly":false},
 {"document":"B","role":"APPLICANT","kind":"FORM","guardianOnly":false}
]}
```

模板发布后不可修改；实例保留创建时筛选条件后的完整节点快照。节点支持同一角色多次出现，各文件节点必须连续。

| 方法 | 路径 | 请求 |
| --- | --- | --- |
| POST | /api/workflows | `{templateId,guardian,previousId?}` |
| GET | /api/workflows/{id} | 当前节点、版本、动作、任务 |
| POST | /api/workflows/{id}/actions | `{version,role,payload,failArchiveOnce}` |
| POST | /api/worker/tick | 执行一轮有界任务 |
| POST | /api/jobs/{id}/retry | DEAD 任务人工重新入队 |
| GET | /api/archives/{id} | 下载已生成归档 |
| GET | /api/state | 流程与任务总览 |

## 存储边界

单进程原子 JSON 快照，进程文件锁禁止多实例共用目录；业务推进和任务创建在同一次快照提交中完成。
worker 先提交 RUNNING 与尝试次数，再生成文件，最后将归档内容与 DONE 一次提交；重启回收遗留 RUNNING。
实际工程通常需要数据库事务、持久化 outbox、租约和多 worker 抢占；此版只演示单进程语义，不宣称分布式保证。

角色由请求显式传入，用于演示节点权限规则，**不是实际用户认证**。未实现完整表单 schema、密码登录、流程撤销、超时通知、生产级审计或文件签名。
`data/state.json` 整体重写，适合小数据；不提供历史格式自动迁移。默认仅监听 127.0.0.1。

## 测试

`mvn test` 覆盖条件分支、错误角色、乐观版本、并发操作、任务原子创建、失败重试、重启回收、模板不可变、文件归档隔离、历史轮次及死信重试。
崩溃边界通过测试在服务关闭后写入 RUNNING 快照模拟，不声称测试执行了操作系统断电。

## 实现与验证说明

本项目为全新演示代码，使用虚构规则和样例，由作者结合业务经验、通过 AI 辅助实现。
已验证项目与受环境限制的检查见 [VERIFICATION.md](VERIFICATION.md)。
