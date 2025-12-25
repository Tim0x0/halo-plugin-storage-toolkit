# Halo 存储工具箱

[![GitHub Release](https://img.shields.io/github/v/release/Tim0x0/halo-plugin-storage-toolkit?style=flat-square&logo=github)](https://github.com/Tim0x0/halo-plugin-storage-toolkit/releases)
[![GitHub License](https://img.shields.io/github/license/Tim0x0/halo-plugin-storage-toolkit?style=flat-square)](https://github.com/Tim0x0/halo-plugin-storage-toolkit/blob/main/LICENSE)
[![Halo Version](https://img.shields.io/badge/Halo-%3E%3D2.22.0-blue?style=flat-square)](https://www.halo.run)

Halo 存储增强插件，支持图片格式转换、水印添加等功能，帮助优化网站图片加载性能。

![处理日志示例](docs/doc_sample1.png)

## 功能特性

### 🖼️ 格式转换
- 自动将上传的图片转换为 WebP 格式
- 可调节输出质量（1-100）
- 显著减小图片体积，提升加载速度

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
- 支持控制台上传和编辑器上传

### 📊 处理日志
- 记录每次图片处理的详细信息
- 统计成功/失败/跳过数量
- 显示节省的存储空间
- 支持按文件名、状态、时间筛选
- 自动清理过期日志

## 安装

1. 从 [Releases](https://github.com/Tim0x0/halo-plugin-storage-toolkit/releases) 下载最新版本的 JAR 文件
2. 在 Halo 后台 → 插件 → 安装 → 本地上传
3. 启用插件

> ⚠️ **注意**：本插件使用了 WebP native 库进行图片格式转换。由于 JVM 的 native 库加载机制限制，**插件更新后需要重启 Halo** 才能正常工作。热更新可能导致 native 库冲突或功能异常。

## 使用说明

### 基本配置

1. 进入插件设置页面
2. 在「全局设置」中启用图片处理
3. 根据需要选择目标存储策略和分组

### 格式转换

1. 在「图片处理」→「格式转换」中启用
2. 选择目标格式（目前支持 WebP）
3. 调节输出质量（推荐 75-85）

### 添加水印

1. 在「图片处理」→「水印设置」中启用
2. 选择水印类型（文字/图片）
3. 配置水印内容、位置、透明度等参数

### 查看日志

1. 进入 Halo 后台 → 工具 → 存储处理日志
2. 查看处理统计和详细记录
3. 可按条件筛选日志

## 开发

### 环境要求

- Java 21+
- Node.js 20+
- pnpm 9+

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
- **前端**：Vue 3、TypeScript、Halo Console Components
- **图片处理**：Java ImageIO、WebP ImageIO

## 许可证

[GPL-3.0](./LICENSE) © Tim0x0
