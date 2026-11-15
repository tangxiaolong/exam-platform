package com.exam.platform.modules.exam.dto;

import java.time.LocalDateTime;

/** 保存答案结果。saved=false 表示客户端版本落后、服务端保留更新版本。 */
public record SaveAnswerResponse(boolean saved, int serverVersion, LocalDateTime serverTime) {
}
