# Halo 存储工具箱

[![GitHub Release](https://img.shields.io/github/v/release/Tim0x0/halo-plugin-storage-toolkit?style=flat-square&logo=github)](https://github.com/Tim0x0/halo-plugin-storage-toolkit/releases)
[![GitHub License](https://img.shields.io/github/license/Tim0x0/halo-plugin-storage-toolkit?style=flat-square)](https://github.com/Tim0x0/halo-plugin-storage-toolkit/blob/main/LICENSE)
[![Halo Version](https://img.shields.io/badge/Halo-%3E%3D2.22.1-blue?style=flat-square)](https://www.halo.run)

Halo 存储增强插件，支持图片处理（压缩、水印、格式转换）、批量处理、附件库分析（断链检测、引用扫描、重复文件检测）和 URL 引用替换等功能。

![处理日志示例](docs/doc_sample2.png)

## 功能特性

### 🖼️ 格式转换
- 自动将上传的图片转换为 WebP 或 AVIF 格式
- 可调节输出质量（1-100）
- 可调节压缩等级（WebP 0-6，AVIF 0-10，值越大压缩越慢但文件越小）
- 显著减小图片体积，提升加载速度
- AVIF 格式压缩率更高，适合现代浏览器

### 💧 水印功能
- **文字水印**：自定义文字、字体大小、颜色
- **图片水印**：支持上传自定义水印图片，可调节缩放比例
- **位置设置**：九宫格位置选择（左上、居中、右下等）
- **透明度**：可调节水印透明度（0-100%）
- **边距控制**：自定义 X/Y 方向边距百分比

### 🎯 精准控制
- 指定目标存储策略
- 指定目标分组
- 文件格式过滤（jpeg、png、gif、webp）
- 文件大小过滤（最小/最大限制）
- 支持控制台上传和编辑器上传（Halo 2.22+）

### ⚡ 性能优化
- 流式处理大图片，降低内存占用
- 支持处理超大尺寸图片

### ⚡ 批量处理
- **功能全面**：支持对已上传的图片进行批量**压缩**、**添加水印**和**格式转换**
- **灵活策略**：支持选择**是否保留原文件**（保留原图则生成新附件，不保留则覆盖原附件）
- **进度监控**：实时显示处理进度、成功/失败数量、跳过数量
- **结果统计**：详细展示处理结果、节省空间统计和失败原因

### 📈 存储分析与优化
- **存储统计**：按类型、策略、分组统计附件数量和大小，提供可视化图表
- **重复文件检测**：基于文件 MD5 哈希识别重复文件，显示可节省空间
- **死链检测**：扫描文章、页面、评论、瞬间、图库等内容，发现引用的失效附件链接
- **死链白名单**：支持配置忽略特定的 URL 模式（支持精确匹配和前缀匹配）
- **未引用检测**：识别未被任何内容使用的"孤儿"附件，支持一键清理

### 🔗 URL 引用替换
- **自动替换**：批量处理覆盖原文件后，自动替换内容中的旧附件链接
- **重复合并**：删除重复附件时，自动将引用转移到保留的附件
- **断链修复**：支持从断链检测结果中手动替换失效 URL
- **覆盖范围**：文章、页面、评论、回复、用户资料、主题/插件配置等

### 📊 日志系统
- **处理日志**：记录每次图片处理的详细信息，统计成功/失败/跳过数量，显示节省的存储空间
- **清理日志**：记录所有删除操作详情（删除原因、文件大小、操作者、删除时间等）
- **替换日志**：记录 URL 引用替换操作的详情（来源、替换前后 URL、成功/失败状态）
- 支持按文件名、状态、时间、来源筛选
- 自动清理过期日志

## 安装

1. 从 [Releases](https://github.com/Tim0x0/halo-plugin-storage-toolkit/releases) 下载最新版本的 JAR 文件
2. 在 Halo 后台 → 插件 → 安装 → 本地上传
3. 启用插件

> ⚠️ **注意**：本插件使用了 WebP 和 AVIF native 库进行图片格式转换。由于 JVM 的 native 库加载机制限制，**插件更新后需要重启 Halo** 才能正常工作。热更新可能导致 native 库冲突或功能异常。

## 使用说明

### 基本配置

1. 进入插件设置页面
2. 在「全局设置」中启用图片处理
3. 根据需要选择目标存储策略和分组

### 格式转换

1. 在「图片处理」→「格式转换」中启用
2. 选择目标格式（WebP 或 AVIF）
3. 调节输出质量（推荐 75-85）

> 💡 **格式选择建议**：WebP 兼容性好，推荐大多数场景使用；AVIF 压缩率更高但需要较新浏览器支持（Chrome 85+、Firefox 93+、Safari 16+）

### 添加水印

1. 在「图片处理」→「水印设置」中启用
2. 选择水印类型（文字/图片）
3. 配置水印内容、位置、透明度等参数

### 查看日志

1. 进入 Halo 后台 → 工具 → 存储工具箱
2. 在「处理日志」标签查看处理统计和详细记录
3. 可按条件筛选日志

### 存储分析

1. 进入 Halo 后台 → 工具 → 存储工具箱
2. 在「统计」标签查看存储空间分布
3. 在「分析」标签进行重复检测和引用扫描

## 开发

### 环境要求

- Java 21+
- Node.js 20+
- pnpm 12+

### 本地开发

```bash
# 克隆项目
git clone https://github.com/Tim0x0/halo-plugin-storage-toolkit.git
cd halo-plugin-storage-toolkit

# 启动 Halo 开发服务器
./gradlew haloServer

# 开发前端（新终端）
cd ui
pnpm install
pnpm dev
```

### 构建

```bash
./gradlew build
```

构建产物位于 `build/libs` 目录。

## 技术栈

- **后端**：Java 21、Spring WebFlux、Halo Plugin API
- **前端**：Vue 3、TypeScript、Halo Console Components、ECharts
- **图片处理**：Java ImageIO、WebP ImageIO、AVIF ImageIO

## 许可证

[GPL-3.0](./LICENSE) © [Tim0x0](https://github.com/Tim0x0/)

## 作者

**[Tim](https://blog.timxs.com)**

### 任务、预览与日志存储

- 批量处理面板的「任务中心」保留历史任务和逐项进度。取消或重启后可继续未执行项；可安全重试的失败项会创建独立任务，并复用原任务的处理配置。
- 上传、引用替换或删除期间发生中断的附件会标为「需核查」。这些附件不会自动重放，请先检查新文件和引用的实际状态。
- 点击附件名称可按当前配置试处理，对比图片、尺寸及体积；此操作不上传附件，也不修改引用。交互预览限制为 20 MB，批量任务仍遵循插件配置。
- 批量处理、重复附件删除和未引用附件删除前会读取当前内容并显示影响预览。预览是查询时刻的结果，执行过程仍会重新检查相关引用。
- 图片处理日志、清理日志、URL 替换日志及任务历史存储在 `${halo.work-dir}/plugins/storage-toolkit/toolkit.sqlite`。首次启用时自动迁移旧版日志：逐条写入 SQLite 并读回核验后才删除旧记录；迁移中断可以重试。
- SQLite 使用 WAL 模式。备份请停止 Halo 后复制整个 `storage-toolkit` 目录，或使用 SQLite 的在线备份接口；运行时只复制 `.sqlite` 文件可能漏掉 WAL 中的数据。Halo 自带内容备份不一定包含此目录，迁移及更新前请一并备份。

### 运行环境与可选原生依赖

- 安装包默认保留常见 Linux x86_64 / aarch64 原生库，适用于多数 Ubuntu、Debian、CentOS 和常规 Halo Docker 部署；SQLite 另保留相同架构的 musl 版本。
- 「环境检测」读取 Halo JVM 所在系统、CPU 架构、libc 和容器标记，与访问后台的电脑无关。没有明确容器标记时显示「待确认」，不把容器检测结果当作安装依据。
- macOS、Windows、FreeBSD 和其他架构按上游库实际提供的平台匹配。缺少依赖时可以点击「下载并激活」；没有匹配版本会显示「暂不支持」。AVIF 上游未提供 macOS x86_64 / FreeBSD 库，WebP 上游未提供 Windows ARM64 / FreeBSD 库。
- Alpine 等 musl 环境可使用 SQLite，当前 WebP / AVIF 仅有 glibc 构建，检测页不会提供不兼容的下载。
- SQLite 缺失时仍可打开环境检测、统计和附件分析；批量任务、自动上传处理、清理与链接替换暂不可用，原有附件访问不受影响。补齐 SQLite 后会初始化日志存储并迁移历史记录。
- 首次补齐依赖会在当前插件中激活，并实际检查数据库读写或图片编解码；已经发生原生库加载失败时按页面提示重启 Halo。插件不会自动重启 Halo 或容器。
- 下载固定版本的上游包，核验完整包及平台原生库的 SHA-256，只保存匹配平台的原生资源。下载使用插件「代理设置」，需要能访问 Maven Central（SQLite、WebP）和 JitPack（AVIF）。失败后可在检测页重试。
- 已下载依赖保存在 `${halo.work-dir}/plugins/storage-toolkit/native-dependencies/`，重启会校验并复用。Docker 请持久化 Halo 数据目录；备份、迁移时保留整个 `storage-toolkit` 目录。
