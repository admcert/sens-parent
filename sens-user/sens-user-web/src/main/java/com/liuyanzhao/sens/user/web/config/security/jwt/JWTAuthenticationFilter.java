package com.liuyanzhao.sens.user.web.config.security.jwt;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.liuyanzhao.sens.common.util.ResponseUtil;
import com.liuyanzhao.sens.user.web.config.properties.TokenProperties;
import com.liuyanzhao.sens.user.web.constant.SecurityConstant;
import com.liuyanzhao.sens.user.web.util.SecurityUtil;
import com.liuyanzhao.sens.user.web.vo.TokenUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author liuyanzhao
 */
@Slf4j
public class JWTAuthenticationFilter extends BasicAuthenticationFilter {

    private TokenProperties tokenProperties;

    private StringRedisTemplate redisTemplate;

    private SecurityUtil securityUtil;

    public JWTAuthenticationFilter(AuthenticationManager authenticationManager, TokenProperties tokenProperties, StringRedisTemplate redisTemplate, SecurityUtil securityUtil) {
        super(authenticationManager);
        this.tokenProperties = tokenProperties;
        this.redisTemplate = redisTemplate;
        this.securityUtil = securityUtil;
    }

    public JWTAuthenticationFilter(AuthenticationManager authenticationManager, AuthenticationEntryPoint authenticationEntryPoint) {
        super(authenticationManager, authenticationEntryPoint);
    }

    /**
     * 验证参数中是否有 accessToken
     * @param request
     * @param response
     * @param chain
     * @throws IOException
     * @throws ServletException
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {

        String header = request.getHeader(SecurityConstant.HEADER);
        if(StrUtil.isBlank(header)){
            header = request.getParameter(SecurityConstant.HEADER);
        }
//        if(StrUtil.isBlank(header)){
//            header = getCookieValue(request, SecurityConstant.HEADER);
//
//        }
        Boolean notValid = StrUtil.isBlank(header) || (!tokenProperties.getRedis() && !header.startsWith(SecurityConstant.TOKEN_SPLIT));
        if (notValid) {
            chain.doFilter(request, response);
            return;
        }
        try {
            UsernamePasswordAuthenticationToken authentication = getAuthentication(header, response);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }catch (Exception e){
            e.toString();
        }

        chain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken getAuthentication(String header, HttpServletResponse response) {

        // 用户名
        String username = null;
        // 权限
        List<GrantedAuthority> authorities = new ArrayList<>();

        if(tokenProperties.getRedis()){
            // redis
            String v = redisTemplate.opsForValue().get(SecurityConstant.TOKEN_PRE + header);
            if(StrUtil.isBlank(v)){
                ResponseUtil.out(response, ResponseUtil.resultMap(false,401,"登录已失效，请重新登录"));
                return null;
            }
            TokenUser user = new Gson().fromJson(v, TokenUser.class);
            username = user.getUsername();
            if(tokenProperties.getStorePerms()){
                // 缓存了权限
                for(String ga : user.getPermissions()){
                    authorities.add(new SimpleGrantedAuthority(ga));
                }
            }else{
                // 未缓存 读取权限数据
                authorities = securityUtil.getCurrUserPerms(username);
            }
            if(!user.getSaveLogin()){
                // 若未保存登录状态重新设置失效时间
                redisTemplate.opsForValue().set(SecurityConstant.USER_TOKEN + username, header, tokenProperties.getTokenExpireTime(), TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(SecurityConstant.TOKEN_PRE + header, v, tokenProperties.getTokenExpireTime(), TimeUnit.MINUTES);
            }
        }else{
            // JWT
            try {
                // 解析token
                Claims claims = Jwts.parser()
                        .setSigningKey(SecurityConstant.JWT_SIGN_KEY)
                        .parseClaimsJws(header.replace(SecurityConstant.TOKEN_SPLIT, ""))
                        .getBody();

                // 获取用户名
                username = claims.getSubject();
                // 获取权限
                if(tokenProperties.getStorePerms()) {
                    // 缓存了权限
                    String authority = claims.get(SecurityConstant.AUTHORITIES).toString();
                    if(StrUtil.isNotBlank(authority)){
                        List<String> list = new Gson().fromJson(authority, new TypeToken<List<String>>(){}.getType());
                        for(String ga : list){
                            authorities.add(new SimpleGrantedAuthority(ga));
                        }
                    }
                }else{
                    // 未缓存 读取权限数据
                    authorities = securityUtil.getCurrUserPerms(username);
                }
            } catch (ExpiredJwtException e) {
                ResponseUtil.out(response, ResponseUtil.resultMap(false,401,"登录已失效，请重新登录"));
            } catch (Exception e){
                log.error(e.toString());
                ResponseUtil.out(response, ResponseUtil.resultMap(false,500,"解析token错误"));
            }
        }

        if(StrUtil.isNotBlank(username)) {
            // 踩坑提醒 此处password不能为null
            User principal = new User(username, "", authorities);
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }
        return null;
    }

    private String getCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length <= 0) {
             return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(cookieName)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

