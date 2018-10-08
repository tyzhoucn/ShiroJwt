package com.wang.config.shiro.jwt;

import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.wang.exception.CustomException;
import com.wang.model.common.Constant;
import com.wang.model.common.ResponseBean;
import com.wang.util.JwtUtil;
import com.wang.util.JedisUtil;
import com.wang.util.common.JsonConvertUtil;
import com.wang.util.common.PropertiesUtil;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * JWT过滤
 * @author Wang926454
 * @date 2018/8/30 15:47
 */
public class JwtFilter extends BasicHttpAuthenticationFilter {
    /**
     * Logger
     */
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * 这里我们详细说明下为什么最终返回的都是true，即允许访问
     * 例如我们提供一个地址 GET /article
     * 登入用户和游客看到的内容是不同的
     * 如果在这里返回了false，请求会被直接拦截，用户看不到任何东西
     * 所以我们在这里返回true，Controller中可以通过 subject.isAuthenticated() 来判断用户是否登入
     * 如果有些资源只有登入用户才能访问，我们只需要在方法上面加上 @RequiresAuthentication 注解即可
     * 但是这样做有一个缺点，就是不能够对GET,POST等请求进行分别过滤鉴权(因为我们重写了官方的方法)，但实际上对应用影响不大
     */
    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        // 判断用户是否想要登入
        if (this.isLoginAttempt(request, response)) {
            try {
                // 进行Shiro的登录UserRealm
                this.executeLogin(request, response);
            } catch (Exception e) {
                // 认证出现异常，传递错误信息msg
                String msg = e.getMessage();
                // 获取应用异常(该Cause是导致抛出此throwable(异常)的throwable(异常))
                Throwable throwable = e.getCause();
                if(throwable != null && throwable instanceof SignatureVerificationException ){
                    // 该异常为JWT的AccessToken认证失败(Token或者密钥不正确)
                    // throw (SignatureVerificationException) throwable;
                    msg = "Token或者密钥不正确(" + throwable.getMessage() + ")";
                } else if(throwable != null && throwable instanceof TokenExpiredException){
                    // 该异常为JWT的AccessToken已过期，判断RefreshToken未过期就进行AccessToken刷新
                    if(this.refreshToken(request, response)){
                        return true;
                    }else{
                        msg = "Token已过期(" + throwable.getMessage() + ")";
                    }
                } else{
                    // 应用异常不为空
                    if(throwable != null) {
                        // 获取应用异常msg
                        msg = throwable.getMessage();
                    }
                }
                /**
                 * 错误两种处理方式
                 * 1. 将非法请求转发到/401的Controller处理，抛出自定义无权访问异常被全局捕捉再返回Response信息
                 * 2. 无需转发，直接返回Response信息
                 * 一般使用第二种(更方便)
                 */
                // 直接返回Response信息
                this.response401(request, response, msg);
                return false;
            }
        }
        return true;
    }

    /**
     * 检测Header里面是否包含Authorization字段，有就进行Token登录认证授权
     */
    @Override
    protected boolean isLoginAttempt(ServletRequest request, ServletResponse response) {
        HttpServletRequest req = (HttpServletRequest) request;
        String authorization = req.getHeader("Authorization");
        return authorization != null;
    }

    /**
     * 进行AccessToken登录认证授权
     */
    @Override
    protected boolean executeLogin(ServletRequest request, ServletResponse response) throws Exception {
        // 拿到当前Header中Authorization的AccessToken
        JwtToken token = new JwtToken(this.getToken(request, response));
        // 提交给UserRealm进行认证，如果错误他会抛出异常并被捕获
        this.getSubject(request, response).login(token);
        // 如果没有抛出异常则代表登入成功，返回true
        return true;
    }

    /**
     * 获取Header中Authorization的AccessToken
     */
    private String getToken(ServletRequest request, ServletResponse response) {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        String token = httpServletRequest.getHeader("Authorization");
        return token;
    }

    /**
     * TODO: 此处为AccessToken刷新，进行判断RefreshToken是否过期，未过期就返回新的AccessToken且继续正常访问
     */
    private boolean refreshToken(ServletRequest request, ServletResponse response) {
        // 拿到当前Header中Authorization的AccessToken
        String token = this.getToken(request, response);
        // 获取当前Token的帐号信息
        String account = JwtUtil.getClaim(token, Constant.ACCOUNT);
        // 判断Redis中RefreshToken是否存在
        if(JedisUtil.exists(Constant.PREFIX_SHIRO_REFRESH_TOKEN + account)){
            // Redis中RefreshToken还存在，获取RefreshToken的时间戳
            String currentTimeMillisRedis = JedisUtil.getObject(Constant.PREFIX_SHIRO_REFRESH_TOKEN + account).toString();
            // 获取当前AccessToken中的时间戳，与RefreshToken的时间戳对比
            if(JwtUtil.getClaim(token, Constant.CURRENT_TIME_MILLIS).equals(currentTimeMillisRedis)){
                // 通过说明该AccessToken时间戳与RefreshToken时间戳一致，进行AccessToken刷新
                // 获取当前最新时间戳
                String currentTimeMillis = String.valueOf(System.currentTimeMillis());
                // 获取RefreshToken剩余过期时间
                Long refreshTokenExpireTimeRedis = JedisUtil.getExpireTime(Constant.PREFIX_SHIRO_REFRESH_TOKEN + account);
                // 读取配置文件，获取一个AccessToken的过期时间
                PropertiesUtil.readProperties("config.properties");
                String accessTokenExpireTime = PropertiesUtil.getProperty("accessTokenExpireTime");
                // 设置RefreshToken中的时间戳为当前最新时间戳，且过期时间为RefreshToken剩余过期时间加上一个新的AccessToken过期时间
                JedisUtil.setObject(Constant.PREFIX_SHIRO_REFRESH_TOKEN + account, currentTimeMillis,
                        refreshTokenExpireTimeRedis.intValue() + Integer.parseInt(accessTokenExpireTime));
                // 刷新AccessToken，设置时间戳为当前最新时间戳
                token = JwtUtil.sign(account, currentTimeMillis);
                // 将新刷新的AccessToken再次进行Shiro的登录
                JwtToken jwtToken = new JwtToken(token);
                // 提交给UserRealm进行认证，如果错误他会抛出异常并被捕获，如果没有抛出异常则代表登入成功，返回true
                this.getSubject(request, response).login(jwtToken);
                // 最后将刷新的AccessToken存放在Response的Header中的Authorization字段返回
                HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                httpServletResponse.setHeader("Authorization", token);
                httpServletResponse.setHeader("Access-Control-Expose-Headers", "Authorization");
                return true;
            }
        }
        return false;
    }

    /**
     * 无需转发，直接返回Response信息
     */
    private void response401(ServletRequest req, ServletResponse resp, String msg) {
        HttpServletResponse httpServletResponse = (HttpServletResponse) resp;
        httpServletResponse.setStatus(401);
        httpServletResponse.setCharacterEncoding("UTF-8");
        httpServletResponse.setContentType("application/json; charset=utf-8");
        PrintWriter out = null;
        try {
            out = httpServletResponse.getWriter();
            String data = JsonConvertUtil.objectToJson(new ResponseBean(401, "无权访问(Unauthorized):" + msg, null));
            out.append(data);
        } catch (IOException e) {
            logger.error(e.getMessage());
            throw new CustomException("直接返回Response信息出现IOException异常");
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * 对跨域提供支持
     */
    @Override
    protected boolean preHandle(ServletRequest request, ServletResponse response) throws Exception {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        httpServletResponse.setHeader("Access-control-Allow-Origin", httpServletRequest.getHeader("Origin"));
        httpServletResponse.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS,PUT,DELETE");
        httpServletResponse.setHeader("Access-Control-Allow-Headers", httpServletRequest.getHeader("Access-Control-Request-Headers"));
        // 跨域时会首先发送一个OPTIONS请求，这里我们给OPTIONS请求直接返回正常状态
        if (httpServletRequest.getMethod().equals(RequestMethod.OPTIONS.name())) {
            httpServletResponse.setStatus(HttpStatus.OK.value());
            return false;
        }
        return super.preHandle(request, response);
    }
}
