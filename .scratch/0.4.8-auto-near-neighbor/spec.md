---
triage: done
title: 0.4.8 近邻同型自动对齐
tracker: .scratch
---

# 0.4.8 近邻同型自动对齐

## Problem Statement

0.4.7 只对白名单类型、跨作者近邻做软「疑似重复 · 待确认」。同机连记、同人多设备、
睡眠双开、成长/自定义/症状双记都没有收口。家人要人点确认，否则汇总双计。

## Solution

ADR-0023：30 分钟窗、精确类型、用药/疫苗比名称、自定义比项目。服务器 commit 后
自动写来源关系；Owner 记录为展示版；其余按称呼再按事件时间排序。不 tombstone。

## Out of Scope

- 计划履行冲突收件箱
- NAS generation 持久化
- 自动闭合/改绑睡眠

见 `issues/01`–`05`。
