# 04: NAS include_live_keys 仅 mismatch 类

**What to build:** pull 加性 opt-in `include_live_keys` + `live_key_types`。缺省关，
信封在未请求时与 0.4.7 普查字节兼容。客户端只在 mismatch 重走时对对不上的类要键，
用精确差集替换启发式。

**Blocked by:** 01

**Status:** done

- [x] 查询参数缺省 false；未请求不附加 `keys`
- [x] 仅请求的类型在 `live_census.<type>` 上加 `keys` 数组
- [x] 客户端重走带 `live_key_types`；`localLive - serverLive` 写成 extra 回执
- [x] 服务端与客户端 wire 测试；7/7 类型仍都出现 count+digest

## Comments

`include_live_keys` + `live_key_types` 缺省关。`Store::attach_live_keys_to_census` 只给请求的类加键。
客户端 mismatch 重走才要键。未请求时信封仍是 count+digest。

Ran: `cd tools/lezi-sync && cargo fmt --all -- --check && cargo test --locked --lib live_census && cargo clippy --all-targets --all-features -- -D warnings`.
