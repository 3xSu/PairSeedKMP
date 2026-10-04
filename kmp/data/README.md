# kmp/data — 数据层

- 职责：SQLDelight schema（`.sq`）与数据访问实现（查询封装、Repository 实现）。
- 现状（2026-10-04）：17 表 `.sq` 已落地（`src/commonMain/sqldelight/com/pairseedkmp/data/`，一表一文件）；建库入口 `DatabaseDriverFactory`（commonMain expect + androidMain actual，库文件 `pairseedkmp.db`）；JVM 冒烟 3 用例在 `src/androidUnitTest/`。命名查询随数据访问计划补。
- 表结构以 `.agent-docs/consensus/设计/数据设计/数据设计.md` 为真值（含 2026-10-03 表名与枚举落库决定）；字段速查见同目录《数据库现状表.md》。
- 依赖：`:kmp:domain` 待数据访问落地时接入（当前未接）。
