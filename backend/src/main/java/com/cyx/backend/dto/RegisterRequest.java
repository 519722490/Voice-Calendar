package com.cyx.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 30, message = "用户名长度必须在 3 到 30 位之间")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 72, message = "密码长度必须在 6 到 72 位之间")
        String password,

        @Size(max = 30, message = "昵称最多 30 个字符")
        String displayName
) {
}
