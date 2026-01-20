/**
 * 分页相关常量
 */
export const PAGE_SIZE_OPTIONS = [20, 50, 100] as const

export type PageSizeOption = typeof PAGE_SIZE_OPTIONS[number]

export const DEFAULT_PAGE_SIZE: PageSizeOption = 20
