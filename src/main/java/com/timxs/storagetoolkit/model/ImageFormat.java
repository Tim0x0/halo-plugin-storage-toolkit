package com.timxs.storagetoolkit.model;

/**
 * 图片格式枚举
 * 定义支持的目标图片格式及其 MIME 类型和扩展名
 */
public enum ImageFormat {
    
    /**
     * WebP 格式（有损/无损压缩，体积小）
     */
    WEBP("image/webp", "webp"),
    
    /**
     * 保持原格式不变
     */
    ORIGINAL(null, null);

    /**
     * MIME 类型
     */
    private final String mimeType;
    
    /**
     * 文件扩展名
     */
    private final String extension;

    ImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    /**
     * 获取 MIME 类型
     *
     * @return MIME 类型
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * 获取文件扩展名
     *
     * @return 扩展名
     */
    public String getExtension() {
        return extension;
    }

    /**
     * 根据 MIME 类型获取对应的格式
     *
     * @param mimeType MIME 类型
     * @return 对应的格式，未找到返回 ORIGINAL
     */
    public static ImageFormat fromMimeType(String mimeType) {
        if (mimeType == null) {
            return ORIGINAL;
        }
        for (ImageFormat format : values()) {
            if (mimeType.equals(format.mimeType)) {
                return format;
            }
        }
        return ORIGINAL;
    }

    /**
     * 根据文件扩展名获取对应的格式
     *
     * @param extension 扩展名（可带点号）
     * @return 对应的格式，未找到返回 ORIGINAL
     */
    public static ImageFormat fromExtension(String extension) {
        if (extension == null) {
            return ORIGINAL;
        }
        String ext = extension.toLowerCase().replace(".", "");
        for (ImageFormat format : values()) {
            if (ext.equals(format.extension)) {
                return format;
            }
        }
        return ORIGINAL;
    }
}
