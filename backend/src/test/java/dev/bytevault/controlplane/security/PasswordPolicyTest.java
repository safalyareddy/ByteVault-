package dev.bytevault.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bytevault.controlplane.web.ApiException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void enforcesLengthWithinBcryptLimit() {
        assertThatThrownBy(() -> UserService.validatePassword("elevenchars")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UserService.validatePassword(null)).isInstanceOf(ApiException.class);
        assertThatCode(() -> UserService.validatePassword("twelve-chars")).doesNotThrowAnyException();
        assertThatThrownBy(() -> UserService.validatePassword("x".repeat(73)))
                .as("BCrypt would silently truncate").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UserService.validatePassword("é".repeat(40)))
                .as("72-byte limit is in bytes, not chars").isInstanceOf(ApiException.class);
    }
}
