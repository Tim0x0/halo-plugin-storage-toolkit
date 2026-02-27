package com.timxs.storagetoolkit.model;

/**
 * 图片处理结果
 *
 * @param data        处理后的图片数据
 * @param filename    新文件名（格式转换后扩展名会改变）
 * @param contentType 新的 MIME 类型
 * @param status      处理状态
 * @param message     处理消息（错误信息等）
 */
public record ProcessingResult(
    byte[] data,
    String filename,
    String contentType,
    ProcessingStatus status,
    String message
) {
    /**
     * 创建成功结果
     */
    public static ProcessingResult success(byte[] data, String filename, String contentType) {
        return new ProcessingResult(data, filename, contentType, ProcessingStatus.SUCCESS, null);
    }

    /**
     * 创建成功结果（附带说明信息）
     */
    public static ProcessingResult success(byte[] data, String filename, String contentType, String message) {
        return new ProcessingResult(data, filename, contentType, ProcessingStatus.SUCCESS, message);
    }

    /**
     * 创建部分成功结果
     */
    public static ProcessingResult partial(byte[] data, String filename, String contentType, String message) {
        return new ProcessingResult(data, filename, contentType, ProcessingStatus.PARTIAL, message);
    }

    /**
     * 创建失败结果（返回原数据）
     */
    public static ProcessingResult failed(byte[] originalData, String filename, String contentType, String message) {
        return new ProcessingResult(originalData, filename, contentType, ProcessingStatus.FAILED, message);
    }

    /**
     * 创建跳过结果
     */
    public static ProcessingResult skipped(byte[] originalData, String filename, String contentType, String reason) {
        return new ProcessingResult(originalData, filename, contentType, ProcessingStatus.SKIPPED, reason);
    }
}
