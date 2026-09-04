package cn.aigc.dit.common.security;

import java.io.Serializable;

/**
 * JWT 解析后的当前登录用户。
 */
public class LoginUser implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_CREATOR = "CREATOR";

    private Long userId;
    private String username;
    private String email;
    private String displayName;
    private String role;

    public LoginUser() {
    }

    public LoginUser(Long userId, String username, String email, String displayName, String role) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
