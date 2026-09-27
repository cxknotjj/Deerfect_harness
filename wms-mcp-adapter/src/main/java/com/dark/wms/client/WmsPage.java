package com.dark.wms.client;

import java.util.List;
import java.util.Map;

/**
 * WMS 分页查询结果（jeecg {@code result.records} 解包后）。
 *
 * @param records 当前页记录列表
 * @param total   命中总数（WMS 未回传 total 时以当页记录数兜底）
 */
public record WmsPage(List<Map<String, Object>> records, long total) {
}
