# 第三方组件

## Mihomo v1.19.33

项目基于上游固定提交 `3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8` 构建 Android ARM64 内核 `v1.19.33-minclash.1`，于 2026-11-14 增加由 Root supervisor 显式启用的 TCP 保活策略。许可为 GNU General Public License v3，全文随内置模块以 `LICENSE-mihomo.txt` 提供。修改见 `mihomo-patches/`；构建同时在 `dist` 生成包含修改、测试、完整上游源码及官方 vendor 依赖的 `mihomo-v1.19.33-minclash.1-source.tar.gz`，提供安装包时应一并提供该源码包。

- [上游发布](https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.33)
- [对应源码](https://github.com/MetaCubeX/mihomo/tree/v1.19.33)
- [源码归档](https://github.com/MetaCubeX/mihomo/archive/refs/tags/v1.19.33.tar.gz)

## SnakeYAML 2.5

用于解析与规范化 YAML 配置，许可为 Apache License 2.0。许可全文随 APK 以 `assets/licenses/snakeyaml-LICENSE.txt` 提供。

- [上游项目](https://bitbucket.org/snakeyaml/snakeyaml)
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)
