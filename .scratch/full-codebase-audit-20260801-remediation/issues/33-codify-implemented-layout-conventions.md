# 33 — 固化已落地目录约定并收口

**What to build:** Android/Rust locality 票完成后，只把 current tree 与稳定防回潮规则写入
`docs/prd/tech.md`、`AGENTS.md` 和 tracker 状态；不留下指向已删除 scratch 目标的空头承诺。

**Source:** merged directory E1 + documentation half of B3
**Blocked by:** 17、22、24、26、27、28、29、30、31、32
**Status:** blocked
**Size:** S

## Acceptance criteria

- [ ] `docs/prd/tech.md` 描述实际已落地的 module/package/server locality；未完成部分明确 Later 或不写。
- [ ] `AGENTS.md` 写清：新代码进入既有能力/调用流子包，不在已分区根继续平铺，
  禁止新增无产品合同的源码字符串/行数 StructureTest。
- [ ] tech、AGENTS、实际目录与根 README/部署约定无矛盾；不新增第三份完整 Gradle 边表。
- [ ] 删除或标 complete 前，本 tracker 32 个可执行票都有 fixed-HEAD 验收证据，18 保持 wontfix。
- [ ] `.scratch/README.md` 只索引 active tracker；关闭后按本地 tracker 规则保留 commit/PRD 入口。

## Validation

运行 Markdown 相对链接检查、`git diff --check`，逐项比较实际目录、Gradle 边、ISSUES 状态；
不得把历史 build/device/NAS 证据复用为新结构票验收。

## Documentation Gate

本票即最终 documentation/closure gate；通常不新增 ADR，除非实施中产生难逆且反直觉的新决策。

## Out of scope

不补做未完成结构票，不添加行数守卫，不保留已合并 tracker 的重复目录。
