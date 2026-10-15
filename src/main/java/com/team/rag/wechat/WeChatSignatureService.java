package com.team.rag.wechat;

import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 微信签名验证服务。
 *
 * <p>负责验证微信服务器回调请求的签名，确保请求来源的合法性。
 * 在本学术 RAG 系统中，该组件是微信消息通道的安全门卫，位于 HTTP Controller
 * 的入口处，所有微信回调请求在处理前都必须通过签名验证。
 *
 * <p>签名验证算法（微信官方文档）：
 * <ol>
 *   <li>将 Token、timestamp、nonce 三个参数按字典序排序；</li>
 *   <li>将排序后的三个字符串拼接为一个字符串；</li>
 *   <li>对拼接后的字符串进行 SHA-1 加密；</li>
 *   <li>将加密结果与微信传来的 signature 参数比较（忽略大小写）。</li>
 * </ol>
 *
 * @see WeChatProperties 微信配置属性（提供 Token）
 */
@Service
public class WeChatSignatureService {

    private static final Logger log = LoggerFactory.getLogger(WeChatSignatureService.class);

    /** 微信配置属性，用于获取签名验证所需的 Token */
    private final WeChatProperties properties;

    /**
     * 构造函数，注入依赖。
     *
     * @param properties 微信配置属性
     */
    public WeChatSignatureService(WeChatProperties properties) {
        this.properties = properties;
    }

    /**
     * 验证微信回调请求的签名。
     *
     * @param signature 微信传来的签名字符串
     * @param timestamp 微信传来的 timestamp 参数
     * @param nonce     微信传来的 nonce 参数
     * @return 签名是否合法
     */
    public boolean verify(String signature, String timestamp, String nonce) {
        // 检查配置和参数是否完整
        if (!properties.hasCallbackConfig() || isBlank(signature) || isBlank(timestamp) || isBlank(nonce)) {
            log.warn("WeChat verify missing params | hasConfig={} | sig={} | ts={} | nonce={}",
                    properties.hasCallbackConfig(), signature, timestamp, nonce);
            return false;
        }
        // 步骤1：将 Token、timestamp、nonce 按字典序排序
        String[] values = {properties.token(), timestamp, nonce};
        Arrays.sort(values);
        // 步骤2：拼接排序后的字符串
        String joined = String.join("", values);
        // 步骤3：计算 SHA-1 哈希值
        String expected = sha1(joined);
        log.info("WeChat verify | sorted values={} | expected={} | received={}", joined, expected, signature);
        // 步骤4：比较计算结果与传入的签名（忽略大小写）
        return expected.equalsIgnoreCase(signature);
    }

    /**
     * 计算字符串的 SHA-1 哈希值。
     *
     * @param value 待哈希的字符串
     * @return SHA-1 哈希值的十六进制字符串（小写）
     * @throws IllegalStateException 如果 SHA-1 算法不可用
     */
    private String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            // 将字节数组转换为十六进制字符串
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate WeChat signature", e);
        }
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * @param value 待检查的字符串
     * @return 是否为空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
