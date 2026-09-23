package cn.aigc.dit.server.security;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ActiveUserGuard activeUserGuard;

    public JwtAuthenticationFilter(JwtService jwtService, ActiveUserGuard activeUserGuard) {
        this.jwtService = jwtService;
        this.activeUserGuard = activeUserGuard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (!StringUtils.hasText(token) && acceptsQueryToken(request)) {
                token = request.getParameter("token");
            }
            if (StringUtils.hasText(token)) {
                LoginUser user = jwtService.parse(token);
                activeUserGuard.requireEnabled(user.getUserId());
                UserContext.set(user);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } catch (BizException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute("dit.auth.error", e.getMessage());
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }

    static boolean acceptsQueryToken(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        return path.startsWith("/api/media")
                || path.matches("^/api/drama/shots/\\d+/captions\\.vtt$")
                || path.matches("^/api/drama/episodes/\\d+/film/captions\\.vtt$");
    }
}
